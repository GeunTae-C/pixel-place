package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.infra.ProgrammaticFlushTransactionExecutor;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.SynchronizedDirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.WalRetentionResult;
import dev.cgt.pixelplace.wal.application.WalSegmentRetention;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 executor와 worker 분기로 확정 outcome·dirty/pending 소유권 및 정리 허가 경계 검증 */
class FlushWalRetentionTest {
    private static final TileKey KEY = new TileKey(0, 0, 0);

    @Test
    void actualExecutorCommitReturnsBeforeRetentionAndDelayedNoOpRetriesSameAuthority() {
        var f = new Fixture();
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(inv -> { committed.set(true); return null; }).when(f.manager).commit(f.status);
        when(f.port.deleteCommittedPrefix(10)).thenAnswer(inv -> {
            assertTrue(committed.get());
            assertTrue(f.dirty.drainDirtyTiles().isEmpty());
            return WalRetentionResult.delayed(0, 0, WalRetentionResult.DelayKind.IO);
        }).thenReturn(WalRetentionResult.completed(1));
        assertEquals(FlushRunResult.COMMITTED, f.worker.flushOnce());
        when(f.capture.capturePlan()).thenReturn(FlushPlan.noOp(77, DbBootstrapState.INITIALIZED));
        // no-op expected 값을 새 삭제 허가로 쓰지 않는 worker 경계
        assertEquals(FlushRunResult.NO_OP, f.worker.flushOnce());
        var order = inOrder(f.manager, f.persistence, f.port);
        order.verify(f.manager).getTransaction(any());
        order.verify(f.persistence).persist(f.plan);
        order.verify(f.manager).commit(f.status);
        order.verify(f.port, times(2)).deleteCommittedPrefix(10);
        verify(f.port, never()).deleteCommittedPrefix(77);
        f.assertCommittedOwnership();
    }

    @Test
    void freshProcessNoOpNeverCreatesAuthorityOrTouchesRetentionDependencies() {
        var f = new Fixture();
        when(f.capture.capturePlan()).thenReturn(FlushPlan.noOp(999, DbBootstrapState.INITIALIZED));
        assertEquals(FlushRunResult.NO_OP, f.worker.flushOnce());
        verifyNoInteractions(f.port, f.manager, f.persistence, f.coordinator);
        verify(f.store, times(1)).current();
    }

    @Test
    void exactCommitReconciliationClearsPendingBeforeRetentionAndDoesNotRestoreDirty() {
        var f = new Fixture();
        var pending = PendingAmbiguousFlush.from(f.plan);
        f.store.installIfAbsent(pending);
        clearInvocations(f.store);
        when(f.probe.probe()).thenReturn(new FlushDbState(10, DbBootstrapState.INITIALIZED));
        assertEquals(FlushRunResult.RECONCILED_COMMIT, f.worker.flushOnce());
        var order = inOrder(f.probe, f.store, f.port);
        order.verify(f.store).current();
        order.verify(f.probe).probe();
        order.verify(f.store).clearIfSame(pending);
        order.verify(f.store).current();
        order.verify(f.port).deleteCommittedPrefix(10);
        verifyNoInteractions(f.manager, f.persistence, f.capture);
        f.assertCommittedOwnership();
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "unresolved", "rollback", "install-unconfirmed", "ambiguous", "definite", "skip"})
    void nonCommitAndUnconfirmedOwnershipPathsNeverCallRetention(String path) {
        var f = new Fixture();
        var pending = PendingAmbiguousFlush.from(f.plan);
        if (List.of("clear", "unresolved", "rollback").contains(path)) {
            f.store.installIfAbsent(pending);
            long observed = path.equals("rollback") ? 3 : path.equals("unresolved") ? 7 : 10;
            when(f.probe.probe()).thenReturn(new FlushDbState(observed, DbBootstrapState.INITIALIZED));
            if (path.equals("clear")) doThrow(new IllegalStateException("clear failed")).when(f.store).clearIfSame(pending);
        }
        if (path.equals("ambiguous") || path.equals("install-unconfirmed")) {
            doThrow(new IllegalStateException("commit unknown")).when(f.manager).commit(f.status);
            if (path.equals("install-unconfirmed")) doNothing().when(f.store).installIfAbsent(any());
        }
        if (path.equals("definite")) doThrow(new IllegalStateException("body failure")).when(f.persistence).persist(f.plan);
        if (path.equals("skip")) f.guard.tryRun(() -> assertEquals(FlushRunResult.SKIPPED_ALREADY_RUNNING, f.worker.flushOnce()));
        else if (path.equals("rollback")) assertEquals(FlushRunResult.RECONCILED_ROLLBACK, f.worker.flushOnce());
        else assertThrows(RuntimeException.class, f.worker::flushOnce);
        verifyNoInteractions(f.port, f.retention);
        if (path.equals("rollback") || path.equals("definite")) {
            assertEquals(f.plan.drainedDirtyTiles(), f.dirty.drainDirtyTiles());
            verify(f.dirty).restoreDirtyTiles(f.plan.drainedDirtyTiles());
        } else {
            verify(f.dirty, never()).restoreDirtyTiles(any());
        }
        if (path.equals("ambiguous") || path.equals("unresolved") || path.equals("clear")) assertTrue(f.store.current().isPresent());
        if (path.equals("install-unconfirmed")) assertThrows(RuntimeException.class, f.ready::requireNotFatal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-ready", "fatal-after-capture", "pending"})
    void commitStaysConfirmedWhenEligibilityIsLostAndNoFilesAreAccessed(String path) {
        var f = new Fixture();
        doAnswer(inv -> {
            if (path.equals("pending")) f.store.installIfAbsent(PendingAmbiguousFlush.from(f.plan));
            else if (path.equals("not-ready")) f.ready.markNotReady();
            else f.ready.markFatalNotReady();
            return null;
        }).when(f.manager).commit(f.status);
        assertEquals(FlushRunResult.COMMITTED, f.worker.flushOnce());
        verifyNoInteractions(f.port);
        verify(f.dirty, never()).restoreDirtyTiles(any());
        verify(f.manager, never()).rollback(any());
        if (!path.equals("fatal-after-capture")) {
            if (path.equals("pending")) f.store.clearIfSame(f.store.current().orElseThrow());
            else f.ready.markReady();
            f.retention.retryIfEligible();
            verify(f.port).deleteCommittedPrefix(10);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending-exception", "pending-null", "storage-runtime", "storage-error", "result-null"})
    void cleanupFailureAfterRealCommitIsFatalWithoutRollbackAmbiguousOrDirtyRestore(String point) {
        var f = new Fixture();
        Throwable failure = point.equals("storage-error") ? new AssertionError("storage") : new IllegalStateException("retention");
        doAnswer(inv -> {
            if (point.equals("pending-exception")) doThrow(failure).when(f.store).current();
            if (point.equals("pending-null")) doReturn(null).when(f.store).current();
            return null;
        }).when(f.manager).commit(f.status);
        if (point.startsWith("storage")) doThrow(failure).when(f.port).deleteCommittedPrefix(10);
        if (point.equals("result-null")) doReturn(null).when(f.port).deleteCommittedPrefix(10);
        Throwable actual = assertThrows(Throwable.class, f.worker::flushOnce);
        if (!point.endsWith("null")) assertSame(failure, actual);
        assertThrows(RuntimeException.class, f.ready::requireNotFatal);
        verify(f.manager).commit(f.status);
        verify(f.manager, never()).rollback(any());
        verify(f.dirty, never()).restoreDirtyTiles(any());
        verify(f.store, never()).installIfAbsent(any());
        assertTrue(f.guard.tryRun(() -> { }));
        clearInvocations(f.port, f.store);
        assertThrows(RuntimeException.class, f.worker::flushOnce);
        verifyNoInteractions(f.port, f.store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "error"})
    void delayedWarningFailuresKeepCommitAndNeverRestoreDirty(String kind) {
        var f = new Fixture();
        Throwable failure = kind.equals("error") ? new AssertionError("logger") : new IllegalStateException("logger");
        when(f.port.deleteCommittedPrefix(10)).thenReturn(WalRetentionResult.delayed(0, 0, WalRetentionResult.DelayKind.IO));
        doThrow(failure).when(f.retention).warnDelayed(any());
        if (kind.equals("error")) assertSame(failure, assertThrows(AssertionError.class, f.worker::flushOnce));
        else assertEquals(FlushRunResult.COMMITTED, f.worker.flushOnce());
        assertTrue(f.ready.isReady());
        f.assertCommittedOwnership();
        assertTrue(f.guard.tryRun(() -> { }));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 9})
    void invalidOrDecreasingAuthorityIsFatalAndDoesNotIssueMoreDeletes(long invalid) {
        var f = new Fixture();
        f.retention.onCommitConfirmed(10);
        clearInvocations(f.port);
        assertThrows(IllegalArgumentException.class, () -> f.retention.onCommitConfirmed(invalid));
        assertThrows(RuntimeException.class, f.ready::requireNotFatal);
        f.retention.retryIfEligible();
        verifyNoInteractions(f.port);
    }

    @Test
    void sameAndIncreasingConfirmationsAreAllowedButNewInstanceHasNoAuthority() {
        var f = new Fixture();
        f.retention.onCommitConfirmed(10); f.retention.onCommitConfirmed(10); f.retention.onCommitConfirmed(20);
        verify(f.port, times(2)).deleteCommittedPrefix(10);
        verify(f.port).deleteCommittedPrefix(20);
        clearInvocations(f.port);
        new FlushWalRetention(f.coordinator, f.ready, f.store, f.port, dev.cgt.pixelplace.measurement.Measurements.disabled()).retryIfEligible();
        verifyNoInteractions(f.port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime/error", "error/runtime", "error/error", "error/same"})
    void fatalMarkFailurePreservesErrorPriorityAndDistinctSuppressedIdentity(String kinds) {
        var f = new Fixture();
        Throwable first = kinds.startsWith("runtime") ? new IllegalStateException("storage") : new AssertionError("storage");
        Throwable later = kinds.endsWith("same") ? first : kinds.endsWith("runtime") ? new IllegalStateException("mark") : new AssertionError("mark");
        doThrow(first).when(f.port).deleteCommittedPrefix(10);
        doAnswer(inv -> { inv.callRealMethod(); throw later; }).when(f.ready).markFatalNotReady();
        Throwable actual = assertThrows(Throwable.class, f.worker::flushOnce);
        Throwable primary = first instanceof Error ? first : later;
        assertSame(primary, actual);
        assertArrayEquals(first == later ? new Throwable[0] : new Throwable[]{primary == first ? later : first}, actual.getSuppressed());
        f.assertCommittedOwnership();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retentionFailureAfterExactClearKeepsPendingAndDirtyOwnershipSettled(boolean error) {
        var f = new Fixture();
        var pending = PendingAmbiguousFlush.from(f.plan);
        f.store.installIfAbsent(pending);
        clearInvocations(f.store);
        when(f.probe.probe()).thenReturn(new FlushDbState(10, DbBootstrapState.INITIALIZED));
        Throwable failure = error ? new AssertionError("cleanup") : new IllegalStateException("cleanup");
        doThrow(failure).when(f.port).deleteCommittedPrefix(10);
        assertSame(failure, assertThrows(Throwable.class, f.worker::flushOnce));
        verify(f.store).clearIfSame(pending);
        f.assertCommittedOwnership();
        assertThrows(RuntimeException.class, f.ready::requireNotFatal);
        verifyNoInteractions(f.manager, f.persistence, f.capture);
    }

    /** 실제 dirty drain·executor를 쓰되 DB syscall과 retention port만 실패 대역으로 제한 */
    private static class Fixture {
        final ServiceReadiness ready = spy(new ServiceReadiness());
        final FlushBoundaryCoordinator coordinator = spy(new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()));
        final PendingAmbiguousFlushStore store = spy(new PendingAmbiguousFlushStore());
        final WalSegmentRetention port = mock(WalSegmentRetention.class);
        final SynchronizedDirtyTileTracker dirty = spy(new SynchronizedDirtyTileTracker());
        final FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = mock(TransactionStatus.class);
        final FlushPersistenceService persistence = mock(FlushPersistenceService.class);
        final FlushDbStateProbe probe = mock(FlushDbStateProbe.class);
        final FlushSingleFlightGuard guard = new FlushSingleFlightGuard();
        final FlushWalRetention retention = spy(new FlushWalRetention(coordinator, ready, store, port, dev.cgt.pixelplace.measurement.Measurements.disabled()));
        final FlushPlan plan;
        final FlushWorker worker;

        Fixture() {
            ready.markReady(); dirty.markDirty(KEY, 10, 2);
            plan = FlushPlan.nonNoOp(3, 10,
                    List.of(new WalRecord(10, 7, 0, 0, 0, 0, 0, 17, LocalDateTime.of(2026, 9, 14, 0, 0))),
                    List.of(new FlushTileSnapshot(KEY, new byte[BoardConstants.TILE_PIXEL_COUNT], 2)),
                    dirty.drainDirtyTiles(), DbBootstrapState.INITIALIZED);
            when(capture.capturePlan()).thenReturn(plan);
            when(manager.getTransaction(any())).thenReturn(status);
            when(port.deleteCommittedPrefix(anyLong())).thenReturn(WalRetentionResult.completed(0));
            worker = new FlushWorker(guard, ready, capture, new ProgrammaticFlushTransactionExecutor(manager, persistence, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                    store, new FlushReconciliationService(probe), dirty, retention, dev.cgt.pixelplace.measurement.Measurements.disabled());
        }

        void assertCommittedOwnership() {
            verify(manager, never()).rollback(any());
            verify(store, never()).installIfAbsent(any());
            verify(dirty, never()).restoreDirtyTiles(any());
            assertTrue(dirty.drainDirtyTiles().isEmpty());
            assertTrue(store.current().isEmpty());
        }
    }
}
