package dev.cgt.pixelplace.overview.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverviewServiceTest {

    @Test
    void notReadySkipsRendererWithoutChangingReadiness() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        ServiceReadiness readiness = new ServiceReadiness();
        OverviewService service = new OverviewService(renderer, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());

        service.refresh();

        verify(renderer, never()).render();
        assertFalse(readiness.isReady());
        assertTrue(service.currentPng().isEmpty());
    }

    @Test
    void successiveSuccessfulRefreshesAtomicallyReplacePublishedBytes() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] first = {1, 2, 3};
        byte[] second = {4, 5, 6};
        when(renderer.render()).thenReturn(first, second);
        OverviewService service = new OverviewService(renderer, readyReadiness(), dev.cgt.pixelplace.measurement.Measurements.disabled());

        service.refresh();
        assertSame(first, service.currentPng().orElseThrow());

        service.refresh();
        assertSame(second, service.currentPng().orElseThrow());
    }

    @Test
    void runtimeFailureKeepsLastImageLogsOriginalThrowableAndKeepsReady() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] first = {1, 2, 3};
        RuntimeException failure = new IllegalStateException("render failed");
        when(renderer.render()).thenReturn(first).thenThrow(failure);
        ServiceReadiness readiness = readyReadiness();
        OverviewService service = new OverviewService(renderer, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());
        FailureLogAppender appender = attachAppender();

        try {
            service.refresh();
            service.refresh();

            assertSame(first, service.currentPng().orElseThrow());
            assertSame(failure, appender.failure.get());
            assertEquals(Level.ERROR, appender.level.get());
            assertTrue(readiness.isReady());
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void firstFailureLeavesNoCurrentImageAndLaterSuccessRetriesAfterGuardRelease() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] recovered = {9};
        when(renderer.render())
                .thenThrow(new IllegalStateException("first failed"))
                .thenReturn(recovered);
        OverviewService service = new OverviewService(renderer, readyReadiness(), dev.cgt.pixelplace.measurement.Measurements.disabled());

        service.refresh();
        assertTrue(service.currentPng().isEmpty());

        service.refresh();
        assertSame(recovered, service.currentPng().orElseThrow());
        verify(renderer, times(2)).render();
    }

    @Test
    void rawErrorPropagatesUnchangedKeepsLastImageAndReleasesGuard() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] first = {1, 2, 3};
        byte[] recovered = {4, 5, 6};
        AssertionError fatal = new AssertionError("renderer fatal");
        when(renderer.render()).thenReturn(first).thenThrow(fatal).thenReturn(recovered);
        ServiceReadiness readiness = readyReadiness();
        OverviewService service = new OverviewService(renderer, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());

        service.refresh();
        AssertionError thrown = assertThrows(AssertionError.class, service::refresh);

        assertSame(fatal, thrown);
        assertSame(first, service.currentPng().orElseThrow());
        assertTrue(readiness.isReady());

        service.refresh();
        assertSame(recovered, service.currentPng().orElseThrow());
        verify(renderer, times(3)).render();
    }

    @Test
    void nullAndEmptyRendererResultsAreNeverPublished() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] valid = {7};
        when(renderer.render()).thenReturn(null, new byte[0], valid);
        OverviewService service = new OverviewService(renderer, readyReadiness(), dev.cgt.pixelplace.measurement.Measurements.disabled());

        service.refresh();
        assertTrue(service.currentPng().isEmpty());
        service.refresh();
        assertTrue(service.currentPng().isEmpty());
        service.refresh();
        assertSame(valid, service.currentPng().orElseThrow());
    }

    @Test
    void overlappingRefreshSkipsImmediatelyWithoutDuplicateRender() throws Exception {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        byte[] completed = {1};
        when(renderer.render()).thenAnswer(invocation -> {
            firstEntered.countDown();
            awaitOrFail(releaseFirst);
            return completed;
        });
        OverviewService service = new OverviewService(renderer, readyReadiness(), dev.cgt.pixelplace.measurement.Measurements.disabled());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(service::refresh);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            Future<?> duplicate = executor.submit(service::refresh);
            duplicate.get(1, TimeUnit.SECONDS);
            verify(renderer, times(1)).render();

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertSame(completed, service.currentPng().orElseThrow());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readersKeepCompleteOldImageUntilNewGenerationFinishes() throws Exception {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] oldImage = {1, 1, 1};
        byte[] newImage = {2, 2, 2};
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        when(renderer.render())
                .thenReturn(oldImage)
                .thenAnswer(invocation -> {
                    secondEntered.countDown();
                    awaitOrFail(releaseSecond);
                    return newImage;
                });
        OverviewService service = new OverviewService(renderer, readyReadiness(), dev.cgt.pixelplace.measurement.Measurements.disabled());
        service.refresh();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> generation = executor.submit(service::refresh);
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertSame(oldImage, service.currentPng().orElseThrow());
            assertArrayEquals(new byte[]{1, 1, 1}, service.currentPng().orElseThrow());

            releaseSecond.countDown();
            generation.get(5, TimeUnit.SECONDS);
            assertSame(newImage, service.currentPng().orElseThrow());
        } finally {
            releaseSecond.countDown();
            executor.shutdownNow();
        }
    }

    private ServiceReadiness readyReadiness() {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        return readiness;
    }

    private void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch wait timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Latch wait interrupted.", exception);
        }
    }

    private FailureLogAppender attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(OverviewService.class);
        FailureLogAppender appender = new FailureLogAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(FailureLogAppender appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(OverviewService.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static final class FailureLogAppender extends AppenderBase<ILoggingEvent> {

        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Level> level = new AtomicReference<>();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getThrowableProxy() instanceof ThrowableProxy throwableProxy
                    && failure.compareAndSet(null, throwableProxy.getThrowable())) {
                level.set(event.getLevel());
            }
        }
    }
}
