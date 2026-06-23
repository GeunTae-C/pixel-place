package dev.cgt.pixelplace.recovery.web;

import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/*
 * startup recovery 완료 전 read/write HTTP API 진입 차단용 공통 guard
 * 불완전한 메모리 authoritative state 노출과 recovery 중 write 순서 충돌 방지
 */
@Component
public class ReadinessGuardInterceptor implements HandlerInterceptor {

    private static final String NOT_READY_RESPONSE_BODY = "{\"message\":\"Service is not ready.\"}";

    private final ServiceReadiness serviceReadiness;

    public ReadinessGuardInterceptor(ServiceReadiness serviceReadiness) {
        this.serviceReadiness = serviceReadiness;
    }

    /*
     * recovery 완료 여부 확인 후 controller 진입 허용
     * ready 전 요청은 부분 복구 상태를 읽거나 변경할 수 있으므로 이 경계에서 503 처리
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {
        if (serviceReadiness.isReady()) {
            return true;
        }

        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(NOT_READY_RESPONSE_BODY);
        return false;
    }
}
