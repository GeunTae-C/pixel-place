package dev.cgt.pixelplace.recovery.web;

import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;

/*
 * HTTP guard 통과 뒤 command/core readiness 재검사에서 발생한 전용 예외의 503 변환 경계
 * 일반 IllegalStateException은 최초 fatal 요청 등 다른 내부 실패이므로 변환 책임 없음
 */
@RestControllerAdvice
public class ServiceNotReadyExceptionHandler {

    private static final MediaType NOT_READY_CONTENT_TYPE =
            new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    private static final String NOT_READY_RESPONSE_BODY =
            "{\"message\":\"" + ServiceNotReadyException.MESSAGE + "\"}";

    /* interceptor 통과 후 감지된 not-ready도 기존 readiness 실패와 같은 HTTP 계약으로 변환 */
    @ExceptionHandler(ServiceNotReadyException.class)
    public ResponseEntity<String> handleServiceNotReady() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(NOT_READY_CONTENT_TYPE)
                .body(NOT_READY_RESPONSE_BODY);
    }
}
