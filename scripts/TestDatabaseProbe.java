import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 이전 완료된 3307의 실제 경로·UUID·회귀 catalog 읽기 전용 검증과 소유 SQL 종료 경계 */
class TestDatabaseProbe {
    static String stage = "arguments";
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception failure) {
            // JDBC 예외 메시지의 접속·인증 정보 출력 방지
            System.err.println("DATABASE_PROBE_FAILED stage=" + stage + " type=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    static int remaining() throws Exception {
        String value=System.getenv("PIXEL_PLACE_CAP_EXPIRES");
        if(value==null || value.isBlank())return 10000;
        long left=Long.parseLong(value)-System.currentTimeMillis();
        String stop=System.getenv("PIXEL_PLACE_CAP_STOP");
        if(left<=0 || stop!=null && Files.exists(Path.of(stop)))throw new java.util.concurrent.TimeoutException("CAP probe deadline/STOP");
        return (int)Math.min(10000,left);
    }
    static void bound(Connection connection,Statement statement) throws Exception {
        connection.setNetworkTimeout(Runnable::run,remaining());
        statement.setQueryTimeout(Math.max(1,(remaining()+999)/1000));
    }
    static void run(String[] args) throws Exception {
        stage = "arguments";
        remaining();
        if (args.length != 5 || !Set.of("verify", "shutdown").contains(args[0]))
            throw new IllegalArgumentException("Explicit action/root/UUID/PID/start required");
        Path root = Path.of(args[1]);
        stage = "before_db_path";
        owned(root.resolve("data"), root);
        identity(root, args[3], args[4], "before_db");
        stage = "before_db_owner";
        var owner = new Properties();
        try (var input = Files.newInputStream(root.resolve("regression-ownership.properties"))) { owner.load(input); }
        if (!args[2].equals(owner.getProperty("server_uuid")) || !"pixel_place_test".equals(owner.getProperty("catalog")))
            throw new IllegalStateException("Regression ownership mismatch");
        stage = "db_credentials";
        var credentials = new Properties();
        credentials.setProperty("user", Objects.requireNonNull(System.getenv("PIXEL_PLACE_BENCH_DB_USERNAME")));
        credentials.setProperty("password", Objects.requireNonNull(System.getenv("DB_PASSWORD")));
        stage = "db_connect";
        try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/pixel_place_test?connectTimeout="+remaining()+"&socketTimeout="+remaining(), credentials);
             var statement = connection.createStatement()) {
            stage = "db_variables";
            bound(connection,statement);
            var values = new HashMap<String, String>();
            try (var rows = statement.executeQuery("SHOW VARIABLES WHERE Variable_name IN ('server_uuid','port','bind_address','version','datadir','innodb_data_home_dir','innodb_log_group_home_dir','innodb_undo_directory','tmpdir','innodb_temp_data_file_path','innodb_temp_tablespaces_dir','log_bin_basename','log_bin_index','log_error','slow_query_log_file','general_log_file','secure_file_priv','pid_file','innodb_flush_log_at_trx_commit','sync_binlog','log_bin','binlog_format','binlog_row_image')")) {
                while (rows.next()) values.put(rows.getString(1), rows.getString(2));
            }
            for (var expected : Map.of("server_uuid", args[2], "port", "3307", "bind_address", "127.0.0.1",
                    "innodb_flush_log_at_trx_commit", "1", "sync_binlog", "1", "log_bin", "ON", "binlog_format", "ROW", "binlog_row_image", "FULL").entrySet())
                if (!expected.getValue().equals(values.get(expected.getKey()))) throw new IllegalStateException("Live identity/durability mismatch");
            if (!values.get("version").startsWith("8.4.11") || !Path.of(values.get("datadir")).toRealPath().equals(root.resolve("data").toRealPath()))
                throw new IllegalStateException("Live data location mismatch");
            stage = "db_paths";
            for (String key : List.of("datadir", "innodb_data_home_dir", "innodb_log_group_home_dir", "innodb_undo_directory", "tmpdir",
                    "innodb_temp_tablespaces_dir", "log_bin_basename", "log_bin_index", "log_error", "slow_query_log_file", "general_log_file", "secure_file_priv", "pid_file", "innodb_temp_data_file_path")) {
                String value = values.get(key);
                if (value == null || value.isBlank()) throw new IllegalStateException("Missing storage path");
                if (key.equals("innodb_temp_data_file_path")) value = value.substring(0, value.indexOf(':', 2));
                owned(Path.of(value), root);
                System.out.println(key + "=" + value);
            }
            stage = "db_clients";
            // 동시 회귀나 다른 세션이 사용 중이면 종료·새 환경 채택 금지
            bound(connection,statement);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER<>'event_scheduler' AND COMMAND<>'Daemon'")) {
                if (!rows.next() || rows.getLong(1) != 0) throw new IllegalStateException("Other database client present");
            }
            System.out.println("LIVE_DATABASE_VERIFIED uuid=" + args[2] + " port=3307 catalog=pixel_place_test");
            // 셸 검증 이후 같은 UUID로 재기동한 다른 프로세스의 종료 방지
            identity(root, args[3], args[4], "after_db");
            if (args[0].equals("shutdown")) {stage = "sql_shutdown";bound(connection,statement);statement.execute("SHUTDOWN");}
        }
    }
    // 비밀정보 없이 DB 검사 전후 및 PID/start 조회 실패 지점 구분
    static void identity(Path root, String expectedPid, String expectedStart, String phase) throws Exception {
        stage = phase + "_pid_lookup";
        long pid = Long.parseLong(expectedPid);
        var process = ProcessHandle.of(pid).orElseThrow();
        checkIdentity(root, pid, expectedStart, phase, process);
    }
    static void checkIdentity(Path root, long pid, String expectedStart, String phase, ProcessHandle process) throws Exception {
        stage = phase + "_process_alive";
        if (!process.isAlive()) throw new IllegalStateException("PID not alive");
        stage = phase + "_start_lookup";
        var start = process.info().startInstant().orElseThrow();
        stage = phase + "_pid_file";
        if (Long.parseLong(Files.readString(root.resolve("mysql.pid")).trim()) != pid)
            throw new IllegalStateException("PID file changed");
        stage = phase + "_start_match";
        if (start.toEpochMilli() != Instant.parse(expectedStart).toEpochMilli())
            throw new IllegalStateException("PID/start changed");
    }
    static void owned(Path path, Path root) throws Exception {
        if (!root.isAbsolute() || !root.equals(root.normalize()) || !path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(root))
            throw new IllegalStateException("Storage outside owned root");
        for (Path p = path; p != null; p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && (!p.toRealPath().equals(p) || Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IllegalStateException("Storage alias forbidden");
        }
    }
}
