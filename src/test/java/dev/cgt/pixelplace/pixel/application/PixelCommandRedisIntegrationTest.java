package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.pixel.infra.RedisPixelCooldown;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.cgt.pixelplace.pixel.application.PixelUserWriteGateTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/* 실제 적용 Redis 설정과 빈 전용 DB를 먼저 확인한 뒤 고정 소유 key 하나만 생성·정리 */
@Tag("redis-integration")
@ResourceLock("pixel-place-local-redis-test")
class PixelCommandRedisIntegrationTest {
    private static final long USER_ID = 9_015_000_001L;
    private static final String OWNED_KEY = "cooldown:user:9015000001";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataRedisProperties.class)
    static class RedisSettings { }

    @Test
    void realRedisSerializesSameUserAndKeepsActual180SecondTtl() {
        verifyMode(false);
    }

    @Test
    void realRedisGroupSerializesSameUserAndKeepsActual180SecondTtl() {
        verifyMode(true);
    }

    private void verifyMode(boolean group) {
        String configured = System.getenv("PIXEL_PLACE_TEST_REDIS_DATABASE");
        assertNotNull(configured, "Explicit PIXEL_PLACE_TEST_REDIS_DATABASE is required before any Redis access");
        int testDatabase = Integer.parseInt(configured);
        assertTrue(testDatabase >= 1 && testDatabase <= 15, "Test Redis database must be 1..15");
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RedisSettings.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    var production = context.getBean(DataRedisProperties.class);
                    // URL/cluster/sentinel은 database·instance 분리의 별도 검증 없이 사용 금지
                    assertTrue(production.getUrl() == null || production.getUrl().isBlank(), "Redis URL requires explicit isolation review");
                    assertNull(production.getCluster(), "Standalone Redis only");
                    assertNull(production.getSentinel(), "Standalone Redis only");
                    assertTrue(Set.of("localhost", "127.0.0.1", "::1").contains(production.getHost()), "Local Redis only");
                    assertNotEquals(production.getDatabase(), testDatabase, "Production Redis database collision");
                    System.out.println("REDIS_ISOLATION productionDb=" + production.getDatabase() + " testDb=" + testDatabase);
                    var configuration = new RedisStandaloneConfiguration(production.getHost(), production.getPort());
                    configuration.setDatabase(testDatabase);
                    if (production.getUsername() != null) configuration.setUsername(production.getUsername());
                    if (production.getPassword() != null) configuration.setPassword(production.getPassword());
                    var factory = new LettuceConnectionFactory(configuration);
                    factory.afterPropertiesSet(); factory.start();
                    try {
                        try (var connection = factory.getConnection()) {
                            assertEquals("0", connection.serverCommands().info("cluster").getProperty("cluster_enabled"));
                            assertEquals("master", connection.serverCommands().info("replication").getProperty("role"));
                            // 기존 흔적이 있으면 삭제나 테스트 write 없이 즉시 중단
                            assertEquals(0L, connection.serverCommands().dbSize(), "Existing data in test Redis DB: no mutation permitted");
                        }
                        System.out.println("REDIS_ISOLATION standalone=true initialDbSize=0");
                        runCompetition(new StringRedisTemplate(factory),group);
                    } finally { factory.destroy(); }
                });
    }

    private void runCompetition(StringRedisTemplate redis,boolean group) throws Exception {
        assertEquals("cooldown:user:" + USER_ID, OWNED_KEY);
        assertFalse(redis.hasKey(OWNED_KEY));
        var created = new AtomicBoolean();
        var actualCooldown = new RedisPixelCooldown(redis);
        var cooldown = spy(new PixelCooldown() {
            public void checkWritable(long userId) { actualCooldown.checkWritable(userId); }
            public void startCooldown(long userId) { actualCooldown.startCooldown(userId); created.set(true); }
        });
        var readiness = new ServiceReadiness(); readiness.markReady();
        var sequence = spy(new EventSeqManager()); var wal = mock(WalAppender.class);
        var board = new InMemoryTileBoard(); var dirty = mock(DirtyTileTracker.class);
        var broadcast = mock(PixelBroadcastService.class); var gate = new PixelUserWriteGate();
        var core = spy(new PixelWriteService(sequence, wal, board, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()));
        var boundary=new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled());
        PixelWriteExecutor executor=group?new GroupPixelWriteExecutor(boundary,core,dirty,readiness,new WriteExecutionProperties(),dev.cgt.pixelplace.measurement.Measurements.disabled())
                :new SinglePixelWriteExecutor(boundary,core,dirty,readiness,dev.cgt.pixelplace.measurement.Measurements.disabled());
        var command = new PixelCommandService(cooldown, executor,
                broadcast,
                readiness,
                gate,
                dev.cgt.pixelplace.measurement.Measurements.disabled());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); await(release); return null; }).when(wal).appendAndFsync(any());
        doAnswer(call -> { entered.countDown(); await(release); return null; }).when(wal).appendBatchAndFsync(any());
        try (executor; var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> command.writePixel(USER_ID, 1, 2, 3)); await(entered);
                var second = pool.submit(() -> command.writePixel(USER_ID, 1, 2, 4)); awaitQueued(lock(gate, USER_ID), 1);
                verify(cooldown, times(1)).checkWritable(USER_ID);
                release.countDown(); var result = first.get(5, TimeUnit.SECONDS);
                var rejected = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
                var active = assertInstanceOf(PixelCooldownActiveException.class, rejected.getCause());
                assertTrue(active.remainingMillis() > 0 && active.remainingMillis() <= 180_000);
                long ttl = redis.getExpire(OWNED_KEY, TimeUnit.MILLISECONDS);
                assertTrue(ttl > 170_000 && ttl <= 180_000);
                verify(sequence, times(1)).allocate();
                if(group){verify(wal,times(1)).appendBatchAndFsync(any());verify(wal,never()).appendAndFsync(any());}
                else verify(wal, times(1)).appendAndFsync(any());
                verify(dirty, times(1)).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
                verify(broadcast, times(1)).broadcast(any()); assertEquals(0, gate.entryCount());
                assertEquals(3, board.getRequired(result.tileKey()).pixels()[2 * 256 + 1]);
            } finally { release.countDown(); }
        } finally {
            // 성공 저장을 직접 확인한 고정 소유 key만 exact delete. DB 전체 삭제는 사용하지 않음
            if (created.get()) assertTrue(redis.delete(OWNED_KEY));
        }
        assertFalse(redis.hasKey(OWNED_KEY));
        System.out.println("REDIS_OWNERSHIP created=1 deleted=1 remainingOwnedKeys=0");
    }
}
