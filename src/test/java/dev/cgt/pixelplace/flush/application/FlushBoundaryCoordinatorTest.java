package dev.cgt.pixelplace.flush.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlushBoundaryCoordinatorTest {

    private final FlushBoundaryCoordinator coordinator = new FlushBoundaryCoordinator();

    @Test
    void secondCallbackWaitsUntilFirstCallbackReleasesBoundary() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> coordinator.coordinate(() -> {
                firstEntered.countDown();
                await(releaseFirst);
                return "first";
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> {
                secondStarted.countDown();
                return coordinator.coordinate(() -> {
                    secondEntered.countDown();
                    return "second";
                });
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
            assertEquals(0L, secondEntered.getCount());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void coordinateReturnsCallbackValue() {
        Object expected = new Object();

        Object actual = coordinator.coordinate(() -> expected);

        assertSame(expected, actual);
    }

    @Test
    void runtimeExceptionReleasesBoundaryAndPropagatesSameInstance() {
        IllegalStateException failure = new IllegalStateException("capture failed");

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator.coordinate(() -> {
                    throw failure;
                })
        );

        assertSame(failure, actual);
        assertEquals("next", coordinator.coordinate(() -> "next"));
    }

    @Test
    void errorReleasesBoundaryAndPropagatesSameInstance() {
        AssertionError failure = new AssertionError("fatal capture failure");

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> coordinator.coordinate(() -> {
                    throw failure;
                })
        );

        assertSame(failure, actual);
        assertEquals("next", coordinator.coordinate(() -> "next"));
    }

    @Test
    void nullCallbackIsRejected() {
        assertThrows(NullPointerException.class, () -> coordinator.coordinate(null));
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
