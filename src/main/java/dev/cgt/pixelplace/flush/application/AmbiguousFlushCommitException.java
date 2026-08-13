package dev.cgt.pixelplace.flush.application;

/* DB 반영 여부가 불명확하여 dirty를 pending 소유권으로 보존했음을 나타내는 예외 */
public class AmbiguousFlushCommitException extends RuntimeException {

    public AmbiguousFlushCommitException(String message, Throwable cause) {
        super(message, cause);
    }
}
