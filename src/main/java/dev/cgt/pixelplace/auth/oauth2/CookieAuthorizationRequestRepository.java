package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.config.OriginPolicy;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.time.Duration;

/** 브라우저당 진행 중 로그인 하나의 cookie 저장소. 세션 사용 및 callback state 인증 책임 없음 */
public final class CookieAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    public static final String COOKIE_NAME = "pp_oauth2_auth_request";
    public static final String COOKIE_PATH = "/login/oauth2/code/kakao";
    private final AuthorizationRequestCookieCodec codec;
    private final AuthProperties properties;
    private final OriginPolicy origins;

    public CookieAuthorizationRequestRepository(AuthorizationRequestCookieCodec codec, AuthProperties properties, OriginPolicy origins) {
        this.codec = codec;
        this.properties = properties;
        this.origins = origins;
    }

    /** 유효 cookie를 Spring의 state 비교 단계로 전달. 복원 실패는 authorization_request_not_found 입력 */
    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String value = null;
        boolean found = false;
        for (Cookie cookie : cookies) {
            if (!COOKIE_NAME.equals(cookie.getName())) continue;
            // 같은 이름의 다중 cookie는 Path가 보안 경계가 아님을 고려해 모호한 선택 없이 거부
            if (found) return null;
            found = true;
            value = cookie.getValue();
        }
        if (value == null) return null;
        try { return codec.decode(value); }
        catch (OAuth2AuthenticationException ignored) { return null; }
    }

    /** 완성 header 검증 전 응답 변경 금지. null 요청은 동일 속성 삭제로 처리 */
    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request, HttpServletResponse response) {
        ensureUncommitted(response);
        if (authorizationRequest == null) {
            deleteAuthorizationRequest(response);
            return;
        }
        String header = header(codec.encode(authorizationRequest), properties.authorizationRequestTtl());
        CookieByteLimits.header(header);
        response.addHeader(HttpHeaders.SET_COOKIE, header);
    }

    /** Spring callback remove 단계에서 먼저 삭제 허용. 후속 handler의 반복 삭제도 같은 속성 보존 */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        deleteAuthorizationRequest(response);
        return loadAuthorizationRequest(request);
    }

    /** 성공·실패 handler가 사용할 명시적 삭제 진입점. commit된 응답은 변경 불가 */
    public void deleteAuthorizationRequest(HttpServletResponse response) {
        ensureUncommitted(response);
        String header = header("", Duration.ZERO);
        CookieByteLimits.header(header);
        response.addHeader(HttpHeaders.SET_COOKIE, header);
    }

    private String header(String value, Duration lifetime) {
        return ResponseCookie.from(COOKIE_NAME, value).path(COOKIE_PATH).maxAge(lifetime)
                .httpOnly(true).secure(origins.cookieSecure()).sameSite("Lax").build().toString();
    }

    private static void ensureUncommitted(HttpServletResponse response) {
        if (response.isCommitted()) {
            // 삭제·실패 처리가 성공한 것처럼 조용히 반환하지 않음
            throw AuthorizationRequestCookieCodec.failure();
        }
    }
}
