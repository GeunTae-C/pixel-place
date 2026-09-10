package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.config.OriginPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.util.UriUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

import static dev.cgt.pixelplace.auth.oauth2.AuthorizationRequestJson.require;

/** 요청 cookie의 기밀성·위조 방지와 PKCE 복원 경계. JSON 복원은 GCM 인증 성공 뒤에만 허용 */
public final class AuthorizationRequestCookieCodec {
    static final String PREFIX = "v1.";
    static final String AAD = "pixel-place:pp_oauth2_auth_request:v1";
    private static final String AUTHORIZATION_URI = "https://kauth.kakao.com/oauth/authorize";
    private final AuthProperties properties;
    private final OriginPolicy origins;
    private final Clock clock;
    private final AuthorizationRequestJson json;
    private final SecureRandom random = new SecureRandom();

    public AuthorizationRequestCookieCodec(AuthProperties properties, OriginPolicy origins, Clock clock) {
        this(properties, origins, clock, new AuthorizationRequestJson());
    }

    AuthorizationRequestCookieCodec(AuthProperties properties, OriginPolicy origins, Clock clock, AuthorizationRequestJson json) {
        this.properties = properties;
        this.origins = origins;
        this.clock = clock;
        this.json = json;
    }

    /** 검증·암호화·value 길이 확인 후에만 게시 가능한 값 반환. 실패 시 부분 cookie 생성 금지 */
    public String encode(OAuth2AuthorizationRequest request) {
        try {
            validateRequest(request);
            byte[] plaintext = json.write(request, clock.instant().plus(properties.authorizationRequestTtl()));
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(plaintext);
            String value = PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
            CookieByteLimits.value(value);
            return value;
        } catch (GeneralSecurityException | RuntimeException ignored) {
            throw failure();
        }
    }

    /** 크기·wire 형식·인증·envelope·PKCE 순서 보존. 실제 callback state 비교는 Spring 인증 경로 책임 */
    public OAuth2AuthorizationRequest decode(String value) {
        try {
            CookieByteLimits.value(value);
            require(value.startsWith(PREFIX));
            String encoded = value.substring(PREFIX.length());
            require(encoded.matches("[A-Za-z0-9_-]+"));
            byte[] packed = Base64.getUrlDecoder().decode(encoded);
            require(packed.length >= 12 + 16 + 1);
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(packed).equals(encoded));
            byte[] nonce = Arrays.copyOf(packed, 12);
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(packed, 12, packed.length - 12);
            OAuth2AuthorizationRequest request = json.read(plaintext, clock.instant());
            validateRequest(request);
            return request;
        } catch (GeneralSecurityException | RuntimeException ignored) {
            // crypto·parser의 입력 포함 오류가 일반 500이나 원문 로그로 새지 않게 고정 인증 실패로 변환
            throw failure();
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, properties.oauthCookieKey(), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void validateRequest(OAuth2AuthorizationRequest request) throws GeneralSecurityException {
        require(request != null && "authorization_code".equals(request.getGrantType().getValue())
                && "code".equals(request.getResponseType().getValue()));
        require(AUTHORIZATION_URI.equals(request.getAuthorizationUri())
                && origins.providerCallbackUri().toString().equals(request.getRedirectUri())
                && "kakao".equals(request.getAttributes().get("registration_id")));
        require(request.getClientId() != null && !request.getClientId().isBlank()
                && request.getState() != null && !request.getState().isBlank());
        Object verifier = request.getAttributes().get("code_verifier");
        require(verifier instanceof String text && text.matches("[A-Za-z0-9._~-]{43,128}"));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(((String) verifier).getBytes(StandardCharsets.US_ASCII)));
        require("S256".equals(request.getAdditionalParameters().get("code_challenge_method"))
                && challenge.equals(request.getAdditionalParameters().get("code_challenge")));
        validateRequestUri(request, challenge);
    }

    private static void validateRequestUri(OAuth2AuthorizationRequest request, String challenge) {
        URI uri = URI.create(request.getAuthorizationRequestUri());
        require(uri.getRawQuery() != null && uri.getRawFragment() == null
                && request.getAuthorizationRequestUri().substring(0, request.getAuthorizationRequestUri().indexOf('?')).equals(AUTHORIZATION_URI));
        Map<String, String> query = new HashMap<>();
        for (String part : uri.getRawQuery().split("&", -1)) {
            String[] pair = part.split("=", 2);
            require(pair.length == 2);
            // Spring URI builder의 RFC URI 표현을 해석. form decoder의 '+'→공백 변환 적용 금지
            require(query.putIfAbsent(UriUtils.decode(pair[0], StandardCharsets.UTF_8), UriUtils.decode(pair[1], StandardCharsets.UTF_8)) == null);
        }
        // 저장된 필드와 실제 redirect에 쓰일 URI가 서로 다른 PKCE/state를 담는 우회 방지
        require("code".equals(query.get("response_type")) && request.getClientId().equals(query.get("client_id"))
                && request.getRedirectUri().equals(query.get("redirect_uri")) && request.getState().equals(query.get("state"))
                && challenge.equals(query.get("code_challenge")) && "S256".equals(query.get("code_challenge_method"))
                && !query.containsKey("code_verifier"));
    }

    static OAuth2AuthenticationException failure() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_request", "Invalid authorization request cookie", null));
    }
}
