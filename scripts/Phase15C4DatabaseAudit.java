import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 같은 소유 MySQL 프로세스의 실제 UUID·내구성·catalog를 읽기 전용으로 확인하는 C-4 증거 보강 */
class Phase15C4DatabaseAudit {
    public static void main(String[] args) {
        try { verify(); }
        catch (Exception failure) {
            // JDBC 원문의 접속 정보가 증거 로그에 유출되지 않게 원인 종류만 노출
            System.out.println("C4_LIVE_MYSQL_FAILED type="+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static void verify() throws Exception {
        Path root = Path.of("E:/pixel-place-phase15/phase15-a-20260918").toRealPath();
        var owner = new Properties();
        try (var in = Files.newInputStream(root.resolve("mysql/regression-ownership.properties"))) { owner.load(in); }
        var credentials = new Properties();
        credentials.setProperty("user", Objects.requireNonNull(System.getenv("PIXEL_PLACE_BENCH_DB_USERNAME")));
        credentials.setProperty("password", Objects.requireNonNull(System.getenv("DB_PASSWORD")));
        try (var c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/?connectTimeout=15000&socketTimeout=15000", credentials)) {
            c.setReadOnly(true);
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT @@server_uuid,@@port,@@datadir,@@bind_address,@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@binlog_row_image,@@log_bin,@@binlog_format")) {
                if (!r.next() || !owner.getProperty("server_uuid").equals(r.getString(1)) || r.getInt(2)!=3307 ||
                    !Path.of(r.getString(3)).toRealPath().equals(root.resolve("mysql/data").toRealPath()) ||
                    !r.getString(4).equals("127.0.0.1") || r.getInt(5)!=1 || r.getInt(6)!=1 ||
                    !r.getString(7).equals("FULL") || !r.getBoolean(8) || !r.getString(9).equals("ROW"))
                    throw new IllegalStateException("Live owned MySQL identity/durability mismatch");
            }
            try (var s = c.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                s.setString(1,owner.getProperty("catalog"));
                try(var r=s.executeQuery()){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Owned regression catalog missing");}
            }
        }
        System.out.println("C4_LIVE_MYSQL_VERIFIED uuid=owned port=3307 datadir=owned loopback=true innodbFlush=1 syncBinlog=1 binlog=true rowImage=FULL format=ROW catalog=owned readOnly=true");
    }
}
