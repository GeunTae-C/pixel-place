package dev.cgt.pixelplace.flush.application;

import java.util.Objects;
import java.util.Optional;

/*
 * transaction outcome과 원래 RuntimeException/Error를 손실 없이 전달하는 immutable result
 * committed에는 failure를 섞지 않고 실패 outcome에는 반드시 cause가 존재하도록 제한
 */
public final class FlushTransactionResult {

    private final FlushTransactionOutcome outcome;
    private final Throwable failureCause;

    private FlushTransactionResult(FlushTransactionOutcome outcome, Throwable failureCause) {
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome == FlushTransactionOutcome.COMMITTED && failureCause != null) {
            throw new IllegalArgumentException("Committed result must not contain a failure cause.");
        }
        if (outcome != FlushTransactionOutcome.COMMITTED && failureCause == null) {
            throw new IllegalArgumentException("Failure outcome requires a failure cause.");
        }
        this.failureCause = failureCause;
    }

    public static FlushTransactionResult committed() {
        return new FlushTransactionResult(FlushTransactionOutcome.COMMITTED, null);
    }

    public static FlushTransactionResult definiteRollback(Throwable cause) {
        return new FlushTransactionResult(
                FlushTransactionOutcome.DEFINITE_ROLLBACK,
                Objects.requireNonNull(cause, "cause must not be null")
        );
    }

    public static FlushTransactionResult ambiguousCommit(Throwable cause) {
        return new FlushTransactionResult(
                FlushTransactionOutcome.AMBIGUOUS_COMMIT,
                Objects.requireNonNull(cause, "cause must not be null")
        );
    }

    public FlushTransactionOutcome outcome() {
        return outcome;
    }

    public Optional<Throwable> failureCause() {
        return Optional.ofNullable(failureCause);
    }
}
