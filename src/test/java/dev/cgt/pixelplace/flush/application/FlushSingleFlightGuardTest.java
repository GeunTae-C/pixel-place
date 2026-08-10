package dev.cgt.pixelplace.flush.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlushSingleFlightGuardTest {

    private final FlushSingleFlightGuard guard = new FlushSingleFlightGuard();

    @Test
    void emptyGuardRunsCycleAndReturnsTrue() {
        AtomicBoolean executed = new AtomicBoolean();

        boolean ran = guard.tryRun(() -> executed.set(true));

        assertTrue(ran);
        assertTrue(executed.get());
    }

    @Test
    // 실행 중 호출은 기다리지 않으며 checkpoint/WAL/dirty/persistence 모사 작업에도 진입 금지
    void concurrentCycleIsSkippedWithoutStartingAnyWork() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger checkpointReads = new AtomicInteger();
        AtomicInteger walReads = new AtomicInteger();
        AtomicInteger dirtyDrains = new AtomicInteger();
        AtomicInteger persistenceCalls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> guard.tryRun(() -> {
                firstEntered.countDown();
                await(releaseFirst);
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            Future<Boolean> skipped = executor.submit(() -> guard.tryRun(() -> {
                checkpointReads.incrementAndGet();
                walReads.incrementAndGet();
                dirtyDrains.incrementAndGet();
                persistenceCalls.incrementAndGet();
            }));

            assertFalse(skipped.get(5, TimeUnit.SECONDS));
            assertEquals(0, checkpointReads.get());
            assertEquals(0, walReads.get());
            assertEquals(0, dirtyDrains.get());
            assertEquals(0, persistenceCalls.get());

            releaseFirst.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nextCycleCanRunAfterSuccessfulCycle() {
        assertTrue(guard.tryRun(() -> { }));

        AtomicBoolean nextExecuted = new AtomicBoolean();
        assertTrue(guard.tryRun(() -> nextExecuted.set(true)));
        assertTrue(nextExecuted.get());
    }

    @Test
    void runtimeExceptionIsPropagatedAndNextCycleCanRun() {
        IllegalArgumentException cause = new IllegalArgumentException("db unavailable");
        IllegalStateException failure = new IllegalStateException("flush failed", cause);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> guard.tryRun(() -> {
                    throw failure;
                })
        );

        assertSame(failure, actual);
        assertSame(cause, actual.getCause());
        assertTrue(guard.tryRun(() -> { }));
    }

    @Test
    void errorIsPropagatedAndNextCycleCanRun() {
        AssertionError failure = new AssertionError("fatal flush failure");

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> guard.tryRun(() -> {
                    throw failure;
                })
        );

        assertSame(failure, actual);
        assertTrue(guard.tryRun(() -> { }));
    }

    @Test
    void sameThreadNestedCycleIsSkippedWithoutExecutingNestedCallback() {
        AtomicReference<Boolean> nestedResult = new AtomicReference<>();
        AtomicBoolean nestedExecuted = new AtomicBoolean();

        boolean outerResult = guard.tryRun(() -> nestedResult.set(
                guard.tryRun(() -> nestedExecuted.set(true))
        ));

        assertTrue(outerResult);
        assertFalse(nestedResult.get());
        assertFalse(nestedExecuted.get());
    }

    @Test
    void nullCallbackIsRejectedWithoutChangingGuardState() {
        assertThrows(NullPointerException.class, () -> guard.tryRun(null));

        assertTrue(guard.tryRun(() -> { }));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch wait timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Latch wait was interrupted.", exception);
        }
    }
}
