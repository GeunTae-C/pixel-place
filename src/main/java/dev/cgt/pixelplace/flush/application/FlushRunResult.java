package dev.cgt.pixelplace.flush.application;

/* flushOnce가 예외 없이 종료된 경로의 외부 관측 결과 */
public enum FlushRunResult {
    /* 다른 whole-cycle 실행 중이라 boundary 작업 전에 무대기 종료 */
    SKIPPED_ALREADY_RUNNING,

    /* checkpoint 이후 실제 WAL record 부재로 transaction 없이 종료 */
    NO_OP,

    /* 새 immutable plan의 DB transaction commit 확인 */
    COMMITTED,

    /* 이전 ambiguous transaction commit 확인과 pending dirty 폐기 */
    RECONCILED_COMMIT,

    /* 이전 ambiguous transaction rollback 확인과 pending dirty 복원 */
    RECONCILED_ROLLBACK
}
