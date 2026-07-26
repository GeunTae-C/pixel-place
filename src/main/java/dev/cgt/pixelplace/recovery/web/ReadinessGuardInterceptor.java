package dev.cgt.pixelplace.recovery.web;

import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/*
 * startup recovery 미완료와 runtime fatal/not-ready 상태의 HTTP API 진입 차단용 공통 guard
 * WAL durable tail과 memory authoritative state 불일치 상태의 read/write 노출 방지
 */
@Component
public class ReadinessGuardInterceptor implements HandlerInterceptor {

    private static final String NOT_READY_RESPONSE_BODY =
            "{\"message\":\"" + ServiceNotReadyException.MESSAGE + "\"}";

    private final ServiceReadiness serviceReadiness;

    public ReadinessGuardInterceptor(ServiceReadiness serviceReadiness) {
        this.serviceReadiness = serviceReadiness;
    }

    /*
     * 현재 서비스 안전 상태 확인 후 controller 진입 허용
     * recovery 미완료 또는 runtime fatal 상태의 요청은 불일치 상태를 노출할 수 있으므로 이 경계에서 503 처리
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
