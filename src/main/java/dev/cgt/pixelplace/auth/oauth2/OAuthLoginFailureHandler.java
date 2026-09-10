package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import java.io.IOException;

/** callback/start 실패의 고정 redirect·두 cookie cleanup. provider 입력·예외 메시지 재노출 금지 */
public final class OAuthLoginFailureHandler implements AuthenticationFailureHandler {
    private final CookieAuthorizationRequestRepository requests;
    private final HandoffCookie handoff;
    private final String failureUri;

    public OAuthLoginFailureHandler(CookieAuthorizationRequestRepository requests, HandoffCookie handoff, OriginPolicy origins) {
        this.requests = requests; this.handoff = handoff;
        failureUri = origins.frontendAuthCallbackUri() + "?error=oauth_login_failed";
    }

    /** 미commit 실패만 정리. Spring 시작 필터가 감싼 전송 오류도 원래 인스턴스로 전파 */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) throws IOException {
        SecurityContextHolder.clearContext();
        if (response.isCommitted()) {
            // 두 번째 redirect·cookie 변경으로 이미 게시된 응답을 복구하려 하면 안 됨
            if (failure.getCause() instanceof IOException transport) throw transport;
            if (failure.getCause() instanceof RuntimeException transport) throw transport;
            throw failure;
        }
        requests.deleteAuthorizationRequest(response);
        handoff.delete(response);
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(failureUri);
    }
}
