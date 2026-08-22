package dev.cgt.pixelplace.flush.scheduling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlushSchedulingErrorHandlerTest {

    @Test
    void errorIsLoggedWithStackTraceAndRethrownWithoutChangingExistingSuppressedOrder() {
        RuntimeException first = new RuntimeException("first");
        AssertionError second = new AssertionError("second");
        AssertionError failure = new AssertionError("fatal");
        failure.addSuppressed(first);
        failure.addSuppressed(second);
        StackTraceElement[] stackTrace = failure.getStackTrace().clone();
        AtomicReference<Throwable> logged = new AtomicReference<>();
        FlushSchedulingErrorHandler handler = new FlushSchedulingErrorHandler(logged::set);

        AssertionError actual = assertThrows(AssertionError.class, () -> handler.handleError(failure));

        assertSame(failure, logged.get());
        assertSame(failure, actual);
        assertArrayEquals(stackTrace, actual.getStackTrace());
        assertSuppressedIdentity(actual, first, second);
    }

    @Test
    void runtimeAndErrorLoggingFailuresAreAddedOnceAfterExistingSuppressedAndOriginalErrorWins() {
        assertLoggingFailureIsAppended(new IllegalStateException("logger runtime"));
        assertLoggingFailureIsAppended(new AssertionError("logger error"));
    }

    @Test
    void selfAlreadySuppressedAndSuppressionDisabledLoggingFailuresKeepOriginalIdentity() {
        AssertionError selfFailure = new AssertionError("self");
        FlushSchedulingErrorHandler selfHandler = new FlushSchedulingErrorHandler(failure -> {
            throw (Error) failure;
        });
        assertSame(
                selfFailure,
                assertThrows(AssertionError.class, () -> selfHandler.handleError(selfFailure))
        );
        assertEquals(0, selfFailure.getSuppressed().length);

        RuntimeException alreadySuppressed = new RuntimeException("logger");
        AssertionError duplicateFailure = new AssertionError("duplicate");
        duplicateFailure.addSuppressed(alreadySuppressed);
        AtomicInteger duplicateLogCalls = new AtomicInteger();
        FlushSchedulingErrorHandler duplicateHandler = new FlushSchedulingErrorHandler(failure -> {
            duplicateLogCalls.incrementAndGet();
            throw alreadySuppressed;
        });
        assertSame(
                duplicateFailure,
                assertThrows(AssertionError.class, () -> duplicateHandler.handleError(duplicateFailure))
        );
        assertEquals(1, duplicateLogCalls.get());
        assertSuppressedIdentity(duplicateFailure, alreadySuppressed);

        SuppressionDisabledError disabled = new SuppressionDisabledError();
        RuntimeException loggingFailure = new RuntimeException("logger");
        FlushSchedulingErrorHandler disabledHandler = new FlushSchedulingErrorHandler(failure -> {
            throw loggingFailure;
        });
        assertSame(
                disabled,
                assertThrows(SuppressionDisabledError.class, () -> disabledHandler.handleError(disabled))
        );
        assertEquals(0, disabled.getSuppressed().length);
    }

    @Test
    void suppressionDuplicateCheckUsesReferenceIdentityInsteadOfEquals() {
        EqualRuntimeException existing = new EqualRuntimeException("existing");
        EqualRuntimeException distinctButEqual = new EqualRuntimeException("new");
        AssertionError failure = new AssertionError("fatal");
        failure.addSuppressed(existing);
        FlushSchedulingErrorHandler handler = new FlushSchedulingErrorHandler(ignored -> {
            throw distinctButEqual;
        });

        assertSame(failure, assertThrows(AssertionError.class, () -> handler.handleError(failure)));

        assertSuppressedIdentity(failure, existing, distinctButEqual);
    }

    @Test
    void nonErrorIsLoggedAndReturnsNormallyEvenWhenLoggingThrowsRuntimeException() {
        AtomicReference<Throwable> logged = new AtomicReference<>();
        RuntimeException failure = new RuntimeException("task runtime");
        FlushSchedulingErrorHandler successHandler = new FlushSchedulingErrorHandler(logged::set);

        assertDoesNotThrow(() -> successHandler.handleError(failure));
        assertSame(failure, logged.get());

        AtomicInteger logCalls = new AtomicInteger();
        FlushSchedulingErrorHandler failingHandler = new FlushSchedulingErrorHandler(ignored -> {
            logCalls.incrementAndGet();
            throw new IllegalStateException("logger runtime");
        });
        assertDoesNotThrow(() -> failingHandler.handleError(failure));
        assertEquals(1, logCalls.get());
    }

    @Test
    void nonErrorLoggingErrorIsRethrownAsTheSameInstance() {
        RuntimeException taskFailure = new RuntimeException("task runtime");
        AssertionError loggingError = new AssertionError("logger fatal");
        FlushSchedulingErrorHandler handler = new FlushSchedulingErrorHandler(ignored -> {
            throw loggingError;
        });

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> handler.handleError(taskFailure)
        );

        assertSame(loggingError, actual);
    }

    private void assertLoggingFailureIsAppended(Throwable loggingFailure) {
        RuntimeException first = new RuntimeException("first");
        AssertionError second = new AssertionError("second");
        AssertionError failure = new AssertionError("fatal");
        failure.addSuppressed(first);
        failure.addSuppressed(second);
        AtomicInteger logCalls = new AtomicInteger();
        FlushSchedulingErrorHandler handler = new FlushSchedulingErrorHandler(ignored -> {
            logCalls.incrementAndGet();
            if (loggingFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) loggingFailure;
        });

        Error actual = assertThrows(Error.class, () -> handler.handleError(failure));

        assertSame(failure, actual);
        assertEquals(1, logCalls.get());
        assertSuppressedIdentity(actual, first, second, loggingFailure);
    }

    private void assertSuppressedIdentity(Throwable actual, Throwable... expected) {
        Throwable[] suppressed = actual.getSuppressed();
        assertEquals(expected.length, suppressed.length);
        for (int index = 0; index < expected.length; index++) {
            assertSame(expected[index], suppressed[index]);
        }
    }

    private static final class SuppressionDisabledError extends Error {

        private SuppressionDisabledError() {
            super("suppression disabled", null, false, true);
        }
    }

    private static final class EqualRuntimeException extends RuntimeException {

        private EqualRuntimeException(String message) {
            super(message);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualRuntimeException;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
