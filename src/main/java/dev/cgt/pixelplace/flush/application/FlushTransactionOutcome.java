package dev.cgt.pixelplace.flush.application;

/* DB 반영 여부에 따른 dirty/pending 소유권 결정을 위한 transaction outcome */
public enum FlushTransactionOutcome {
    /* commit 정상 반환으로 DB 반영 완료 확인 */
    COMMITTED,

    /* commit 미시도와 rollback 완료가 모두 확인된 dirty 복원 가능 결과 */
    DEFINITE_ROLLBACK,

    /* commit 또는 rollback 결과를 확정할 수 없어 pending reconciliation이 필요한 결과 */
    AMBIGUOUS_COMMIT
}
