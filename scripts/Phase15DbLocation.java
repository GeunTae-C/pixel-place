import java.sql.*;
import java.util.*;

// 사용자 지정 E: 전환 전에 실제 MySQL 파일·임시 경로를 읽기 전용으로 확인하는 준비 도구
class Phase15DbLocation {
    public static void main(String[] args) throws Exception {
        var credentials = new Properties();
        credentials.setProperty("user", System.getenv("PIXEL_PLACE_BENCH_DB_USERNAME"));
        credentials.setProperty("password", System.getenv("DB_PASSWORD"));
        int port = args.length == 0 ? 3306 : Integer.parseInt(args[0]);
        if (port != 3306 && port != 3307) throw new IllegalArgumentException("Approved local ports only");
        try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+port+"/?connectTimeout=15000&socketTimeout=15000", credentials);
             var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            try (var rows = statement.executeQuery("SHOW VARIABLES WHERE Variable_name IN ('version','datadir','innodb_directories','innodb_data_home_dir','innodb_log_group_home_dir','innodb_undo_directory','tmpdir','innodb_temp_data_file_path','innodb_temp_tablespaces_dir','log_bin_basename','innodb_file_per_table','innodb_redo_log_capacity','innodb_buffer_pool_size','innodb_buffer_pool_instances','innodb_flush_log_at_trx_commit','sync_binlog','binlog_format','binlog_row_image','binlog_transaction_compression','innodb_doublewrite','innodb_flush_method','innodb_io_capacity','innodb_io_capacity_max','innodb_read_io_threads','innodb_write_io_threads','innodb_log_buffer_size','innodb_page_size','max_connections','max_allowed_packet','transaction_isolation','character_set_server','collation_server','lower_case_table_names','sql_mode','performance_schema','binlog_expire_logs_seconds','max_binlog_size','table_open_cache','thread_cache_size','innodb_flush_neighbors','innodb_adaptive_hash_index','log_error','bind_address','port','log_bin','general_log','slow_query_log','innodb_dedicated_server','innodb_purge_threads','innodb_change_buffering','temptable_max_ram','temptable_max_mmap','tmp_table_size','max_heap_table_size')")) {
                while (rows.next()) System.out.println(rows.getString(1) + "=" + rows.getString(2));
            }
            try (var rows = statement.executeQuery("SELECT d.PATH FROM information_schema.INNODB_DATAFILES d JOIN information_schema.INNODB_TABLESPACES s ON s.SPACE=d.SPACE WHERE s.NAME='pixel_place_bench_r20260917144727572_1ba209/tiles'")) {
                while(rows.next()) System.out.println("lastSmokeTilesPath="+rows.getString(1));
            }
        }
    }
}
