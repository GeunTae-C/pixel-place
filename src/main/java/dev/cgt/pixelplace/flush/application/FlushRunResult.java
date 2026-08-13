package dev.cgt.pixelplace.flush.application;

/* flushOnce가 예외 없이 종료된 경로의 외부 관측 결과 */
public enum FlushRunResult {
    SKIPPED_ALREADY_RUNNING,
    NO_OP,
    COMMITTED,
    RECONCILED_COMMIT,
    RECONCILED_ROLLBACK
}
