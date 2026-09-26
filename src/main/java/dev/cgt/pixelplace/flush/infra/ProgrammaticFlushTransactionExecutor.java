package dev.cgt.pixelplace.flush.infra;

import dev.cgt.pixelplace.flush.application.FlushPersistenceService;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.flush.application.FlushPlan;
import dev.cgt.pixelplace.flush.application.FlushTransactionExecutor;
import dev.cgt.pixelplace.flush.application.FlushTransactionResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.Objects;

/*
 * flush body의 REQUIRES_NEW transaction 시작·commit·rollback과 outcome 분류를 직접 소유하는 adapter
 * commit 예외 뒤 완료된 status에 rollback을 재호출하지 않아 불명확한 DB 결과를 왜곡하지 않음
 */
@Component
@Profile("!stub")
public class ProgrammaticFlushTransactionExecutor implements FlushTransactionExecutor {

    static final String TRANSACTION_NAME = "pixel-place-flush-persistence";

    private final PlatformTransactionManager transactionManager;
    private final PixelMeasurement measurement;
    private final FlushPersistenceService flushPersistenceService;

    public ProgrammaticFlushTransactionExecutor(
            PlatformTransactionManager transactionManager,
            FlushPersistenceService flushPersistenceService,
            PixelMeasurement measurement
    ) {
        this.transactionManager = transactionManager;
        this.flushPersistenceService = flushPersistenceService;
        this.measurement = measurement;
    }

    /*
     * immutable plan 하나를 새 physical transaction으로 실행하고 확인 가능한 outcome만 분류
     * commit 예외와 rollback 완료 미확인은 dirty 복원 가능한 definite rollback으로 추정 금지
     */
    @Override
    public FlushTransactionResult execute(FlushPlan plan) {
        var scope = measurement.begin(PixelMeasurement.Operation.transaction);
        Throwable failure = null;
        PixelMeasurement.Outcome outcome = PixelMeasurement.Outcome.failure;
        try {
            FlushTransactionResult result = executeTransaction(plan);
            outcome = switch (result.outcome()) {
                case COMMITTED -> PixelMeasurement.Outcome.committed;
                case DEFINITE_ROLLBACK -> PixelMeasurement.Outcome.rollback;
                case AMBIGUOUS_COMMIT -> PixelMeasurement.Outcome.ambiguous;
            };
            return result;
        } catch (RuntimeException | Error problem) { failure = problem; throw problem; }
        finally { measurement.endPreserving(scope, outcome, failure); }
    }

    private FlushTransactionResult executeTransaction(FlushPlan plan) {
        FlushPlan currentPlan = Objects.requireNonNull(plan, "plan must not be null");
        if (currentPlan.noOp()) {
            throw new IllegalArgumentException("No-op plan must not start a transaction.");
        }

        TransactionStatus status;
        try {
            status = Objects.requireNonNull(
                    transactionManager.getTransaction(transactionDefinition()),
                    "transactionManager returned null status"
            );
        } catch (RuntimeException | Error startFailure) {
            // body 시작 전 실패이므로 DB write 없음이 확정된 definite rollback 의미
            return FlushTransactionResult.definiteRollback(startFailure);
        }

        try {
            flushPersistenceService.persist(currentPlan);
        } catch (RuntimeException | Error bodyFailure) {
            return rollbackAfterBodyFailure(status, bodyFailure);
        }

        try {
            transactionManager.commit(status);
            return FlushTransactionResult.committed();
        } catch (RuntimeException | Error commitFailure) {
            // commit 호출 이후 status는 완료 상태이므로 rollback 재호출 금지, 결과는 항상 ambiguous
            return FlushTransactionResult.ambiguousCommit(commitFailure);
        }
    }

    private FlushTransactionResult rollbackAfterBodyFailure(TransactionStatus status, Throwable bodyFailure) {
        try {
            transactionManager.rollback(status);
            return FlushTransactionResult.definiteRollback(bodyFailure);
        } catch (RuntimeException | Error rollbackFailure) {
            return FlushTransactionResult.ambiguousCommit(
                    selectPrimaryFailure(bodyFailure, rollbackFailure)
            );
        }
    }

    private Throwable selectPrimaryFailure(Throwable firstFailure, Throwable laterFailure) {
        if (firstFailure instanceof Error) {
            addSuppressed(firstFailure, laterFailure);
            return firstFailure;
        }
        if (laterFailure instanceof Error) {
            // rollback Error는 body RuntimeException보다 우선하며 동일 instance를 outcome에 보존
            addSuppressed(laterFailure, firstFailure);
            return laterFailure;
        }
        addSuppressed(firstFailure, laterFailure);
        return firstFailure;
    }

    private void addSuppressed(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    private TransactionDefinition transactionDefinition() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(TRANSACTION_NAME);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definition.setReadOnly(false);
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return definition;
    }
}
