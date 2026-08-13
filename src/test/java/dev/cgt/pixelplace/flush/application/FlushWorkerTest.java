package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.application.SynchronizedDirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlushWorkerTest {

    private static final TileKey KEY_A = new TileKey(0, 0, 0);
    private static final TileKey KEY_B = new TileKey(0, 1, 0);
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 4, 3, 6, 0);

    @Test
    void concurrentSecondCallSkipsBeforePendingCaptureAndTransaction() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch transactionEntered = new CountDownLatch(1);
        CountDownLatch releaseTransaction = new CountDownLatch(1);
        when(fixture.transactionExecutor.execute(fixture.plan)).thenAnswer(invocation -> {
            transactionEntered.countDown();
            await(releaseTransaction);
            return FlushTransactionResult.committed();
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<FlushRunResult> first = executor.submit(fixture.worker::flushOnce);
            assertTrue(transactionEntered.await(5, TimeUnit.SECONDS));

            Future<FlushRunResult> second = executor.submit(fixture.worker::flushOnce);
            assertEquals(FlushRunResult.SKIPPED_ALREADY_RUNNING, second.get(5, TimeUnit.SECONDS));
            verify(fixture.store, times(1)).current();
            verify(fixture.captureService, times(1)).capturePlan();
            verify(fixture.transactionExecutor, times(1)).execute(fixture.plan);
            verifyNoInteractions(fixture.dirtyTileTracker, fixture.reconciliationService);

            releaseTransaction.countDown();
            assertEquals(FlushRunResult.COMMITTED, first.get(5, TimeUnit.SECONDS));
        } finally {
            releaseTransaction.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sameThreadNestedFlushSkipsWithoutPendingOrCaptureWork() {
        Fixture fixture = new Fixture();
        AtomicReference<FlushRunResult> nested = new AtomicReference<>();
        when(fixture.transactionExecutor.execute(fixture.plan)).thenAnswer(invocation -> {
            nested.set(fixture.worker.flushOnce());
            return FlushTransactionResult.committed();
        });

        assertEquals(FlushRunResult.COMMITTED, fixture.worker.flushOnce());
        assertEquals(FlushRunResult.SKIPPED_ALREADY_RUNNING, nested.get());
        verify(fixture.store, times(1)).current();
        verify(fixture.captureService, times(1)).capturePlan();
    }

    @Test
    void noOpReturnsWithoutTransactionPendingOrDirtyChanges() {
        Fixture fixture = new Fixture();
        when(fixture.captureService.capturePlan()).thenReturn(noOp());

        assertEquals(FlushRunResult.NO_OP, fixture.worker.flushOnce());

        verifyNoInteractions(fixture.transactionExecutor, fixture.dirtyTileTracker);
        verify(fixture.store, never()).installIfAbsent(any());
    }

    @Test
    void committedSuccessReleasesGuardForNextFlushOnce() {
        Fixture fixture = new Fixture();
        when(fixture.captureService.capturePlan()).thenReturn(fixture.plan, noOp());

        assertEquals(FlushRunResult.COMMITTED, fixture.worker.flushOnce());
        assertEquals(FlushRunResult.NO_OP, fixture.worker.flushOnce());

        verify(fixture.captureService, times(2)).capturePlan();
        verify(fixture.transactionExecutor, times(1)).execute(fixture.plan);
    }

    @Test
    void committedDiscardsCandidateAndKeepsNewLiveDirty() {
        SynchronizedDirtyTileTracker tracker = new SynchronizedDirtyTileTracker();
        FlushPlan plan = initializedPlan(List.of(new DirtyTile(KEY_A, 7L, 2L)));
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenAnswer(invocation -> {
            tracker.markDirty(KEY_A, 11L, 4L);
            return FlushTransactionResult.committed();
        });
        FlushWorker worker = worker(capture, executor, store, reconciliation, tracker);

        assertEquals(FlushRunResult.COMMITTED, worker.flushOnce());

        assertTrue(store.current().isEmpty());
        assertEquals(List.of(new DirtyTile(KEY_A, 11L, 4L)), tracker.drainDirtyTiles());
    }

    @Test
    void definiteRollbackRestoresOnlyActualDrainedDirtyNotWalAffectedOrBootstrapTargets() {
        DirtyTile dirtyB = new DirtyTile(KEY_B, 7L, 2L);
        Fixture fixture = new Fixture(initializedPlan(List.of(dirtyB)));
        RuntimeException failure = new RuntimeException("rolled back");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.definiteRollback(failure));

        FlushPersistenceRolledBackException actual = assertThrows(
                FlushPersistenceRolledBackException.class,
                fixture.worker::flushOnce
        );

        assertSame(failure, actual.getCause());
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(List.of(dirtyB));
        verify(fixture.dirtyTileTracker, never()).markDirty(any(), anyLong(), anyLong());
        verify(fixture.transactionExecutor, times(1)).execute(fixture.plan);

        reset(fixture.captureService, fixture.transactionExecutor, fixture.dirtyTileTracker);
        DirtyTile bootstrapDirty = new DirtyTile(KEY_B, 1L, 1L);
        FlushPlan bootstrapPlan = bootstrapPlan(List.of(bootstrapDirty));
        when(fixture.captureService.capturePlan()).thenReturn(bootstrapPlan);
        when(fixture.transactionExecutor.execute(bootstrapPlan))
                .thenReturn(FlushTransactionResult.definiteRollback(failure));
        assertThrows(FlushPersistenceRolledBackException.class, fixture.worker::flushOnce);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(List.of(bootstrapDirty));
    }

    @Test
    void definiteRollbackRestoreDoesNotReplaceNewerLiveDirty() {
        SynchronizedDirtyTileTracker tracker = new SynchronizedDirtyTileTracker();
        tracker.markDirty(KEY_A, 12L, 4L);
        DirtyTile stale = new DirtyTile(KEY_A, 7L, 99L);
        FlushPlan plan = initializedPlan(List.of(stale));
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenReturn(
                FlushTransactionResult.definiteRollback(new RuntimeException("rollback"))
        );
        FlushWorker worker = worker(
                capture,
                executor,
                new PendingAmbiguousFlushStore(),
                mock(FlushReconciliationService.class),
                tracker
        );

        assertThrows(FlushPersistenceRolledBackException.class, worker::flushOnce);

        assertEquals(List.of(new DirtyTile(KEY_A, 12L, 4L)), tracker.drainDirtyTiles());
    }

    @Test
    void definiteRollbackRestoreFailureIsSuppressedOnTransactionFailure() {
        Fixture fixture = new Fixture();
        RuntimeException transactionFailure = new RuntimeException("rollback");
        RuntimeException restoreFailure = new RuntimeException("restore");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.definiteRollback(transactionFailure));
        doThrow(restoreFailure).when(fixture.dirtyTileTracker)
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());

        FlushPersistenceRolledBackException actual = assertThrows(
                FlushPersistenceRolledBackException.class,
                fixture.worker::flushOnce
        );

        assertSame(transactionFailure, actual.getCause());
        assertEquals(1, transactionFailure.getSuppressed().length);
        assertSame(restoreFailure, transactionFailure.getSuppressed()[0]);
    }

    @Test
    void definiteRollbackRuntimeFailureAndRestoreErrorRethrowsSameRestoreErrorWithoutWrapper() {
        Fixture fixture = new Fixture();
        RuntimeException transactionFailure = new RuntimeException("rollback");
        AssertionError restoreError = new AssertionError("restore fatal");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.definiteRollback(transactionFailure));
        doThrow(restoreError).when(fixture.dirtyTileTracker)
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(restoreError, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(transactionFailure, actual.getSuppressed()[0]);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(fixture.plan.drainedDirtyTiles());
        verify(fixture.store, never()).installIfAbsent(any());
    }

    @Test
    void definiteRollbackErrorRestoresThenRethrowsSameErrorInstance() {
        Fixture fixture = new Fixture();
        AssertionError failure = new AssertionError("rollback fatal");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.definiteRollback(failure));

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(failure, actual);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(fixture.plan.drainedDirtyTiles());
    }

    @Test
    void definiteRollbackErrorKeepsRestoreFailureSuppressedAndSamePrimary() {
        Fixture fixture = new Fixture();
        AssertionError failure = new AssertionError("rollback fatal");
        IllegalStateException restoreFailure = new IllegalStateException("restore failed");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.definiteRollback(failure));
        doThrow(restoreFailure).when(fixture.dirtyTileTracker)
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(failure, actual);
        assertSame(restoreFailure, actual.getSuppressed()[0]);
    }

    @Test
    void sameDefiniteRollbackAndRestoreErrorIsNotSelfSuppressed() {
        Fixture fixture = new Fixture();
        AssertionError sameFailure = new AssertionError("same rollback and restore fatal");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.definiteRollback(sameFailure));
        doThrow(sameFailure).when(fixture.dirtyTileTracker)
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(sameFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        verify(fixture.transactionExecutor, times(1)).execute(fixture.plan);
        verify(fixture.dirtyTileTracker, times(1))
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());
        verify(fixture.store, never()).installIfAbsent(any());
    }

    @Test
    void ambiguousOutcomeInstallsExactCandidateWithoutImmediateDirtyRestore() {
        FlushPlan plan = initializedPlan(List.of(new DirtyTile(KEY_B, 7L, 2L)));
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        RuntimeException failure = new RuntimeException("commit unknown");
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenReturn(FlushTransactionResult.ambiguousCommit(failure));
        FlushWorker worker = worker(capture, executor, store, mock(FlushReconciliationService.class), tracker);

        AmbiguousFlushCommitException actual = assertThrows(
                AmbiguousFlushCommitException.class,
                worker::flushOnce
        );

        assertSame(failure, actual.getCause());
        PendingAmbiguousFlush pending = store.current().orElseThrow();
        assertEquals(plan.expectedLastFlushedEventSeq(), pending.expectedLastFlushedEventSeq());
        assertEquals(plan.flushTargetEventSeq(), pending.flushTargetEventSeq());
        assertEquals(plan.bootstrapState(), pending.bootstrapState());
        assertEquals(plan.drainedDirtyTiles(), pending.drainedDirtyTiles());
        verifyNoInteractions(tracker);
        verify(executor, times(1)).execute(plan);
    }

    @Test
    void unexpectedRuntimeFailureIsAmbiguousAndPreservesPending() {
        FlushPlan plan = initializedPlan(List.of());
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        RuntimeException failure = new RuntimeException("executor escaped");
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenThrow(failure);
        FlushWorker worker = worker(
                capture,
                executor,
                store,
                mock(FlushReconciliationService.class),
                mock(DirtyTileTracker.class)
        );

        AmbiguousFlushCommitException actual = assertThrows(
                AmbiguousFlushCommitException.class,
                worker::flushOnce
        );

        assertSame(failure, actual.getCause());
        assertTrue(store.current().isPresent());
    }

    @Test
    void ambiguousOutcomeErrorInstallsPendingThenRethrowsSameError() {
        FlushPlan plan = initializedPlan(List.of());
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        AssertionError failure = new AssertionError("commit fatal");
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenReturn(FlushTransactionResult.ambiguousCommit(failure));
        FlushWorker worker = worker(
                capture,
                executor,
                store,
                mock(FlushReconciliationService.class),
                mock(DirtyTileTracker.class)
        );

        AssertionError actual = assertThrows(AssertionError.class, worker::flushOnce);

        assertSame(failure, actual);
        assertTrue(store.current().isPresent());
    }

    @Test
    void ambiguousErrorKeepsPendingInstallFailureSuppressedAndSamePrimary() {
        Fixture fixture = new Fixture();
        AssertionError failure = new AssertionError("commit fatal");
        IllegalStateException installFailure = new IllegalStateException("pending install failed");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.ambiguousCommit(failure));
        doThrow(installFailure).when(fixture.store).installIfAbsent(any(PendingAmbiguousFlush.class));

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(failure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(installFailure, actual.getSuppressed()[0]);
        verifyNoInteractions(fixture.dirtyTileTracker);
    }

    @Test
    void sameAmbiguousCauseAndPendingInstallErrorIsNotSelfSuppressed() {
        Fixture fixture = new Fixture();
        AssertionError sameFailure = new AssertionError("same ambiguous and install fatal");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.ambiguousCommit(sameFailure));
        doThrow(sameFailure).when(fixture.store)
                .installIfAbsent(any(PendingAmbiguousFlush.class));

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(sameFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        verify(fixture.transactionExecutor, times(1)).execute(fixture.plan);
        verify(fixture.store, times(1)).installIfAbsent(any(PendingAmbiguousFlush.class));
        verifyNoInteractions(fixture.dirtyTileTracker);
    }

    @Test
    void ambiguousRuntimeFailureAndPendingInstallErrorRethrowsSameInstallErrorWithoutWrapper() {
        Fixture fixture = new Fixture();
        RuntimeException transactionFailure = new RuntimeException("commit unknown");
        AssertionError installError = new AssertionError("pending install fatal");
        when(fixture.transactionExecutor.execute(fixture.plan))
                .thenReturn(FlushTransactionResult.ambiguousCommit(transactionFailure));
        doThrow(installError).when(fixture.store).installIfAbsent(any(PendingAmbiguousFlush.class));

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(installError, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(transactionFailure, actual.getSuppressed()[0]);
        verify(fixture.store).installIfAbsent(any(PendingAmbiguousFlush.class));
        verifyNoInteractions(fixture.dirtyTileTracker);
    }

    @Test
    void unexpectedExecutorRuntimeFailureAndPendingInstallErrorRethrowsSameInstallError() {
        Fixture fixture = new Fixture();
        RuntimeException transactionFailure = new RuntimeException("executor escaped");
        AssertionError installError = new AssertionError("pending install fatal");
        when(fixture.transactionExecutor.execute(fixture.plan)).thenThrow(transactionFailure);
        doThrow(installError).when(fixture.store).installIfAbsent(any(PendingAmbiguousFlush.class));

        AssertionError actual = assertThrows(AssertionError.class, fixture.worker::flushOnce);

        assertSame(installError, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(transactionFailure, actual.getSuppressed()[0]);
        verify(fixture.store).installIfAbsent(any(PendingAmbiguousFlush.class));
        verifyNoInteractions(fixture.dirtyTileTracker);
    }

    @Test
    void rawExecutorErrorInstallsPendingThenRethrowsSameError() {
        FlushPlan plan = initializedPlan(List.of());
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        AssertionError failure = new AssertionError("executor fatal");
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenThrow(failure);
        FlushWorker worker = worker(
                capture,
                executor,
                store,
                mock(FlushReconciliationService.class),
                mock(DirtyTileTracker.class)
        );

        AssertionError actual = assertThrows(AssertionError.class, worker::flushOnce);

        assertSame(failure, actual);
        assertTrue(store.current().isPresent());
    }

    @Test
    void nullTransactionResultIsTreatedAsAmbiguousAndPreservesPending() {
        FlushPlan plan = initializedPlan(List.of());
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        when(capture.capturePlan()).thenReturn(plan);
        when(executor.execute(plan)).thenReturn(null);
        FlushWorker worker = worker(
                capture,
                executor,
                store,
                mock(FlushReconciliationService.class),
                mock(DirtyTileTracker.class)
        );

        assertThrows(AmbiguousFlushCommitException.class, worker::flushOnce);
        assertTrue(store.current().isPresent());
    }

    @Test
    void pendingCandidateRuntimeFailureAndDirtyRestoreErrorRethrowsSameRestoreError() {
        Fixture fixture = new Fixture();
        RuntimeException candidateFailure = new RuntimeException("candidate failed");
        AssertionError restoreError = new AssertionError("restore fatal");
        doThrow(restoreError).when(fixture.dirtyTileTracker)
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());
        FlushWorker worker = new FlushWorker(
                fixture.guard,
                fixture.captureService,
                fixture.transactionExecutor,
                fixture.store,
                fixture.reconciliationService,
                fixture.dirtyTileTracker
        ) {
            @Override
            PendingAmbiguousFlush createPendingCandidateFrom(FlushPlan plan) {
                throw candidateFailure;
            }
        };

        AssertionError actual = assertThrows(AssertionError.class, worker::flushOnce);

        assertSame(restoreError, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(candidateFailure, actual.getSuppressed()[0]);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(fixture.plan.drainedDirtyTiles());
        verify(fixture.transactionExecutor, never()).execute(any());
        verify(fixture.store, never()).installIfAbsent(any());
    }

    @Test
    void samePendingCandidateAndDirtyRestoreErrorIsNotSelfSuppressed() {
        Fixture fixture = new Fixture();
        AssertionError sameFailure = new AssertionError("same candidate and restore fatal");
        doThrow(sameFailure).when(fixture.dirtyTileTracker)
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());
        FlushWorker worker = new FlushWorker(
                fixture.guard,
                fixture.captureService,
                fixture.transactionExecutor,
                fixture.store,
                fixture.reconciliationService,
                fixture.dirtyTileTracker
        ) {
            @Override
            PendingAmbiguousFlush createPendingCandidateFrom(FlushPlan plan) {
                throw sameFailure;
            }
        };

        AssertionError actual = assertThrows(AssertionError.class, worker::flushOnce);

        assertSame(sameFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        verify(fixture.dirtyTileTracker, times(1))
                .restoreDirtyTiles(fixture.plan.drainedDirtyTiles());
        verify(fixture.transactionExecutor, never()).execute(any());
        verify(fixture.store, never()).installIfAbsent(any());
    }

    @Test
    void pendingCommitIsReconciledBeforeCaptureAndClearsWithoutDirtyRestore() {
        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(
                initializedPlan(List.of(new DirtyTile(KEY_A, 7L, 2L)))
        );
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        store.installIfAbsent(pending);
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        when(reconciliation.reconcile(pending)).thenReturn(FlushReconciliationDecision.COMMIT_CONFIRMED);
        FlushWorker worker = worker(
                capture,
                mock(FlushTransactionExecutor.class),
                store,
                reconciliation,
                tracker
        );

        assertEquals(FlushRunResult.RECONCILED_COMMIT, worker.flushOnce());

        assertTrue(store.current().isEmpty());
        verify(reconciliation).reconcile(pending);
        verifyNoInteractions(capture, tracker);
    }

    @Test
    void pendingRollbackRestoresBeforeClearAndNeverCapturesInSameCall() {
        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(
                initializedPlan(List.of(new DirtyTile(KEY_A, 7L, 2L)))
        );
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        store.installIfAbsent(pending);
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        when(reconciliation.reconcile(pending)).thenReturn(FlushReconciliationDecision.ROLLBACK_CONFIRMED);
        FlushWorker worker = worker(
                capture,
                mock(FlushTransactionExecutor.class),
                store,
                reconciliation,
                tracker
        );

        assertEquals(FlushRunResult.RECONCILED_ROLLBACK, worker.flushOnce());

        verify(tracker).restoreDirtyTiles(pending.drainedDirtyTiles());
        assertTrue(store.current().isEmpty());
        verifyNoInteractions(capture);
    }

    @Test
    void rollbackRestoreFailureKeepsPendingAndBlocksCapture() {
        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(initializedPlan(List.of()));
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        store.installIfAbsent(pending);
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        IllegalStateException restoreFailure = new IllegalStateException("restore failed");
        when(reconciliation.reconcile(pending)).thenReturn(FlushReconciliationDecision.ROLLBACK_CONFIRMED);
        doThrow(restoreFailure).when(tracker).restoreDirtyTiles(pending.drainedDirtyTiles());
        FlushWorker worker = worker(
                capture,
                mock(FlushTransactionExecutor.class),
                store,
                reconciliation,
                tracker
        );

        assertSame(restoreFailure, assertThrows(IllegalStateException.class, worker::flushOnce));

        assertSame(pending, store.current().orElseThrow());
        verifyNoInteractions(capture);
    }

    @Test
    void unresolvedOrProbeFailureKeepsPendingAndNeverCaptures() {
        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(initializedPlan(List.of()));
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        store.installIfAbsent(pending);
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        UnresolvedFlushCommitException failure = new UnresolvedFlushCommitException("unresolved");
        when(reconciliation.reconcile(pending)).thenThrow(failure);
        FlushWorker worker = worker(
                capture,
                mock(FlushTransactionExecutor.class),
                store,
                reconciliation,
                mock(DirtyTileTracker.class)
        );

        assertSame(failure, assertThrows(UnresolvedFlushCommitException.class, worker::flushOnce));

        assertSame(pending, store.current().orElseThrow());
        verifyNoInteractions(capture);
    }

    @Test
    void reconciledCommitDoesNotDeleteNewerLiveDirtyForSameTile() {
        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(
                initializedPlan(List.of(new DirtyTile(KEY_A, 7L, 2L)))
        );
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        store.installIfAbsent(pending);
        SynchronizedDirtyTileTracker tracker = new SynchronizedDirtyTileTracker();
        tracker.markDirty(KEY_A, 12L, 4L);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        when(reconciliation.reconcile(pending)).thenReturn(FlushReconciliationDecision.COMMIT_CONFIRMED);
        FlushWorker worker = worker(
                mock(FlushPlanCaptureService.class),
                mock(FlushTransactionExecutor.class),
                store,
                reconciliation,
                tracker
        );

        assertEquals(FlushRunResult.RECONCILED_COMMIT, worker.flushOnce());
        assertEquals(List.of(new DirtyTile(KEY_A, 12L, 4L)), tracker.drainDirtyTiles());
    }

    @Test
    void reconciledRollbackOldDirtyDoesNotOverwriteNewerLiveDirty() {
        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(
                initializedPlan(List.of(new DirtyTile(KEY_A, 7L, 99L)))
        );
        PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();
        store.installIfAbsent(pending);
        SynchronizedDirtyTileTracker tracker = new SynchronizedDirtyTileTracker();
        tracker.markDirty(KEY_A, 12L, 4L);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        when(reconciliation.reconcile(pending)).thenReturn(FlushReconciliationDecision.ROLLBACK_CONFIRMED);
        FlushWorker worker = worker(
                mock(FlushPlanCaptureService.class),
                mock(FlushTransactionExecutor.class),
                store,
                reconciliation,
                tracker
        );

        assertEquals(FlushRunResult.RECONCILED_ROLLBACK, worker.flushOnce());
        assertEquals(List.of(new DirtyTile(KEY_A, 12L, 4L)), tracker.drainDirtyTiles());
    }

    @Test
    void capturedPlanRunsTransactionWithoutWorkerReadinessRecheck() {
        Fixture fixture = new Fixture();
        List<String> fieldTypes = Arrays.stream(FlushWorker.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();

        assertEquals(FlushRunResult.COMMITTED, fixture.worker.flushOnce());
        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("ServiceReadiness")));
        verify(fixture.transactionExecutor).execute(fixture.plan);
    }

    @Test
    void guardReleasesAfterNoOpCaptureFailureDefiniteAmbiguousAndReconciliationFailure() {
        assertGuardReleasedAfterNoOp();
        assertGuardReleasedAfterCaptureFailure();
        assertGuardReleasedAfterDefiniteRollback();
        assertGuardReleasedAfterAmbiguousCommit();
        assertGuardReleasedAfterReconciliationFailure();
    }

    private void assertGuardReleasedAfterNoOp() {
        Fixture fixture = new Fixture();
        when(fixture.captureService.capturePlan()).thenReturn(noOp());
        assertEquals(FlushRunResult.NO_OP, fixture.worker.flushOnce());
        assertTrue(fixture.guard.tryRun(() -> { }));
    }

    private void assertGuardReleasedAfterCaptureFailure() {
        Fixture fixture = new Fixture();
        RuntimeException failure = new RuntimeException("capture");
        when(fixture.captureService.capturePlan()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, fixture.worker::flushOnce));
        assertTrue(fixture.guard.tryRun(() -> { }));
    }

    private void assertGuardReleasedAfterDefiniteRollback() {
        Fixture fixture = new Fixture();
        when(fixture.transactionExecutor.execute(fixture.plan)).thenReturn(
                FlushTransactionResult.definiteRollback(new RuntimeException("rollback"))
        );
        assertThrows(FlushPersistenceRolledBackException.class, fixture.worker::flushOnce);
        assertTrue(fixture.guard.tryRun(() -> { }));
    }

    private void assertGuardReleasedAfterAmbiguousCommit() {
        Fixture fixture = new Fixture();
        when(fixture.transactionExecutor.execute(fixture.plan)).thenReturn(
                FlushTransactionResult.ambiguousCommit(new RuntimeException("ambiguous"))
        );
        assertThrows(AmbiguousFlushCommitException.class, fixture.worker::flushOnce);
        assertTrue(fixture.guard.tryRun(() -> { }));
    }

    private void assertGuardReleasedAfterReconciliationFailure() {
        Fixture fixture = new Fixture();
        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(fixture.plan);
        RuntimeException failure = new RuntimeException("probe");
        when(fixture.store.current()).thenReturn(Optional.of(pending));
        when(fixture.reconciliationService.reconcile(pending)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, fixture.worker::flushOnce));
        assertTrue(fixture.guard.tryRun(() -> { }));
    }

    private static FlushWorker worker(
            FlushPlanCaptureService capture,
            FlushTransactionExecutor executor,
            PendingAmbiguousFlushStore store,
            FlushReconciliationService reconciliation,
            DirtyTileTracker tracker
    ) {
        return new FlushWorker(
                new FlushSingleFlightGuard(),
                capture,
                executor,
                store,
                reconciliation,
                tracker
        );
    }

    private static FlushPlan noOp() {
        return FlushPlan.noOp(3L, DbBootstrapState.INITIALIZED);
    }

    private static FlushPlan initializedPlan(List<DirtyTile> dirtyTiles) {
        Set<TileKey> snapshotKeys = new HashSet<>();
        snapshotKeys.add(KEY_A);
        dirtyTiles.stream().map(DirtyTile::tileKey).forEach(snapshotKeys::add);
        List<FlushTileSnapshot> snapshots = snapshotKeys.stream()
                .map(key -> snapshot(key, key.equals(KEY_A) ? 100L : 2L))
                .toList();
        return FlushPlan.nonNoOp(
                3L,
                10L,
                List.of(record(10L, KEY_A)),
                snapshots,
                dirtyTiles,
                DbBootstrapState.INITIALIZED
        );
    }

    private static FlushPlan bootstrapPlan(List<DirtyTile> dirtyTiles) {
        List<FlushTileSnapshot> snapshots = new CanonicalZ0TileKeys().orderedKeys().stream()
                .map(key -> snapshot(key, key.equals(KEY_B) ? 1L : 0L))
                .toList();
        return FlushPlan.nonNoOp(
                0L,
                1L,
                List.of(record(1L, KEY_A)),
                snapshots,
                dirtyTiles,
                DbBootstrapState.BOOTSTRAP_PENDING
        );
    }

    private static FlushTileSnapshot snapshot(TileKey key, long version) {
        return new FlushTileSnapshot(key, new byte[BoardConstants.TILE_PIXEL_COUNT], version);
    }

    private static WalRecord record(long eventSeq, TileKey key) {
        return new WalRecord(
                eventSeq,
                7L,
                key.z(),
                key.tx(),
                key.ty(),
                key.tx() * BoardConstants.TILE_SIZE,
                key.ty() * BoardConstants.TILE_SIZE,
                17,
                TIME
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch wait timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Latch wait interrupted.", exception);
        }
    }

    private static final class Fixture {

        private final FlushSingleFlightGuard guard = new FlushSingleFlightGuard();
        private final FlushPlanCaptureService captureService = mock(FlushPlanCaptureService.class);
        private final FlushTransactionExecutor transactionExecutor = mock(FlushTransactionExecutor.class);
        private final PendingAmbiguousFlushStore store = mock(PendingAmbiguousFlushStore.class);
        private final FlushReconciliationService reconciliationService = mock(FlushReconciliationService.class);
        private final DirtyTileTracker dirtyTileTracker = mock(DirtyTileTracker.class);
        private final FlushPlan plan;
        private final FlushWorker worker;

        private Fixture() {
            this(initializedPlan(List.of(new DirtyTile(KEY_A, 7L, 2L))));
        }

        private Fixture(FlushPlan plan) {
            this.plan = plan;
            when(store.current()).thenReturn(Optional.empty());
            when(captureService.capturePlan()).thenReturn(plan);
            when(transactionExecutor.execute(plan)).thenReturn(FlushTransactionResult.committed());
            this.worker = new FlushWorker(
                    guard,
                    captureService,
                    transactionExecutor,
                    store,
                    reconciliationService,
                    dirtyTileTracker
            );
        }
    }
}
