package dev.cgt.pixelplace.flush.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import dev.cgt.pixelplace.flush.application.DbBootstrapState;
import dev.cgt.pixelplace.flush.application.FlushPlan;
import dev.cgt.pixelplace.flush.application.FlushPlanCaptureService;
import dev.cgt.pixelplace.flush.application.FlushReconciliationService;
import dev.cgt.pixelplace.flush.application.FlushRunResult;
import dev.cgt.pixelplace.flush.application.FlushSingleFlightGuard;
import dev.cgt.pixelplace.flush.application.FlushTransactionExecutor;
import dev.cgt.pixelplace.flush.application.FlushTransactionResult;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlushSchedulingIntegrationTest {

    @Test
    void namedFlushSchedulerContinuesAfterRuntimeFailureAndPlainTaskUsesDefaultScheduler() {
        CountDownLatch secondFlush = new CountDownLatch(1);
        CountDownLatch releaseSecondFlush = new CountDownLatch(1);
        AtomicInteger flushCalls = new AtomicInteger();
        AtomicReference<String> flushThread = new AtomicReference<>();
        FlushWorker worker = mock(FlushWorker.class);
        when(worker.flushOnce()).thenAnswer(invocation -> {
            flushThread.compareAndSet(null, Thread.currentThread().getName());
            if (flushCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("first invocation failed");
            }
            secondFlush.countDown();
            awaitOrFail(releaseSecondFlush);
            return FlushRunResult.NO_OP;
        });
        UnqualifiedScheduledTask plainTask = new UnqualifiedScheduledTask();

        try (SchedulingContext scheduling = openContext(worker, plainTask)) {
            try {
                assertTrue(await(secondFlush, 5_000));
                assertTrue(await(plainTask.firstInvocation, 5_000));
                assertTrue(flushThread.get().startsWith("pixel-place-flush-"));
                assertTrue(plainTask.firstThread.get().startsWith("test-default-"));
                assertFalse(plainTask.ranOnFlushScheduler.get());
                verify(worker, times(2)).flushOnce();
            } finally {
                releaseSecondFlush.countDown();
            }
        }
    }

    @Test
    void fixedDelayDoesNotOverlapWhileFirstInvocationIsBlocked() {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        FlushWorker worker = mock(FlushWorker.class);
        when(worker.flushOnce()).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            int currentActive = active.incrementAndGet();
            maxActive.accumulateAndGet(currentActive, Math::max);
            try {
                if (call == 1) {
                    firstEntered.countDown();
                    awaitOrFail(releaseFirst);
                } else if (call == 2) {
                    secondEntered.countDown();
                }
                return FlushRunResult.NO_OP;
            } finally {
                active.decrementAndGet();
            }
        });
        UnqualifiedScheduledTask plainTask = new UnqualifiedScheduledTask();

        try (SchedulingContext scheduling = openContext(worker, plainTask)) {
            try {
                assertTrue(await(firstEntered, 5_000));
                assertTrue(await(plainTask.firstInvocation, 5_000));
                assertFalse(await(secondEntered, 200));

                releaseFirst.countDown();
                assertTrue(await(secondEntered, 5_000));
                assertEquals(1, maxActive.get());
                assertFalse(plainTask.ranOnFlushScheduler.get());
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void rawErrorIsLoggedWithIdentityAndStopsTheRepeatingFlushTask() {
        RuntimeException suppressed = new RuntimeException("worker cleanup");
        AssertionError failure = new AssertionError("worker fatal");
        failure.addSuppressed(suppressed);
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch secondCall = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FlushWorker worker = mock(FlushWorker.class);
        when(worker.flushOnce()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstCall.countDown();
                throw failure;
            }
            secondCall.countDown();
            return FlushRunResult.NO_OP;
        });
        ErrorLogAppender appender = attachErrorAppender();

        try (SchedulingContext scheduling = openContext(worker, new UnqualifiedScheduledTask())) {
            assertTrue(await(firstCall, 5_000));
            assertTrue(await(appender.logged, 5_000));
            assertSame(failure, appender.failure.get());
            assertEquals(Level.ERROR, appender.level.get());
            assertEquals(1, failure.getSuppressed().length);
            assertSame(suppressed, failure.getSuppressed()[0]);
            assertFalse(await(secondCall, 300));
            assertEquals(1, calls.get());
        } finally {
            detachErrorAppender(appender);
        }
    }

    @Test
    void workerPromotedErrorKeepsTransactionFailureAndStopsRepeatingTaskEndToEnd() {
        RuntimeException transactionFailure = new RuntimeException("commit unknown");
        AssertionError currentError = new AssertionError("pending current fatal");
        FlushPlan plan = initializedPlan();
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = mock(PendingAmbiguousFlushStore.class);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        DirtyTileTracker dirtyTileTracker = mock(DirtyTileTracker.class);
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan))
                .thenReturn(FlushTransactionResult.ambiguousCommit(transactionFailure));
        when(store.current())
                .thenReturn(Optional.empty())
                .thenThrow(currentError);
        FlushWorker realWorker = new FlushWorker(
                new FlushSingleFlightGuard(),
                readiness,
                capture,
                executor,
                store,
                reconciliation,
                dirtyTileTracker,
                mock(dev.cgt.pixelplace.flush.application.FlushWalRetention.class)
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        FlushWorker observedWorker = spy(realWorker);
        CountDownLatch firstCall = new CountDownLatch(1);
        CountDownLatch secondCall = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstCall.countDown();
            } else {
                secondCall.countDown();
            }
            return invocation.callRealMethod();
        }).when(observedWorker).flushOnce();
        ErrorLogAppender appender = attachErrorAppender();

        try (SchedulingContext scheduling = openContext(
                observedWorker,
                new UnqualifiedScheduledTask()
        )) {
            assertTrue(await(firstCall, 5_000));
            assertTrue(await(appender.logged, 5_000));
            assertSame(currentError, appender.failure.get());
            assertEquals(1, currentError.getSuppressed().length);
            assertSame(transactionFailure, currentError.getSuppressed()[0]);
            assertThrows(ServiceNotReadyException.class, readiness::requireNotFatal);
            assertFalse(await(secondCall, 300));
            assertEquals(1, calls.get());
            verify(store, times(2)).current();
            verify(store).installIfAbsent(any());
            verifyNoInteractions(reconciliation, dirtyTileTracker);
        } finally {
            detachErrorAppender(appender);
        }
    }

    @Test
    // 실제 scheduled invocation과 manual 호출이 같은 Worker single-flight를 공유하는지 검증
    void manualCallSkipsWithoutBoundaryAccessWhileScheduledTransactionIsRunning() {
        FlushPlan plan = initializedPlan();
        CountDownLatch firstTransactionEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondTransactionEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondTransaction = new CountDownLatch(1);
        AtomicInteger transactionCalls = new AtomicInteger();
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = mock(PendingAmbiguousFlushStore.class);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        DirtyTileTracker dirtyTileTracker = mock(DirtyTileTracker.class);
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        when(store.current()).thenReturn(Optional.empty());
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenAnswer(invocation -> {
            if (transactionCalls.incrementAndGet() == 1) {
                firstTransactionEntered.countDown();
                awaitOrFail(releaseFirstTransaction);
            } else if (transactionCalls.get() == 2) {
                secondTransactionEntered.countDown();
                awaitOrFail(releaseSecondTransaction);
            }
            return FlushTransactionResult.committed();
        });
        FlushWorker worker = new FlushWorker(
                new FlushSingleFlightGuard(),
                readiness,
                capture,
                executor,
                store,
                reconciliation,
                dirtyTileTracker,
                mock(dev.cgt.pixelplace.flush.application.FlushWalRetention.class)
        , dev.cgt.pixelplace.measurement.Measurements.disabled());

        try (SchedulingContext scheduling = openContext(worker, new UnqualifiedScheduledTask())) {
            try {
                assertTrue(await(firstTransactionEntered, 5_000));

                assertEquals(FlushRunResult.SKIPPED_ALREADY_RUNNING, worker.flushOnce());
                verify(store, times(1)).current();
                verify(capture, times(1)).capturePlan();
                verify(executor, times(1)).execute(plan);
                verifyNoInteractions(reconciliation, dirtyTileTracker);

                releaseFirstTransaction.countDown();
                assertTrue(await(secondTransactionEntered, 5_000));
                verify(store, times(2)).current();
                verify(capture, times(2)).capturePlan();
                verify(executor, times(2)).execute(plan);
                verify(store, never()).installIfAbsent(any());
            } finally {
                releaseFirstTransaction.countDown();
                releaseSecondTransaction.countDown();
            }
        }
    }

    private SchedulingContext openContext(
            FlushWorker worker,
            UnqualifiedScheduledTask plainTask
    ) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "flush-integration",
                Map.of("pixel-place.flush.fixed-delay", "20ms")
        ));
        context.registerBean(FlushWorker.class, () -> worker);
        context.registerBean(UnqualifiedScheduledTask.class, () -> plainTask);
        context.registerBean(
                "taskScheduler",
                ThreadPoolTaskScheduler.class,
                () -> {
                    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                    scheduler.setThreadNamePrefix("test-default-");
                    return scheduler;
                }
        );
        context.register(FlushSchedulingConfiguration.class, FlushScheduler.class);
        try {
            context.refresh();
            return new SchedulingContext(
                    context,
                    context.getBean("flushTaskScheduler", ThreadPoolTaskScheduler.class),
                    context.getBean("taskScheduler", ThreadPoolTaskScheduler.class)
            );
        } catch (RuntimeException | Error startupFailure) {
            context.close();
            throw startupFailure;
        }
    }

    private FlushPlan initializedPlan() {
        FlushPlan plan = mock(FlushPlan.class);
        when(plan.noOp()).thenReturn(false);
        when(plan.expectedLastFlushedEventSeq()).thenReturn(3L);
        when(plan.flushTargetEventSeq()).thenReturn(4L);
        when(plan.bootstrapState()).thenReturn(DbBootstrapState.INITIALIZED);
        when(plan.drainedDirtyTiles()).thenReturn(List.of());
        return plan;
    }

    private ErrorLogAppender attachErrorAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(FlushSchedulingErrorHandler.class);
        ErrorLogAppender appender = new ErrorLogAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachErrorAppender(ErrorLogAppender appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(FlushSchedulingErrorHandler.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private boolean await(CountDownLatch latch, long timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Latch wait was interrupted.", exception);
        }
    }

    private void awaitOrFail(CountDownLatch latch) {
        if (!await(latch, 5_000)) {
            throw new AssertionError("Latch wait timed out.");
        }
    }

    private static final class UnqualifiedScheduledTask {

        private final CountDownLatch firstInvocation = new CountDownLatch(1);
        private final AtomicReference<String> firstThread = new AtomicReference<>();
        private final AtomicBoolean ranOnFlushScheduler = new AtomicBoolean();

        @Scheduled(fixedDelay = 10, initialDelay = 0)
        void run() {
            String threadName = Thread.currentThread().getName();
            firstThread.compareAndSet(null, threadName);
            if (threadName.startsWith("pixel-place-flush-")) {
                ranOnFlushScheduler.set(true);
            }
            firstInvocation.countDown();
        }
    }

    private static final class ErrorLogAppender extends AppenderBase<ILoggingEvent> {

        private final CountDownLatch logged = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Level> level = new AtomicReference<>();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLevel() != Level.ERROR
                    || !(event.getThrowableProxy() instanceof ThrowableProxy throwableProxy)) {
                return;
            }
            if (failure.compareAndSet(null, throwableProxy.getThrowable())) {
                level.set(event.getLevel());
                logged.countDown();
            }
        }
    }

    private record SchedulingContext(
            AnnotationConfigApplicationContext context,
            ThreadPoolTaskScheduler flushScheduler,
            ThreadPoolTaskScheduler defaultScheduler
    ) implements AutoCloseable {

        @Override
        public void close() {
            context.close();
            assertTrue(flushScheduler.getScheduledThreadPoolExecutor().isShutdown());
            assertTrue(defaultScheduler.getScheduledThreadPoolExecutor().isShutdown());
        }
    }
}
