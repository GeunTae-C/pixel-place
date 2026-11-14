import java.nio.file.*;
import java.sql.*;
import java.util.*;

// 측정이 끝난 소유 E: 프로세스만 정상 종료. 기존 서비스와 파일·로그 보존
class Phase15OwnedDatabaseStop {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("E:/pixel-place-phase15/phase15-a-20260918");
        var owner = new Properties();
        try (var input = Files.newInputStream(root.resolve("mysql/regression-ownership.properties"))) { owner.load(input); }
        var credentials = new Properties();
        credentials.setProperty("user", Objects.requireNonNull(System.getenv("PIXEL_PLACE_BENCH_DB_USERNAME")));
        credentials.setProperty("password", Objects.requireNonNull(System.getenv("DB_PASSWORD")));
        try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/?connectTimeout=15000&socketTimeout=15000", credentials);
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT @@port,@@server_uuid,@@datadir,@@bind_address")) {
                if (!rows.next() || rows.getInt(1) != 3307 || !rows.getString(2).equals(owner.getProperty("server_uuid"))
                        || !Path.of(rows.getString(3)).toRealPath().equals(root.resolve("mysql/data").toRealPath())
                        || !"127.0.0.1".equals(rows.getString(4))) throw new IllegalStateException("Owned E instance identity mismatch");
            }
            statement.execute("SHUTDOWN");
        }
    }
}
