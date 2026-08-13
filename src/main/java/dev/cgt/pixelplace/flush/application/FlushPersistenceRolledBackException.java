package dev.cgt.pixelplace.flush.application;

/* DB rollback과 drained dirty 복구를 처리했지만 이번 flush가 실패했음을 나타내는 예외 */
public class FlushPersistenceRolledBackException extends RuntimeException {

    public FlushPersistenceRolledBackException(String message, Throwable cause) {
        super(message, cause);
    }
}
