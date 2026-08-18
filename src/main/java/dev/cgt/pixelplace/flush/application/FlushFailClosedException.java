package dev.cgt.pixelplace.flush.application;

/* pending exact identity 확인 불가로 service를 irreversible fatal-not-ready로 닫았음을 나타내는 예외 */
public class FlushFailClosedException extends RuntimeException {

    public FlushFailClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
