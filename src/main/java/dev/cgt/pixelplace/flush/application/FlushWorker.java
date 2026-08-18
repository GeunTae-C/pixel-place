package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/*
 * pending reconciliation, immutable plan capture, transaction outcome과 dirty 소유권을 조립하는 flush entrypoint
 * whole-cycle single-flight는 DB row lock과 별개이며 scheduler 없이 직접 호출 가능한 public API 제공
 */
@Service
@Profile("!stub")
public class FlushWorker {

    private final FlushSingleFlightGuard flushSingleFlightGuard;
    private final ServiceReadiness serviceReadiness;
    private final FlushPlanCaptureService flushPlanCaptureService;
    private final FlushTransactionExecutor flushTransactionExecutor;
    private final PendingAmbiguousFlushStore pendingAmbiguousFlushStore;
    private final FlushReconciliationService flushReconciliationService;
    private final DirtyTileTracker dirtyTileTracker;

    public FlushWorker(
            FlushSingleFlightGuard flushSingleFlightGuard,
            ServiceReadiness serviceReadiness,
            FlushPlanCaptureService flushPlanCaptureService,
            FlushTransactionExecutor flushTransactionExecutor,
            PendingAmbiguousFlushStore pendingAmbiguousFlushStore,
            FlushReconciliationService flushReconciliationService,
            DirtyTileTracker dirtyTileTracker
    ) {
        this.flushSingleFlightGuard = flushSingleFlightGuard;
        this.serviceReadiness = serviceReadiness;
        this.flushPlanCaptureService = flushPlanCaptureService;
        this.flushTransactionExecutor = flushTransactionExecutor;
        this.pendingAmbiguousFlushStore = pendingAmbiguousFlushStore;
        this.flushReconciliationService = flushReconciliationService;
        this.dirtyTileTracker = dirtyTileTracker;
    }

    /* pending 확인부터 outcome 처리까지 기다림 없는 단일 cycle로 보호하는 scheduler 독립 API */
    public FlushRunResult flushOnce() {
        AtomicReference<FlushRunResult> cycleResult = new AtomicReference<>();
        boolean executed = flushSingleFlightGuard.tryRun(() -> cycleResult.set(runCycle()));
        if (!executed) {
            return FlushRunResult.SKIPPED_ALREADY_RUNNING;
        }

        return Objects.requireNonNull(
                cycleResult.get(),
                "Flush cycle completed without a result."
        );
    }

    private FlushRunResult runCycle() {
        // fatal 확인을 pending 조회보다 먼저 수행하되 temporary not-ready reconciliation은 허용
        serviceReadiness.requireNotFatal();

        Optional<PendingAmbiguousFlush> pending = pendingAmbiguousFlushStore.current();
        if (pending.isPresent()) {
            return reconcilePending(pending.orElseThrow());
        }

        FlushPlan plan = flushPlanCaptureService.capturePlan();
        if (plan.noOp()) {
            return FlushRunResult.NO_OP;
        }

        PendingAmbiguousFlush candidate = createPendingCandidate(plan);
        FlushTransactionResult transactionResult;
        try {
            transactionResult = flushTransactionExecutor.execute(plan);
        } catch (RuntimeException unexpectedFailure) {
            return failAmbiguous(candidate, unexpectedFailure);
        } catch (Error unexpectedError) {
            return failAmbiguous(candidate, unexpectedError);
        }

        if (transactionResult == null || transactionResult.outcome() == null) {
            return failAmbiguous(
                    candidate,
                    new IllegalStateException("Flush transaction executor returned an invalid result.")
            );
        }

        return switch (transactionResult.outcome()) {
            case COMMITTED -> FlushRunResult.COMMITTED;
            case DEFINITE_ROLLBACK -> failDefiniteRollback(
                    plan,
                    transactionResult.failureCause().orElseGet(
                            () -> new IllegalStateException("Definite rollback result is missing its cause.")
                    )
            );
            case AMBIGUOUS_COMMIT -> failAmbiguous(
                    candidate,
                    transactionResult.failureCause().orElseGet(
                            () -> new IllegalStateException("Ambiguous commit result is missing its cause.")
                    )
            );
        };
    }

    private FlushRunResult reconcilePending(PendingAmbiguousFlush pending) {
        FlushReconciliationDecision decision = Objects.requireNonNull(
                flushReconciliationService.reconcile(pending),
                "reconciliation returned null decision"
        );
        if (decision == FlushReconciliationDecision.COMMIT_CONFIRMED) {
            pendingAmbiguousFlushStore.clearIfSame(pending);
            return FlushRunResult.RECONCILED_COMMIT;
        }

        if (decision == FlushReconciliationDecision.ROLLBACK_CONFIRMED) {
            // restore 성공 전 pending을 clear하면 dirty 소유권이 사라져 다음 cycle 복구 불가
            dirtyTileTracker.restoreDirtyTiles(pending.drainedDirtyTiles());
            pendingAmbiguousFlushStore.clearIfSame(pending);
            return FlushRunResult.RECONCILED_ROLLBACK;
        }

        throw new IllegalStateException("Unsupported reconciliation decision: " + decision);
    }

    private PendingAmbiguousFlush createPendingCandidate(FlushPlan plan) {
        try {
            // transaction 시작 전 copy 완료, ambiguous outcome 뒤 새 allocation/live 조회 의존 금지
            return createPendingCandidateFrom(plan);
        } catch (RuntimeException | Error candidateFailure) {
            Throwable primaryFailure = restoreWithPrimary(plan.drainedDirtyTiles(), candidateFailure);
            throw asRuntimeExceptionOrRethrowError(primaryFailure);
        }
    }

    // candidate 생성 실패와 dirty 복구 우선순위를 독립 검증하기 위한 package-private boundary
    PendingAmbiguousFlush createPendingCandidateFrom(FlushPlan plan) {
        return PendingAmbiguousFlush.from(plan);
    }

    private FlushRunResult failDefiniteRollback(FlushPlan plan, Throwable transactionFailure) {
        // WAL affected/full bootstrap target을 합성하지 않고 실제 drain 목록만 다음 plan용으로 복구
        Throwable primaryFailure = restoreWithPrimary(plan.drainedDirtyTiles(), transactionFailure);
        if (primaryFailure instanceof Error error) {
            throw error;
        }
        throw new FlushPersistenceRolledBackException(
                "Flush transaction rolled back. target=" + plan.flushTargetEventSeq(),
                primaryFailure
        );
    }

    private FlushRunResult failAmbiguous(PendingAmbiguousFlush candidate, Throwable transactionFailure) {
        Throwable installFailure = null;
        try {
            // ambiguous dirty는 tracker에 즉시 복구하지 않고 pending 하나에만 보존
            pendingAmbiguousFlushStore.installIfAbsent(candidate);
        } catch (RuntimeException | Error failure) {
            installFailure = failure;
        }

        Optional<PendingAmbiguousFlush> observedPending = null;
        Throwable currentFailure = null;
        try {
            // 정상 반환과 예외 반환 모두 exact candidate 사후확인 없이는 설치 완료로 간주 금지
            observedPending = pendingAmbiguousFlushStore.current();
        } catch (RuntimeException | Error failure) {
            currentFailure = failure;
        }

        if (currentFailure == null
                && observedPending != null
                && observedPending.orElse(null) == candidate) {
            return failConfirmedAmbiguous(candidate, transactionFailure, installFailure);
        }

        // pending 소유권 추측 대신 failure 전파 전에 process-local irreversible fail-closed 설치
        serviceReadiness.markFatalNotReady();
        Throwable confirmationFailure = currentFailure != null
                ? currentFailure
                : new IllegalStateException(
                        "Pending ambiguous flush exact instance could not be confirmed."
                );
        return failClosedAmbiguous(
                candidate,
                transactionFailure,
                installFailure,
                confirmationFailure
        );
    }

    private FlushRunResult failConfirmedAmbiguous(
            PendingAmbiguousFlush candidate,
            Throwable transactionFailure,
            Throwable installFailure
    ) {
        Throwable primaryFailure = installFailure == null
                ? transactionFailure
                : selectPrimaryFailure(transactionFailure, installFailure);
        if (primaryFailure instanceof Error error) {
            throw error;
        }
        throw new AmbiguousFlushCommitException(
                "Flush commit outcome is ambiguous. target=" + candidate.flushTargetEventSeq(),
                primaryFailure
        );
    }

    private FlushRunResult failClosedAmbiguous(
            PendingAmbiguousFlush candidate,
            Throwable transactionFailure,
            Throwable installFailure,
            Throwable confirmationFailure
    ) {
        Error primaryError = firstError(
                transactionFailure,
                installFailure,
                confirmationFailure
        );
        if (primaryError != null) {
            addSuppressedOnce(primaryError, transactionFailure);
            addSuppressedOnce(primaryError, installFailure);
            addSuppressedOnce(primaryError, confirmationFailure);
            throw primaryError;
        }

        FlushFailClosedException fatalFailure = new FlushFailClosedException(
                "Pending ambiguous flush installation could not be confirmed. target="
                        + candidate.flushTargetEventSeq(),
                transactionFailure
        );
        addSuppressedOnce(fatalFailure, installFailure);
        addSuppressedOnce(fatalFailure, confirmationFailure);
        throw fatalFailure;
    }

    private Throwable restoreWithPrimary(List<DirtyTile> drainedDirtyTiles, Throwable primaryFailure) {
        try {
            dirtyTileTracker.restoreDirtyTiles(drainedDirtyTiles);
            return primaryFailure;
        } catch (RuntimeException | Error restoreFailure) {
            return selectPrimaryFailure(primaryFailure, restoreFailure);
        }
    }

    private Throwable selectPrimaryFailure(Throwable firstFailure, Throwable laterFailure) {
        if (firstFailure instanceof Error) {
            addSuppressedOnce(firstFailure, laterFailure);
            return firstFailure;
        }
        if (laterFailure instanceof Error) {
            // 복구 Error 승격은 DB outcome과 dirty/pending 소유권 판정을 바꾸지 않음
            addSuppressedOnce(laterFailure, firstFailure);
            return laterFailure;
        }
        addSuppressedOnce(firstFailure, laterFailure);
        return firstFailure;
    }

    private Error firstError(Throwable... failures) {
        for (Throwable failure : failures) {
            if (failure instanceof Error error) {
                return error;
            }
        }
        return null;
    }

    private RuntimeException asRuntimeExceptionOrRethrowError(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return (RuntimeException) failure;
    }

    private void addSuppressedOnce(Throwable primaryFailure, Throwable secondaryFailure) {
        if (secondaryFailure == null
                || secondaryFailure == primaryFailure
                || secondaryFailure == primaryFailure.getCause()) {
            return;
        }
        for (Throwable existing : primaryFailure.getSuppressed()) {
            if (existing == secondaryFailure) {
                return;
            }
        }
        primaryFailure.addSuppressed(secondaryFailure);
    }
}
