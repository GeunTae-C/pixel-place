package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.config.OriginPolicy;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 AES-GCM·Spring 요청 객체를 사용해 인증 선행·PKCE·쿠키 수명과 게시/삭제 경계 고정 */
class AuthorizationRequestCookieTest {
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05.123456789Z");
    private static final AuthProperties PROPERTIES = properties(1);
    private static final OriginPolicy ORIGINS = origins(false);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void roundTripPreservesEveryRequestFieldAndUsesFreshNonce() {
        var original = request(ORIGINS);
        var codec = codec(NOW);
        String first = codec.encode(original);
        String second = codec.encode(original);
        assertThat(first).startsWith("v1.").isNotEqualTo(second);
        assertThat(Arrays.copyOf(unpack(first), 12)).isNotEqualTo(Arrays.copyOf(unpack(second), 12));
        assertSameRequest(original, codec.decode(first));
        assertSameRequest(original, codec.decode(second));
        assertThat(first).doesNotContain(original.getState(), original.getAttribute("code_verifier"));
    }

    @Test
    void serverExpiryUsesInjectedClockAndSharedTtlAtNanosecondBoundary() {
        String value = codec(NOW).encode(request(ORIGINS));
        Instant expires = NOW.plus(PROPERTIES.authorizationRequestTtl());
        assertThat(codec(expires.minusNanos(1)).decode(value)).isNotNull();
        failure(() -> codec(expires).decode(value));
        failure(() -> codec(expires.plusNanos(1)).decode(value));
    }

    @Test
    void sizeFormatNonceCiphertextTagAndAadFailuresNeverDeserialize() throws Exception {
        var json = spy(new AuthorizationRequestJson());
        var codec = new AuthorizationRequestCookieCodec(PROPERTIES, ORIGINS, fixed(NOW), json);
        String valid = codec.encode(request(ORIGINS));
        clearInvocations(json);
        List<String> values = new ArrayList<>(Arrays.asList(null, "", "x".repeat(3801), valid.replaceFirst("v1", "v2"),
                "v1.!", "v1.A", "v1.AA==", "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[28])));
        for (int index : new int[]{0, 12, unpack(valid).length - 1}) {
            byte[] bytes = unpack(valid);
            bytes[index] ^= 1;
            values.add(pack(bytes));
        }
        values.add(encrypt("{}".getBytes(StandardCharsets.UTF_8), "wrong-purpose", PROPERTIES));
        values.add(encrypt("{}".getBytes(StandardCharsets.UTF_8), AuthorizationRequestCookieCodec.AAD, properties(3)));
        for (String value : values) failure(() -> codec.decode(value));
        verify(json, never()).read(any(byte[].class), any(Instant.class));
    }

    @Test
    void authenticatedMalformedEnvelopeAndRequestAreAuthenticationFailures() throws Exception {
        byte[] original = new AuthorizationRequestJson().write(request(ORIGINS), NOW.plusSeconds(180));
        List<byte[]> malformed = new ArrayList<>();
        malformed.add("{".getBytes(StandardCharsets.UTF_8));
        malformed.add("[]".getBytes(StandardCharsets.UTF_8));
        malformed.add((new String(original, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8));
        for (String field : List.of("version", "expiresAt", "request")) {
            ObjectNode root = (ObjectNode) JSON.readTree(original);
            root.remove(field);
            malformed.add(JSON.writeValueAsBytes(root));
        }
        for (String field : List.of("version", "expiresAt", "request")) {
            ObjectNode root = (ObjectNode) JSON.readTree(original);
            root.put(field, "wrong");
            malformed.add(JSON.writeValueAsBytes(root));
        }
        ObjectNode version = (ObjectNode) JSON.readTree(original);
        version.put("version", 2);
        malformed.add(JSON.writeValueAsBytes(version));
        version.put("version", 4_294_967_297L);
        malformed.add(JSON.writeValueAsBytes(version));
        ObjectNode extra = (ObjectNode) JSON.readTree(original);
        extra.put("untrustedType", "java.lang.Object");
        malformed.add(JSON.writeValueAsBytes(extra));
        for (String field : List.of("authorizationUri", "grantType", "responseType", "clientId", "redirectUri", "scopes", "state", "attributes", "additionalParameters", "authorizationRequestUri")) {
            ObjectNode root = (ObjectNode) JSON.readTree(original);
            ((ObjectNode) root.path("request")).remove(field);
            malformed.add(JSON.writeValueAsBytes(root));
            root = (ObjectNode) JSON.readTree(original);
            ((ObjectNode) root.path("request")).put(field, 123);
            malformed.add(JSON.writeValueAsBytes(root));
        }
        // 인증된 JSON도 parser의 중복 key 덮어쓰기나 모호한 구조를 허용하면 안 됨
        malformed.add(new String(original, StandardCharsets.UTF_8).replace("\"version\":1", "\"version\":2,\"version\":1").getBytes(StandardCharsets.UTF_8));
        for (byte[] plaintext : malformed) {
            String value = encrypt(plaintext, AuthorizationRequestCookieCodec.AAD, PROPERTIES);
            failure(() -> codec(NOW).decode(value));
            assertThat(repository(codec(NOW), ORIGINS).loadAuthorizationRequest(incoming(value))).isNull();
        }
    }

    @Test
    void malformedPkceAndRequestBindingFailOnBothCreationAndAuthenticatedRestoration() throws Exception {
        var valid = request(ORIGINS);
        List<OAuth2AuthorizationRequest> invalid = new ArrayList<>();
        for (String verifier : List.of("", "a".repeat(42), "a".repeat(129), "!".repeat(43), "가".repeat(43), "a".repeat(43))) {
            invalid.add(OAuth2AuthorizationRequest.from(valid).attributes(map -> map.put("code_verifier", verifier)).build());
        }
        invalid.add(OAuth2AuthorizationRequest.from(valid).attributes(map -> map.remove("code_verifier")).build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).additionalParameters(map -> map.remove("code_challenge")).build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).additionalParameters(map -> map.remove("code_challenge_method")).build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).additionalParameters(map -> map.put("code_challenge_method", "plain")).build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).additionalParameters(map -> map.put("code_challenge", valid.getAdditionalParameters().get("code_challenge") + "=")).build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).attributes(map -> map.put("registration_id", "other")).build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).redirectUri("http://localhost:8080/login/oauth2/code/other").build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).authorizationUri("https://example.com/authorize").build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).state("").build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).authorizationRequestUri(valid.getAuthorizationRequestUri().replace("state=", "state=wrong")).build());
        invalid.add(OAuth2AuthorizationRequest.from(valid).authorizationRequestUri(valid.getAuthorizationRequestUri() + "&state=duplicate").build());
        for (var request : invalid) {
            failure(() -> codec(NOW).encode(request));
            String encoded = encrypt(new AuthorizationRequestJson().write(request, NOW.plusSeconds(180)), AuthorizationRequestCookieCodec.AAD, PROPERTIES);
            failure(() -> codec(NOW).decode(encoded));
            var response = new MockHttpServletResponse();
            assertThat(repository(codec(NOW), ORIGINS).removeAuthorizationRequest(incoming(encoded), response)).isNull();
            assertDeleted(response, false);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {43, 128})
    void validPkceVerifierLengthExtremesRoundTrip(int length) throws Exception {
        String verifier = ("a._~-Z0".repeat(20)).substring(0, length);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var original = OAuth2AuthorizationRequest.from(request(ORIGINS))
                .attributes(map -> map.put("code_verifier", verifier))
                .additionalParameters(map -> map.put("code_challenge", challenge)).authorizationRequestUri((String) null).build();
        assertSameRequest(original, codec(NOW).decode(codec(NOW).encode(original)));
    }

    @Test
    void byteLimitsAcceptMaximumAndRejectOneByteOverIndependently() {
        assertThatCode(() -> CookieByteLimits.value("x".repeat(3800))).doesNotThrowAnyException();
        failure(() -> CookieByteLimits.value("x".repeat(3801)));
        assertThatCode(() -> CookieByteLimits.header("x".repeat(4096))).doesNotThrowAnyException();
        failure(() -> CookieByteLimits.header("x".repeat(4097)));
        assertThatCode(() -> CookieByteLimits.value("가".repeat(1266) + "xx")).doesNotThrowAnyException();
        failure(() -> CookieByteLimits.value("가".repeat(1267)));
        assertThatCode(() -> CookieByteLimits.header("가".repeat(1365) + "x")).doesNotThrowAnyException();
        failure(() -> CookieByteLimits.header("가".repeat(1365) + "xx"));
    }

    @Test
    void oversizedAndUnsupportedFieldsNeverPublishPartialLiveCookie() {
        for (Object extension : List.of("x".repeat(4000), List.of("unsupported"), 123)) {
            var request = OAuth2AuthorizationRequest.from(request(ORIGINS)).attributes(map -> map.put("extension", extension)).build();
            var response = new MockHttpServletResponse();
            response.addHeader("Existing", "preserved");
            failure(() -> repository(codec(NOW), ORIGINS).saveAuthorizationRequest(request, new MockHttpServletRequest(), response));
            assertThat(response.getHeaders("Set-Cookie")).isEmpty();
            assertThat(response.getHeader("Existing")).isEqualTo("preserved");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createAndRepeatedDeleteUseSameHostOnlyAttributes(boolean secure) {
        var origins = origins(secure);
        var codec = new AuthorizationRequestCookieCodec(PROPERTIES, origins, fixed(NOW));
        var repository = repository(codec, origins);
        var response = new MockHttpServletResponse();
        var original = request(origins);
        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), response);
        String header = response.getHeader("Set-Cookie");
        assertAttributes(header, secure);
        assertThat(header).contains("Max-Age=" + PROPERTIES.authorizationRequestTtl().toSeconds());
        assertThat(header.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        var incoming = incoming(value);
        incoming.setParameter("state", "different-incoming-state");
        // cookie 유효성과 callback state 인증은 별개. 실제 state 거부는 B2의 Spring 인증 경로 책임
        assertSameRequest(original, repository.loadAuthorizationRequest(incoming));
        assertSameRequest(original, repository.removeAuthorizationRequest(incoming, response));
        repository.saveAuthorizationRequest(null, incoming, response);
        repository.deleteAuthorizationRequest(response);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(4);
        List<String> headers = new ArrayList<>(response.getHeaders("Set-Cookie"));
        assertThat(headers.subList(1, 4)).allSatisfy(deleted -> {
            assertAttributes(deleted, secure);
            assertThat(deleted).startsWith(CookieAuthorizationRequestRepository.COOKIE_NAME + "=;").contains("Max-Age=0");
        });
        assertThat(headers.get(1)).isEqualTo(headers.get(2)).isEqualTo(headers.get(3));
        assertThat(incoming.getSession(false)).isNull();
    }

    @Test
    void missingExpiredTamperedAndDuplicateCookiesReturnNullAndRemoveStillDeletes() {
        var repository = repository(codec(NOW.plusSeconds(180)), ORIGINS);
        String valid = codec(NOW).encode(request(ORIGINS));
        for (MockHttpServletRequest request : List.of(new MockHttpServletRequest(), incoming("bad"), incoming(valid))) {
            assertThat(repository.loadAuthorizationRequest(request)).isNull();
            var response = new MockHttpServletResponse();
            assertThat(repository.removeAuthorizationRequest(request, response)).isNull();
            assertDeleted(response, false);
        }
        var duplicate = incoming(valid);
        duplicate.setCookies(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, valid), new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, valid));
        assertThat(repository(codec(NOW), ORIGINS).loadAuthorizationRequest(duplicate)).isNull();
        duplicate.setCookies(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, null), new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, valid));
        assertThat(repository(codec(NOW), ORIGINS).loadAuthorizationRequest(duplicate)).isNull();
    }

    @Test
    void missingRequestUsesRealSpringAuthorizationRequestNotFoundWithoutExchange() {
        var clients = mock(ClientRegistrationRepository.class);
        var service = mock(OAuth2AuthorizedClientService.class);
        var authentication = mock(AuthenticationManager.class);
        var filter = new OAuth2LoginAuthenticationFilter(clients, service);
        filter.setAuthenticationManager(authentication);
        filter.setAuthorizationRequestRepository(repository(codec(NOW), ORIGINS));
        var request = incoming("malformed");
        request.setParameter("code", "test-only-code");
        request.setParameter("state", "test-only-state");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.attemptAuthentication(request, response))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        error -> assertThat(error.getError().getErrorCode()).isEqualTo("authorization_request_not_found"));
        verifyNoInteractions(clients, service, authentication);
        assertDeleted(response, false);
    }

    @Test
    void committedResponseCannotPretendToSaveOrRemoveCookie() {
        var repository = repository(codec(NOW), ORIGINS);
        var response = new MockHttpServletResponse();
        response.setCommitted(true);
        failure(() -> repository.saveAuthorizationRequest(request(ORIGINS), new MockHttpServletRequest(), response));
        failure(() -> repository.removeAuthorizationRequest(new MockHttpServletRequest(), response));
        failure(() -> repository.deleteAuthorizationRequest(response));
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    private static OAuth2AuthorizationRequest request(OriginPolicy origins) {
        var builder = OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .clientId("test-only-client").redirectUri(origins.providerCallbackUri().toString()).state("test-only-state-+/가")
                .scopes(Set.of()).attributes(Map.of("registration_id", "kakao", "custom_attribute", "preserved"))
                .additionalParameters(Map.of("prompt", "login"));
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        return builder.build();
    }

    private static void assertSameRequest(OAuth2AuthorizationRequest original, OAuth2AuthorizationRequest restored) {
        assertThat(restored.getGrantType()).isEqualTo(original.getGrantType());
        assertThat(restored.getResponseType()).isEqualTo(original.getResponseType());
        assertThat(restored.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
        assertThat(restored.getClientId()).isEqualTo(original.getClientId());
        assertThat(restored.getRedirectUri()).isEqualTo(original.getRedirectUri());
        assertThat(restored.getScopes()).isEqualTo(original.getScopes());
        assertThat(restored.getState()).isEqualTo(original.getState());
        assertThat(restored.getAttributes()).isEqualTo(original.getAttributes());
        assertThat(restored.getAdditionalParameters()).isEqualTo(original.getAdditionalParameters());
        assertThat(restored.getAuthorizationRequestUri()).isEqualTo(original.getAuthorizationRequestUri());
    }

    private static void assertAttributes(String header, boolean secure) {
        assertThat(header).contains("Path=/login/oauth2/code/kakao", "HttpOnly", "SameSite=Lax").doesNotContain("Domain=");
        assertThat(header.contains("; Secure")).isEqualTo(secure);
    }

    private static void assertDeleted(MockHttpServletResponse response, boolean secure) {
        assertAttributes(response.getHeader("Set-Cookie"), secure);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    private static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                error -> assertThat(error.getError().getDescription()).isEqualTo("Invalid authorization request cookie"));
    }

    private static Clock fixed(Instant now) { return Clock.fixed(now, ZoneOffset.UTC); }
    private static AuthorizationRequestCookieCodec codec(Instant now) { return new AuthorizationRequestCookieCodec(PROPERTIES, ORIGINS, fixed(now)); }
    private static CookieAuthorizationRequestRepository repository(AuthorizationRequestCookieCodec codec, OriginPolicy origins) {
        return new CookieAuthorizationRequestRepository(codec, PROPERTIES, origins);
    }
    private static MockHttpServletRequest incoming(String value) {
        var request = new MockHttpServletRequest("GET", CookieAuthorizationRequestRepository.COOKIE_PATH);
        request.setCookies(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, value));
        return request;
    }
    private static OriginPolicy origins(boolean secure) {
        String scheme = secure ? "https" : "http";
        return new OriginPolicy(scheme + "://localhost:3000", scheme + "://localhost:8080", scheme + "://localhost:3000/",
                scheme + "://localhost:8080/login/oauth2/code/kakao", secure);
    }
    private static AuthProperties properties(int marker) {
        byte[] jwt = new byte[32];
        byte[] cookie = new byte[32];
        Arrays.fill(jwt, (byte) marker);
        Arrays.fill(cookie, (byte) (marker + 1));
        return new AuthProperties(Base64.getEncoder().encodeToString(jwt), Base64.getEncoder().encodeToString(cookie), Duration.ofMinutes(15));
    }
    private static byte[] unpack(String value) { return Base64.getUrlDecoder().decode(value.substring(3)); }
    private static String pack(byte[] bytes) { return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String encrypt(byte[] plaintext, String aad, AuthProperties properties) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, properties.oauthCookieKey(), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plaintext);
        return pack(ByteBuffer.allocate(12 + ciphertext.length).put(nonce).put(ciphertext).array());
    }
}
