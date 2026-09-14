package dev.cgt.pixelplace.flush.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.JpaCheckpointFence;
import dev.cgt.pixelplace.checkpoint.infra.JpaCheckpointReader;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.flush.application.AmbiguousFlushCommitException;
import dev.cgt.pixelplace.flush.application.FlushPersistenceRolledBackException;
import dev.cgt.pixelplace.flush.application.DbBootstrapState;
import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.flush.application.FlushDbState;
import dev.cgt.pixelplace.flush.application.FlushPersistenceService;
import dev.cgt.pixelplace.flush.application.FlushPlan;
import dev.cgt.pixelplace.flush.application.FlushPlanCaptureService;
import dev.cgt.pixelplace.flush.application.FlushReconciliationService;
import dev.cgt.pixelplace.flush.application.FlushRunResult;
import dev.cgt.pixelplace.flush.application.FlushSingleFlightGuard;
import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;
import dev.cgt.pixelplace.flush.application.FlushTransactionOutcome;
import dev.cgt.pixelplace.flush.application.FlushTransactionExecutor;
import dev.cgt.pixelplace.flush.application.FlushTransactionResult;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.pixel.application.PixelBroadcastService;
import dev.cgt.pixelplace.pixel.application.PixelCommandService;
import dev.cgt.pixelplace.pixel.application.PixelCooldown;
import dev.cgt.pixelplace.pixel.application.PixelEventWriter;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
import dev.cgt.pixelplace.pixel.infra.JpaPixelEventWriter;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryDbViewCaptureService;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryService;
import dev.cgt.pixelplace.tile.application.SynchronizedDirtyTileTracker;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.application.TileStateSnapshot;
import dev.cgt.pixelplace.tile.application.TileSnapshotWriter;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.domain.TileState;
import dev.cgt.pixelplace.tile.infra.JpaTileMetadataReader;
import dev.cgt.pixelplace.tile.infra.JpaTileSnapshotLoader;
import dev.cgt.pixelplace.tile.infra.JpaTileSnapshotWriter;
import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import dev.cgt.pixelplace.wal.infra.FileWalAppender;
import dev.cgt.pixelplace.wal.infra.FileWalReplaySource;
import dev.cgt.pixelplace.wal.infra.SegmentedWalStorage;
import dev.cgt.pixelplace.wal.infra.WalProperties;
import dev.cgt.pixelplace.wal.infra.MySqlRetentionTestStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 로컬 pixel_place_test와 root DDL에서 persistence 원자성 및 fresh runtime restart 경계 검증
 * 제한 JPA context와 실제 recovery/worker만 사용하며 Redis, 자동 scheduler, test-managed transaction 제외
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("mysql-integration")
@Tag("mysql-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "pixel_place_test", mode = ResourceAccessMode.READ_WRITE)
@Import({
        CanonicalZ0TileKeys.class,
        DbBootstrapClassifier.class,
        JpaTileMetadataReader.class,
        JpaCheckpointFence.class,
        JpaCheckpointReader.class,
        JpaTileSnapshotLoader.class,
        StartupRecoveryDbViewCaptureService.class,
        JpaPixelEventWriter.class,
        JpaTileSnapshotWriter.class,
        FlushPersistenceService.class,
        ProgrammaticFlushTransactionExecutor.class,
        JpaFlushDbStateProbe.class,
        FlushReconciliationService.class
})
class FlushPersistenceMySqlIntegrationTest {

    private static final String CATALOG = "pixel_place_test";
    private static final String REQUIRED_MYSQL_VERSION = "8.4.11";
    private static final TileKey KEY_A = new TileKey(0, 0, 0);
    private static final TileKey KEY_B = new TileKey(0, 1, 0);
    private static final byte[] ZERO_TILE = new byte[BoardConstants.TILE_PIXEL_COUNT];

    @Autowired
    private DataSource dataSource;

    @Autowired
    private dev.cgt.pixelplace.user.infra.UserJpaRepository users;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CheckpointFence checkpointFence;

    @Autowired
    private CheckpointReader checkpointReader;

    @Autowired
    private TileMetadataReader tileMetadataReader;

    @Autowired
    private DbBootstrapClassifier dbBootstrapClassifier;

    @Autowired
    private PixelEventWriter pixelEventWriter;

    @Autowired
    private TileSnapshotWriter tileSnapshotWriter;

    @Autowired
    private FlushPersistenceService persistenceService;

    @Autowired
    private ProgrammaticFlushTransactionExecutor transactionExecutor;

    @Autowired
    private StartupRecoveryDbViewCaptureService recoveryDbViewCaptureService;

    @Autowired
    private FlushReconciliationService flushReconciliationService;

    @TempDir
    Path tempDirectory;

    private final CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();

    @BeforeAll
    void bootstrapActualRootSchemaInTestCatalog() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String database = requireTestCatalog(connection);
            String version = selectServerValue(connection, "SELECT VERSION()");
            String versionComment = selectServerValue(connection, "SELECT @@version_comment");
            System.out.println("MYSQL_INTEGRATION_DATABASE=" + database);
            System.out.println("MYSQL_INTEGRATION_VERSION=" + version);
            System.out.println("MYSQL_INTEGRATION_VERSION_COMMENT=" + versionComment);
            assertRequiredMySqlVersion(version, versionComment);

            // root SQL 안전성 검증을 destructive test schema 초기화보다 먼저 완료
            List<String> executableStatements = loadAndValidateRootSqlStatements();
            dropTestTables(connection);
            requireTestCatalog(connection);
            executeValidatedRootSql(connection, executableStatements);
            requireTestCatalog(connection);
        }

        assertRootSchemaContract();
    }

    @BeforeEach
    void resetEventAndCheckpointState() {
        // 각 destructive fixture 작업과 같은 connection에서 test catalog 재검증
        executeGuardedUpdate("DELETE FROM pixel_events");
        executeGuardedUpdate("DELETE FROM wal_checkpoint");
        executeGuardedUpdate(
                "INSERT INTO wal_checkpoint(checkpoint_name, last_flushed_event_seq) VALUES ('main', 0)"
        );
    }

    @Test
    void bootstrapPlanCommitsOriginalEventsFullCanonicalTilesAndCheckpoint() {
        prepareBootstrap();
        LocalDateTime precise = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_456_789);
        WalRecord record = record(1L, KEY_A, 17, precise);
        FlushPlan plan = bootstrapPlan(
                List.of(record),
                KEY_A,
                filledPixels((byte) 7),
                4L
        );

        FlushTransactionResult result = transactionExecutor.execute(plan);

        assertEquals(FlushTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(1, eventCount());
        assertEquals(precise.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), eventCreatedAt(1L));
        assertEquals(precise, record.createdAt());
        assertEquals(precise, plan.walRecords().get(0).createdAt());
        assertEquals(BoardConstants.Z0_TILE_COUNT, tileCount());
        assertTrue(canonicalKeys.exactlyMatches(tileMetadataReader.readAllTileKeys()));
        assertArrayEquals(filledPixels((byte) 7), tileData(KEY_A));
        assertEquals(BoardConstants.TILE_PIXEL_COUNT, tileData(KEY_A).length);
        assertEquals(4L, tileVersion(KEY_A));
        assertEquals(1L, checkpoint());
    }

    @Test
    // 실제 users AUTO_INCREMENT ID가 principal·두 JWT·controller·file WAL·immutable plan·MySQL event까지 보존되는 연결
    void provisionedInternalIdentitySurvivesLoginTokensControllerWalPlanAndActualMySql() throws Exception {
        prepareBootstrap();
        var provisioning=new dev.cgt.pixelplace.user.application.UserProvisioningService(users);
        var delegate=new org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService();
        var rest=new org.springframework.web.client.RestTemplate();
        var provider=org.springframework.test.web.client.MockRestServiceServer.bindTo(rest).build();
        delegate.setRestOperations(rest);
        var userInfo=new dev.cgt.pixelplace.auth.oauth2.KakaoOAuth2UserService(delegate,provisioning);
        provider.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"id\":987654321987}",org.springframework.http.MediaType.APPLICATION_JSON));
        var request=new org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest(
                dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.registration(),
                new org.springframework.security.oauth2.core.OAuth2AccessToken(org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER,
                        "test-only-provider",java.time.Instant.now(),java.time.Instant.now().plusSeconds(60)));
        var principal=(dev.cgt.pixelplace.auth.oauth2.KakaoPrincipal)userInfo.loadUser(request);
        long internalId=principal.internalUserId();
        assertTrue(internalId>0 && internalId!=principal.kakaoUserId());
        assertEquals(internalId,users.findByKakaoUserId(principal.kakaoUserId()).orElseThrow().getId());
        var tokens=new dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens(dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.PROPERTIES,
                dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.CLOCK);
        var handoff=tokens.issueHandoff(internalId);
        assertEquals(Long.toString(internalId),tokens.handoffDecoder().decode(handoff.getTokenValue()).getSubject());
        var exchange=new dev.cgt.pixelplace.auth.web.HandoffExchangeService(tokens,dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.CLOCK);
        var access=tokens.accessDecoder().decode(exchange.exchange(handoff.getTokenValue()).accessToken());
        assertEquals(Long.toString(internalId),access.getSubject());
        Path identityPath = tempDirectory.resolve("c-identity.wal");
        AtomicReference<FlushPlan> segmentPlan = new AtomicReference<>();
        var runtime=createFreshRuntime(identityPath, null, false, newWalStorage(identityPath, 1L), plan -> {
            segmentPlan.set(plan);
            return transactionExecutor.execute(plan);
        });
        try {
            runtime.recover();
            var controller=new dev.cgt.pixelplace.pixel.web.PixelController(runtime.pixelCommandService);
            var result=controller.writePixel(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(access),
                    new dev.cgt.pixelplace.pixel.web.PixelWriteRequest(1,2,3));
            assertEquals(200,result.getStatusCode().value());
            var wal=runtime.fileWalReplaySource.readAfter(0).records();assertEquals(1,wal.size());assertEquals(internalId,wal.getFirst().userId());
            var plan=runtime.flushPlanCaptureService.capturePlan();assertEquals(internalId,plan.walRecords().getFirst().userId());
            assertEquals(FlushTransactionOutcome.COMMITTED,transactionExecutor.execute(plan).outcome());
            assertEquals(internalId,jdbcTemplate.queryForObject("SELECT user_id FROM pixel_events WHERE event_seq = ?",Long.class,wal.getFirst().eventSeq()));
            assertEquals(wal.getFirst().eventSeq(),checkpoint());provider.verify();
            // 같은 Access principal의 다음 요청이 실제 segment 경계를 지나 worker 정리까지 연결
            var second = controller.writePixel(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(access),
                    new dev.cgt.pixelplace.pixel.web.PixelWriteRequest(BoardConstants.TILE_SIZE, 0, 4));
            assertEquals(200, second.getStatusCode().value());
            assertEquals(List.of(identityPath, segment(identityPath, 1)), walFiles(identityPath));
            List<WalRecord> crossing = readFileRecords(segment(identityPath, 1));
            assertEquals(List.of(2L), eventSequences(crossing));
            assertEquals(internalId, crossing.getFirst().userId());
            assertEquals(1L, checkpoint());
            assertEquals(FlushRunResult.COMMITTED, runtime.flush());
            assertEquals(crossing, segmentPlan.get().walRecords());
            assertEquals(internalId, segmentPlan.get().walRecords().getFirst().userId());
            assertEquals(List.of(internalId, internalId), jdbcTemplate.queryForList(
                    "SELECT user_id FROM pixel_events ORDER BY event_seq", Long.class));
            assertEquals(2L, checkpoint());
            assertEquals(2, eventCount());
            assertEquals(List.of(segment(identityPath, 1)), walFiles(identityPath));
            assertDirtyEmpty(runtime);
        } finally { retireQuietly(runtime); }
    }

    @Test
    void initializedPlanUpdatesOnlyCapturedTargetAndAdvancesCheckpoint() {
        prepareInitialized(KEY_A, KEY_B);
        WalRecord record = record(1L, KEY_A, 19, LocalDateTime.of(2026, 4, 3, 6, 1));
        FlushPlan plan = initializedPlan(
                0L,
                List.of(record),
                List.of(snapshot(KEY_A, filledPixels((byte) 9), 5L))
        );

        FlushTransactionResult result = transactionExecutor.execute(plan);

        assertEquals(FlushTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(1, eventCount());
        assertArrayEquals(filledPixels((byte) 9), tileData(KEY_A));
        assertEquals(5L, tileVersion(KEY_A));
        assertArrayEquals(ZERO_TILE, tileData(KEY_B));
        assertEquals(0L, tileVersion(KEY_B));
        assertEquals(1L, checkpoint());
        assertEquals(BoardConstants.Z0_TILE_COUNT, tileCount());
    }

    @Test
    void eventSeqGapsPersistInExplicitEventOrderAndCheckpointUsesLastActualRecord() {
        prepareInitialized(KEY_A);
        List<WalRecord> records = List.of(
                record(3L, KEY_A, 3, LocalDateTime.of(2026, 4, 3, 6, 3)),
                record(7L, KEY_A, 7, LocalDateTime.of(2026, 4, 3, 6, 2)),
                record(10L, KEY_A, 10, LocalDateTime.of(2026, 4, 3, 6, 1))
        );
        FlushPlan plan = initializedPlan(
                0L,
                records,
                List.of(snapshot(KEY_A, filledPixels((byte) 10), 10L))
        );

        FlushTransactionResult result = transactionExecutor.execute(plan);

        assertEquals(FlushTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(
                List.of(3L, 7L, 10L),
                jdbcTemplate.queryForList(
                        "SELECT event_seq FROM pixel_events ORDER BY event_seq",
                        Long.class
                )
        );
        assertEquals(10L, checkpoint());
    }

    @Test
    void eventsSharingSameMillisecondRemainDistinctByEventSeq() {
        prepareInitialized(KEY_A, KEY_B);
        LocalDateTime first = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_456_789);
        LocalDateTime second = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_999_999);
        List<WalRecord> records = List.of(
                record(1L, KEY_A, 1, first),
                record(2L, KEY_B, 2, second)
        );
        FlushPlan plan = initializedPlan(
                0L,
                records,
                List.of(
                        snapshot(KEY_A, filledPixels((byte) 1), 1L),
                        snapshot(KEY_B, filledPixels((byte) 2), 1L)
                )
        );

        FlushTransactionResult result = transactionExecutor.execute(plan);

        assertEquals(FlushTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(List.of(1L, 2L), jdbcTemplate.queryForList(
                "SELECT event_seq FROM pixel_events ORDER BY event_seq",
                Long.class
        ));
        assertEquals(eventCreatedAt(1L), eventCreatedAt(2L));
        assertEquals(first, records.get(0).createdAt());
        assertEquals(second, records.get(1).createdAt());
        assertEquals(2L, checkpoint());
    }

    @Test
    void tileWriterFailureRollsBackAlreadyFlushedEventAndLeavesTileAndCheckpointUnchanged() {
        prepareInitialized(KEY_A);
        FlushPlan plan = singleInitializedPlan();
        RuntimeException injected = new RuntimeException("tile writer injected failure");
        TileSnapshotWriter failingTileWriter = snapshots -> {
            throw injected;
        };
        FlushPersistenceService failingService = new FlushPersistenceService(
                checkpointFence,
                tileMetadataReader,
                dbBootstrapClassifier,
                pixelEventWriter,
                failingTileWriter
        );
        ProgrammaticFlushTransactionExecutor executor = new ProgrammaticFlushTransactionExecutor(
                transactionManager,
                failingService
        );

        FlushTransactionResult result = executor.execute(plan);

        assertFailure(result, injected);
        assertEquals(0, eventCount());
        assertArrayEquals(ZERO_TILE, tileData(KEY_A));
        assertEquals(0L, tileVersion(KEY_A));
        assertEquals(0L, checkpoint());
    }

    @Test
    void checkpointFailureRollsBackFlushedEventAndTile() {
        prepareInitialized(KEY_A);
        FlushPlan plan = singleInitializedPlan();
        RuntimeException injected = new RuntimeException("checkpoint injected failure");
        CheckpointFence failingAdvance = new CheckpointFence() {
            @Override
            public long lockMainCheckpoint() {
                return checkpointFence.lockMainCheckpoint();
            }

            @Override
            public void advanceMainCheckpoint(long expected, long target) {
                throw injected;
            }
        };
        FlushPersistenceService failingService = new FlushPersistenceService(
                failingAdvance,
                tileMetadataReader,
                dbBootstrapClassifier,
                pixelEventWriter,
                tileSnapshotWriter
        );
        ProgrammaticFlushTransactionExecutor executor = new ProgrammaticFlushTransactionExecutor(
                transactionManager,
                failingService
        );

        FlushTransactionResult result = executor.execute(plan);

        assertFailure(result, injected);
        assertEquals(0, eventCount());
        assertArrayEquals(ZERO_TILE, tileData(KEY_A));
        assertEquals(0L, tileVersion(KEY_A));
        assertEquals(0L, checkpoint());
    }

    @Test
    void duplicateEventSeqFailsWithoutUpdatingExistingRowTileOrCheckpoint() {
        prepareInitialized(KEY_A);
        LocalDateTime existingTime = LocalDateTime.of(2026, 4, 3, 5, 0, 0, 321_000_000);
        executeGuardedUpdate(
                """
                        INSERT INTO pixel_events
                            (event_seq, user_id, z, tx, ty, x, y, color, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L,
                99L,
                0,
                0,
                0,
                0,
                0,
                33,
                existingTime
        );
        FlushPlan duplicatePlan = initializedPlan(
                0L,
                List.of(record(1L, KEY_A, 44, LocalDateTime.of(2026, 4, 3, 6, 0))),
                List.of(snapshot(KEY_A, filledPixels((byte) 44), 2L))
        );

        FlushTransactionResult result = transactionExecutor.execute(duplicatePlan);

        assertEquals(FlushTransactionOutcome.DEFINITE_ROLLBACK, result.outcome());
        assertEquals(1, eventCount());
        assertEquals(99L, jdbcTemplate.queryForObject(
                "SELECT user_id FROM pixel_events WHERE event_seq = 1",
                Long.class
        ));
        assertEquals(33, jdbcTemplate.queryForObject(
                "SELECT color FROM pixel_events WHERE event_seq = 1",
                Integer.class
        ));
        assertEquals(existingTime, eventCreatedAt(1L));
        assertArrayEquals(ZERO_TILE, tileData(KEY_A));
        assertEquals(0L, checkpoint());
    }

    @Test
    void expectedCheckpointMismatchStopsBeforeEventAndTileWrites() {
        prepareInitialized(KEY_A);
        executeGuardedUpdate(
                "UPDATE wal_checkpoint SET last_flushed_event_seq = 5 WHERE checkpoint_name = 'main'"
        );

        FlushTransactionResult result = transactionExecutor.execute(singleInitializedPlan());

        assertEquals(FlushTransactionOutcome.DEFINITE_ROLLBACK, result.outcome());
        assertEquals(0, eventCount());
        assertArrayEquals(ZERO_TILE, tileData(KEY_A));
        assertEquals(5L, checkpoint());
    }

    @Test
    void missingMainCheckpointFailsWithoutAutoSeedOrWrites() {
        prepareInitialized(KEY_A);
        executeGuardedUpdate("DELETE FROM wal_checkpoint WHERE checkpoint_name = 'main'");

        FlushTransactionResult result = transactionExecutor.execute(singleInitializedPlan());

        assertEquals(FlushTransactionOutcome.DEFINITE_ROLLBACK, result.outcome());
        assertEquals(0, eventCount());
        assertArrayEquals(ZERO_TILE, tileData(KEY_A));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wal_checkpoint WHERE checkpoint_name = 'main'",
                Integer.class
        ));
    }

    @Test
    void partialTileMetadataFailsWithoutAutomaticRepairOrWrites() {
        preparePartialDatabase();

        FlushTransactionResult result = transactionExecutor.execute(singleInitializedPlan());

        assertEquals(FlushTransactionOutcome.DEFINITE_ROLLBACK, result.outcome());
        assertEquals(0, eventCount());
        assertEquals(1, tileCount());
        assertArrayEquals(ZERO_TILE, tileData(KEY_A));
        assertEquals(0L, checkpoint());
    }

    @Test
    void conditionalCheckpointAdvanceAffectsExactlyOneRealRow() {
        prepareInitialized(KEY_A);
        TransactionTemplate template = requiresNewTemplate("checkpoint-cas-integration");

        template.executeWithoutResult(status -> {
            assertEquals(0L, checkpointFence.lockMainCheckpoint());
            checkpointFence.advanceMainCheckpoint(0L, 5L);
        });

        assertEquals(5L, checkpoint());
    }

    @Test
    void secondTransactionCannotPassMainCheckpointLockBeforeFirstCompletes() throws Exception {
        prepareInitialized(KEY_A);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondLockAttempted = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> inNewTransaction("first-lock", () -> {
                checkpointFence.lockMainCheckpoint();
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> {
                inNewTransaction("second-lock", () -> {
                    secondLockAttempted.countDown();
                    checkpointFence.lockMainCheckpoint();
                    secondLocked.countDown();
                });
            });
            assertTrue(secondLockAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(secondLocked.await(300, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(0L, secondLocked.getCount());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void reconciliationProbeWaitsForAmbiguousCheckpointLockResolution() throws Exception {
        prepareInitialized(KEY_A);
        CountDownLatch ambiguousUpdated = new CountDownLatch(1);
        CountDownLatch releaseAmbiguous = new CountDownLatch(1);
        CountDownLatch probeLockAttempted = new CountDownLatch(1);
        CountDownLatch probeCompleted = new CountDownLatch(1);
        AtomicReference<FlushDbState> observed = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CheckpointFence observingCheckpointFence = new CheckpointFence() {
            @Override
            public long lockMainCheckpoint() {
                probeLockAttempted.countDown();
                return checkpointFence.lockMainCheckpoint();
            }

            @Override
            public void advanceMainCheckpoint(long expected, long target) {
                checkpointFence.advanceMainCheckpoint(expected, target);
            }
        };
        JpaFlushDbStateProbe observingProbe = new JpaFlushDbStateProbe(
                transactionManager,
                observingCheckpointFence,
                tileMetadataReader,
                dbBootstrapClassifier
        );

        try {
            Future<?> ambiguous = executor.submit(() -> inNewTransaction("ambiguous-holder", () -> {
                checkpointFence.lockMainCheckpoint();
                checkpointFence.advanceMainCheckpoint(0L, 9L);
                ambiguousUpdated.countDown();
                await(releaseAmbiguous);
            }));
            assertTrue(ambiguousUpdated.await(10, TimeUnit.SECONDS));

            Future<?> probe = executor.submit(() -> {
                try {
                    observed.set(observingProbe.probe());
                } finally {
                    probeCompleted.countDown();
                }
            });
            assertTrue(probeLockAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(probe.isDone());
            assertFalse(probeCompleted.await(300, TimeUnit.MILLISECONDS));

            releaseAmbiguous.countDown();
            ambiguous.get(10, TimeUnit.SECONDS);
            probe.get(10, TimeUnit.SECONDS);
            assertEquals(new FlushDbState(9L, DbBootstrapState.INITIALIZED), observed.get());
        } finally {
            releaseAmbiguous.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    // 같은 MySQL commit 상태와 WAL bytes만 넘겨 fresh process-local graph 경계 검증
    void freshRuntimeRestartFlushesWalOnlyRecoveryAndPreventsDoubleReplay() throws Exception {
        prepareBootstrap();
        Path walPath = tempDirectory.resolve("runtime-a-b-c-d.wal");
        byte[] whiteTile = TileState.allWhite().pixels();
        assertFalse(Files.exists(walPath));

        FreshRuntimeGraph runtimeA = null;
        FreshRuntimeGraph runtimeB = null;
        FreshRuntimeGraph runtimeC = null;
        FreshRuntimeGraph runtimeD = null;
        try {
            assertTrue(AopUtils.isAopProxy(recoveryDbViewCaptureService));
            runtimeA = createFreshRuntime(walPath);
            assertRuntimePristine(runtimeA);
            assertNoActualTransaction();
            runtimeA.recover();
            assertTrue(runtimeA.serviceReadiness.isReady());
            assertEquals(BoardConstants.Z0_TILE_COUNT, runtimeA.board.size());
            assertArrayEquals(whiteTile, runtimeA.board.getRequired(KEY_A).pixels());

            assertNoActualTransaction();
            PixelWriteResult eventOne = runtimeA.write(7L, 0, 0, 17);
            assertEquals(1L, eventOne.eventSeq());
            assertEquals(1L, eventOne.tileVersion());
            WalReplayBatch runtimeAWal = runtimeA.fileWalReplaySource.readAfter(0L);
            assertEquals(1L, runtimeAWal.walLastEventSeq());
            assertEquals(1, runtimeAWal.records().size());
            LocalDateTime eventOneCreatedAt = runtimeAWal.records().get(0).createdAt();

            assertNoActualTransaction();
            assertEquals(FlushRunResult.COMMITTED, runtimeA.flush());
            assertEquals(1, eventCount(1L));
            assertEquals(eventOneCreatedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), eventCreatedAt(1L));
            assertEquals(BoardConstants.Z0_TILE_COUNT, tileCount());
            assertArrayEquals(runtimeA.board.getRequired(KEY_A).pixels(), tileData(KEY_A));
            assertEquals(runtimeA.board.getRequired(KEY_A).tileVersion(), tileVersion(KEY_A));
            assertArrayEquals(whiteTile, tileData(KEY_B));
            assertEquals(0L, tileVersion(KEY_B));
            assertEquals(1L, checkpoint());
            assertDirtyEmpty(runtimeA);
            assertTrue(runtimeA.pendingAmbiguousFlushStore.current().isEmpty());
            long walSizeAfterRuntimeA = Files.size(walPath);
            assertTrue(walSizeAfterRuntimeA > 0L);

            runtimeA.retire();
            runtimeB = createFreshRuntime(walPath);
            assertFreshRuntimeIsolation(runtimeA, runtimeB);
            assertRetiredRuntimeRejected(runtimeA);
            runtimeA = null;
            assertRuntimePristine(runtimeB);

            byte[] committedEventOneBytes = tileData(KEY_A);
            long committedEventOneVersion = tileVersion(KEY_A);
            assertNoActualTransaction();
            runtimeB.recover();
            assertTrue(runtimeB.fileWalReplaySource.readAfter(1L).records().isEmpty());
            assertArrayEquals(committedEventOneBytes, runtimeB.board.getRequired(KEY_A).pixels());
            assertEquals(committedEventOneVersion, runtimeB.board.getRequired(KEY_A).tileVersion());
            assertEquals(1L, runtimeB.eventSeqManager.currentLastIssued());
            assertDirtyEmpty(runtimeB);
            assertTrue(runtimeB.pendingAmbiguousFlushStore.current().isEmpty());
            assertTrue(runtimeB.serviceReadiness.isReady());

            assertNoActualTransaction();
            PixelWriteResult eventTwo = runtimeB.write(7L, 1, 0, 19);
            assertEquals(2L, eventTwo.eventSeq());
            assertEquals(committedEventOneVersion + 1L, eventTwo.tileVersion());
            assertEquals(1, eventCount());
            assertEquals(1L, checkpoint());
            assertArrayEquals(committedEventOneBytes, tileData(KEY_A));
            WalReplayBatch runtimeBWal = runtimeB.fileWalReplaySource.readAfter(1L);
            assertEquals(2L, runtimeBWal.walLastEventSeq());
            assertEquals(List.of(2L), runtimeBWal.records().stream().map(WalRecord::eventSeq).toList());
            assertEquals(19, Byte.toUnsignedInt(runtimeB.board.getRequired(KEY_A).pixels()[1]));

            runtimeB.retire();
            runtimeC = createFreshRuntime(walPath);
            assertFreshRuntimeIsolation(runtimeB, runtimeC);
            assertRetiredRuntimeRejected(runtimeB);
            runtimeB = null;
            assertRuntimePristine(runtimeC);

            assertNoActualTransaction();
            runtimeC.recover();
            assertEquals(2L, runtimeC.eventSeqManager.currentLastIssued());
            assertEquals(19, Byte.toUnsignedInt(runtimeC.board.getRequired(KEY_A).pixels()[1]));
            assertEquals(committedEventOneVersion + 1L, runtimeC.board.getRequired(KEY_A).tileVersion());
            assertEquals(1, eventCount());
            assertEquals(1L, checkpoint());
            assertDirtyEmpty(runtimeC);
            assertTrue(runtimeC.pendingAmbiguousFlushStore.current().isEmpty());
            assertTrue(runtimeC.serviceReadiness.isReady());

            byte[] recoveredEventTwoBytes = runtimeC.board.getRequired(KEY_A).pixels();
            long recoveredEventTwoVersion = runtimeC.board.getRequired(KEY_A).tileVersion();
            byte[] unchangedTileBytes = tileData(KEY_B);
            long unchangedTileVersion = tileVersion(KEY_B);
            long walSizeBeforeRuntimeCFlush = Files.size(walPath);
            assertNoActualTransaction();
            assertEquals(FlushRunResult.COMMITTED, runtimeC.flush());
            assertEquals(1, eventCount(1L));
            assertEquals(1, eventCount(2L));
            assertEquals(2, eventCount());
            assertEquals(2L, checkpoint());
            assertArrayEquals(recoveredEventTwoBytes, tileData(KEY_A));
            assertEquals(recoveredEventTwoVersion, tileVersion(KEY_A));
            assertArrayEquals(unchangedTileBytes, tileData(KEY_B));
            assertEquals(unchangedTileVersion, tileVersion(KEY_B));
            assertDirtyEmpty(runtimeC);
            assertTrue(runtimeC.pendingAmbiguousFlushStore.current().isEmpty());
            assertEquals(walSizeBeforeRuntimeCFlush, Files.size(walPath));

            runtimeC.retire();
            runtimeD = createFreshRuntime(walPath);
            assertFreshRuntimeIsolation(runtimeC, runtimeD);
            assertRetiredRuntimeRejected(runtimeC);
            runtimeC = null;
            assertRuntimePristine(runtimeD);

            WalReplayBatch noReplayBatch = runtimeD.fileWalReplaySource.readAfter(2L);
            assertEquals(2L, noReplayBatch.walLastEventSeq());
            assertTrue(noReplayBatch.records().isEmpty());
            assertNoActualTransaction();
            runtimeD.recover();
            assertEquals(2L, runtimeD.eventSeqManager.currentLastIssued());
            assertArrayEquals(recoveredEventTwoBytes, runtimeD.board.getRequired(KEY_A).pixels());
            assertEquals(recoveredEventTwoVersion, runtimeD.board.getRequired(KEY_A).tileVersion());
            assertEquals(19, Byte.toUnsignedInt(runtimeD.board.getRequired(KEY_A).pixels()[1]));
            assertEquals(1, eventCount(1L));
            assertEquals(1, eventCount(2L));
            assertEquals(2L, checkpoint());
            assertDirtyEmpty(runtimeD);
            assertTrue(runtimeD.pendingAmbiguousFlushStore.current().isEmpty());
            assertTrue(runtimeD.serviceReadiness.isReady());
            assertEquals(walSizeBeforeRuntimeCFlush, Files.size(walPath));

            runtimeD.retire();
            assertRetiredRuntimeRejected(runtimeD);
            runtimeD = null;
        } finally {
            retireQuietly(runtimeA);
            retireQuietly(runtimeB);
            retireQuietly(runtimeC);
            retireQuietly(runtimeD);
        }
    }

    @Test
    // DB 확정 prefix와 미flush WAL suffix를 구분하며 gap·동일 픽셀 덮어쓰기의 새 graph 복구 검증
    void segmentedCommitCleanupThenWalOnlySuffixRecoversLatestMemory() throws Exception {
        prepareBootstrap();
        Path path = tempDirectory.resolve("segmented-suffix.wal");
        byte[] committedA;
        byte[] committedB;
        byte[] latestA;
        byte[] latestB;
        List<WalRecord> committedRecords;
        try (FreshRuntimeGraph runtime = createSegmentedRuntime(path)) {
            runtime.recover();
            assertEquals(1L, runtime.write(7L, 0, 0, 11).eventSeq());
            // 발급만 소비한 2는 WAL record가 없는 합법적인 gap
            assertEquals(2L, runtime.eventSeqManager.allocate());
            assertEquals(3L, runtime.write(7L, BoardConstants.TILE_SIZE, 0, 21).eventSeq());
            assertEquals(4L, runtime.write(7L, 0, 0, 12).eventSeq());
            assertEquals(List.of(segment(path, 0), segment(path, 1), segment(path, 2)), walFiles(path));
            committedRecords = runtime.fileWalReplaySource.readAfter(0).records();
            assertEquals(List.of(1L, 3L, 4L), eventSequences(committedRecords));
            committedA = runtime.board.getRequired(KEY_A).pixels();
            committedB = runtime.board.getRequired(KEY_B).pixels();
            assertEquals(FlushRunResult.COMMITTED, runtime.flush());
            assertDatabaseState(4L, List.of(1L, 3L, 4L), committedA, 2L, committedB, 1L);
            assertPersistedRecords(committedRecords);
            assertEquals(List.of(segment(path, 2)), walFiles(path));
            assertDirtyEmpty(runtime);

            assertEquals(5L, runtime.write(7L, 0, 0, 13).eventSeq());
            assertEquals(6L, runtime.eventSeqManager.allocate());
            assertEquals(7L, runtime.write(7L, BoardConstants.TILE_SIZE + 1, 0, 22).eventSeq());
            latestA = runtime.board.getRequired(KEY_A).pixels();
            latestB = runtime.board.getRequired(KEY_B).pixels();
            assertEquals(13, Byte.toUnsignedInt(latestA[0]));
            assertEquals(22, Byte.toUnsignedInt(latestB[1]));
            assertDatabaseState(4L, List.of(1L, 3L, 4L), committedA, 2L, committedB, 1L);
        }
        try (FreshRuntimeGraph recovered = createSegmentedRuntime(path)) {
            assertRuntimePristine(recovered);
            recovered.recover();
            assertTrue(recovered.serviceReadiness.isReady());
            WalReplayBatch suffix = recovered.fileWalReplaySource.readAfter(4L);
            assertEquals(List.of(5L, 7L), eventSequences(suffix.records()));
            assertEquals(7L, suffix.walLastEventSeq());
            assertEquals(7L, recovered.eventSeqManager.currentLastIssued());
            assertMemoryState(recovered, latestA, 3L, latestB, 2L);
            assertDatabaseState(4L, List.of(1L, 3L, 4L), committedA, 2L, committedB, 1L);
            assertDirtyEmpty(recovered);
            assertTrue(recovered.pendingAmbiguousFlushStore.current().isEmpty());
            assertEquals(8L, recovered.write(7L, 1, 0, 14).eventSeq());
            assertEquals(8L, recovered.fileWalReplaySource.readAfter(7L).walLastEventSeq());
            assertDirtyState(recovered, List.of(new DirtyTile(KEY_A, 8L, 4L)));
            assertDatabaseState(4L, List.of(1L, 3L, 4L), committedA, 2L, committedB, 1L);
        }
    }

    @ParameterizedTest(name = "committed records={0}")
    @ValueSource(ints = {1, 3})
    // 첫 회전 및 prefix 삭제 뒤 관측 가능한 empty active. JVM 강제 종료를 모사하는 시험은 아님
    void committedTailWithEmptyActiveSeedsFirstCommandWithoutExtraAllocation(int writes) throws Exception {
        prepareInitialized(KEY_A, KEY_B);
        Path path = tempDirectory.resolve("empty-active-" + writes + ".wal");
        List<Long> committed = new ArrayList<>();
        byte[] committedA;
        byte[] committedB;
        try (FreshRuntimeGraph runtime = createSegmentedRuntime(path)) {
            runtime.recover();
            for (int i = 0; i < writes; i++) committed.add(runtime.write(7L, 0, 0, 10 + i).eventSeq());
            assertEquals(FlushRunResult.COMMITTED, runtime.flush());
            assertEquals(List.of(segment(path, writes - 1)), walFiles(path));
            committedA = tileData(KEY_A);
            committedB = tileData(KEY_B);
        }
        Path tailFile = segment(path, writes - 1);
        byte[] tailBytes = Files.readAllBytes(tailFile);
        Path emptyActive = segment(path, writes);
        // 이전 graph 종료 후 next create + empty force까지만 완료된 임시 disk 상태 구성
        try (FileChannel empty = FileChannel.open(emptyActive, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            empty.force(true);
        }
        try (FreshRuntimeGraph recovered = createSegmentedRuntime(path)) {
            recovered.recover();
            assertEquals(writes, recovered.eventSeqManager.currentLastIssued());
            WalReplayBatch batch = recovered.fileWalReplaySource.readAfter(writes);
            assertEquals(writes, batch.walLastEventSeq());
            assertTrue(batch.records().isEmpty());
            assertEquals(writes, checkpoint());
            assertEquals(0L, Files.size(emptyActive));
            assertArrayEquals(tailBytes, Files.readAllBytes(tailFile));
            assertDirtyEmpty(recovered);
            assertTrue(recovered.pendingAmbiguousFlushStore.current().isEmpty());
            assertMemoryState(recovered, committedA, writes, committedB, 0L);

            PixelWriteResult first = recovered.write(7L, 1, 0, 31);
            assertEquals(writes + 1L, first.eventSeq());
            assertEquals(writes + 1L, first.tileVersion());
            assertEquals(writes + 1L, recovered.eventSeqManager.currentLastIssued());
            assertEquals(List.of(tailFile, emptyActive), walFiles(path));
            assertArrayEquals(tailBytes, Files.readAllBytes(tailFile));
            List<WalRecord> appended = readFileRecords(emptyActive);
            assertEquals(List.of(writes + 1L), eventSequences(appended));
            assertEquals(31, appended.getFirst().color());
            assertEquals(31, Byte.toUnsignedInt(recovered.board.getRequired(KEY_A).pixels()[1]));
            assertDirtyState(recovered, List.of(new DirtyTile(KEY_A, writes + 1L, writes + 1L)));
            assertDatabaseState(writes, committed, committedA, writes, committedB, 0L);
        }
    }

    @Test
    // 실제 commit 확정 뒤 delete IOException만 발생. dirty 복원 없이 같은 worker NO_OP로 재시도
    void committedDatabaseSurvivesDeleteFailureAndSameProcessNoOpRetries() throws Exception {
        prepareInitialized(KEY_A, KEY_B);
        Path path = tempDirectory.resolve("delete-delay.wal");
        MySqlRetentionTestStorage storage = new MySqlRetentionTestStorage(tempDirectory, path);
        AtomicInteger transactions = new AtomicInteger();
        try (FreshRuntimeGraph runtime = createFreshRuntime(path, null, false, storage, plan -> {
            transactions.incrementAndGet();
            return transactionExecutor.execute(plan);
        })) {
            runtime.recover();
            writeThreeAcrossTiles(runtime);
            List<DirtyTile> expectedDirty = List.of(new DirtyTile(KEY_A, 3L, 2L), new DirtyTile(KEY_B, 2L, 1L));
            assertDirtyState(runtime, expectedDirty);
            Map<Path, byte[]> before = walBytes(path);
            List<WalRecord> records = runtime.fileWalReplaySource.readAfter(0).records();
            storage.beforeDelete(() -> {
                assertNoActualTransaction();
                assertEquals(3L, checkpoint());
                assertEquals(3, eventCount());
                assertDirtyEmpty(runtime);
                assertTrue(runtime.pendingAmbiguousFlushStore.current().isEmpty());
            });
            storage.failDeletion(true);
            assertEquals(FlushRunResult.COMMITTED, runtime.flush());
            assertEquals(1, transactions.get());
            assertEquals(1, storage.deleteAttempts());
            assertWalBytes(before, path);
            byte[] pixelsA = runtime.board.getRequired(KEY_A).pixels();
            byte[] pixelsB = runtime.board.getRequired(KEY_B).pixels();
            assertDatabaseState(3L, List.of(1L, 2L, 3L), pixelsA, 2L, pixelsB, 1L);
            assertPersistedRecords(records);
            assertTrue(runtime.serviceReadiness.isReady());
            assertDirtyEmpty(runtime);
            storage.failDeletion(false);
            assertEquals(FlushRunResult.NO_OP, runtime.flush());
            assertEquals(1, transactions.get());
            assertEquals(3, storage.deleteAttempts());
            assertEquals(List.of(segment(path, 2)), walFiles(path));
            assertEquals(3L, runtime.fileWalReplaySource.readAfter(3L).walLastEventSeq());
            assertDatabaseState(3L, List.of(1L, 2L, 3L), pixelsA, 2L, pixelsB, 1L);
            assertDirtyEmpty(runtime);
        }
    }

    @Test
    // 실제 commit 응답만 유실 처리. 실제 DB exact reconciliation과 pending clear 이후 syscall 허용
    void lostCommitResponseRetainsWalUntilActualDatabaseReconciliationClearsPending() throws Exception {
        prepareInitialized(KEY_A, KEY_B);
        Path path = tempDirectory.resolve("ambiguous-segments.wal");
        MySqlRetentionTestStorage storage = new MySqlRetentionTestStorage(tempDirectory, path);
        AtomicInteger transactions = new AtomicInteger();
        RuntimeException lostResponse = new RuntimeException("Test-only lost commit response");
        try (FreshRuntimeGraph runtime = createFreshRuntime(path, null, false, storage, plan -> {
            transactions.incrementAndGet();
            FlushTransactionResult committed = transactionExecutor.execute(plan);
            assertEquals(FlushTransactionOutcome.COMMITTED, committed.outcome());
            assertNoActualTransaction();
            assertEquals(plan.flushTargetEventSeq(), checkpoint());
            assertEquals(0, storage.deleteAttempts());
            return FlushTransactionResult.ambiguousCommit(lostResponse);
        })) {
            runtime.recover();
            writeThreeAcrossTiles(runtime);
            Map<Path, byte[]> before = walBytes(path);
            List<WalRecord> records = runtime.fileWalReplaySource.readAfter(0).records();
            AmbiguousFlushCommitException failure = assertThrows(AmbiguousFlushCommitException.class, runtime::flush);
            assertSameThrowable(lostResponse, failure.getCause());
            var pending = runtime.pendingAmbiguousFlushStore.current().orElseThrow();
            assertEquals(0L, pending.expectedLastFlushedEventSeq());
            assertEquals(3L, pending.flushTargetEventSeq());
            assertEquals(DbBootstrapState.INITIALIZED, pending.bootstrapState());
            assertEquals(List.of(new DirtyTile(KEY_A, 3L, 2L), new DirtyTile(KEY_B, 2L, 1L)), pending.drainedDirtyTiles());
            assertDirtyEmpty(runtime);
            assertEquals(0, storage.deleteAttempts());
            assertWalBytes(before, path);
            byte[] pixelsA = runtime.board.getRequired(KEY_A).pixels();
            byte[] pixelsB = runtime.board.getRequired(KEY_B).pixels();
            assertDatabaseState(3L, List.of(1L, 2L, 3L), pixelsA, 2L, pixelsB, 1L);
            assertPersistedRecords(records);
            storage.beforeDelete(() -> {
                assertNoActualTransaction();
                assertTrue(runtime.pendingAmbiguousFlushStore.current().isEmpty());
                assertEquals(3L, checkpoint());
                assertDirtyEmpty(runtime);
            });
            assertEquals(FlushRunResult.RECONCILED_COMMIT, runtime.flush());
            assertEquals(1, transactions.get());
            assertEquals(2, storage.deleteAttempts());
            assertTrue(runtime.pendingAmbiguousFlushStore.current().isEmpty());
            assertEquals(List.of(segment(path, 2)), walFiles(path));
            assertDatabaseState(3L, List.of(1L, 2L, 3L), pixelsA, 2L, pixelsB, 1L);
            assertDirtyEmpty(runtime);
        }
    }

    @Test
    // events·tiles·checkpoint 실제 write 후 body 실패로 rollback, dirty/WAL 소유권과 정상 재시도·복구 검증
    void persistenceBodyRollbackPreservesWalAndDirtyThenFlushAndRestartSucceed() throws Exception {
        prepareInitialized(KEY_A, KEY_B);
        Path path = tempDirectory.resolve("rollback-segments.wal");
        MySqlRetentionTestStorage storage = new MySqlRetentionTestStorage(tempDirectory, path);
        AtomicBoolean failBody = new AtomicBoolean(true);
        AtomicInteger advanced = new AtomicInteger();
        RuntimeException injected = new RuntimeException("Test-only failure after checkpoint write");
        CheckpointFence failingFence = new CheckpointFence() {
            @Override
            public long lockMainCheckpoint() { return checkpointFence.lockMainCheckpoint(); }

            @Override
            public void advanceMainCheckpoint(long expected, long target) {
                checkpointFence.advanceMainCheckpoint(expected, target);
                advanced.incrementAndGet();
                if (failBody.get()) throw injected;
            }
        };
        var failingExecutor = new ProgrammaticFlushTransactionExecutor(transactionManager,
                new FlushPersistenceService(failingFence, tileMetadataReader, dbBootstrapClassifier,
                        pixelEventWriter, tileSnapshotWriter));
        byte[] pixelsA;
        byte[] pixelsB;
        try (FreshRuntimeGraph runtime = createFreshRuntime(path, null, false, storage, failingExecutor)) {
            runtime.recover();
            writeThreeAcrossTiles(runtime);
            Map<Path, byte[]> before = walBytes(path);
            pixelsA = runtime.board.getRequired(KEY_A).pixels();
            pixelsB = runtime.board.getRequired(KEY_B).pixels();
            FlushPersistenceRolledBackException failure = assertThrows(FlushPersistenceRolledBackException.class, runtime::flush);
            assertSameThrowable(injected, failure.getCause());
            assertEquals(1, advanced.get());
            assertDatabaseState(0L, List.of(), ZERO_TILE, 0L, ZERO_TILE, 0L);
            assertWalBytes(before, path);
            assertEquals(0, storage.deleteAttempts());
            assertDirtyState(runtime, List.of(new DirtyTile(KEY_A, 3L, 2L), new DirtyTile(KEY_B, 2L, 1L)));
            assertTrue(runtime.pendingAmbiguousFlushStore.current().isEmpty());
            assertTrue(runtime.serviceReadiness.isReady());
            assertMemoryState(runtime, pixelsA, 2L, pixelsB, 1L);
            failBody.set(false);
            assertEquals(FlushRunResult.COMMITTED, runtime.flush());
            assertEquals(2, advanced.get());
            assertEquals(2, storage.deleteAttempts());
            assertEquals(List.of(segment(path, 2)), walFiles(path));
            assertDatabaseState(3L, List.of(1L, 2L, 3L), pixelsA, 2L, pixelsB, 1L);
            assertDirtyEmpty(runtime);
        }
        try (FreshRuntimeGraph recovered = createSegmentedRuntime(path)) {
            recovered.recover();
            assertEquals(3L, recovered.eventSeqManager.currentLastIssued());
            assertTrue(recovered.fileWalReplaySource.readAfter(3L).records().isEmpty());
            assertMemoryState(recovered, pixelsA, 2L, pixelsB, 1L);
            assertEquals(FlushRunResult.NO_OP, recovered.flush());
            assertEquals(List.of(segment(path, 2)), walFiles(path));
            assertDatabaseState(3L, List.of(1L, 2L, 3L), pixelsA, 2L, pixelsB, 1L);
        }
    }

    @Test
    // 원래 JSON Lines legacy를 그대로 채택한 뒤 세 번의 회전·정리·새 graph에서 번호 및 record 보존
    void legacyWalRepeatedRotationCleanupAndRestartNeverReuseNumbersOrDuplicateRecords() throws Exception {
        prepareBootstrap();
        Path path = tempDirectory.resolve("legacy-migration.wal");
        WalRecord legacy = record(5L, KEY_A, 17, LocalDateTime.of(2026, 9, 15, 1, 2, 3, 123_456_789));
        byte[] original = new WalRecordJsonCodec(new ObjectMapper()).serializeLine(legacy);
        Files.write(path, original, StandardOpenOption.CREATE_NEW);
        List<WalRecord> allRecords = new ArrayList<>(List.of(legacy));
        long tail = 5L;
        byte[] pixelsA = TileState.allWhite().pixels();
        pixelsA[0] = 17;
        byte[] pixelsB = TileState.allWhite().pixels();
        for (int round = 0; round < 3; round++) {
            try (FreshRuntimeGraph runtime = createSegmentedRuntime(path)) {
                runtime.recover();
                assertTrue(runtime.serviceReadiness.isReady());
                assertEquals(tail, runtime.eventSeqManager.currentLastIssued());
                assertMemoryState(runtime, pixelsA, round + 1L, pixelsB, round);
                if (round == 0) {
                    assertArrayEquals(original, Files.readAllBytes(path));
                    assertEquals(List.of(path), walFiles(path));
                } else {
                    assertFalse(Files.exists(path));
                    assertEquals(List.of(segment(path, round * 2L)), walFiles(path));
                    assertTrue(runtime.fileWalReplaySource.readAfter(tail).records().isEmpty());
                }
                assertEquals(tail + 1L, runtime.write(7L, BoardConstants.TILE_SIZE, 0, 20 + round).eventSeq());
                assertEquals(tail + 2L, runtime.write(7L, 0, 0, 30 + round).eventSeq());
                allRecords.addAll(runtime.fileWalReplaySource.readAfter(tail).records());
                tail += 2;
                pixelsA = runtime.board.getRequired(KEY_A).pixels();
                pixelsB = runtime.board.getRequired(KEY_B).pixels();
                assertEquals(30 + round, Byte.toUnsignedInt(pixelsA[0]));
                assertEquals(20 + round, Byte.toUnsignedInt(pixelsB[0]));
                assertEquals(FlushRunResult.COMMITTED, runtime.flush());
                assertDatabaseState(tail, eventSequences(allRecords), pixelsA, round + 2L, pixelsB, round + 1L);
                assertPersistedRecords(allRecords);
                Path retained = segment(path, (round + 1L) * 2);
                assertEquals(List.of(retained), walFiles(path));
                assertEquals(List.of(tail), eventSequences(readFileRecords(retained)));
                assertFalse(Files.exists(path));
                assertDirtyEmpty(runtime);
            }
        }
        try (FreshRuntimeGraph recovered = createSegmentedRuntime(path)) {
            recovered.recover();
            assertEquals(11L, recovered.eventSeqManager.currentLastIssued());
            assertEquals(11L, recovered.fileWalReplaySource.readAfter(11L).walLastEventSeq());
            assertTrue(recovered.fileWalReplaySource.readAfter(11L).records().isEmpty());
            assertMemoryState(recovered, pixelsA, 4L, pixelsB, 3L);
            assertEquals(12L, recovered.write(7L, 1, 0, 40).eventSeq());
            assertEquals(List.of(segment(path, 6), segment(path, 7)), walFiles(path));
            assertEquals(List.of(12L), eventSequences(readFileRecords(segment(path, 7))));
            assertDatabaseState(11L, List.of(5L, 6L, 7L, 8L, 9L, 10L, 11L), pixelsA, 4L, pixelsB, 3L);
        }
    }

    private void writeThreeAcrossTiles(FreshRuntimeGraph runtime) {
        assertEquals(1L, runtime.write(7L, 0, 0, 11).eventSeq());
        assertEquals(2L, runtime.write(7L, BoardConstants.TILE_SIZE, 0, 21).eventSeq());
        assertEquals(3L, runtime.write(7L, 0, 0, 12).eventSeq());
    }

    private Map<Path, byte[]> walBytes(Path path) throws IOException {
        Map<Path, byte[]> bytes = new HashMap<>();
        for (Path file : walFiles(path)) bytes.put(file, Files.readAllBytes(file));
        return bytes;
    }

    private void assertWalBytes(Map<Path, byte[]> expected, Path path) throws IOException {
        assertEquals(expected.keySet(), new HashSet<>(walFiles(path)));
        for (var entry : expected.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }

    private Path segment(Path base, long number) {
        return number == 0 ? base : base.resolveSibling(base.getFileName() + ".seg-"
                + String.format(Locale.ROOT, "%019d", number));
    }

    private List<Path> walFiles(Path base) throws IOException {
        try (Stream<Path> files = Files.list(base.getParent())) {
            String name = base.getFileName().toString();
            return files.filter(file -> file.getFileName().toString().equals(name)
                    || file.getFileName().toString().startsWith(name + ".seg-")).sorted().toList();
        }
    }

    private List<WalRecord> readFileRecords(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        assertTrue(bytes.length > 0);
        assertEquals((byte) '\n', bytes[bytes.length - 1]);
        WalRecordParser parser = new WalRecordParser(new ObjectMapper());
        List<WalRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            records.add(parser.parseLine(line, records.size() + 1L));
        }
        return records;
    }

    private List<Long> eventSequences(List<WalRecord> records) {
        return records.stream().map(WalRecord::eventSeq).toList();
    }

    private void assertDatabaseState(long expectedCheckpoint, List<Long> events,
                                     byte[] pixelsA, long versionA, byte[] pixelsB, long versionB) {
        assertNoActualTransaction();
        assertEquals(expectedCheckpoint, checkpoint());
        assertEquals(events, jdbcTemplate.queryForList("SELECT event_seq FROM pixel_events ORDER BY event_seq", Long.class));
        assertEquals(BoardConstants.Z0_TILE_COUNT, tileCount());
        assertTrue(canonicalKeys.exactlyMatches(tileMetadataReader.readAllTileKeys()));
        assertArrayEquals(pixelsA, tileData(KEY_A));
        assertEquals(versionA, tileVersion(KEY_A));
        assertArrayEquals(pixelsB, tileData(KEY_B));
        assertEquals(versionB, tileVersion(KEY_B));
    }

    private void assertMemoryState(FreshRuntimeGraph runtime, byte[] pixelsA, long versionA,
                                   byte[] pixelsB, long versionB) {
        assertEquals(BoardConstants.Z0_TILE_COUNT, runtime.board.size());
        assertArrayEquals(pixelsA, runtime.board.getRequired(KEY_A).pixels());
        assertEquals(versionA, runtime.board.getRequired(KEY_A).tileVersion());
        assertArrayEquals(pixelsB, runtime.board.getRequired(KEY_B).pixels());
        assertEquals(versionB, runtime.board.getRequired(KEY_B).tileVersion());
    }

    private void assertDirtyState(FreshRuntimeGraph runtime, List<DirtyTile> expected) {
        List<DirtyTile> actual = runtime.dirtyTileTracker.drainDirtyTiles();
        runtime.dirtyTileTracker.restoreDirtyTiles(actual);
        assertEquals(expected, actual);
    }

    private void assertPersistedRecords(List<WalRecord> records) {
        for (WalRecord record : records) {
            assertEquals(1, eventCount(record.eventSeq()));
            assertEquals(record.userId(), jdbcTemplate.queryForObject(
                    "SELECT user_id FROM pixel_events WHERE event_seq = ?", Long.class, record.eventSeq()));
            assertEquals(record.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), eventCreatedAt(record.eventSeq()));
        }
    }

    @TestFactory
    // 실제 MySQL의 잘못된 checkpoint/tile shape를 case별 fresh recovery/plan-capture graph에서 fail-fast 검증
    Stream<DynamicTest> inconsistentDatabaseShapesFailBeforeWalMemorySeedReadyAndWorker() {
        List<DatabaseFailureCase> cases = List.of(
                new DatabaseFailureCase("positive checkpoint and zero tiles", "positive-zero", () -> {
                    resetDatabaseToBootstrap();
                    executeGuardedUpdate(
                            "UPDATE wal_checkpoint SET last_flushed_event_seq = 1 WHERE checkpoint_name = 'main'"
                    );
                }),
                new DatabaseFailureCase("partial z zero rows", "partial-z0", () -> {
                    resetDatabaseToBootstrap();
                    preparePartialDatabase();
                }),
                new DatabaseFailureCase("canonical rows plus non z zero row", "extra-z", () -> {
                    resetDatabaseToBootstrap();
                    prepareInitialized();
                    executeGuardedUpdate(
                            "INSERT INTO tiles(z, tx, ty, data, tile_version) VALUES (?, ?, ?, ?, ?)",
                            1,
                            0,
                            0,
                            ZERO_TILE,
                            0L
                    );
                }),
                new DatabaseFailureCase("missing main checkpoint", "missing-main", () -> {
                    resetDatabaseToBootstrap();
                    prepareInitialized();
                    executeGuardedUpdate("DELETE FROM wal_checkpoint WHERE checkpoint_name = 'main'");
                })
        );

        return cases.stream().map(failureCase -> DynamicTest.dynamicTest(
                failureCase.name(),
                () -> assertDatabaseFailureCase(failureCase)
        ));
    }

    @TestFactory
    // 실제 WAL scan 뒤 조립된 잘못된 batch도 memory/seed/ready 이전에 차단
    Stream<DynamicTest> inconsistentWalBatchesFailBeforeMemorySeedReadyAndWorker() {
        List<WalFailureCase> cases = List.of(
                new WalFailureCase(
                        "WAL tail before checkpoint",
                        "tail-before-checkpoint",
                        2L,
                        UnaryOperator.identity()
                ),
                new WalFailureCase(
                        "empty records with larger tail",
                        "empty-larger-tail",
                        0L,
                        batch -> new WalReplayBatch(List.of(), batch.walLastEventSeq())
                ),
                new WalFailureCase(
                        "last replay record and tail mismatch",
                        "last-tail-mismatch",
                        0L,
                        batch -> new WalReplayBatch(batch.records(), batch.walLastEventSeq() + 1L)
                )
        );

        return cases.stream().map(failureCase -> DynamicTest.dynamicTest(
                failureCase.name(),
                () -> assertWalFailureCase(failureCase)
        ));
    }

    private FreshRuntimeGraph createFreshRuntime(Path walPath) {
        return createFreshRuntime(walPath, null, false);
    }

    private FreshRuntimeGraph createFreshRuntime(
            Path walPath,
            UnaryOperator<WalReplayBatch> replayBatchTransform
    ) {
        return createFreshRuntime(
                walPath,
                Objects.requireNonNull(replayBatchTransform, "replayBatchTransform must not be null"),
                true
        );
    }

    private FreshRuntimeGraph createFreshRuntime(
            Path walPath,
            UnaryOperator<WalReplayBatch> replayBatchTransform,
            boolean failureObservationEnabled
    ) {
        return createFreshRuntime(walPath, replayBatchTransform, failureObservationEnabled,
                newWalStorage(walPath, new WalProperties().getMaxSegmentBytes()), transactionExecutor);
    }

    // record 한 건보다 작은 M으로 실제 회전을 결정론적으로 만드는 C 통합 fixture
    private FreshRuntimeGraph createSegmentedRuntime(Path walPath) {
        return createFreshRuntime(walPath, null, false, newWalStorage(walPath, 1L), transactionExecutor);
    }

    private SegmentedWalStorage newWalStorage(Path walPath, long maxBytes) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(walPath);
        properties.setMaxSegmentBytes(maxBytes);
        ObjectMapper mapper = new ObjectMapper();
        return new SegmentedWalStorage(properties, new WalRecordParser(mapper), new WalRecordJsonCodec(mapper));
    }

    private FreshRuntimeGraph createFreshRuntime(
            Path walPath,
            UnaryOperator<WalReplayBatch> replayBatchTransform,
            boolean failureObservationEnabled,
            SegmentedWalStorage storage,
            FlushTransactionExecutor executor
    ) {
        InMemoryTileBoard board = failureObservationEnabled
                ? new TrackingInMemoryTileBoard()
                : new InMemoryTileBoard();
        EventSeqManager eventSeqManager = new EventSeqManager();
        ServiceReadiness serviceReadiness = new ServiceReadiness();
        SynchronizedDirtyTileTracker dirtyTileTracker = new SynchronizedDirtyTileTracker();
        FlushBoundaryCoordinator flushBoundaryCoordinator = new FlushBoundaryCoordinator();
        FlushSingleFlightGuard flushSingleFlightGuard = new FlushSingleFlightGuard();
        PendingAmbiguousFlushStore pendingAmbiguousFlushStore = new PendingAmbiguousFlushStore();

        FileWalAppender fileWalAppender = new FileWalAppender(storage);
        FileWalReplaySource fileWalReplaySource = failureObservationEnabled
                ? new TrackingFileWalReplaySource(
                        storage,
                        replayBatchTransform
                )
                : new FileWalReplaySource(storage);
        StartupRecoveryService startupRecoveryService = new StartupRecoveryService(
                recoveryDbViewCaptureService,
                dbBootstrapClassifier,
                canonicalKeys,
                fileWalReplaySource,
                board,
                eventSeqManager,
                serviceReadiness
        );
        PixelWriteService pixelWriteService = new PixelWriteService(
                eventSeqManager,
                fileWalAppender,
                board,
                serviceReadiness
        );
        PixelCooldown noOpCooldown = new PixelCooldown() {
            @Override
            public void checkWritable(long userId) {
                // 실제 Redis 없이 command/write/dirty 경계만 검증하는 제한 fixture
            }

            @Override
            public void startCooldown(long userId) {
                // 성공 write 이후 Redis 후처리 책임 제외
            }
        };
        PixelBroadcastService noOpBroadcast = message -> {
            // 실제 WebSocket 없이 durable runtime 상태 전이만 검증
        };
        PixelCommandService pixelCommandService = new PixelCommandService(
                noOpCooldown,
                flushBoundaryCoordinator,
                pixelWriteService,
                dirtyTileTracker,
                noOpBroadcast,
                serviceReadiness
        );
        FlushPlanCaptureService flushPlanCaptureService = new FlushPlanCaptureService(
                serviceReadiness,
                checkpointReader,
                tileMetadataReader,
                dbBootstrapClassifier,
                flushBoundaryCoordinator,
                fileWalReplaySource,
                dirtyTileTracker,
                board
        );
        FlushWorker flushWorker = new FlushWorker(
                flushSingleFlightGuard,
                serviceReadiness,
                flushPlanCaptureService,
                executor,
                pendingAmbiguousFlushStore,
                flushReconciliationService,
                dirtyTileTracker,
                new dev.cgt.pixelplace.flush.application.FlushWalRetention(flushBoundaryCoordinator, serviceReadiness, pendingAmbiguousFlushStore, storage)
        );

        return new FreshRuntimeGraph(
                storage,
                board,
                eventSeqManager,
                serviceReadiness,
                dirtyTileTracker,
                flushBoundaryCoordinator,
                flushSingleFlightGuard,
                pendingAmbiguousFlushStore,
                fileWalAppender,
                fileWalReplaySource,
                startupRecoveryService,
                pixelWriteService,
                pixelCommandService,
                flushPlanCaptureService,
                flushWorker
        );
    }

    private void assertRuntimePristine(FreshRuntimeGraph runtime) {
        assertFalse(runtime.serviceReadiness.isReady());
        assertEquals(0L, runtime.eventSeqManager.currentLastIssued());
        assertEquals(BoardConstants.Z0_TILE_COUNT, runtime.board.size());
        assertDirtyEmpty(runtime);
        assertTrue(runtime.pendingAmbiguousFlushStore.current().isEmpty());
        assertFalse(runtime.retired());
    }

    private void assertFreshRuntimeIsolation(FreshRuntimeGraph previous, FreshRuntimeGraph current) {
        assertNotSame(previous.storage, current.storage);
        assertNotSame(previous.board, current.board);
        assertNotSame(previous.eventSeqManager, current.eventSeqManager);
        assertNotSame(previous.serviceReadiness, current.serviceReadiness);
        assertNotSame(previous.dirtyTileTracker, current.dirtyTileTracker);
        assertNotSame(previous.flushBoundaryCoordinator, current.flushBoundaryCoordinator);
        assertNotSame(previous.flushSingleFlightGuard, current.flushSingleFlightGuard);
        assertNotSame(previous.pendingAmbiguousFlushStore, current.pendingAmbiguousFlushStore);
        assertNotSame(previous.fileWalAppender, current.fileWalAppender);
        assertNotSame(previous.fileWalReplaySource, current.fileWalReplaySource);
        assertNotSame(previous.startupRecoveryService, current.startupRecoveryService);
        assertNotSame(previous.pixelWriteService, current.pixelWriteService);
        assertNotSame(previous.pixelCommandService, current.pixelCommandService);
        assertNotSame(previous.flushPlanCaptureService, current.flushPlanCaptureService);
        assertNotSame(previous.flushWorker, current.flushWorker);
    }

    private void assertRetiredRuntimeRejected(FreshRuntimeGraph runtime) {
        assertTrue(runtime.retired());
        assertFalse(runtime.serviceReadiness.isReady());
        assertThrows(ServiceNotReadyException.class, runtime.flushWorker::flushOnce);
    }

    private void retireQuietly(FreshRuntimeGraph runtime) {
        if (runtime != null) {
            runtime.retire();
        }
    }

    private void assertDirtyEmpty(FreshRuntimeGraph runtime) {
        assertTrue(runtime.dirtyTileTracker.drainDirtyTiles().isEmpty());
    }

    private void assertNoActualTransaction() {
        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "Production call must start without a test-managed outer transaction."
        );
    }

    private void assertDatabaseFailureCase(DatabaseFailureCase failureCase) {
        failureCase.setup().run();
        if (!failureCase.fileStem().equals("missing-main")) {
            assertEquals(
                    DbBootstrapState.INCONSISTENT,
                    dbBootstrapClassifier.classify(checkpoint(), tileMetadataReader.readAllTileKeys())
            );
        }

        FreshRuntimeGraph recoveryRuntime = createFreshRuntime(
                tempDirectory.resolve(failureCase.fileStem() + "-recovery.wal"),
                UnaryOperator.identity()
        );
        int initialMemoryMutationCalls = recoveryRuntime.memoryMutationCalls();
        try {
            assertRuntimePristine(recoveryRuntime);
            assertNoActualTransaction();
            assertThrows(IllegalStateException.class, recoveryRuntime::recover);
            assertEquals(0, recoveryRuntime.replayReadCount());
            assertEquals(initialMemoryMutationCalls, recoveryRuntime.memoryMutationCalls());
            assertEquals(0L, recoveryRuntime.eventSeqManager.currentLastIssued());
            assertFalse(recoveryRuntime.serviceReadiness.isReady());
            assertDirtyEmpty(recoveryRuntime);
            assertTrue(recoveryRuntime.pendingAmbiguousFlushStore.current().isEmpty());
            assertEquals(0, recoveryRuntime.workerInvocationCount());
        } finally {
            recoveryRuntime.retire();
        }

        FreshRuntimeGraph planRuntime = createFreshRuntime(
                tempDirectory.resolve(failureCase.fileStem() + "-plan.wal"),
                UnaryOperator.identity()
        );
        int initialPlanMemoryMutationCalls = planRuntime.memoryMutationCalls();
        try {
            assertRuntimePristine(planRuntime);
            planRuntime.serviceReadiness.markReady();
            assertNoActualTransaction();
            assertThrows(IllegalStateException.class, planRuntime.flushPlanCaptureService::capturePlan);
            assertEquals(0, planRuntime.replayReadCount());
            assertEquals(initialPlanMemoryMutationCalls, planRuntime.memoryMutationCalls());
            assertEquals(0L, planRuntime.eventSeqManager.currentLastIssued());
            assertTrue(planRuntime.serviceReadiness.isReady());
            assertDirtyEmpty(planRuntime);
            assertTrue(planRuntime.pendingAmbiguousFlushStore.current().isEmpty());
            assertEquals(0, planRuntime.workerInvocationCount());
        } finally {
            planRuntime.retire();
        }
    }

    private void assertWalFailureCase(WalFailureCase failureCase) {
        resetDatabaseToBootstrap();
        prepareInitialized();
        executeGuardedUpdate(
                "UPDATE wal_checkpoint SET last_flushed_event_seq = ? WHERE checkpoint_name = 'main'",
                failureCase.checkpoint()
        );

        Path walPath = tempDirectory.resolve(failureCase.fileStem() + ".wal");
        FreshRuntimeGraph runtime = createFreshRuntime(walPath, failureCase.batchTransform());
        try {
            runtime.fileWalAppender.appendAndFsync(
                    record(1L, KEY_A, 17, LocalDateTime.of(2026, 4, 3, 6, 0))
            );
            assertTrue(Files.exists(walPath));
            assertTrue(Files.size(walPath) > 0L);
            int initialMemoryMutationCalls = runtime.memoryMutationCalls();

            assertRuntimePristine(runtime);
            assertNoActualTransaction();
            assertThrows(IllegalStateException.class, runtime::recover);
            assertEquals(1, runtime.replayReadCount());
            assertEquals(initialMemoryMutationCalls, runtime.memoryMutationCalls());
            assertEquals(0L, runtime.eventSeqManager.currentLastIssued());
            assertFalse(runtime.serviceReadiness.isReady());
            assertDirtyEmpty(runtime);
            assertTrue(runtime.pendingAmbiguousFlushStore.current().isEmpty());
            assertEquals(0, runtime.workerInvocationCount());
        } catch (IOException exception) {
            throw new AssertionError("Failed to inspect test WAL.", exception);
        } finally {
            runtime.retire();
        }
    }

    private void resetDatabaseToBootstrap() {
        executeGuardedUpdate("DELETE FROM pixel_events");
        executeGuardedUpdate("DELETE FROM tiles");
        executeGuardedUpdate("DELETE FROM wal_checkpoint");
        executeGuardedUpdate(
                "INSERT INTO wal_checkpoint(checkpoint_name, last_flushed_event_seq) VALUES ('main', 0)"
        );
    }

    private void prepareBootstrap() {
        executeGuardedUpdate("DELETE FROM tiles");
        assertEquals(DbBootstrapState.BOOTSTRAP_PENDING, dbBootstrapClassifier.classify(
                checkpoint(),
                tileMetadataReader.readAllTileKeys()
        ));
    }

    private void prepareInitialized(TileKey... resetKeys) {
        List<TileKey> currentKeys = tileMetadataReader.readAllTileKeys();
        if (!canonicalKeys.exactlyMatches(currentKeys)) {
            executeGuardedUpdate("DELETE FROM tiles");
            seedCanonicalTiles();
        }
        for (TileKey key : resetKeys) {
            executeGuardedUpdate(
                    """
                            UPDATE tiles
                            SET data = ?, tile_version = 0
                            WHERE z = ? AND tx = ? AND ty = ?
                            """,
                    ZERO_TILE,
                    key.z(),
                    key.tx(),
                    key.ty()
            );
        }
        assertEquals(BoardConstants.Z0_TILE_COUNT, tileCount());
        assertEquals(DbBootstrapState.INITIALIZED, dbBootstrapClassifier.classify(
                checkpoint(),
                tileMetadataReader.readAllTileKeys()
        ));
    }

    private void preparePartialDatabase() {
        executeGuardedUpdate("DELETE FROM tiles");
        executeGuardedUpdate(
                "INSERT INTO tiles(z, tx, ty, data, tile_version) VALUES (?, ?, ?, ?, ?)",
                KEY_A.z(),
                KEY_A.tx(),
                KEY_A.ty(),
                ZERO_TILE,
                0L
        );
    }

    private void seedCanonicalTiles() {
        List<TileKey> keys = canonicalKeys.orderedKeys();
        int[] results = Objects.requireNonNull(jdbcTemplate.execute((ConnectionCallback<int[]>) connection -> {
            requireTestCatalog(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tiles(z, tx, ty, data, tile_version) VALUES (?, ?, ?, ?, ?)"
            )) {
                for (TileKey key : keys) {
                    statement.setInt(1, key.z());
                    statement.setInt(2, key.tx());
                    statement.setInt(3, key.ty());
                    statement.setBytes(4, ZERO_TILE);
                    statement.setLong(5, 0L);
                    statement.addBatch();
                }
                return statement.executeBatch();
            }
        }));
        assertEquals(keys.size(), results.length);
    }

    private FlushPlan bootstrapPlan(
            List<WalRecord> records,
            TileKey changedKey,
            byte[] changedPixels,
            long changedVersion
    ) {
        List<FlushTileSnapshot> snapshots = canonicalKeys.orderedKeys().stream()
                .map(key -> key.equals(changedKey)
                        ? snapshot(key, changedPixels, changedVersion)
                        : snapshot(key, ZERO_TILE, 0L))
                .toList();
        return FlushPlan.nonNoOp(
                0L,
                records.get(records.size() - 1).eventSeq(),
                records,
                snapshots,
                List.of(),
                DbBootstrapState.BOOTSTRAP_PENDING
        );
    }

    private FlushPlan singleInitializedPlan() {
        return initializedPlan(
                0L,
                List.of(record(1L, KEY_A, 17, LocalDateTime.of(2026, 4, 3, 6, 0))),
                List.of(snapshot(KEY_A, filledPixels((byte) 7), 2L))
        );
    }

    private FlushPlan initializedPlan(
            long expectedCheckpoint,
            List<WalRecord> records,
            List<FlushTileSnapshot> snapshots
    ) {
        return FlushPlan.nonNoOp(
                expectedCheckpoint,
                records.get(records.size() - 1).eventSeq(),
                records,
                snapshots,
                List.of(),
                DbBootstrapState.INITIALIZED
        );
    }

    private FlushTileSnapshot snapshot(TileKey key, byte[] pixels, long version) {
        return new FlushTileSnapshot(key, pixels, version);
    }

    private WalRecord record(
            long eventSeq,
            TileKey key,
            int color,
            LocalDateTime createdAt
    ) {
        return new WalRecord(
                eventSeq,
                7L,
                key.z(),
                key.tx(),
                key.ty(),
                key.tx() * BoardConstants.TILE_SIZE,
                key.ty() * BoardConstants.TILE_SIZE,
                color,
                createdAt
        );
    }

    private byte[] filledPixels(byte value) {
        byte[] pixels = new byte[BoardConstants.TILE_PIXEL_COUNT];
        Arrays.fill(pixels, value);
        return pixels;
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pixel_events", Integer.class);
    }

    private int eventCount(long eventSeq) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pixel_events WHERE event_seq = ?",
                Integer.class,
                eventSeq
        );
    }

    private int tileCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tiles", Integer.class);
    }

    private long checkpoint() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT last_flushed_event_seq FROM wal_checkpoint WHERE checkpoint_name = 'main'",
                Long.class
        );
        assertNotNull(value);
        return value;
    }

    private LocalDateTime eventCreatedAt(long eventSeq) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at FROM pixel_events WHERE event_seq = ?",
                (resultSet, rowNumber) -> resultSet.getObject(1, LocalDateTime.class),
                eventSeq
        );
    }

    private byte[] tileData(TileKey key) {
        return jdbcTemplate.queryForObject(
                "SELECT data FROM tiles WHERE z = ? AND tx = ? AND ty = ?",
                (resultSet, rowNumber) -> resultSet.getBytes(1),
                key.z(),
                key.tx(),
                key.ty()
        );
    }

    private long tileVersion(TileKey key) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT tile_version FROM tiles WHERE z = ? AND tx = ? AND ty = ?",
                Long.class,
                key.z(),
                key.tx(),
                key.ty()
        );
        assertNotNull(value);
        return value;
    }

    private void assertFailure(FlushTransactionResult result, Throwable expectedCause) {
        assertEquals(FlushTransactionOutcome.DEFINITE_ROLLBACK, result.outcome());
        assertSameThrowable(expectedCause, result.failureCause().orElseThrow());
    }

    private void assertSameThrowable(Throwable expected, Throwable actual) {
        assertTrue(expected == actual, () -> "Expected same throwable instance. actual=" + actual);
    }

    private TransactionTemplate requiresNewTemplate(String name) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setName(name);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(false);
        return template;
    }

    private void inNewTransaction(String name, Runnable action) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = transactionManager.getTransaction(definition);
        try {
            action.run();
        } catch (RuntimeException | Error bodyFailure) {
            Throwable primaryFailure = bodyFailure;
            try {
                transactionManager.rollback(status);
            } catch (RuntimeException | Error rollbackFailure) {
                primaryFailure = selectPrimaryFailure(bodyFailure, rollbackFailure);
            }
            if (primaryFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) primaryFailure;
        }
        // commit 호출 이후 예외가 나도 완료 status에 rollback 재호출 금지
        transactionManager.commit(status);
    }

    private Throwable selectPrimaryFailure(Throwable firstFailure, Throwable laterFailure) {
        if (firstFailure instanceof Error) {
            addSuppressed(firstFailure, laterFailure);
            return firstFailure;
        }
        if (laterFailure instanceof Error) {
            addSuppressed(laterFailure, firstFailure);
            return laterFailure;
        }
        addSuppressed(firstFailure, laterFailure);
        return firstFailure;
    }

    private void addSuppressed(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch wait timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Latch wait interrupted.", exception);
        }
    }

    private String requireTestCatalog(Connection connection) throws SQLException {
        String catalog = selectServerValue(connection, "SELECT DATABASE()");
        assertEquals(
                CATALOG,
                catalog,
                "Destructive MySQL integration work is allowed only in pixel_place_test."
        );
        return catalog;
    }

    private String selectServerValue(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            assertTrue(resultSet.next());
            return Objects.requireNonNull(resultSet.getString(1), query + " returned null");
        }
    }

    private void assertRequiredMySqlVersion(String version, String versionComment) {
        String normalizedVersion = version.toLowerCase(Locale.ROOT);
        String normalizedComment = versionComment.toLowerCase(Locale.ROOT);
        assertFalse(normalizedVersion.contains("mariadb"), "MariaDB is not accepted as MySQL 8.4.11.");
        assertFalse(normalizedComment.contains("mariadb"), "MariaDB is not accepted as MySQL 8.4.11.");
        assertTrue(
                normalizedComment.contains("mysql"),
                () -> "Unexpected database product comment: " + versionComment
        );
        assertTrue(
                version.matches("^8\\.4\\.11(?:[-+~].*)?$"),
                () -> "MySQL semantic version must be " + REQUIRED_MYSQL_VERSION + ". actual=" + version
        );
    }

    private List<String> loadAndValidateRootSqlStatements() throws IOException {
        return parseRootSqlStatements(Files.readString(Path.of("pixel_place.sql"), StandardCharsets.UTF_8));
    }

    // DB 없이도 production catalog 제외와 seed 비퇴행 계약 검증 가능
    private List<String> parseRootSqlStatements(String rootSql) {
        String withoutComments = rootSql.lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .reduce("", (left, right) -> left + "\n" + right);
        List<String> executableStatements = new ArrayList<>();
        Set<String> createTableNames = new HashSet<>();
        boolean createDatabaseFound = false;
        boolean useDatabaseFound = false;
        int checkpointSeedCount = 0;

        for (String rawStatement : withoutComments.split(";")) {
            String statement = rawStatement.trim();
            if (statement.isEmpty()) {
                continue;
            }
            String normalized = normalizeSql(statement);
            if (normalized.contains("DROP DATABASE")) {
                throw new IllegalStateException("Root SQL must not contain DROP DATABASE.");
            }
            if (normalized.startsWith("CREATE DATABASE")) {
                String expected = "CREATE DATABASE IF NOT EXISTS `PIXEL_PLACE` "
                        + "CHARACTER SET UTF8MB4 COLLATE UTF8MB4_UNICODE_CI";
                if (!normalized.equals(expected) || createDatabaseFound) {
                    throw new IllegalStateException("Unexpected CREATE DATABASE statement in root SQL.");
                }
                createDatabaseFound = true;
                continue;
            }
            if (normalized.startsWith("USE ")) {
                if (!normalized.equals("USE `PIXEL_PLACE`") || useDatabaseFound) {
                    throw new IllegalStateException("Unexpected USE statement in root SQL.");
                }
                useDatabaseFound = true;
                continue;
            }
            if (normalized.startsWith("SELECT ")) {
                continue;
            }

            rejectSchemaQualifiedIdentifier(normalized);
            String unquoted = normalized.replace("`", "");
            if (unquoted.startsWith("CREATE TABLE IF NOT EXISTS TILES ")) {
                if (!createTableNames.add("tiles")) throw new IllegalStateException("Duplicate root table definition.");
            } else if (unquoted.startsWith("CREATE TABLE IF NOT EXISTS PIXEL_EVENTS ")) {
                if (!createTableNames.add("pixel_events")) throw new IllegalStateException("Duplicate root table definition.");
            } else if (unquoted.startsWith("CREATE TABLE IF NOT EXISTS WAL_CHECKPOINT ")) {
                if (!createTableNames.add("wal_checkpoint")) throw new IllegalStateException("Duplicate root table definition.");
            } else if (unquoted.startsWith("CREATE TABLE IF NOT EXISTS USERS ")) {
                if (!createTableNames.add("users")) throw new IllegalStateException("Duplicate root table definition.");
            } else if (unquoted.startsWith("INSERT INTO WAL_CHECKPOINT ")) {
                checkpointSeedCount++;
                if (!unquoted.equals("INSERT INTO WAL_CHECKPOINT ( CHECKPOINT_NAME, LAST_FLUSHED_EVENT_SEQ ) VALUES ( 'MAIN', 0 ) ON DUPLICATE KEY UPDATE CHECKPOINT_NAME = CHECKPOINT_NAME")) {
                    // 기존 checkpoint를 되돌리는 seed는 실행 전에 차단
                    throw new IllegalStateException("Unexpected checkpoint seed contract.");
                }
            } else {
                throw new IllegalStateException("Unexpected executable statement in root SQL: " + normalized);
            }
            executableStatements.add(statement);
        }

        if (!createDatabaseFound || !useDatabaseFound) {
            throw new IllegalStateException("Root SQL must retain the production CREATE DATABASE and USE statements.");
        }
        if (!createTableNames.equals(Set.of("tiles", "pixel_events", "wal_checkpoint", "users"))) {
            throw new IllegalStateException("Root SQL must define exactly the four expected tables.");
        }
        if (checkpointSeedCount != 1) {
            throw new IllegalStateException("Root SQL must contain exactly one wal_checkpoint seed statement.");
        }
        return List.copyOf(executableStatements);
    }

    private String normalizeSql(String statement) {
        return statement.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private void rejectSchemaQualifiedIdentifier(String normalizedStatement) {
        String unquoted = normalizedStatement.replace("`", "");
        if (unquoted.matches("(?s).*\\b[A-Z][A-Z0-9_]*\\s*\\.\\s*[A-Z][A-Z0-9_]*\\b.*")) {
            throw new IllegalStateException("Schema-qualified identifiers are forbidden in executable root SQL.");
        }
    }

    private void dropTestTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            requireTestCatalog(connection);
            statement.executeUpdate("DROP TABLE IF EXISTS pixel_events");
            requireTestCatalog(connection);
            statement.executeUpdate("DROP TABLE IF EXISTS tiles");
            requireTestCatalog(connection);
            statement.executeUpdate("DROP TABLE IF EXISTS wal_checkpoint");
            requireTestCatalog(connection);
            statement.executeUpdate("DROP TABLE IF EXISTS users");
        }
    }

    private void executeValidatedRootSql(
            Connection connection,
            List<String> executableStatements
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String executable : executableStatements) {
                requireTestCatalog(connection);
                statement.execute(executable);
            }
        }
    }

    private int executeGuardedUpdate(String sql, Object... parameters) {
        Integer result = jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            requireTestCatalog(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    Object parameter = parameters[index];
                    if (parameter instanceof byte[] bytes) {
                        statement.setBytes(index + 1, bytes);
                    } else {
                        statement.setObject(index + 1, parameter);
                    }
                }
                return statement.executeUpdate();
            }
        });
        return Objects.requireNonNull(result, "guarded update returned null");
    }

    private void assertRootSchemaContract() {
        assertEquals(4, jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = ?
                          AND table_name IN ('tiles', 'pixel_events', 'wal_checkpoint', 'users')
                        """,
                Integer.class,
                CATALOG
        ));

        List<Map<String, Object>> columnRows = jdbcTemplate.queryForList(
                """
                        SELECT table_name, column_name, column_type, is_nullable, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = ?
                          AND table_name IN ('tiles', 'pixel_events', 'wal_checkpoint', 'users')
                        """,
                CATALOG
        );
        Map<String, String> actualColumns = new HashMap<>();
        for (Map<String, Object> row : columnRows) {
            String table = row.get("table_name").toString().toLowerCase(Locale.ROOT);
            String column = row.get("column_name").toString().toLowerCase(Locale.ROOT);
            String type = row.get("column_type").toString().toLowerCase(Locale.ROOT);
            String nullable = row.get("is_nullable").toString().toUpperCase(Locale.ROOT);
            Object precision = row.get("datetime_precision");
            actualColumns.put(
                    table + "." + column,
                    type + "|" + nullable + "|" + (precision == null ? "-" : precision)
            );
        }
        assertEquals(expectedColumns(), actualColumns);

        List<Map<String, Object>> indexRows = jdbcTemplate.queryForList(
                """
                        SELECT table_name, index_name, non_unique, seq_in_index, column_name
                        FROM information_schema.statistics
                        WHERE table_schema = ?
                          AND table_name IN ('tiles', 'pixel_events', 'wal_checkpoint', 'users')
                        """,
                CATALOG
        );
        Set<String> actualIndexes = new HashSet<>();
        for (Map<String, Object> row : indexRows) {
            actualIndexes.add(
                    row.get("table_name").toString().toLowerCase(Locale.ROOT)
                            + "|" + row.get("index_name").toString()
                            + "|" + ((Number) row.get("non_unique")).intValue()
                            + "|" + ((Number) row.get("seq_in_index")).intValue()
                            + "|" + row.get("column_name").toString().toLowerCase(Locale.ROOT)
            );
        }
        assertEquals(expectedIndexes(), actualIndexes);
        assertEquals(0L, checkpoint());
    }

    private Map<String, String> expectedColumns() {
        return Map.ofEntries(
                Map.entry("tiles.z", "tinyint unsigned|NO|-"),
                Map.entry("tiles.tx", "tinyint unsigned|NO|-"),
                Map.entry("tiles.ty", "tinyint unsigned|NO|-"),
                Map.entry("tiles.data", "mediumblob|NO|-"),
                Map.entry("tiles.tile_version", "bigint unsigned|NO|-"),
                Map.entry("tiles.updated_at", "datetime(3)|NO|3"),
                Map.entry("pixel_events.event_seq", "bigint unsigned|NO|-"),
                Map.entry("pixel_events.user_id", "bigint unsigned|NO|-"),
                Map.entry("pixel_events.z", "tinyint unsigned|NO|-"),
                Map.entry("pixel_events.tx", "tinyint unsigned|NO|-"),
                Map.entry("pixel_events.ty", "tinyint unsigned|NO|-"),
                Map.entry("pixel_events.x", "smallint unsigned|NO|-"),
                Map.entry("pixel_events.y", "smallint unsigned|NO|-"),
                Map.entry("pixel_events.color", "smallint unsigned|NO|-"),
                Map.entry("pixel_events.created_at", "datetime(3)|NO|3"),
                Map.entry("wal_checkpoint.checkpoint_name", "varchar(64)|NO|-"),
                Map.entry("wal_checkpoint.last_flushed_event_seq", "bigint unsigned|NO|-"),
                Map.entry("wal_checkpoint.updated_at", "datetime(3)|NO|3"),
                Map.entry("users.id", "bigint unsigned|NO|-"),
                Map.entry("users.kakao_user_id", "bigint unsigned|NO|-"),
                Map.entry("users.created_at", "datetime(3)|NO|3"),
                Map.entry("users.updated_at", "datetime(3)|NO|3")
        );
    }

    private Set<String> expectedIndexes() {
        return Set.of(
                "tiles|PRIMARY|0|1|z",
                "tiles|PRIMARY|0|2|tx",
                "tiles|PRIMARY|0|3|ty",
                "tiles|idx_tiles_updated_at|1|1|updated_at",
                "pixel_events|PRIMARY|0|1|event_seq",
                "pixel_events|idx_pixel_events_user_id|1|1|user_id",
                "wal_checkpoint|PRIMARY|0|1|checkpoint_name",
                "users|PRIMARY|0|1|id",
                "users|uk_users_kakao_user_id|0|1|kakao_user_id"
        );
    }

    // try-with-resources로 이전 storage 종료를 다음 graph 생성보다 앞에 고정
    private final class FreshRuntimeGraph implements AutoCloseable {

        private final SegmentedWalStorage storage;
        private final InMemoryTileBoard board;
        private final EventSeqManager eventSeqManager;
        private final ServiceReadiness serviceReadiness;
        private final SynchronizedDirtyTileTracker dirtyTileTracker;
        private final FlushBoundaryCoordinator flushBoundaryCoordinator;
        private final FlushSingleFlightGuard flushSingleFlightGuard;
        private final PendingAmbiguousFlushStore pendingAmbiguousFlushStore;
        private final FileWalAppender fileWalAppender;
        private final FileWalReplaySource fileWalReplaySource;
        private final StartupRecoveryService startupRecoveryService;
        private final PixelWriteService pixelWriteService;
        private final PixelCommandService pixelCommandService;
        private final FlushPlanCaptureService flushPlanCaptureService;
        private final FlushWorker flushWorker;

        private boolean retired;
        private int workerInvocationCount;

        private FreshRuntimeGraph(
                SegmentedWalStorage storage,
                InMemoryTileBoard board,
                EventSeqManager eventSeqManager,
                ServiceReadiness serviceReadiness,
                SynchronizedDirtyTileTracker dirtyTileTracker,
                FlushBoundaryCoordinator flushBoundaryCoordinator,
                FlushSingleFlightGuard flushSingleFlightGuard,
                PendingAmbiguousFlushStore pendingAmbiguousFlushStore,
                FileWalAppender fileWalAppender,
                FileWalReplaySource fileWalReplaySource,
                StartupRecoveryService startupRecoveryService,
                PixelWriteService pixelWriteService,
                PixelCommandService pixelCommandService,
                FlushPlanCaptureService flushPlanCaptureService,
                FlushWorker flushWorker
        ) {
            this.storage = storage;
            this.board = board;
            this.eventSeqManager = eventSeqManager;
            this.serviceReadiness = serviceReadiness;
            this.dirtyTileTracker = dirtyTileTracker;
            this.flushBoundaryCoordinator = flushBoundaryCoordinator;
            this.flushSingleFlightGuard = flushSingleFlightGuard;
            this.pendingAmbiguousFlushStore = pendingAmbiguousFlushStore;
            this.fileWalAppender = fileWalAppender;
            this.fileWalReplaySource = fileWalReplaySource;
            this.startupRecoveryService = startupRecoveryService;
            this.pixelWriteService = pixelWriteService;
            this.pixelCommandService = pixelCommandService;
            this.flushPlanCaptureService = flushPlanCaptureService;
            this.flushWorker = flushWorker;
        }

        private void recover() {
            requireActive();
            startupRecoveryService.recover();
        }

        private PixelWriteResult write(long userId, int x, int y, int color) {
            requireActive();
            return pixelCommandService.writePixel(userId, x, y, color);
        }

        private FlushRunResult flush() {
            requireActive();
            workerInvocationCount++;
            return flushWorker.flushOnce();
        }

        private void retire() {
            if (retired) {
                return;
            }
            serviceReadiness.markNotReady();
            fileWalAppender.close();
            retired = true;
        }

        @Override
        public void close() {
            retire();
        }

        private boolean retired() {
            return retired;
        }

        private int workerInvocationCount() {
            return workerInvocationCount;
        }

        private int memoryMutationCalls() {
            if (board instanceof TrackingInMemoryTileBoard trackingBoard) {
                return trackingBoard.memoryMutationCalls();
            }
            throw new IllegalStateException("Memory mutation observation is not enabled for this runtime.");
        }

        private int replayReadCount() {
            if (fileWalReplaySource instanceof TrackingFileWalReplaySource trackingSource) {
                return trackingSource.readCount();
            }
            throw new IllegalStateException("WAL read observation is not enabled for this runtime.");
        }

        private void requireActive() {
            if (retired) {
                throw new IllegalStateException("Retired runtime graph must not be reused.");
            }
        }
    }

    private static final class TrackingInMemoryTileBoard extends InMemoryTileBoard {

        private int memoryMutationCalls;

        @Override
        public synchronized void initializeAllWhite() {
            memoryMutationCalls++;
            super.initializeAllWhite();
        }

        @Override
        public synchronized void loadAll(List<TileStateSnapshot> snapshots) {
            memoryMutationCalls++;
            super.loadAll(snapshots);
        }

        @Override
        public synchronized void applyReplayRecord(int x, int y, int color) {
            memoryMutationCalls++;
            super.applyReplayRecord(x, y, color);
        }

        private int memoryMutationCalls() {
            return memoryMutationCalls;
        }
    }

    private static final class TrackingFileWalReplaySource extends FileWalReplaySource {

        private final UnaryOperator<WalReplayBatch> batchTransform;
        private int readCount;

        private TrackingFileWalReplaySource(
                SegmentedWalStorage storage,
                UnaryOperator<WalReplayBatch> batchTransform
        ) {
            super(storage);
            this.batchTransform = Objects.requireNonNull(batchTransform, "batchTransform must not be null");
        }

        @Override
        public WalReplayBatch readAfter(long lastFlushedEventSeq) {
            readCount++;
            return Objects.requireNonNull(
                    batchTransform.apply(super.readAfter(lastFlushedEventSeq)),
                    "batchTransform returned null"
            );
        }

        private int readCount() {
            return readCount;
        }
    }

    private record DatabaseFailureCase(String name, String fileStem, Runnable setup) {
    }

    private record WalFailureCase(
            String name,
            String fileStem,
            long checkpoint,
            UnaryOperator<WalReplayBatch> batchTransform
    ) {
    }
}
