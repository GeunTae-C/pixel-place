package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.config.OriginPolicy;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import java.time.*;
import java.util.*;

/** B2 명시적 조립용 fixture. 실제 credential·DB·카카오 네트워크 사용 없음 */
public final class LoginTestSupport {
    public static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final OriginPolicy ORIGINS = new OriginPolicy("http://localhost:3000", "http://localhost:8080",
            "http://localhost:3000/", "http://localhost:8080/login/oauth2/code/kakao", false);
    public static final AuthProperties PROPERTIES = new AuthProperties(key(3), key(7), Duration.ofMinutes(15));
    private static String key(int value) {
        byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }
    public static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("kakao").clientId("dummy-client").clientSecret("dummy-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(ORIGINS.providerCallbackUri().toString())
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token").userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id").build();
    }
    public static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
    private LoginTestSupport() { }
}
