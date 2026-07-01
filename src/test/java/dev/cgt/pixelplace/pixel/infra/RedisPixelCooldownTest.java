package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.pixel.application.PixelCooldownActiveException;
import dev.cgt.pixelplace.pixel.application.PixelCooldownUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * RedisPixelCooldown TTL 정책 검증
 * 실제 Redis 연결 없이 key, TTL, 예외 매핑 계약만 고정
 */
class RedisPixelCooldownTest {

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final RedisPixelCooldown pixelCooldown = new RedisPixelCooldown(stringRedisTemplate);

    @Test
    // 남은 TTL은 cooldown 활성 상태로 간주
    void checkWritableThrowsCooldownActiveWhenTtlRemains() {
        when(stringRedisTemplate.getExpire("cooldown:user:7", TimeUnit.MILLISECONDS))
                .thenReturn(123_000L);

        PixelCooldownActiveException exception = assertThrows(
                PixelCooldownActiveException.class,
                () -> pixelCooldown.checkWritable(7L)
        );

        assertEquals(123_000L, exception.remainingMillis());
    }

    @Test
    // key 없음, 만료 직후, Redis null 응답은 write 가능 상태
    void checkWritablePassesWhenTtlIsAbsentOrExpired() {
        when(stringRedisTemplate.getExpire("cooldown:user:7", TimeUnit.MILLISECONDS)).thenReturn(-2L);
        assertDoesNotThrow(() -> pixelCooldown.checkWritable(7L));

        when(stringRedisTemplate.getExpire("cooldown:user:7", TimeUnit.MILLISECONDS)).thenReturn(0L);
        assertDoesNotThrow(() -> pixelCooldown.checkWritable(7L));

        when(stringRedisTemplate.getExpire("cooldown:user:7", TimeUnit.MILLISECONDS)).thenReturn(null);
        assertDoesNotThrow(() -> pixelCooldown.checkWritable(7L));
    }

    @Test
    // TTL 없는 cooldown key는 정책 훼손 상태
    void checkWritableThrowsUnavailableWhenKeyHasNoTtl() {
        when(stringRedisTemplate.getExpire("cooldown:user:7", TimeUnit.MILLISECONDS))
                .thenReturn(-1L);

        assertThrows(PixelCooldownUnavailableException.class, () -> pixelCooldown.checkWritable(7L));
    }

    @Test
    // 성공 write 이후 정확한 key와 180초 TTL 기록
    void startCooldownSetsRedisKeyWithCooldownDuration() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        pixelCooldown.startCooldown(7L);

        verify(valueOperations).set("cooldown:user:7", "1", Duration.ofSeconds(180));
    }

    @Test
    // Redis TTL 조회 실패는 application 예외로 감싸기
    void checkWritableWrapsRedisGetExpireFailure() {
        when(stringRedisTemplate.getExpire("cooldown:user:7", TimeUnit.MILLISECONDS))
                .thenThrow(new RuntimeException("redis down"));

        assertThrows(PixelCooldownUnavailableException.class, () -> pixelCooldown.checkWritable(7L));
    }

    @Test
    // Redis set 실패는 command service가 후처리 실패로 다룰 수 있도록 application 예외로 감싸기
    void startCooldownWrapsRedisSetFailure() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set("cooldown:user:7", "1", Duration.ofSeconds(180));

        assertThrows(PixelCooldownUnavailableException.class, () -> pixelCooldown.startCooldown(7L));
    }
}
