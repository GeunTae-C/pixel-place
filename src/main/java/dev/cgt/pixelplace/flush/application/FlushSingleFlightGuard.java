package dev.cgt.pixelplace.flush.application;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * flush cycle 전체 중첩 실행을 기다림 없이 차단하는 JVM 단일 인스턴스 guard
 * write/plan capture boundary lock과 별개이며 checkpoint 조회나 persistence 책임은 갖지 않음
 */
@Component
public class FlushSingleFlightGuard {

    private final AtomicBoolean running = new AtomicBoolean();

    /*
     * 비실행 상태에서만 cycle을 한 번 실행하고 실행 중 호출과 같은 thread 재진입은 false로 skip
     * callback 실패를 그대로 전파하되 다음 cycle을 위해 running 상태는 반드시 복구
     */
    public boolean tryRun(Runnable flushCycle) {
        Objects.requireNonNull(flushCycle, "flushCycle must not be null");

        if (!running.compareAndSet(false, true)) {
            return false;
        }

        try {
            flushCycle.run();
            return true;
        } finally {
            running.set(false);
        }
    }
}
