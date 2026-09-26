package dev.cgt.pixelplace.pixel.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/*
 * 동일 내부 사용자 ID의 cooldown 검사부터 성공 후 저장까지 JVM 내 직렬화
 * holder와 대기자의 참조를 함께 보존하여 같은 ID에 두 lock이 생기는 제거 경쟁 방지
 */
@Component
public class PixelUserWriteGate {
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final long timeoutNanos;

    public PixelUserWriteGate() {
        this(Duration.ofSeconds(5));
    }

    // 테스트의 짧은 대기 한도 주입용이며 production 무한 대기는 허용하지 않음
    PixelUserWriteGate(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Gate timeout must be positive.");
        }
        timeoutNanos = timeout.toNanos();
    }

    /* 획득 대기만 제한하며 callback I/O를 강제 종료하지 않음. 모든 종료 경로에서 참조 반환 */
    public <T> T execute(long userId, Supplier<T> callback) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be greater than zero.");
        }
        Objects.requireNonNull(callback, "callback");
        Entry entry = entries.compute(userId, (key, current) -> {
            Entry selected = current == null ? new Entry() : current;
            selected.references++;
            return selected;
        });
        boolean acquired = false;
        try {
            // map의 key별 compute를 벗어난 뒤 대기해야 release와 다른 사용자 진입을 막지 않음
            acquired = entry.lock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new PixelWriteBusyException();
            }
            return callback.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PixelWriteBusyException();
        } finally {
            if (acquired) {
                entry.lock.unlock();
            }
            entries.compute(userId, (key, current) -> {
                if (current != entry) {
                    // 대기자의 참조가 존재하는 동안 entry 교체는 소유권 불변식 위반
                    throw new IllegalStateException("User gate entry ownership changed.");
                }
                return --entry.references == 0 ? null : entry;
            });
        }
    }

    int entryCount() {
        return entries.size();
    }

    // compute 안에서만 참조 수를 읽고 갱신하는 entry
    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
