package dev.cgt.pixelplace.flush.application;

/* DB 반영 여부에 따른 dirty/pending 소유권 결정을 위한 transaction outcome */
public enum FlushTransactionOutcome {
    COMMITTED,
    DEFINITE_ROLLBACK,
    AMBIGUOUS_COMMIT
}
