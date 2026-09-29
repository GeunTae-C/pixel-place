package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실패한 관측·증거 저장 뒤에도 native child 종료 소유권을 포기하지 않는 helper 계약 */
class WalTestJvmLifecycleTest {
    @TempDir Path directory;

    @Test void normalExitWithinObservationNeedsNoIncompleteEvidence() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
        WalTestJvm.awaitNormalExit(process, directory, 10);
        assertFalse(Files.exists(directory.resolve("close-incomplete.txt")));
        verify(process, never()).waitFor();
        neverTerminate(process);
    }

    @Test void timeoutIsRecordedBeforeNormalWaitAndLateExitZeroStillFails() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor()).thenAnswer(call -> {
            assertTrue(Files.readString(directory.resolve("close-incomplete.txt")).contains("normalExitPending=true"));
            return 0;
        });
        assertThrows(AssertionError.class, () -> WalTestJvm.awaitNormalExit(process, directory, 10));
        assertTrue(Files.readString(directory.resolve("close-exit.txt")).contains("exit=0"));
        verify(process).waitFor();
        neverTerminate(process);
    }

    @Test void evidenceSaveFailureCannotAbandonNormalExitWait() throws Exception {
        Process process = mock(Process.class);
        Path unavailable = Files.writeString(directory.resolve("not-a-directory"), "fixture");
        var failure = assertThrows(AssertionError.class, () -> WalTestJvm.awaitNormalExit(process, unavailable, 10));
        assertEquals(2, failure.getSuppressed().length);
        assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
        verify(process).waitFor();
        neverTerminate(process);
    }

    @Test void interruptedObservationWaitsForExitThenRestoresInterruptAndOriginalFailure() throws Exception {
        Process process = mock(Process.class);
        var interrupted = new InterruptedException("observation");
        when(process.waitFor(10, TimeUnit.SECONDS)).thenThrow(interrupted);
        try {
            assertSame(interrupted, assertThrows(InterruptedException.class, () -> WalTestJvm.awaitNormalExit(process, directory, 10)));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(process).waitFor();
            neverTerminate(process);
        } finally { Thread.interrupted(); }
    }

    @Test void interruptDuringLateExitWaitCannotReplaceTimeoutOrReleaseOwnership() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor()).thenThrow(new InterruptedException("late wait")).thenReturn(0);
        try {
            var failure = assertThrows(AssertionError.class, () -> WalTestJvm.awaitNormalExit(process, directory, 10));
            assertInstanceOf(InterruptedException.class, failure.getSuppressed()[0]);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(process, times(2)).waitFor();
            neverTerminate(process);
        } finally { Thread.interrupted(); }
    }

    @Test void identitySaveExceptionClosesActualChildBeforeConstructorFailureEscapes() {
        assertFailedIdentityClosesChild(new IOException("identity save"));
    }

    @Test void identitySaveErrorClosesActualChildAndPreservesErrorIdentity() {
        assertFailedIdentityClosesChild(new LinkageError("identity save"));
    }

    private void assertFailedIdentityClosesChild(Throwable problem) {
        var child = new AtomicReference<Process>();
        Throwable observed = assertThrows(problem.getClass(), () -> new WalTestJvm((evidence, process) -> {
            child.set(process);
            if (problem instanceof Error error) throw error;
            throw (Exception) problem;
        }, CloseChild.class));
        assertSame(problem, observed);
        assertNotNull(child.get());
        assertFalse(child.get().isAlive());
        assertEquals(0, child.get().exitValue());
    }

    private static void neverTerminate(Process process) {
        verify(process, never()).destroy();
        verify(process, never()).destroyForcibly();
    }

    /** 실제 process 생성 뒤 실패하는 생성자의 정상 close IPC만 소비하는 작은 fixture */
    public static final class CloseChild {
        public static void main(String[] arguments) throws Exception {
            if (!"close".equals(new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine()))
                throw new IllegalStateException("Expected normal close command");
        }
    }
}
