package dev.cgt.pixelplace.recovery.application;

/*
 * 이미 not-ready인 서비스에 진입한 요청을 일반 내부 실패와 구분하기 위한 전용 예외
 * 최초 fatal 요청의 원인 예외를 대체하지 않고 후속 요청 차단에만 사용
 */
public class ServiceNotReadyException extends RuntimeException {

    public static final String MESSAGE = "Service is not ready.";

    public ServiceNotReadyException() {
        super(MESSAGE);
    }
}
