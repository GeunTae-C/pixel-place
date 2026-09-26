package dev.cgt.benchmark;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** 예정 도착률을 OS park의 짧은 wake-up 정밀도에 맡기지 않는 발생기 전용 대기. 보충 송신 책임 없음 */
final class BenchmarkPacer {
    private static final long SPIN_WINDOW_NANOS = 20_000_000L;
    private BenchmarkPacer() { }

    /** 먼 시각은 park, 마지막 유한 구간만 spin. 실제 늦은 요청의 not_sent 판정은 caller에 유지 */
    static void await(long deadline) throws InterruptedException {
        await(deadline, System::nanoTime, LockSupport::parkNanos, Thread::onSpinWait);
    }

    static void await(long deadline, LongSupplier clock, LongConsumer park, Runnable spin) throws InterruptedException {
        long remaining;
        while ((remaining = deadline - clock.getAsLong()) > 0) {
            if (Thread.interrupted()) throw new InterruptedException();
            // 짧은 park가 예정 간격보다 늦게 깨는 환경에서도 마지막 구간의 도착 시각 보존
            if (remaining > SPIN_WINDOW_NANOS) park.accept(remaining - SPIN_WINDOW_NANOS);
            else spin.run();
        }
        // park가 deadline 이후에 interrupt로 풀린 경우도 새 송신으로 진행하지 않음
        if (Thread.interrupted()) throw new InterruptedException();
    }
}
