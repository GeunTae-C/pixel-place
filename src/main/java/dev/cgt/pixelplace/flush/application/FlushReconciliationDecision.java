package dev.cgt.pixelplace.flush.application;

/* exact DB 상태로 확인된 이전 ambiguous transaction 결과 */
public enum FlushReconciliationDecision {
    /* pending target checkpoint와 initialized DB shape의 exact 일치 */
    COMMIT_CONFIRMED,

    /* pending expected checkpoint와 이전 bootstrap mode의 exact 일치 */
    ROLLBACK_CONFIRMED
}
