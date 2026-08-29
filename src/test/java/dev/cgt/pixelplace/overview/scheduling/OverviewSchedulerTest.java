package dev.cgt.pixelplace.overview.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.overview.application.OverviewRenderer;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverviewSchedulerTest {

    @Test
    void scheduledInvocationBeforeApplicationReadyEventSkipsService() {
        OverviewService service = mock(OverviewService.class);
        OverviewScheduler scheduler = new OverviewScheduler(service, new RecordingTaskScheduler());

        scheduler.runScheduledRefresh();

        verify(service, never()).refresh();
    }

    @Test
    void scheduledInvocationAfterApplicationReadyEventDelegatesExactlyOnce() {
        OverviewService service = mock(OverviewService.class);
        OverviewScheduler scheduler = new OverviewScheduler(service, new RecordingTaskScheduler());

        scheduler.scheduleInitialRefresh();
        scheduler.runScheduledRefresh();

        verify(service, times(1)).refresh();
    }

    @Test
    void realServiceSkipsRendererWhenScheduledBeforeReady() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        OverviewService service = new OverviewService(renderer, new ServiceReadiness());
        OverviewScheduler scheduler = new OverviewScheduler(service, new RecordingTaskScheduler());

        scheduler.scheduleInitialRefresh();
        scheduler.runScheduledRefresh();

        verify(renderer, never()).render();
    }

    @Test
    void applicationReadyListenerSubmitsWithoutRunningRendererOnListenerThread() {
        OverviewService service = mock(OverviewService.class);
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OverviewScheduler scheduler = new OverviewScheduler(service, taskScheduler);

        scheduler.scheduleInitialRefresh();

        verify(service, never()).refresh();
        assertNotNull(taskScheduler.oneShotTask.get());
        assertEquals(1, taskScheduler.oneShotSubmissions);

        taskScheduler.oneShotTask.get().run();
        verify(service).refresh();
    }

    @Test
    void duplicateApplicationReadyEventDoesNotSubmitInitialRefreshAgain() {
        OverviewService service = mock(OverviewService.class);
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OverviewScheduler scheduler = new OverviewScheduler(service, taskScheduler);

        scheduler.scheduleInitialRefresh();
        scheduler.scheduleInitialRefresh();

        assertEquals(1, taskScheduler.oneShotSubmissions);
        verify(service, never()).refresh();
    }

    @Test
    void initialSubmissionFailureIsLoggedAndPeriodicInvocationCanRetry() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] recovered = {1};
        when(renderer.render()).thenReturn(recovered);
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        OverviewService service = new OverviewService(renderer, readiness);
        RuntimeException submissionFailure = new IllegalStateException("scheduler rejected task");
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        taskScheduler.submissionFailure = submissionFailure;
        OverviewScheduler scheduler = new OverviewScheduler(service, taskScheduler);
        FailureLogAppender appender = attachAppender();

        try {
            scheduler.scheduleInitialRefresh();
            verify(renderer, never()).render();
            assertEquals(1, taskScheduler.oneShotSubmissions);
            assertSame(submissionFailure, appender.failure.get());
            assertEquals(Level.ERROR, appender.level.get());
            assertTrue(readiness.isReady());
            assertTrue(service.currentPng().isEmpty());

            scheduler.runScheduledRefresh();
            verify(renderer).render();
            assertSame(recovered, service.currentPng().orElseThrow());
            assertTrue(readiness.isReady());
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void nullInitialSubmissionResultIsLoggedAndPeriodicInvocationCanRetry() {
        OverviewService service = mock(OverviewService.class);
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        taskScheduler.returnNull = true;
        OverviewScheduler scheduler = new OverviewScheduler(service, taskScheduler);
        FailureLogAppender appender = attachAppender();

        try {
            scheduler.scheduleInitialRefresh();

            verify(service, never()).refresh();
            assertEquals(1, taskScheduler.oneShotSubmissions);
            assertEquals(Level.ERROR, appender.level.get());
            assertEquals(
                    "Initial overview refresh was not scheduled.",
                    appender.failure.get().getMessage()
            );

            scheduler.runScheduledRefresh();
            verify(service).refresh();
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void rendererFailureIsolatedByServiceAllowsNextScheduledRetry() {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        byte[] recovered = {1};
        when(renderer.render())
                .thenThrow(new IllegalStateException("render failed"))
                .thenReturn(recovered);
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        OverviewService service = new OverviewService(renderer, readiness);
        OverviewScheduler scheduler = new OverviewScheduler(service, new RecordingTaskScheduler());

        scheduler.scheduleInitialRefresh();
        scheduler.runScheduledRefresh();
        assertTrue(service.currentPng().isEmpty());
        scheduler.runScheduledRefresh();

        assertSame(recovered, service.currentPng().orElseThrow());
        assertTrue(readiness.isReady());
    }

    @Test
    void metadataUsesDefaultSchedulerFixedDelayAndQualifiedInitialScheduler() throws Exception {
        Method scheduledMethod = OverviewScheduler.class.getDeclaredMethod("runScheduledRefresh");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("", scheduled.scheduler());
        assertEquals(BoardConstants.OVERVIEW_REFRESH_MILLIS, scheduled.fixedDelay());
        assertEquals(BoardConstants.OVERVIEW_REFRESH_MILLIS, scheduled.initialDelay());
        assertEquals(-1L, scheduled.fixedRate());
        assertEquals("", scheduled.fixedRateString());
        assertEquals("", scheduled.cron());

        Method initialMethod = OverviewScheduler.class.getDeclaredMethod("scheduleInitialRefresh");
        EventListener listener = initialMethod.getAnnotation(EventListener.class);
        assertNotNull(listener);
        assertTrue(Arrays.asList(listener.value()).contains(ApplicationReadyEvent.class));

        Profile profile = OverviewScheduler.class.getAnnotation(Profile.class);
        assertNotNull(profile);
        assertEquals(List.of("!stub"), Arrays.asList(profile.value()));

        Constructor<?> constructor = OverviewScheduler.class.getConstructors()[0];
        Qualifier qualifier = Arrays.stream(constructor.getParameterAnnotations()[1])
                .filter(Qualifier.class::isInstance)
                .map(Qualifier.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("taskScheduler", qualifier.value());
    }

    private FailureLogAppender attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(OverviewScheduler.class);
        FailureLogAppender appender = new FailureLogAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(FailureLogAppender appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(OverviewScheduler.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {

        private final AtomicReference<Runnable> oneShotTask = new AtomicReference<>();
        private RuntimeException submissionFailure;
        private boolean returnNull;
        private int oneShotSubmissions;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            oneShotSubmissions++;
            if (submissionFailure != null) {
                throw submissionFailure;
            }
            oneShotTask.set(task);
            if (returnNull) {
                return null;
            }
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task,
                Instant startTime,
                Duration period
        ) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task,
                Instant startTime,
                Duration delay
        ) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return mock(ScheduledFuture.class);
        }
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
