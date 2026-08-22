package dev.cgt.pixelplace.flush.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import dev.cgt.pixelplace.flush.application.AmbiguousFlushCommitException;
import dev.cgt.pixelplace.flush.application.FlushFailClosedException;
import dev.cgt.pixelplace.flush.application.FlushPersistenceRolledBackException;
import dev.cgt.pixelplace.flush.application.FlushRunResult;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.flush.application.UnresolvedFlushCommitException;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FlushSchedulerTest {

    @Test
    void everySuccessfulWorkerResultCallsWorkerExactlyOnceAndReturnsNormally() {
        for (FlushRunResult result : FlushRunResult.values()) {
            FlushWorker worker = mock(FlushWorker.class);
            when(worker.flushOnce()).thenReturn(result);
            FlushScheduler scheduler = new FlushScheduler(worker);

            assertDoesNotThrow(scheduler::runScheduledFlush);

            verify(worker, times(1)).flushOnce();
            verifyNoMoreInteractions(worker);
        }
    }

    @Test
    void successfulResultsUseTheirDistinctOperationalLogLevels() {
        assertResultLogLevel(FlushRunResult.SKIPPED_ALREADY_RUNNING, Level.DEBUG);
        assertResultLogLevel(FlushRunResult.NO_OP, Level.DEBUG);
        assertResultLogLevel(FlushRunResult.COMMITTED, Level.INFO);
        assertResultLogLevel(FlushRunResult.RECONCILED_COMMIT, Level.INFO);
        assertResultLogLevel(FlushRunResult.RECONCILED_ROLLBACK, Level.WARN);
    }

    @Test
    void knownAndUnexpectedRuntimeFailuresStayInsideSchedulerBoundaryWithoutRetry() {
        List<RuntimeException> failures = List.of(
                new ServiceNotReadyException(),
                new FlushPersistenceRolledBackException("rollback", new RuntimeException("db")),
                new AmbiguousFlushCommitException("ambiguous", new RuntimeException("db")),
                new UnresolvedFlushCommitException("unresolved"),
                new FlushFailClosedException("fatal", new RuntimeException("db")),
                new IllegalStateException("unexpected")
        );

        for (RuntimeException failure : failures) {
            FlushWorker worker = mock(FlushWorker.class);
            when(worker.flushOnce()).thenThrow(failure);
            FlushScheduler scheduler = new FlushScheduler(worker);

            assertDoesNotThrow(scheduler::runScheduledFlush);

            verify(worker, times(1)).flushOnce();
            verifyNoMoreInteractions(worker);
        }
    }

    @Test
    void runtimeFailuresAreLoggedWithOriginalThrowableIdentityAtTheirBoundaryLevel() {
        assertFailureLogIdentity(new ServiceNotReadyException(), Level.DEBUG);
        assertFailureLogIdentity(
                new FlushPersistenceRolledBackException("rollback", new RuntimeException("db")),
                Level.ERROR
        );
        assertFailureLogIdentity(
                new AmbiguousFlushCommitException("ambiguous", new RuntimeException("db")),
                Level.ERROR
        );
        assertFailureLogIdentity(new UnresolvedFlushCommitException("unresolved"), Level.ERROR);
        assertFailureLogIdentity(
                new FlushFailClosedException("fatal", new RuntimeException("db")),
                Level.ERROR
        );
        assertFailureLogIdentity(new IllegalStateException("unexpected"), Level.ERROR);
    }

    @Test
    void runtimeFailureDoesNotPreventASecondInvocation() {
        FlushWorker worker = mock(FlushWorker.class);
        when(worker.flushOnce())
                .thenThrow(new IllegalStateException("first failed"))
                .thenReturn(FlushRunResult.NO_OP);
        FlushScheduler scheduler = new FlushScheduler(worker);

        assertDoesNotThrow(scheduler::runScheduledFlush);
        assertDoesNotThrow(scheduler::runScheduledFlush);

        verify(worker, times(2)).flushOnce();
    }

    @Test
    void rawErrorIsRethrownAsTheSameInstanceWithoutASecondWorkerCall() {
        FlushWorker worker = mock(FlushWorker.class);
        AssertionError failure = new AssertionError("fatal");
        when(worker.flushOnce()).thenThrow(failure);
        FlushScheduler scheduler = new FlushScheduler(worker);

        AssertionError actual = assertThrows(AssertionError.class, scheduler::runScheduledFlush);

        assertSame(failure, actual);
        verify(worker, times(1)).flushOnce();
        verifyNoMoreInteractions(worker);
    }

    @Test
    void schedulerOwnsOnlyTheWorkerDependency() {
        List<String> instanceFieldTypes = Arrays.stream(FlushScheduler.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .toList();

        assertEquals(List.of(FlushWorker.class.getName()), instanceFieldTypes);
    }

    private void assertResultLogLevel(FlushRunResult result, Level expectedLevel) {
        FlushWorker worker = mock(FlushWorker.class);
        when(worker.flushOnce()).thenReturn(result);
        CapturingAppender appender = attachAppender();

        try {
            new FlushScheduler(worker).runScheduledFlush();

            assertEquals(expectedLevel, appender.level.get());
            assertNull(appender.failure.get());
        } finally {
            detachAppender(appender);
        }
    }

    private void assertFailureLogIdentity(RuntimeException failure, Level expectedLevel) {
        FlushWorker worker = mock(FlushWorker.class);
        when(worker.flushOnce()).thenThrow(failure);
        CapturingAppender appender = attachAppender();

        try {
            assertDoesNotThrow(new FlushScheduler(worker)::runScheduledFlush);

            assertEquals(expectedLevel, appender.level.get());
            assertSame(failure, appender.failure.get());
        } finally {
            detachAppender(appender);
        }
    }

    private CapturingAppender attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(FlushScheduler.class);
        CapturingAppender appender = new CapturingAppender(logger.getLevel());
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(CapturingAppender appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(FlushScheduler.class);
        logger.detachAppender(appender);
        logger.setLevel(appender.originalLevel);
        appender.stop();
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final Level originalLevel;
        private final AtomicReference<Level> level = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private CapturingAppender(Level originalLevel) {
            this.originalLevel = originalLevel;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (!level.compareAndSet(null, event.getLevel())) {
                return;
            }
            if (event.getThrowableProxy() instanceof ThrowableProxy throwableProxy) {
                failure.set(throwableProxy.getThrowable());
            }
        }
    }
}
