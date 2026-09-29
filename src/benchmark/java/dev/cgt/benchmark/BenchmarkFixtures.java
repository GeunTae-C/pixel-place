package dev.cgt.benchmark;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 새 catalog·WAL과 전용 Redis DB의 소유권만 준비. fixture DB/WAL은 종료 뒤에도 보존 */
final class BenchmarkFixtures implements AutoCloseable {
    final BenchmarkEnvironment.Credentials credentials;
    final LettuceConnectionFactory redisFactory;
    final StringRedisTemplate redis;
    final Map<String, Object> redisFacts;
    final String catalog;
    final Path ownedMysqlRoot;

    BenchmarkFixtures(BenchmarkEnvironment.Credentials credentials, String catalog, boolean requireEmptyRedis) {
        this(credentials, catalog, requireEmptyRedis, null);
    }
    BenchmarkFixtures(BenchmarkEnvironment.Credentials credentials, String catalog, boolean requireEmptyRedis, Path ownedMysqlRoot) {
        this.ownedMysqlRoot = ownedMysqlRoot;
        this.credentials = credentials; this.catalog = catalog;
        var settings = new RedisStandaloneConfiguration(credentials.redisHost(), credentials.redisPort());
        settings.setDatabase(credentials.redisDatabase());
        if (!credentials.redisUsername().isBlank()) settings.setUsername(credentials.redisUsername());
        if (!credentials.redisPassword().isBlank()) settings.setPassword(credentials.redisPassword());
        redisFactory = new LettuceConnectionFactory(settings);
        redisFactory.afterPropertiesSet(); redisFactory.start();
        try (var connection = redisFactory.getConnection()) {
            var cluster = connection.serverCommands().info("cluster"); var replication = connection.serverCommands().info("replication");
            if (!"0".equals(cluster.getProperty("cluster_enabled")) || !"master".equals(replication.getProperty("role")))
                throw new IllegalStateException("Benchmark requires local standalone Redis master");
            long size = connection.serverCommands().dbSize();
            if (requireEmptyRedis && size != 0) throw new IllegalStateException("Redis DB has existing usage; no mutation allowed");
            if (ownedMysqlRoot != null && connection.serverCommands().getClientList().stream()
                    .filter(client -> client.getDatabaseId() == credentials.redisDatabase()).count() != 1)
                throw new IllegalStateException("C Redis DB already has another client; dedicated use not established");
            redisFacts = Map.of("database", credentials.redisDatabase(), "productionDatabase", 0, "regressionDatabase", 1,
                    "initialSize", size, "standalone", true, "version", connection.serverCommands().info("server").getProperty("redis_version"));
        } catch (RuntimeException | Error failure) { redisFactory.destroy(); throw failure; }
        redis = new StringRedisTemplate(redisFactory);
    }

    Connection connect(boolean readOnly) throws SQLException {
        Connection connection = adminConnect(catalog);
        try {
            BenchmarkGuards.requireCatalog(connection, catalog); connection.setReadOnly(readOnly); return connection;
        } catch (SQLException | RuntimeException failure) { connection.close(); throw failure; }
    }
    Connection adminConnect(String database) throws SQLException {
        var properties = new Properties(); properties.setProperty("user", credentials.dbUsername()); properties.setProperty("password", credentials.dbPassword());
        return DriverManager.getConnection(credentials.jdbc(database), properties);
    }

    List<Long> prepare(String runId, Path evidence, BenchmarkSpec.Plan plan, String mode, String precreatedOwner) throws Exception {
        BenchmarkSpec.require(catalog.equals(BenchmarkGuards.catalog(runId)), "exact trial catalog");
        List<String> schema = BenchmarkGuards.schema(Files.readString(Path.of("pixel_place.sql")));
        BenchmarkSpec.require(Set.of("create-new", "precreated-empty").contains(mode), "database mode");
        BenchmarkJson.write(evidence.resolve("catalog-intent.json"), Map.of("catalog", catalog, "mode", mode, "ownershipProven", false));
        try (Connection admin = adminConnect(""); var find = admin.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
            find.setString(1, catalog); long exists;
            try (var rows = find.executeQuery()) { rows.next(); exists = rows.getLong(1); }
            if (mode.equals("create-new")) {
                if (exists != 0) throw new IllegalStateException("Existing trial catalog cannot be reused");
                try (var statement = admin.createStatement()) {
                    statement.executeUpdate("CREATE DATABASE `" + catalog + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                }
            } else if (exists != 1 || !runId.equals(precreatedOwner)) {
                throw new IllegalStateException("Precreated catalog requires explicit current-run ownership");
            }
        }
        try (Connection connection = connect(false)) {
            for (String relation : List.of("tables", "routines", "events")) {
                String column = relation.equals("tables") ? "table_schema" : relation.equals("routines") ? "routine_schema" : "event_schema";
                try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema." + relation + " WHERE " + column + "=?")) {
                    statement.setString(1, catalog); try (var rows = statement.executeQuery()) {
                        rows.next(); if (rows.getLong(1) != 0) throw new IllegalStateException("Trial catalog is not empty; no schema/data mutation allowed");
                    }
                }
            }
            BenchmarkJson.write(evidence.resolve("ownership.json"), Map.of("trialId", runId, "catalog", catalog, "mode", mode, "emptySchemaVerified", true));
            for (String sql : schema) {
                BenchmarkGuards.requireCatalog(connection, catalog);
                try (var statement = connection.createStatement()) { statement.execute(sql); }
            }
            fixtureFiles();
            var ids = new ArrayList<Long>(plan.fixtureUsers());
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("INSERT INTO users(kakao_user_id) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < plan.fixtureUsers(); i++) {
                    statement.setLong(1, 9_015_000_000_000L + i); statement.addBatch();
                    if ((i + 1) % 1000 == 0 || i + 1 == plan.fixtureUsers()) {
                        BenchmarkGuards.requireCatalog(connection, catalog); statement.executeBatch();
                        try (var keys = statement.getGeneratedKeys()) { while (keys.next()) ids.add(keys.getLong(1)); }
                        statement.clearBatch();
                    }
                }
                if (ids.size() != plan.fixtureUsers() || new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(id -> id <= 0))
                    throw new IllegalStateException("Generated fixture identity map is invalid");
                connection.commit();
            } catch (Exception | Error failure) {
                try { connection.rollback(); } catch (SQLException | RuntimeException | Error rollback) {
                    Throwable primary = Phase15BenchmarkMain.preserve(failure, rollback);
                    if (primary != failure) throw (Error) primary;
                }
                throw failure;
            }
            return List.copyOf(ids);
        }
    }

    Map<String, Object> dbFacts() throws SQLException {
        try (var connection = connect(true); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT VERSION(), @@version_comment, @@character_set_database, @@collation_database, @@system_time_zone")) {
            rows.next(); return Map.of("version", rows.getString(1), "versionComment", rows.getString(2),
                    "charset", rows.getString(3), "collation", rows.getString(4), "systemTimeZone", rows.getString(5),
                    "catalog", catalog, "account", credentials.dbUsername(), "host", credentials.dbHost(), "port", credentials.dbPort());
        }
    }
    long checkpoint() throws SQLException {
        try (var connection = connect(true); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT last_flushed_event_seq FROM wal_checkpoint WHERE checkpoint_name='main'")) {
            if (!rows.next()) throw new IllegalStateException("Missing benchmark checkpoint"); return rows.getLong(1);
        }
    }
    long bytesForCatalogs(Set<String> catalogs) throws SQLException {
        long total = 0;
        try (var connection = adminConnect(""); var statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(FILE_SIZE),0) FROM information_schema.INNODB_TABLESPACES WHERE SUBSTRING_INDEX(NAME,'/',1)=?")) {
            for (String owned : catalogs) {
                BenchmarkSpec.require(owned.startsWith("pixel_place_bench_"), "owned budget catalog");
                statement.setString(1, owned); try (var rows = statement.executeQuery()) { rows.next(); total = Math.addExact(total, rows.getLong(1)); }
            }
        }
        return total;
    }
    BenchmarkStorageBudget.Snapshot storageSnapshot() throws Exception {
        try (var connection = adminConnect(""); var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            boolean enabled; long redo; Map<String, Long> volumes = new TreeMap<>(), logs = new TreeMap<>();
            try (var rows = statement.executeQuery("SELECT @@log_bin, @@datadir, @@log_bin_basename, @@innodb_redo_log_capacity")) {
                rows.next(); enabled = rows.getBoolean(1); redo = rows.getLong(4);
                // 현재 local 대표 환경의 실제 data/log volume 여유. remote DB는 별도 환경 검증 없이 진행하지 않음
                BenchmarkSpec.require(Set.of("localhost", "127.0.0.1").contains(credentials.dbHost()), "local DB volume verification");
                for (int column : new int[]{2, 3}) {
                    String value = rows.getString(column); if (value == null || value.isBlank()) continue;
                    Path root = Path.of(value).toAbsolutePath().getRoot();
                    volumes.put(root.toString(), Files.getFileStore(root).getUsableSpace());
                }
            }
            if (enabled) try (var rows = statement.executeQuery("SHOW BINARY LOGS")) {
                while (rows.next()) logs.put(rows.getString(1), rows.getLong(2));
            }
            long shared;
            try (var rows = statement.executeQuery("SELECT COALESCE(SUM(FILE_SIZE),0) FROM information_schema.INNODB_TABLESPACES WHERE SPACE_TYPE IN ('System','Undo','General')")) {
                rows.next(); shared = rows.getLong(1);
            }
            return new BenchmarkStorageBudget.Snapshot(enabled, shared, redo, logs, volumes);
        }
    }
    void requireOwnedStorage() throws Exception {
        try (var connection = adminConnect(""); var statement = connection.createStatement();
             var rows = statement.executeQuery("SHOW VARIABLES WHERE Variable_name IN ('datadir','innodb_data_home_dir','innodb_log_group_home_dir','innodb_undo_directory','tmpdir','innodb_temp_tablespaces_dir','log_bin_basename','log_error','slow_query_log_file','general_log_file')")) {
            int seen = 0;
            while (rows.next()) { requireOwnedPath(Path.of(rows.getString(2))); seen++; }
            if (seen != 10) throw new IllegalStateException("DB storage path observations incomplete");
        }
    }
    Map<String, String> fixtureFiles() throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (var connection = connect(true); var statement = connection.createStatement()) {
            Path data;
            try (var rows = statement.executeQuery("SELECT @@datadir")) { rows.next(); data = Path.of(rows.getString(1)); }
            try (var find = connection.prepareStatement("SELECT s.NAME,d.PATH FROM information_schema.INNODB_DATAFILES d JOIN information_schema.INNODB_TABLESPACES s ON s.SPACE=d.SPACE WHERE SUBSTRING_INDEX(s.NAME,'/',1)=?")) {
                find.setString(1, catalog);
                try (var rows = find.executeQuery()) {
                    while (rows.next()) {
                        Path actual = data.resolve(rows.getString(2)).toAbsolutePath().normalize(); requireOwnedPath(actual);
                        if (!Files.isRegularFile(actual)) throw new IllegalStateException("Fixture data file missing");
                        files.put(rows.getString(1), actual.toString());
                    }
                }
            }
        }
        if (files.size() != 4) throw new IllegalStateException("Fixture data file location proof incomplete");
        return files;
    }
    private void requireOwnedPath(Path path) throws Exception {
        // C의 이미 존재하는 MySQL 저장 root만 별도 허용. 기존 15단계 호출은 원래 guard 유지
        if (ownedMysqlRoot == null) BenchmarkPaths.requireOwned(path);
        else Phase17CPlan.owned(Phase17CPlan.absolute(path.toString()), ownedMysqlRoot);
    }
    Map<String, Long> cleanupOwnedKeys(List<Long> ids) {
        long removed = 0;
        for (long id : ids) {
            String key = "cooldown:user:" + id;
            String value = redis.opsForValue().get(key);
            if (value == null) continue;
            if (!value.equals("1")) throw new IllegalStateException("Owned cooldown key changed; cleanup stopped");
            if (Boolean.TRUE.equals(redis.delete(key))) removed++;
        }
        return Map.of("ownedKeys", (long) ids.size(), "deleted", removed);
    }
    @Override public void close() { redisFactory.destroy(); }
}
