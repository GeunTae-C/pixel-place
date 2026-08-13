package dev.cgt.pixelplace.flush.application;

/* exact DB 상태로 확인된 이전 ambiguous transaction 결과 */
public enum FlushReconciliationDecision {
    COMMIT_CONFIRMED,
    ROLLBACK_CONFIRMED
}
