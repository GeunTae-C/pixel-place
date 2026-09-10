package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import java.io.IOException;

/** 내부 ID → handoff 준비 → 게시 경계. Access 발급·동적 redirect·서버 session 생성 책임 없음 */
public final class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final ServiceJwtTokens tokens;
    private final CookieAuthorizationRequestRepository requests;
    private final HandoffCookie handoff;
    private final AuthenticationFailureHandler failureHandler;
    private final String callback;

    public OAuthLoginSuccessHandler(ServiceJwtTokens tokens, CookieAuthorizationRequestRepository requests,
                                    HandoffCookie handoff, AuthenticationFailureHandler failureHandler, OriginPolicy origins) {
        this.tokens = tokens; this.requests = requests; this.handoff = handoff;
        this.failureHandler = failureHandler; callback = origins.frontendAuthCallbackUri().toString();
    }

    /** 모든 준비 성공 전 live cookie 금지. 전송 오류는 준비 실패 catch 바깥에서 전파 */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        if (response.isCommitted()) throw new IllegalStateException("Authentication response already committed");
        final String preparedCookie;
        try {
            if (!authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof KakaoPrincipal principal)) {
                // 외부 principal 이름을 내부 users.id로 오인한 토큰 발급 차단
                throw new IllegalArgumentException("Invalid login principal");
            }
            preparedCookie = handoff.prepare(tokens.issueHandoff(principal.internalUserId()).getTokenValue());
        } catch (RuntimeException failure) {
            failureHandler.onAuthenticationFailure(request, response,
                    new InternalAuthenticationServiceException("Login preparation failed", failure));
            return;
        }
        requests.deleteAuthorizationRequest(response);
        handoff.delete(response);
        SecurityContextHolder.clearContext();
        response.setHeader("Referrer-Policy", "no-referrer");
        response.addHeader(HttpHeaders.SET_COOKIE, preparedCookie);
        response.sendRedirect(callback);
    }
}
