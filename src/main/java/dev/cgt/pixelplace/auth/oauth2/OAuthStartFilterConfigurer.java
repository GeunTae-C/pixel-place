package dev.cgt.pixelplace.auth.oauth2;

import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

/** Spring이 만든 시작 필터의 공식 확장 지점. 중복 필터 등록 없이 실패 처리·no-save·no-referrer 연결 */
public final class OAuthStartFilterConfigurer implements ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter> {
    private final AuthenticationFailureHandler failureHandler;
    public OAuthStartFilterConfigurer(AuthenticationFailureHandler failureHandler) { this.failureHandler = failureHandler; }

    /** 명시적 OAuth chain이 생성한 시작 필터에 공용 실패 계약 연결 */
    @Override
    public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
        filter.setAuthenticationFailureHandler(failureHandler);
        filter.setRequestCache(new NullRequestCache());
        filter.setAuthorizationRedirectStrategy((request, response, uri) -> {
            response.setHeader("Referrer-Policy", "no-referrer");
            response.sendRedirect(uri);
        });
        return filter;
    }
}
