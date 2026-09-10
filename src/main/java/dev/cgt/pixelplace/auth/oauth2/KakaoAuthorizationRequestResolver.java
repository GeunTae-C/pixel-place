package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** 명시적 카카오 시작 GET만 Spring resolver에 전달. confidential client에도 매번 S256 적용 */
public final class KakaoAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    public static final String START_PATH = "/oauth2/authorization/kakao";
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public KakaoAuthorizationRequestResolver(ClientRegistrationRepository registrations, OriginPolicy origins) {
        var kakao = registrations.findByRegistrationId("kakao");
        if (kakao == null || !origins.providerCallbackUri().toString().equals(kakao.getRedirectUri())) {
            // 요청 Host나 별도 override로 검증된 callback이 바뀌는 구성 차단
            throw new IllegalArgumentException("Invalid Kakao callback registration");
        }
        delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    }

    /** raw 경로까지 일치하는 GET만 새 state·verifier 생성 가능 */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())
                || !(request.getContextPath() + START_PATH).equals(request.getRequestURI())) return null;
        return delegate.resolve(request);
    }

    /** 간접 ClientAuthorizationRequiredException으로 새 로그인·saved request를 만들면 안 됨 */
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return null;
    }
}
