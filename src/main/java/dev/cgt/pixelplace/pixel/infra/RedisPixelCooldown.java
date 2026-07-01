package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.pixel.application.PixelCooldown;
import dev.cgt.pixelplace.pixel.application.PixelCooldownActiveException;
import dev.cgt.pixelplace.pixel.application.PixelCooldownUnavailableException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/*
 * Redis 기반 pixel write cooldown 저장소
 * Redis는 authoritative pixel state가 아니라 per-user cooldown TTL 저장소로만 사용
 */
@Component
public class RedisPixelCooldown implements PixelCooldown {

    static final String KEY_PREFIX = "cooldown:user:";
    static final Duration COOLDOWN_DURATION = Duration.ofSeconds(180);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisPixelCooldown(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*
     * cooldown key TTL 확인
     * TTL이 살아 있으면 write path 진입 금지, TTL 없는 key는 정책 훼손 상태로 실패 처리
     */
    @Override
    public void checkWritable(long userId) {
        try {
            Long remainingMillis = stringRedisTemplate.getExpire(key(userId), TimeUnit.MILLISECONDS);
            if (remainingMillis != null && remainingMillis > 0) {
                throw new PixelCooldownActiveException(remainingMillis);
            }
            if (remainingMillis != null && remainingMillis == -1L) {
                throw new PixelCooldownUnavailableException("Pixel cooldown key has no TTL.", null);
            }
        } catch (PixelCooldownActiveException exception) {
            throw exception;
        } catch (PixelCooldownUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PixelCooldownUnavailableException("Pixel cooldown check failed.", exception);
        }
    }

    /*
     * 성공한 write 이후 180초 cooldown 기록
     * 선점 set 아님, core write 성공 후 후처리
     */
    @Override
    public void startCooldown(long userId) {
        try {
            stringRedisTemplate.opsForValue().set(key(userId), "1", COOLDOWN_DURATION);
        } catch (RuntimeException exception) {
            throw new PixelCooldownUnavailableException("Pixel cooldown set failed.", exception);
        }
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
