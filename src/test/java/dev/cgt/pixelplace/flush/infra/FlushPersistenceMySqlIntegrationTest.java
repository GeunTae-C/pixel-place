package dev.cgt.pixelplace.flush.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import dev.cgt.pixelplace.checkpoint.infra.JpaCheckpointFence;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.flush.application.DbBootstrapState;
import dev.cgt.pixelplace.flush.application.FlushDbState;
import dev.cgt.pixelplace.flush.application.FlushPersistenceService;
import dev.cgt.pixelplace.flush.application.FlushPlan;
import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;
import dev.cgt.pixelplace.flush.application.FlushTransactionOutcome;
import dev.cgt.pixelplace.flush.application.FlushTransactionResult;
import dev.cgt.pixelplace.pixel.application.PixelEventWriter;
import dev.cgt.pixelplace.pixel.infra.JpaPixelEventWriter;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.application.TileSnapshotWriter;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.infra.JpaTileMetadataReader;
import dev.cgt.pixelplace.tile.infra.JpaTileSnapshotWriter;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 로컬 pixel_place_test catalog와 root DDL에서 events/tiles/checkpoint physical transaction 원자성 검증
 * 전체 application, Redis, startup recovery, scheduler와 test-managed transaction은 사용하지 않음
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("mysql-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import({
        CanonicalZ0TileKeys.class,
        DbBootstrapClassifier.class,
        JpaTileMetadataReader.class,
        JpaCheckpointFence.class,
        JpaPixelEventWriter.class,
        JpaTileSnapshotWriter.class,
        FlushPersistenceService.class,
        ProgrammaticFlushTransactionExecutor.class,
        JpaFlushDbStateProbe.class
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CheckpointFence checkpointFence;

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
        String rootSql = Files.readString(Path.of("pixel_place.sql"), StandardCharsets.UTF_8);
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
                createTableNames.add("tiles");
            } else if (unquoted.startsWith("CREATE TABLE IF NOT EXISTS PIXEL_EVENTS ")) {
                createTableNames.add("pixel_events");
            } else if (unquoted.startsWith("CREATE TABLE IF NOT EXISTS WAL_CHECKPOINT ")) {
                createTableNames.add("wal_checkpoint");
            } else if (unquoted.startsWith("INSERT INTO WAL_CHECKPOINT ")) {
                checkpointSeedCount++;
            } else {
                throw new IllegalStateException("Unexpected executable statement in root SQL: " + normalized);
            }
            executableStatements.add(statement);
        }

        if (!createDatabaseFound || !useDatabaseFound) {
            throw new IllegalStateException("Root SQL must retain the production CREATE DATABASE and USE statements.");
        }
        if (!createTableNames.equals(Set.of("tiles", "pixel_events", "wal_checkpoint"))) {
            throw new IllegalStateException("Root SQL must define exactly the three expected tables.");
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
        requireTestCatalog(connection);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS pixel_events");
            statement.executeUpdate("DROP TABLE IF EXISTS tiles");
            statement.executeUpdate("DROP TABLE IF EXISTS wal_checkpoint");
        }
    }

    private void executeValidatedRootSql(
            Connection connection,
            List<String> executableStatements
    ) throws SQLException {
        requireTestCatalog(connection);
        try (Statement statement = connection.createStatement()) {
            for (String executable : executableStatements) {
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
        assertEquals(3, jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = ?
                          AND table_name IN ('tiles', 'pixel_events', 'wal_checkpoint')
                        """,
                Integer.class,
                CATALOG
        ));

        List<Map<String, Object>> columnRows = jdbcTemplate.queryForList(
                """
                        SELECT table_name, column_name, column_type, is_nullable, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = ?
                          AND table_name IN ('tiles', 'pixel_events', 'wal_checkpoint')
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
                          AND table_name IN ('tiles', 'pixel_events', 'wal_checkpoint')
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
                Map.entry("wal_checkpoint.updated_at", "datetime(3)|NO|3")
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
                "wal_checkpoint|PRIMARY|0|1|checkpoint_name"
        );
    }
}
