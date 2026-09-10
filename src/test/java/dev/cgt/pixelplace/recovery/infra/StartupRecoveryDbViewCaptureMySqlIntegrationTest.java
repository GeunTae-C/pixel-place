package dev.cgt.pixelplace.recovery.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.checkpoint.infra.JpaCheckpointReader;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryDbViewCaptureService;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryService;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.infra.JpaTileSnapshotLoader;
import dev.cgt.pixelplace.tile.infra.TileEntity;
import dev.cgt.pixelplace.tile.infra.TileJpaRepository;
import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import dev.cgt.pixelplace.wal.infra.FileWalAppender;
import dev.cgt.pixelplace.wal.infra.FileWalReplaySource;
import dev.cgt.pixelplace.wal.infra.WalProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 실제 MySQL 8.4.11의 capture transaction session/physical connection과 동일 snapshot 검증
 * checkpoint 첫 read 사이의 flush-shaped commit 뒤에도 pre-commit view와 WAL 1회 replay 경계 보존
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
        JpaCheckpointReader.class,
        JpaTileSnapshotLoader.class,
        StartupRecoveryDbViewCaptureService.class,
        StartupRecoveryDbViewCaptureMySqlIntegrationTest.CaptureCoordinationConfiguration.class
})
class StartupRecoveryDbViewCaptureMySqlIntegrationTest {

    private static final String CATALOG = "pixel_place_test";
    private static final String REQUIRED_MYSQL_VERSION = "8.4.11";
    private static final TileKey TARGET_KEY = new TileKey(0, 0, 0);
    private static final byte[] ZERO_TILE = new byte[BoardConstants.TILE_PIXEL_COUNT];
    private static final byte[] BASE_TILE = baseTile();
    private static final byte[] COMMITTED_TILE = committedTile();
    private static final long BASE_TILE_VERSION = 3L;
    private static final long COMMITTED_TILE_VERSION = 4L;
    private static final long BASE_CHECKPOINT = 1L;
    private static final long TARGET_CHECKPOINT = 2L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StartupRecoveryDbViewCaptureService captureService;

    @Autowired
    private CaptureGate captureGate;

    @TempDir
    Path tempDirectory;

    private final CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();

    @BeforeAll
    void bootstrapActualRootSchemaInTestCatalog() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String database = requireTestCatalog(connection);
            String version = selectServerValue(connection, "SELECT VERSION()");
            String versionComment = selectServerValue(connection, "SELECT @@version_comment");
            System.out.println("RECOVERY_SNAPSHOT_DATABASE=" + database);
            System.out.println("RECOVERY_SNAPSHOT_MYSQL_VERSION=" + version);
            System.out.println("RECOVERY_SNAPSHOT_MYSQL_VERSION_COMMENT=" + versionComment);
            assertRequiredMySqlVersion(version, versionComment);

            List<String> executableStatements = loadAndValidateRootSqlStatements();
            dropTestTables(connection);
            executeValidatedRootSql(connection, executableStatements);
        }

        assertRootSchemaContract();
    }

    @BeforeEach
    void resetInitializedBoundary() {
        captureGate.reset();
        resetDatabaseToBootstrap();
        seedCanonicalTiles();
        executeGuardedUpdate(
                """
                        INSERT INTO pixel_events
                            (event_seq, user_id, z, tx, ty, x, y, color, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                BASE_CHECKPOINT,
                7L,
                TARGET_KEY.z(),
                TARGET_KEY.tx(),
                TARGET_KEY.ty(),
                0,
                0,
                5,
                LocalDateTime.of(2026, 4, 3, 6, 0)
        );
        assertEquals(
                1,
                executeGuardedUpdate(
                        """
                                UPDATE wal_checkpoint
                                SET last_flushed_event_seq = ?
                                WHERE checkpoint_name = 'main'
                                  AND last_flushed_event_seq = 0
                                """,
                        BASE_CHECKPOINT
                )
        );
    }

    @Test
    void repeatableReadCaptureUsesOnePhysicalConnectionAndRecoveryReplaysConcurrentCommitOnce()
            throws Exception {
        Path walPath = tempDirectory.resolve("capture-recovery.wal");
        WalProperties walProperties = new WalProperties();
        walProperties.setActiveFile(walPath);
        ObjectMapper objectMapper = new ObjectMapper();
        FileWalAppender fileWalAppender = new FileWalAppender(
                walProperties,
                new WalRecordJsonCodec(objectMapper)
        );
        FileWalReplaySource fileWalReplaySource = new FileWalReplaySource(
                walProperties,
                new WalRecordParser(objectMapper)
        );
        WalRecord eventTwo = new WalRecord(
                TARGET_CHECKPOINT,
                7L,
                TARGET_KEY.z(),
                TARGET_KEY.tx(),
                TARGET_KEY.ty(),
                0,
                0,
                17,
                LocalDateTime.of(2026, 4, 3, 6, 0, 0, 987_654_321)
        );
        fileWalAppender.appendAndFsync(eventTwo);

        InMemoryTileBoard board = new InMemoryTileBoard();
        EventSeqManager eventSeqManager = new EventSeqManager();
        ServiceReadiness readiness = new ServiceReadiness();
        StartupRecoveryService recoveryService = new StartupRecoveryService(
                captureService,
                new DbBootstrapClassifier(canonicalKeys),
                canonicalKeys,
                fileWalReplaySource,
                board,
                eventSeqManager,
                readiness
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> writer = null;

        try {
            writer = executor.submit(() -> {
                await(captureGate.checkpointRead());
                try {
                    commitFlushShapedEvent(eventTwo);
                } finally {
                    captureGate.writerCommitted().countDown();
                }
            });

            assertTrue(AopUtils.isAopProxy(captureService));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            recoveryService.recover();
            writer.get(15, TimeUnit.SECONDS);

            assertEquals(BASE_CHECKPOINT, captureGate.capturedCheckpoint().lastFlushedEventSeq());
            assertTrue(canonicalKeys.exactlyMatches(captureGate.capturedKeys()));
            assertArrayEquals(BASE_TILE, captureGate.capturedTarget().pixels());
            assertEquals(BASE_TILE_VERSION, captureGate.capturedTarget().tileVersion());

            List<CaptureObservation> observations = captureGate.captureObservations();
            assertEquals(List.of("checkpoint", "keys", "snapshots"), observations.stream()
                    .map(CaptureObservation::stage)
                    .toList());
            assertTrue(observations.stream().allMatch(CaptureObservation::transactionActive));
            assertTrue(observations.stream().allMatch(CaptureObservation::readOnly));
            assertTrue(observations.stream().allMatch(observation ->
                    observation.springIsolation() == TransactionDefinition.ISOLATION_REPEATABLE_READ
            ));
            assertTrue(observations.stream().allMatch(observation ->
                    observation.sessionIsolation().equals("REPEATABLE-READ")
            ));
            long captureConnectionId = observations.get(0).connectionId();
            assertTrue(observations.stream().allMatch(observation ->
                    observation.connectionId() == captureConnectionId
            ));
            assertNotEquals(captureConnectionId, captureGate.writerConnectionId());

            assertEquals(TARGET_CHECKPOINT, databaseCheckpoint());
            assertEquals(1, databaseEventCount(TARGET_CHECKPOINT));
            assertEquals(
                    eventTwo.createdAt().truncatedTo(ChronoUnit.MILLIS),
                    databaseEventCreatedAt(TARGET_CHECKPOINT)
            );
            assertArrayEquals(COMMITTED_TILE, databaseTileData(TARGET_KEY));
            assertEquals(COMMITTED_TILE_VERSION, databaseTileVersion(TARGET_KEY));

            assertTrue(readiness.isReady());
            assertEquals(TARGET_CHECKPOINT, eventSeqManager.currentLastIssued());
            assertArrayEquals(COMMITTED_TILE, board.getRequired(TARGET_KEY).pixels());
            assertEquals(COMMITTED_TILE_VERSION, board.getRequired(TARGET_KEY).tileVersion());
            WalReplayBatch noReplay = fileWalReplaySource.readAfter(TARGET_CHECKPOINT);
            assertEquals(TARGET_CHECKPOINT, noReplay.walLastEventSeq());
            assertTrue(noReplay.records().isEmpty());
        } finally {
            captureGate.checkpointRead().countDown();
            captureGate.writerCommitted().countDown();
            if (writer != null) {
                writer.cancel(true);
            }
            readiness.markNotReady();
            fileWalAppender.close();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            } finally {
                resetDatabaseToBootstrap();
            }
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

    private void commitFlushShapedEvent(WalRecord eventTwo) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setName("capture-concurrent-flush-shaped-commit");
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(false);
        template.executeWithoutResult(status -> {
            SessionObservation writerSession = currentSession();
            captureGate.recordWriterConnection(writerSession.connectionId());
            assertEquals(
                    1,
                    executeGuardedUpdate(
                            """
                                    INSERT INTO pixel_events
                                        (event_seq, user_id, z, tx, ty, x, y, color, created_at)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """,
                            eventTwo.eventSeq(),
                            eventTwo.userId(),
                            eventTwo.z(),
                            eventTwo.tx(),
                            eventTwo.ty(),
                            eventTwo.x(),
                            eventTwo.y(),
                            eventTwo.color(),
                            eventTwo.createdAt().truncatedTo(ChronoUnit.MILLIS)
                    )
            );
            assertEquals(
                    1,
                    executeGuardedUpdate(
                            """
                                    UPDATE tiles
                                    SET data = ?, tile_version = ?
                                    WHERE z = ? AND tx = ? AND ty = ?
                                    """,
                            COMMITTED_TILE,
                            COMMITTED_TILE_VERSION,
                            TARGET_KEY.z(),
                            TARGET_KEY.tx(),
                            TARGET_KEY.ty()
                    )
            );
            assertEquals(
                    1,
                    executeGuardedUpdate(
                            """
                                    UPDATE wal_checkpoint
                                    SET last_flushed_event_seq = ?
                                    WHERE checkpoint_name = 'main'
                                      AND last_flushed_event_seq = ?
                                    """,
                            TARGET_CHECKPOINT,
                            BASE_CHECKPOINT
                    )
            );
        });
    }

    private void seedCanonicalTiles() {
        List<TileKey> keys = canonicalKeys.orderedKeys();
        int[] results = Objects.requireNonNull(jdbcTemplate.execute((ConnectionCallback<int[]>) connection -> {
            requireTestCatalog(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tiles(z, tx, ty, data, tile_version) VALUES (?, ?, ?, ?, ?)"
            )) {
                for (TileKey key : keys) {
                    boolean target = key.equals(TARGET_KEY);
                    statement.setInt(1, key.z());
                    statement.setInt(2, key.tx());
                    statement.setInt(3, key.ty());
                    statement.setBytes(4, target ? BASE_TILE : ZERO_TILE);
                    statement.setLong(5, target ? BASE_TILE_VERSION : 0L);
                    statement.addBatch();
                }
                return statement.executeBatch();
            }
        }));
        assertEquals(keys.size(), results.length);
    }

    private SessionObservation currentSession() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT @@transaction_isolation, CONNECTION_ID()",
                (resultSet, rowNumber) -> new SessionObservation(
                        resultSet.getString(1).toUpperCase(Locale.ROOT),
                        resultSet.getLong(2)
                )
        ));
    }

    private long databaseCheckpoint() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT last_flushed_event_seq FROM wal_checkpoint WHERE checkpoint_name = 'main'",
                Long.class
        ));
    }

    private int databaseEventCount(long eventSeq) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pixel_events WHERE event_seq = ?",
                Integer.class,
                eventSeq
        ));
    }

    private LocalDateTime databaseEventCreatedAt(long eventSeq) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at FROM pixel_events WHERE event_seq = ?",
                (resultSet, rowNumber) -> resultSet.getObject(1, LocalDateTime.class),
                eventSeq
        );
    }

    private byte[] databaseTileData(TileKey key) {
        return jdbcTemplate.queryForObject(
                "SELECT data FROM tiles WHERE z = ? AND tx = ? AND ty = ?",
                (resultSet, rowNumber) -> resultSet.getBytes(1),
                key.z(),
                key.tx(),
                key.ty()
        );
    }

    private long databaseTileVersion(TileKey key) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT tile_version FROM tiles WHERE z = ? AND tx = ? AND ty = ?",
                Long.class,
                key.z(),
                key.tx(),
                key.ty()
        ));
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
        assertTrue(normalizedComment.contains("mysql"));
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
        assertEquals(0L, databaseCheckpoint());
    }

    private static byte[] baseTile() {
        byte[] pixels = Arrays.copyOf(ZERO_TILE, ZERO_TILE.length);
        pixels[0] = 5;
        return pixels;
    }

    private static byte[] committedTile() {
        byte[] pixels = Arrays.copyOf(BASE_TILE, BASE_TILE.length);
        pixels[0] = 17;
        return pixels;
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

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureCoordinationConfiguration {

        @Bean
        CaptureGate captureGate(JdbcTemplate jdbcTemplate) {
            return new CaptureGate(jdbcTemplate);
        }

        @Bean
        @Primary
        CheckpointReader coordinatedCheckpointReader(
                JpaCheckpointReader delegate,
                CaptureGate captureGate
        ) {
            return () -> {
                captureGate.recordCaptureSession("checkpoint");
                CheckpointSnapshot checkpoint = delegate.readMainCheckpoint();
                captureGate.recordCapturedCheckpoint(checkpoint);
                captureGate.checkpointRead().countDown();
                await(captureGate.writerCommitted());
                return checkpoint;
            };
        }

        @Bean
        @Primary
        TileJpaRepository coordinatedTileJpaRepository(
                @Qualifier("tileJpaRepository") TileJpaRepository delegate,
                CaptureGate captureGate
        ) {
            return (TileJpaRepository) Proxy.newProxyInstance(
                    TileJpaRepository.class.getClassLoader(),
                    new Class<?>[]{TileJpaRepository.class},
                    (proxy, method, arguments) -> {
                        String methodName = method.getName();
                        if (methodName.equals("findAllTileKeysOrderByZTyTx")) {
                            captureGate.recordCaptureSession("keys");
                        } else if (methodName.equals("findAllByZOrderByTyAscTxAsc")) {
                            captureGate.recordCaptureSession("snapshots");
                        }

                        Object result;
                        try {
                            result = method.invoke(delegate, arguments);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }

                        if (methodName.equals("findAllTileKeysOrderByZTyTx")) {
                            captureGate.recordCapturedKeys(castTileKeys(result));
                        } else if (methodName.equals("findAllByZOrderByTyAscTxAsc")) {
                            captureGate.recordCapturedTarget(castTileEntities(result));
                        }
                        return result;
                    }
            );
        }

        @SuppressWarnings("unchecked")
        private static List<TileKey> castTileKeys(Object value) {
            return (List<TileKey>) value;
        }

        @SuppressWarnings("unchecked")
        private static List<TileEntity> castTileEntities(Object value) {
            return (List<TileEntity>) value;
        }
    }

    static final class CaptureGate {

        private final JdbcTemplate jdbcTemplate;
        private final List<CaptureObservation> captureObservations =
                Collections.synchronizedList(new ArrayList<>());

        private volatile CountDownLatch checkpointRead;
        private volatile CountDownLatch writerCommitted;
        private volatile CheckpointSnapshot capturedCheckpoint;
        private volatile List<TileKey> capturedKeys;
        private volatile CapturedTile capturedTarget;
        private volatile Long writerConnectionId;

        private CaptureGate(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            reset();
        }

        void reset() {
            checkpointRead = new CountDownLatch(1);
            writerCommitted = new CountDownLatch(1);
            captureObservations.clear();
            capturedCheckpoint = null;
            capturedKeys = null;
            capturedTarget = null;
            writerConnectionId = null;
        }

        void recordCaptureSession(String stage) {
            SessionObservation session = Objects.requireNonNull(jdbcTemplate.queryForObject(
                    "SELECT @@transaction_isolation, CONNECTION_ID()",
                    (resultSet, rowNumber) -> new SessionObservation(
                            resultSet.getString(1).toUpperCase(Locale.ROOT),
                            resultSet.getLong(2)
                    )
            ));
            Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            captureObservations.add(new CaptureObservation(
                    stage,
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    Objects.requireNonNull(isolation, "Spring transaction isolation must be available."),
                    session.sessionIsolation(),
                    session.connectionId()
            ));
        }

        void recordCapturedCheckpoint(CheckpointSnapshot checkpoint) {
            capturedCheckpoint = checkpoint;
        }

        void recordCapturedKeys(List<TileKey> keys) {
            capturedKeys = List.copyOf(keys);
        }

        void recordCapturedTarget(List<TileEntity> entities) {
            TileEntity target = entities.stream()
                    .filter(entity -> entity.getZ() == TARGET_KEY.z()
                            && entity.getTx() == TARGET_KEY.tx()
                            && entity.getTy() == TARGET_KEY.ty())
                    .findFirst()
                    .orElseThrow();
            capturedTarget = new CapturedTile(target.getData(), target.getTileVersion());
        }

        void recordWriterConnection(long connectionId) {
            writerConnectionId = connectionId;
        }

        CountDownLatch checkpointRead() {
            return checkpointRead;
        }

        CountDownLatch writerCommitted() {
            return writerCommitted;
        }

        CheckpointSnapshot capturedCheckpoint() {
            return Objects.requireNonNull(capturedCheckpoint, "checkpoint was not captured");
        }

        List<TileKey> capturedKeys() {
            return Objects.requireNonNull(capturedKeys, "keys were not captured");
        }

        CapturedTile capturedTarget() {
            return Objects.requireNonNull(capturedTarget, "target tile was not captured");
        }

        long writerConnectionId() {
            return Objects.requireNonNull(writerConnectionId, "writer connection was not recorded");
        }

        List<CaptureObservation> captureObservations() {
            return List.copyOf(captureObservations);
        }
    }

    private record SessionObservation(String sessionIsolation, long connectionId) {
    }

    private record CaptureObservation(
            String stage,
            boolean transactionActive,
            boolean readOnly,
            int springIsolation,
            String sessionIsolation,
            long connectionId
    ) {
    }

    private record CapturedTile(byte[] pixels, long tileVersion) {

        private CapturedTile {
            pixels = Arrays.copyOf(Objects.requireNonNull(pixels, "pixels must not be null"), pixels.length);
        }

        @Override
        public byte[] pixels() {
            return Arrays.copyOf(pixels, pixels.length);
        }
    }
}
