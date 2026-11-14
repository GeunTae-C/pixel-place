import java.nio.file.*;
import java.sql.*;
import java.util.*;

// 최종 회귀에 한정한 E: 인스턴스·catalog 소유권·실제 저장 경로 검증. 기존 3306 변경 금지
class Phase15RegressionDatabase {
    static final Path ROOT = Path.of("E:/pixel-place-phase15/phase15-a-20260918").toAbsolutePath().normalize();
    static final Path OWNER = ROOT.resolve("mysql/regression-ownership.properties");
    static final String CATALOG = "pixel_place_test";

    public static void main(String[] args) throws Exception {
        boolean after = args.length == 1 && args[0].equals("verify-after");
        if (!after && (args.length != 1 || !args[0].equals("prepare"))) throw new IllegalArgumentException("Explicit mode required");
        var credentials = new Properties();
        credentials.setProperty("user", Objects.requireNonNull(System.getenv("PIXEL_PLACE_BENCH_DB_USERNAME")));
        credentials.setProperty("password", Objects.requireNonNull(System.getenv("DB_PASSWORD")));
        try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/?connectTimeout=15000&socketTimeout=15000", credentials);
             var statement = connection.createStatement()) {
            var observed = new Properties();
            try (var rows = statement.executeQuery("SHOW VARIABLES WHERE Variable_name IN ('server_uuid','port','bind_address','version','datadir','innodb_data_home_dir','innodb_log_group_home_dir','innodb_undo_directory','tmpdir','innodb_temp_data_file_path','innodb_temp_tablespaces_dir','log_bin_basename','log_error','slow_query_log_file','general_log_file')")) {
                while (rows.next()) observed.setProperty(rows.getString(1), rows.getString(2));
            }
            if (!"3307".equals(observed.getProperty("port")) || !"127.0.0.1".equals(observed.getProperty("bind_address")) || !observed.getProperty("version", "").startsWith("8.4.11")) throw new IllegalStateException("Unexpected representative server");
            Path datadir = Path.of(observed.getProperty("datadir")).toRealPath();
            if (!datadir.equals(ROOT.resolve("mysql/data").toRealPath())) throw new IllegalStateException("Unexpected datadir");
            for (String key : List.of("datadir", "innodb_data_home_dir", "innodb_log_group_home_dir", "innodb_undo_directory", "tmpdir", "innodb_temp_tablespaces_dir", "log_bin_basename", "log_error", "slow_query_log_file", "general_log_file", "innodb_temp_data_file_path")) {
                String value = observed.getProperty(key);
                if (value == null || value.isBlank()) throw new IllegalStateException("Storage path absent: " + key);
                if (key.equals("innodb_temp_data_file_path")) value = value.substring(0, value.indexOf(':', 2));
                requireOwned(Path.of(value));
            }
            boolean exists;
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='pixel_place_test'")) { rows.next(); exists = rows.getInt(1) == 1; }
            if (exists) {
                var owner = new Properties();
                try (var input = Files.newInputStream(OWNER)) { owner.load(input); }
                if (!observed.getProperty("server_uuid").equals(owner.getProperty("server_uuid")) || !CATALOG.equals(owner.getProperty("catalog"))) throw new IllegalStateException("Existing catalog ownership not established");
            } else {
                if (after || Files.exists(OWNER)) throw new IllegalStateException("Unexpected regression catalog state");
                statement.executeUpdate("CREATE DATABASE pixel_place_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                var owner = new Properties(); owner.setProperty("server_uuid", observed.getProperty("server_uuid")); owner.setProperty("catalog", CATALOG);
                try (var output = Files.newOutputStream(OWNER, StandardOpenOption.CREATE_NEW)) { owner.store(output, "phase15 final regression only; E instance"); }
            }
            int files = 0;
            try (var rows = statement.executeQuery("SELECT d.PATH FROM information_schema.INNODB_DATAFILES d JOIN information_schema.INNODB_TABLESPACES s ON s.SPACE=d.SPACE WHERE s.NAME LIKE 'pixel_place_test/%'")) {
                while (rows.next()) {
                    Path file = Path.of(rows.getString(1)); if (!file.isAbsolute()) file = datadir.resolve(file);
                    requireOwned(file.toRealPath()); files++;
                }
            }
            if (after && files != 4) throw new IllegalStateException("Expected four regression data files");
            observed.setProperty("catalog", CATALOG); observed.setProperty("verifiedDataFiles", Integer.toString(files));
            Path evidence = Path.of(Objects.requireNonNull(System.getenv("PIXEL_PLACE_REGRESSION_EVIDENCE"))).toRealPath();
            requireOwned(evidence);
            if (!evidence.startsWith(ROOT.resolve("agent-runs").toRealPath())) throw new IllegalStateException("Evidence owner mismatch");
            try (var output = Files.newOutputStream(evidence.resolve("regression-" + (after ? "after" : "before") + ".properties"), StandardOpenOption.CREATE_NEW)) { observed.store(output, "Verified actual E instance; original 3306 not tested"); }
            System.out.println("REGRESSION_DATABASE_VERIFIED port=3307 catalog=pixel_place_test storage=E dataFiles=" + files);
        }
    }

    static void requireOwned(Path value) throws Exception {
        Path path = value.toAbsolutePath().normalize();
        if (!path.startsWith(ROOT) || !value.isAbsolute()) throw new IllegalStateException("Storage outside owned E root");
        Path parent = path; while (!Files.exists(parent)) parent = parent.getParent();
        if (!parent.toRealPath().startsWith(ROOT.toRealPath())) throw new IllegalStateException("Storage alias outside owned E root");
    }
}
