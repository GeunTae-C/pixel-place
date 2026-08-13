package dev.cgt.pixelplace.flush.application;

/* exact checkpoint와 bootstrap mode로도 이전 ambiguous transaction 결과를 확정할 수 없는 실패 */
public class UnresolvedFlushCommitException extends RuntimeException {

    public UnresolvedFlushCommitException(String message) {
        super(message);
    }
}
