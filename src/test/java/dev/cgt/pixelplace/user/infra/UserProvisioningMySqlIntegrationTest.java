package dev.cgt.pixelplace.user.infra;

import dev.cgt.pixelplace.user.application.UserProvisioningService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 전용 MySQL에서 실제 repository별 commit·rollback 및 UNIQUE 경쟁 검증. 테스트 전체 transaction 금지 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never", "spring.jpa.open-in-view=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("mysql-integration")
@Tag("mysql-integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ResourceLock(value = "pixel_place_test", mode = ResourceAccessMode.READ_WRITE)
@Execution(ExecutionMode.SAME_THREAD)
@Import(UserProvisioningService.class)
class UserProvisioningMySqlIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired UserJpaRepository repository;
    @Autowired UserProvisioningService service;
    @Autowired PlatformTransactionManager transactionManager;

    private void requireTestCatalog(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
            assertTrue(result.next());
            assertEquals("pixel_place_test", result.getString(1), "Destructive operation requires test catalog");
        }
    }

    @BeforeEach
    void prepareUsersFromRootDdlOnlyInAllowlistedMySql() throws Exception {
        String sql = Files.readString(Path.of("pixel_place.sql"));
        var definitions = sql.lines().map(line -> line.replaceFirst("--.*$", "")).reduce("", (a,b) -> a + "\n" + b).split(";");
        String users = null;
        for (String definition : definitions) {
            if (definition.trim().startsWith("CREATE TABLE IF NOT EXISTS users (")) {
                assertNull(users);
                users = definition.trim();
            }
        }
        assertNotNull(users);
        assertFalse(users.contains("`"));
        assertFalse(users.contains("."));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            requireTestCatalog(connection);
            try (var result = statement.executeQuery("SELECT VERSION(), @@version_comment")) {
                assertTrue(result.next());
                assertTrue(result.getString(1).matches("^8\\.4\\.11(?:[-+~].*)?$"));
                assertTrue(result.getString(2).toLowerCase(Locale.ROOT).contains("mysql"));
            }
            requireTestCatalog(connection);
            statement.executeUpdate("DROP TABLE IF EXISTS users");
            requireTestCatalog(connection);
            statement.execute(users);
        }
    }

    @AfterEach
    void cleanOnlyAllowlistedUsers() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            requireTestCatalog(connection);
            statement.executeUpdate("DELETE FROM users");
        }
    }

    @Test
    void insertAndReloginPreserveInternalIdAndDbManagedMillisecondTimestamps() throws Exception {
        long id = service.provision(8123456);
        assertTrue(id > 0);
        assertEquals(id, service.provision(8123456));
        UserEntity selected = repository.findByKakaoUserId(8123456).orElseThrow();
        assertNotNull(selected.getCreatedAt());
        assertNotNull(selected.getUpdatedAt());
        assertEquals(0, selected.getCreatedAt().getNano() % 1_000_000);
        assertEquals(0, selected.getUpdatedAt().getNano() % 1_000_000);
        assertEquals(1, repository.count());
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT column_name, column_default, extra FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users'")) {
            requireTestCatalog(connection);
            try (var result = statement.executeQuery()) {
                int count = 0;
                while (result.next()) {
                    count++;
                    String name = result.getString(1);
                    String extra = result.getString(3).toLowerCase(Locale.ROOT);
                    if (name.equals("id")) assertTrue(extra.contains("auto_increment"));
                    if (name.endsWith("_at")) assertEquals("current_timestamp(3)", result.getString(2).toLowerCase(Locale.ROOT));
                    if (name.equals("updated_at")) assertTrue(extra.contains("on update current_timestamp(3)"));
                }
                assertEquals(4, count);
            }
        }
    }

    @Test
    void concurrentInitialRequestsConvergeAfterActualUniqueRollbackAndFreshLookup() throws Exception {
        CyclicBarrier firstMisses = new CyclicBarrier(2);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        UserJpaRepository boundary = mock(UserJpaRepository.class);
        when(boundary.findByKakaoUserId(8123457)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Optional<UserEntity> row = repository.findByKakaoUserId(8123457);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (lookups.incrementAndGet() <= 2) {
                assertTrue(row.isEmpty());
                firstMisses.await(10, TimeUnit.SECONDS);
            }
            return row;
        });
        when(boundary.saveAndFlush(any())).thenAnswer(invocation -> {
            try { return repository.saveAndFlush(invocation.getArgument(0)); }
            catch (DataIntegrityViolationException failure) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                conflicts.incrementAndGet();
                throw failure;
            }
        });
        // barrier는 실제 조회 transaction 반환 뒤만 개입. insert/rollback/재조회는 real repository proxy 사용
        UserProvisioningService concurrentService = new UserProvisioningService(boundary);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> concurrentService.provision(8123457));
            Future<Long> second = executor.submit(() -> concurrentService.provision(8123457));
            assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, conflicts.get());
            assertEquals(3, lookups.get());
            assertEquals(1, repository.count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualAmbientTransactionRejectsProvisioningAndLeavesNoRow() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> service.provision(8123458)));
        assertTrue(repository.findByKakaoUserId(8123458).isEmpty());
    }

    @Test
    void repositoryDeclaredQueryAndInsertHaveActualIndependentTransactions() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        repository.saveAndFlush(new UserEntity(8123459));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(new UserEntity(8123459)));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(repository.findByKakaoUserId(8123459).isPresent());
    }
}
