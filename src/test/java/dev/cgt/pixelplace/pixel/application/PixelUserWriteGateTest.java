package dev.cgt.pixelplace.pixel.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/* holder·대기자·후발 요청의 entry 수명과 예외/interrupt 해제 계약 검증 */
class PixelUserWriteGateTest {
    @Test
    void queuedSuccessorKeepsOriginalEntryAfterHolderExitsAndThirdRequestArrives() throws Exception {
        var gate = new PixelUserWriteGate();
        var holderInside = new CountDownLatch(1); var releaseHolder = new CountDownLatch(1);
        var successorInside = new CountDownLatch(1); var releaseSuccessor = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            try {
                var holder = pool.submit(() -> gate.execute(7, () -> { holderInside.countDown(); await(releaseHolder); return null; }));
                await(holderInside); var original = lock(gate, 7);
                var successor = pool.submit(() -> gate.execute(7, () -> { successorInside.countDown(); await(releaseSuccessor); return null; }));
                awaitQueued(original, 1); releaseHolder.countDown(); holder.get(5, TimeUnit.SECONDS); await(successorInside);
                assertSame(original, lock(gate, 7));
                var third = pool.submit(() -> gate.execute(7, () -> 3)); awaitQueued(original, 1);
                assertFalse(third.isDone()); assertSame(original, lock(gate, 7));
                releaseSuccessor.countDown(); successor.get(5, TimeUnit.SECONDS); assertEquals(3, third.get(5, TimeUnit.SECONDS));
                assertEquals(0, gate.entryCount());
            } finally { releaseHolder.countDown(); releaseSuccessor.countDown(); }
        }
    }

    @Test
    void sameUserWaitersAndThirdArrivalNeverOverlapAndDifferentUserEnters() throws Exception {
        var gate = new PixelUserWriteGate();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inside = new AtomicInteger();
        var maxInside = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(3)) {
            Future<?> holder = pool.submit(() -> gate.execute(7, () -> {
                inside.incrementAndGet(); entered.countDown(); await(release); inside.decrementAndGet(); return null;
            }));
            await(entered);
            ReentrantLock original = lock(gate, 7);
            Future<?> waiter = pool.submit(() -> gate.execute(7, () -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                inside.decrementAndGet(); return null;
            }));
            awaitQueued(original, 1);
            assertEquals(9, gate.execute(8, () -> 9));
            Future<?> third = pool.submit(() -> gate.execute(7, () -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                inside.decrementAndGet(); return null;
            }));
            awaitQueued(original, 2);
            assertSame(original, lock(gate, 7));
            release.countDown();
            holder.get(5, TimeUnit.SECONDS); waiter.get(5, TimeUnit.SECONDS); third.get(5, TimeUnit.SECONDS);
            assertEquals(1, maxInside.get());
            assertEquals(0, gate.entryCount());
        } finally { release.countDown(); }
    }

    @Test
    void timeoutAndInterruptNeverRunCallbackAndReleaseTheirReferences() throws Exception {
        var gate = new PixelUserWriteGate(Duration.ofMillis(150));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var called = new AtomicBoolean();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> gate.execute(7, () -> { entered.countDown(); await(release); return null; }));
            await(entered);
            assertThrows(PixelWriteBusyException.class, () -> gate.execute(7, () -> { called.set(true); return null; }));
            assertEquals(1, gate.entryCount());
            var failure = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            Thread waiter = new Thread(() -> {
                try { gate.execute(7, () -> { called.set(true); return null; }); }
                catch (Throwable ex) { failure.set(ex); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            waiter.start(); awaitQueued(lock(gate, 7), 1); waiter.interrupt(); waiter.join(5000);
            assertFalse(waiter.isAlive());
            assertInstanceOf(PixelWriteBusyException.class, failure.get()); assertTrue(interrupted.get());
            assertFalse(called.get());
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertEquals(0, gate.entryCount());
        } finally { release.countDown(); }
    }

    @Test
    void manyUsersSuccessRuntimeExceptionAndErrorLeaveNoIdleEntries() {
        var gate = new PixelUserWriteGate();
        var runtime = new IllegalStateException("callback");
        var error = new AssertionError("callback");
        for (long id = 1; id <= 1000; id++) {
            long userId = id;
            assertEquals(id, gate.execute(id, () -> userId));
            assertSame(runtime, assertThrows(IllegalStateException.class, () -> gate.execute(userId, () -> { throw runtime; })));
            assertSame(error, assertThrows(AssertionError.class, () -> gate.execute(userId, () -> { throw error; })));
            assertEquals(0, gate.entryCount());
        }
        assertThrows(IllegalArgumentException.class, () -> gate.execute(0, () -> null));
        assertThrows(IllegalArgumentException.class, () -> new PixelUserWriteGate(Duration.ZERO));
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "latch deadline"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    // 시간 지연 대신 실제 lock queue 관측으로 경쟁 진입을 확정하는 test-only seam
    static ReentrantLock lock(PixelUserWriteGate gate, long userId) throws Exception {
        var field = PixelUserWriteGate.class.getDeclaredField("entries"); field.setAccessible(true);
        var entries = (java.util.Map<?, ?>) field.get(gate);
        Object entry = entries.get(userId);
        var lockField = entry.getClass().getDeclaredField("lock"); lockField.setAccessible(true);
        return (ReentrantLock) lockField.get(entry);
    }

    static void awaitQueued(ReentrantLock lock, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (lock.getQueueLength() < count && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        }
        assertTrue(lock.getQueueLength() >= count, "waiter must reach the existing lock");
    }
}
