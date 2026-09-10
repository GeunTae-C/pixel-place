package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.config.OriginPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import java.time.Duration;

/** callback 결과 전달 전용 cookie. 준비와 게시 분리, Pixel 인증·일회용 보장 책임 없음 */
public final class HandoffCookie {
    public static final String NAME = "pp_auth_handoff";
    public static final String PATH = "/api/auth/token";
    private final AuthProperties properties;
    private final OriginPolicy origins;

    public HandoffCookie(AuthProperties properties, OriginPolicy origins) {
        this.properties = properties; this.origins = origins;
    }

    /** 완성 cookie를 검증한 뒤 반환. 응답을 변경하지 않는 준비 단계 */
    public String prepare(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Invalid handoff cookie");
        CookieByteLimits.value(token);
        String header = header(token, properties.handoffTokenTtl());
        CookieByteLimits.header(header);
        return header;
    }

    /** 중복 이름도 인증 실패 입력으로 처리. Path는 보안 경계가 아님 */
    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        boolean found = false;
        String value = null;
        for (var cookie : request.getCookies()) {
            if (!NAME.equals(cookie.getName())) continue;
            if (found) return null;
            found = true;
            value = cookie.getValue();
        }
        if (value == null || value.isBlank() || value.length() > 3800) return null;
        return value;
    }

    /** 생성과 동일한 속성으로 삭제. commit 이후 응답 변경 금지 */
    public void delete(HttpServletResponse response) {
        if (response.isCommitted()) throw new IllegalStateException("Authentication response already committed");
        response.addHeader(HttpHeaders.SET_COOKIE, header("", Duration.ZERO));
    }

    private String header(String value, Duration ttl) {
        return ResponseCookie.from(NAME, value).path(PATH).maxAge(ttl).httpOnly(true)
                .secure(origins.cookieSecure()).sameSite("Lax").build().toString();
    }
}
