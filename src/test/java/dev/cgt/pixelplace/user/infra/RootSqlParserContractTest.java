package dev.cgt.pixelplace.user.infra;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** 두 실제 MySQL parser를 DB 없이 호출하여 catalog 보호·정확한 DDL 집합·checkpoint 비퇴행 검증 */
class RootSqlParserContractTest {
    @SuppressWarnings("unchecked")
    private List<String> parse(String className, String sql) throws Exception {
        Class<?> type = Class.forName(className);
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        var parser = type.getDeclaredMethod("parseRootSqlStatements", String.class);
        parser.setAccessible(true);
        try { return (List<String>) parser.invoke(constructor.newInstance(), sql); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw exception;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev.cgt.pixelplace.flush.infra.FlushPersistenceMySqlIntegrationTest",
            "dev.cgt.pixelplace.recovery.infra.StartupRecoveryDbViewCaptureMySqlIntegrationTest"})
    void bothProductionDdlParsersReturnExactlyFourTablesAndNonRegressiveSeed(String parser) throws Exception {
        String sql = Files.readString(Path.of("pixel_place.sql"));
        List<String> statements = parse(parser, sql);
        assertEquals(5, statements.size());
        Set<String> tables = statements.stream().filter(s -> s.startsWith("CREATE TABLE"))
                .map(s -> s.split("\\s+")[5]).collect(Collectors.toSet());
        assertEquals(Set.of("tiles", "pixel_events", "wal_checkpoint", "users"), tables);
        assertTrue(statements.stream().noneMatch(s -> s.startsWith("CREATE DATABASE") || s.startsWith("USE ") || s.startsWith("SELECT ")));
        for (String mutation : List.of(
                sql + "DELETE FROM users;", sql + "CREATE TABLE IF NOT EXISTS unexpected (id INT);",
                sql + "CREATE TABLE IF NOT EXISTS users (id INT);", sql.replace("USE `pixel_place`", "USE `another_db`"),
                sql.replace("checkpoint_name = checkpoint_name", "last_flushed_event_seq = 0"),
                sql.replace("CREATE TABLE IF NOT EXISTS users", "CREATE TABLE IF NOT EXISTS pixel_place.users"),
                sql + "DROP DATABASE pixel_place;")) {
            assertThrows(IllegalStateException.class, () -> parse(parser, mutation));
        }
    }
}
