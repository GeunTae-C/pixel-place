package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.user.application.UserProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.web.client.RestOperations;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

import static dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 resolver·DefaultOAuth2UserService의 converter 경계와 금지 호출 검증 */
class KakaoLoginComponentsTest {
    @Test
    void exactGetUsesFreshS256AndEncryptedCookiePreservesVerifier() throws Exception {
        var resolver = new KakaoAuthorizationRequestResolver(new InMemoryClientRegistrationRepository(registration()), ORIGINS);
        var codec = new AuthorizationRequestCookieCodec(PROPERTIES, ORIGINS, CLOCK);
        Set<String> verifiers = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            var result = resolver.resolve(request("GET", KakaoAuthorizationRequestResolver.START_PATH));
            String verifier = result.getAttribute("code_verifier");
            assertThat(verifier).matches("[A-Za-z0-9._~-]{43,128}");
            assertThat(verifiers.add(verifier)).isTrue();
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            assertThat(result.getAdditionalParameters()).containsEntry("code_challenge_method", "S256")
                    .containsEntry("code_challenge", challenge);
            assertThat(codec.decode(codec.encode(result)).<String>getAttribute("code_verifier")).isEqualTo(verifier);
        }
    }

    @Test
    void resolverRejectsOtherMethodsPathsIndirectOverloadAndCallbackOverrideBeforeDelegate() {
        var repository = spy(new InMemoryClientRegistrationRepository(registration()));
        var resolver = new KakaoAuthorizationRequestResolver(repository, ORIGINS);
        clearInvocations(repository);
        for (String method : List.of("POST", "HEAD", "OPTIONS", "PUT", "DELETE", "PATCH"))
            assertThat(resolver.resolve(request(method, KakaoAuthorizationRequestResolver.START_PATH))).isNull();
        for (String path : List.of("/oauth2/authorization/other", "/oauth2/authorization/kakao/", "/oauth2/authorization/kakao;x", "/oauth2/authorization/%6bakao"))
            assertThat(resolver.resolve(request("GET", path))).isNull();
        assertThat(resolver.resolve(request("GET", KakaoAuthorizationRequestResolver.START_PATH), "kakao")).isNull();
        verifyNoInteractions(repository);
        var wrong = ClientRegistration.withClientRegistration(registration()).redirectUri("{baseUrl}/login/oauth2/code/kakao").build();
        assertThatThrownBy(() -> new KakaoAuthorizationRequestResolver(new InMemoryClientRegistrationRepository(wrong), ORIGINS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Object> acceptedIds() {
        return Stream.of((byte) 1, (short) 12, 123, 999L, BigInteger.valueOf(Long.MAX_VALUE), "9223372036854775807");
    }
    static Stream<Object> rejectedIds() {
        return Stream.of(null, 0, -1L, 1.0, 1.5f, Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1E3"),
                "1.0", "1e3", "+1", " 1", "1 ", "01", "١", "9223372036854775808", BigInteger.ONE.shiftLeft(63),
                new Object() { @Override public String toString() { throw new AssertionError("toString forbidden"); } },
                new Number() {
                    public int intValue() { throw new AssertionError(); } public long longValue() { throw new AssertionError(); }
                    public float floatValue() { throw new AssertionError(); } public double doubleValue() { throw new AssertionError(); }
                });
    }

    @ParameterizedTest(name = "accepted ID #{index}") @MethodSource("acceptedIds")
    void idOnlyResponseReachesProvisioningAndPrincipalUsesInternalId(Object id) {
        var fixture = new UserFixture();
        when(fixture.users.provision(anyLong())).thenReturn(42L);
        fixture.respond(Collections.singletonMap("id", id));
        var principal = (KakaoPrincipal) fixture.service.loadUser(userRequest(registration()));
        long expected = new BigInteger(id.toString()).longValueExact();
        verify(fixture.users).provision(expected);
        assertThat(principal.internalUserId()).isEqualTo(42);
        assertThat(principal.kakaoUserId()).isEqualTo(expected);
        assertThat(principal.getName()).isEqualTo("42");
        assertThatThrownBy(() -> principal.getAttributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "rejected ID #{index}") @MethodSource("rejectedIds")
    void invalidIdFailsBeforeSpringUserConstructionAndRetryIsPossible(Object id) {
        var fixture = new UserFixture();
        fixture.respond(Collections.singletonMap("id", id));
        assertThatThrownBy(() -> fixture.service.loadUser(userRequest(registration())))
                .isInstanceOf(OAuth2AuthenticationException.class).hasMessage("Invalid Kakao identity");
        verifyNoInteractions(fixture.users);
        fixture.respond(Map.of("id", 123L));
        when(fixture.users.provision(123L)).thenReturn(42L);
        assertThat(fixture.service.loadUser(userRequest(registration())).getName()).isEqualTo("42");
    }

    @Test
    void missingIdAndWrongRegistrationDoNotProvisionOrCallForbiddenHttp() {
        var fixture = new UserFixture();
        fixture.respond(Map.of("unrelated", true));
        assertThatThrownBy(() -> fixture.service.loadUser(userRequest(registration()))).isInstanceOf(OAuth2AuthenticationException.class);
        clearInvocations(fixture.http);
        var other = ClientRegistration.withClientRegistration(registration()).registrationId("other").build();
        assertThatThrownBy(() -> fixture.service.loadUser(userRequest(other))).isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(fixture.http, fixture.users);
    }

    @Test
    void delegateFailuresRetainMeaningAndOnlyProvisioningRuntimeIsMapped() {
        var delegate = mock(DefaultOAuth2UserService.class);
        var users = mock(UserProvisioningService.class);
        var service = new KakaoOAuth2UserService(delegate, users);
        var request = userRequest(registration());
        var authFailure = new OAuth2AuthenticationException(new OAuth2Error("provider_failure"));
        when(delegate.loadUser(request)).thenThrow(authFailure);
        assertThatThrownBy(() -> service.loadUser(request)).isSameAs(authFailure);
        var programming = new IllegalStateException("delegate configuration failure");
        doThrow(programming).when(delegate).loadUser(request);
        assertThatThrownBy(() -> service.loadUser(request)).isSameAs(programming);
        var fixture = new UserFixture();
        fixture.respond(Map.of("id", 123));
        when(fixture.users.provision(123)).thenThrow(programming);
        assertThatThrownBy(() -> fixture.service.loadUser(request)).isInstanceOf(InternalAuthenticationServiceException.class)
                .hasMessage("User provisioning failed").hasNoCause();
        var fatal = new AssertionError("fatal");
        doThrow(fatal).when(fixture.users).provision(123);
        assertThatThrownBy(() -> fixture.service.loadUser(request)).isSameAs(fatal);
    }

    private static OAuth2UserRequest userRequest(ClientRegistration registration) {
        return new OAuth2UserRequest(registration, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "fake-provider-access", NOW, NOW.plusSeconds(300)));
    }
    private static final class UserFixture {
        final RestOperations http = mock(RestOperations.class);
        final UserProvisioningService users = mock(UserProvisioningService.class);
        final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        final KakaoOAuth2UserService service;
        UserFixture() { delegate.setRestOperations(http); service = new KakaoOAuth2UserService(delegate, users); }
        void respond(Map<String, Object> attributes) {
            when(http.exchange(any(RequestEntity.class), any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(attributes));
        }
    }
}
