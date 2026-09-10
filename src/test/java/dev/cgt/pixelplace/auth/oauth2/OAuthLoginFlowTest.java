package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.user.application.UserProvisioningService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationProvider;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.time.*;
import java.util.*;

import static dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 Spring 시작/callback 필터·token HTTP·userinfo 연결. provider HTTP만 대역, B2 production 등록 없음 */
class OAuthLoginFlowTest {
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    private final ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ServiceJwtTokens tokens = spy(new ServiceJwtTokens(PROPERTIES, CLOCK));
    private final AuthorizationRequestCookieCodec codec = new AuthorizationRequestCookieCodec(PROPERTIES, ORIGINS, CLOCK);
    private final CookieAuthorizationRequestRepository requests = spy(new CookieAuthorizationRequestRepository(codec, PROPERTIES, ORIGINS));
    private final HandoffCookie handoff = spy(new HandoffCookie(PROPERTIES, ORIGINS));
    private final OAuthLoginFailureHandler failure = spy(new OAuthLoginFailureHandler(requests, handoff, ORIGINS));
    private final OAuthLoginSuccessHandler success = new OAuthLoginSuccessHandler(tokens, requests, handoff, failure, ORIGINS);
    private final InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration());
    private final KakaoAuthorizationRequestResolver resolver = new KakaoAuthorizationRequestResolver(registrations, ORIGINS);
    private final NoOpAuthorizedClientRepository clients = spy(new NoOpAuthorizedClientRepository());
    private final UserProvisioningService users = mock(UserProvisioningService.class);
    private final RestClientAuthorizationCodeTokenResponseClient tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
    private final MockRestServiceServer tokenHttp;
    private final MockRestServiceServer userHttp;
    private final OAuth2LoginAuthenticationFilter callback;

    OAuthLoginFlowTest() {
        var rest = RestClient.builder().messageConverters(converters -> {
            // 일반 JSON mapper보다 OAuth 응답 converter가 먼저 처리해야 token 응답 의미가 보존됨
            converters.clear();
            converters.add(new org.springframework.http.converter.FormHttpMessageConverter());
            converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
        }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
        tokenHttp = MockRestServiceServer.bindTo(rest).build();
        tokenClient.setRestClient(rest.build());
        var userRest = new RestTemplate();
        userRest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        userHttp = MockRestServiceServer.bindTo(userRest).build();
        var delegate = new DefaultOAuth2UserService();
        delegate.setRestOperations(userRest);
        var provider = new OAuth2LoginAuthenticationProvider(tokenClient, new KakaoOAuth2UserService(delegate, users));
        callback = new OAuth2LoginAuthenticationFilter(registrations, clients, CookieAuthorizationRequestRepository.COOKIE_PATH);
        callback.setAuthenticationManager(new ProviderManager(provider));
        callback.setAuthorizationRequestRepository(requests);
        callback.setSecurityContextRepository(new NullSecurityContextRepository());
        callback.setSessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
        callback.setAuthenticationSuccessHandler(success);
        callback.setAuthenticationFailureHandler(failure);
        // C의 production guard를 구현한 것으로 보지 않음. fixture callback 자체의 exact GET matcher
        callback.setRequiresAuthenticationRequestMatcher(r -> "GET".equals(r.getMethod())
                && CookieAuthorizationRequestRepository.COOKIE_PATH.equals(r.getRequestURI()));
    }

    @BeforeEach void captureLogs() { logs.start(); rootLogger.addAppender(logs); }
    @AfterEach void clearContextAndCheckNoSensitiveLogEcho() {
        rootLogger.detachAppender(logs); logs.stop(); SecurityContextHolder.clearContext();
        // Spring 자체 오류 로그의 cause도 검사. 실패 assertion에 원문 로그 전체 출력 금지
        boolean leaked = logs.list.stream().anyMatch(event -> {
            String text = event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                    : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
            return List.of("private-provider-sentinel", "incoming-code-sentinel", "incoming-state-sentinel",
                    "incoming-error-sentinel", "incoming-description-sentinel", "fake-provider-access", "fake-provider-refresh")
                    .stream().anyMatch(text::contains);
        });
        assertThat(leaked).as("sensitive sentinel echoed to application logs").isFalse();
    }

    @Test
    void fullSpringFlowRestoresPkceSendsItToTokenHttpAndPublishesOnlyInternalHandoff() throws Exception {
        var startRequest = request("GET", KakaoAuthorizationRequestResolver.START_PATH);
        var startResponse = new MockHttpServletResponse();
        start(resolver).doFilter(startRequest, startResponse, new MockFilterChain());
        assertThat(startResponse.getStatus()).isEqualTo(302);
        assertThat(startResponse.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        var cookie = startResponse.getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME);
        var authorization = codec.decode(cookie.getValue());
        expectProvider(authorization.getAttribute("code_verifier"));
        when(users.provision(987654321)).thenReturn(42L);
        var request = callbackRequest(cookie, authorization.getState());
        var response = new MockHttpServletResponse();
        callback.doFilter(request, response, new MockFilterChain());
        assertThat(response.getRedirectedUrl()).isEqualTo(ORIGINS.frontendAuthCallbackUri().toString());
        var live = liveHandoff(response);
        assertThat(tokens.handoffDecoder().decode(live.getValue()).getSubject()).isEqualTo("42");
        assertThat(tokens.handoffDecoder().decode(live.getValue()).getClaimAsString("token_use")).isEqualTo("handoff");
        verify(tokens).issueHandoff(42);
        verify(tokens, never()).issueAccess(anyLong());
        verify(clients).saveAuthorizedClient(any(), any(), same(request), same(response));
        assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("kakao", null, request)).isNull();
        assertClean(request, response, false);
        assertClean(startRequest, startResponse, false);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(CookieAuthorizationRequestRepository.COOKIE_NAME + "=") && h.contains("Max-Age=0")))
                .hasSize(2).allMatch(h -> h.contains("Path=/login/oauth2/code/kakao") && h.contains("HttpOnly") && h.contains("SameSite=Lax"));
        tokenHttp.verify(); userHttp.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "tamper", "malformed", "expired", "pkce-length", "pkce-missing", "pkce-characters", "pkce-mismatch", "pkce-plain", "pkce-challenge-missing"})
    void cookieFailureThroughActualCallbackCleansBothAndNeverCallsProvider(String kind) throws Exception {
        var authorization = resolver.resolve(request("GET", KakaoAuthorizationRequestResolver.START_PATH));
        String value = codec.encode(authorization);
        if (kind.equals("tamper")) value = value.substring(0, 10) + (value.charAt(10) == 'A' ? 'B' : 'A') + value.substring(11);
        if (kind.equals("malformed")) value = "v1.!";
        if (kind.equals("expired")) value = new AuthorizationRequestCookieCodec(PROPERTIES, ORIGINS,
                Clock.fixed(NOW.minusSeconds(180), ZoneOffset.UTC)).encode(authorization);
        if (kind.startsWith("pkce-")) value = invalidPkceCookie(authorization, kind);
        var request = callbackRequest(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, value), authorization.getState());
        if (kind.equals("missing")) request.setCookies();
        var response = new MockHttpServletResponse();
        callback.doFilter(request, response, new MockFilterChain());
        assertFailure(request, response);
        verifyNoInteractions(users);
        verify(tokens, never()).issueHandoff(anyLong());
        tokenHttp.verify(); userHttp.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "mismatch", "provider-error"})
    void callbackStateAndProviderErrorNeverReachCodeExchangeOrEchoIncomingValues(String kind) throws Exception {
        var authorization = resolver.resolve(request("GET", KakaoAuthorizationRequestResolver.START_PATH));
        var request = callbackRequest(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, codec.encode(authorization)),
                kind.equals("mismatch") ? "incoming-state-sentinel" : authorization.getState());
        if (kind.equals("missing")) request.removeParameter("state");
        if (kind.equals("provider-error")) {
            request.removeParameter("code"); request.setParameter("error", "incoming-error-sentinel");
            request.setParameter("error_description", "incoming-description-sentinel");
        }
        var response = new MockHttpServletResponse();
        callback.doFilter(request, response, new MockFilterChain());
        assertFailure(request, response);
        verifyNoInteractions(users);
        tokenHttp.verify(); userHttp.verify();
    }

    @Test
    void provisioningRuntimeInActualCallbackUsesFailureHandlerWithoutLiveCookie() throws Exception {
        var authorization = resolver.resolve(request("GET", KakaoAuthorizationRequestResolver.START_PATH));
        expectProvider(authorization.getAttribute("code_verifier"));
        when(users.provision(987654321)).thenThrow(new IllegalStateException("private-provider-sentinel"));
        var request = callbackRequest(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, codec.encode(authorization)), authorization.getState());
        var response = new MockHttpServletResponse();
        callback.doFilter(request, response, new MockFilterChain());
        assertFailure(request, response);
        tokenHttp.verify(); userHttp.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"token", "cookie", "principal"})
    void successPreparationFailureDirectlyDelegatesAndPublishesNoLiveCookie(String kind) throws Exception {
        if (kind.equals("token")) doThrow(new IllegalStateException("private-token-sentinel")).when(tokens).issueHandoff(42);
        if (kind.equals("cookie")) doThrow(new IllegalArgumentException("private-cookie-sentinel")).when(handoff).prepare(anyString());
        var auth = kind.equals("principal") ? new TestingAuthenticationToken("external", null, "OAUTH2_USER") : authentication();
        var request = request("GET", CookieAuthorizationRequestRepository.COOKIE_PATH);
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(auth);
        success.onAuthenticationSuccess(request, response, auth);
        verify(failure).onAuthenticationFailure(same(request), same(response), any());
        assertFailure(request, response);
    }

    @ParameterizedTest @ValueSource(strings = {"resolver", "cookie"})
    void authorizationStartFailureUsesConfiguredHandlerAndNoRequestCache(String kind) throws Exception {
        OAuth2AuthorizationRequestResolver selected = resolver;
        if (kind.equals("resolver")) {
            selected = mock(OAuth2AuthorizationRequestResolver.class);
            when(selected.resolve(any())).thenThrow(new IllegalStateException("start-private-sentinel"));
        } else doThrow(new IllegalStateException("cookie-private-sentinel")).when(requests).saveAuthorizationRequest(any(), any(), any());
        var request = request("GET", KakaoAuthorizationRequestResolver.START_PATH);
        var response = new MockHttpServletResponse();
        start(selected).doFilter(request, response, new MockFilterChain());
        assertFailure(request, response);
    }

    @Test
    void committedSuccessTransportFailurePropagatesOriginalWithoutRetryAndErrorsAreNotSwallowed() throws Exception {
        var transport = new IOException("transport-sentinel");
        var response = new MockHttpServletResponse() {
            @Override public void sendRedirect(String location) throws IOException { setCommitted(true); throw transport; }
        };
        assertThatThrownBy(() -> success.onAuthenticationSuccess(request("GET", "/callback"), response, authentication())).isSameAs(transport);
        verifyNoInteractions(failure);
        verify(requests, times(1)).deleteAuthorizationRequest(response);
        verify(handoff, times(1)).delete(response);
        var fatal = new AssertionError("fatal");
        doThrow(fatal).when(tokens).issueHandoff(42);
        assertThatThrownBy(() -> success.onAuthenticationSuccess(request("GET", "/callback"), new MockHttpServletResponse(), authentication())).isSameAs(fatal);
        verifyNoInteractions(failure);
    }

    @Test
    void committedStartTransportFailureDoesNotRedirectOrCleanAgain() {
        var transport = new IOException("transport-sentinel");
        var response = new MockHttpServletResponse() {
            @Override public void sendRedirect(String location) throws IOException { setCommitted(true); throw transport; }
        };
        assertThatThrownBy(() -> start(resolver).doFilter(request("GET", KakaoAuthorizationRequestResolver.START_PATH), response, new MockFilterChain()))
                .isSameAs(transport);
        verify(requests, never()).deleteAuthorizationRequest(any()); verify(handoff, never()).delete(any());
    }

    @Test
    void disallowedStartAndCallbackMethodsNeverTouchRequestRepository() throws Exception {
        clearInvocations(requests);
        for (String method : List.of("POST", "HEAD", "OPTIONS", "PUT", "DELETE", "PATCH")) {
            start(resolver).doFilter(request(method, KakaoAuthorizationRequestResolver.START_PATH), new MockHttpServletResponse(), new MockFilterChain());
            callback.doFilter(request(method, CookieAuthorizationRequestRepository.COOKIE_PATH), new MockHttpServletResponse(), new MockFilterChain());
        }
        verifyNoInteractions(requests, users);
    }

    @Test
    void indirectClientAuthorizationFailureDoesNotCreateNewStateCookieOrSavedRequest() throws Exception {
        var request = request("GET", "/fixture/indirect");
        var response = new MockHttpServletResponse();
        start(resolver).doFilter(request, response, (req, res) -> {
            throw new org.springframework.security.oauth2.client.ClientAuthorizationRequiredException("kakao");
        });
        assertFailure(request, response);
        verify(requests, never()).saveAuthorizationRequest(any(), any(), any());
        verifyNoInteractions(users);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).allMatch(h -> h.contains("Max-Age=0"));
        tokenHttp.verify(); userHttp.verify();
    }

    @Test
    void noOpRepositoryDoesNotRetainProviderTokensOrCreateSession() {
        var request = request("GET", "/"); var response = new MockHttpServletResponse();
        var client = new OAuth2AuthorizedClient(registration(), "42", new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "fake-access", NOW, NOW.plusSeconds(60)), new OAuth2RefreshToken("fake-refresh", NOW));
        clients.saveAuthorizedClient(client, authentication(), request, response);
        assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("kakao", authentication(), request)).isNull();
        clients.removeAuthorizedClient("kakao", authentication(), request, response);
        assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("kakao", authentication(), request)).isNull();
        assertClean(request, response, false);
        assertThat(NoOpAuthorizedClientRepository.class.getDeclaredFields()).isEmpty();
    }

    private OAuth2AuthorizationRequestRedirectFilter start(OAuth2AuthorizationRequestResolver selected) {
        var filter = new OAuth2AuthorizationRequestRedirectFilter(selected);
        filter.setAuthorizationRequestRepository(requests);
        return new OAuthStartFilterConfigurer(failure).postProcess(filter);
    }
    private void expectProvider(String verifier) {
        tokenHttp.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("code_verifier", verifier, "client_secret", "dummy-secret", "code", "incoming-code-sentinel")))
                .andRespond(withSuccess("{\"access_token\":\"fake-provider-access\",\"refresh_token\":\"fake-provider-refresh\",\"token_type\":\"Bearer\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        userHttp.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-provider-access"))
                .andRespond(withSuccess("{\"id\":987654321}", MediaType.APPLICATION_JSON));
    }
    private static MockHttpServletRequest callbackRequest(Cookie cookie, String state) {
        var request = request("GET", CookieAuthorizationRequestRepository.COOKIE_PATH);
        request.setCookies(cookie); request.setParameter("code", "incoming-code-sentinel"); request.setParameter("state", state);
        return request;
    }
    private static TestingAuthenticationToken authentication() { return new TestingAuthenticationToken(new KakaoPrincipal(42, 987654321), null, "OAUTH2_USER"); }
    private static Cookie liveHandoff(MockHttpServletResponse response) {
        return Arrays.stream(response.getCookies()).filter(c -> c.getName().equals(HandoffCookie.NAME) && c.getMaxAge() > 0).findFirst().orElseThrow();
    }
    private static void assertFailure(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(ORIGINS.frontendAuthCallbackUri() + "?error=oauth_login_failed");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).anyMatch(h -> h.startsWith(HandoffCookie.NAME + "=;") && h.contains("Max-Age=0"))
                .anyMatch(h -> h.startsWith(CookieAuthorizationRequestRepository.COOKIE_NAME + "=;") && h.contains("Max-Age=0"));
        assertClean(request, response, true);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    private static void assertClean(MockHttpServletRequest request, MockHttpServletResponse response, boolean noLive) {
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith("JSESSIONID="));
        if (noLive) assertThat(Arrays.stream(response.getCookies()).filter(c -> c.getName().equals(HandoffCookie.NAME)))
                .allMatch(c -> c.getMaxAge() == 0);
    }
    private String invalidPkceCookie(org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest authorization, String kind) throws Exception {
        // 인증된 envelope를 잘못된 PKCE로 구성하여 GCM 실패와 복원 실패를 구분
        var json = new AuthorizationRequestJson();
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(json.write(authorization, NOW.plusSeconds(180)));
        // field 이름은 B1의 실제 envelope를 사용
        var attributes = (tools.jackson.databind.node.ObjectNode) tree.path("request").get("attributes");
        var parameters = (tools.jackson.databind.node.ObjectNode) tree.path("request").get("additionalParameters");
        switch (kind) {
            case "pkce-length" -> attributes.put("code_verifier", "a".repeat(42));
            case "pkce-missing" -> attributes.remove("code_verifier");
            case "pkce-characters" -> attributes.put("code_verifier", "!".repeat(43));
            case "pkce-mismatch" -> attributes.put("code_verifier", "a".repeat(43));
            case "pkce-plain" -> parameters.put("code_challenge_method", "plain");
            case "pkce-challenge-missing" -> parameters.remove("code_challenge");
            default -> throw new AssertionError("Unknown fixture");
        }
        byte[] nonce = new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
        var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, PROPERTIES.oauthCookieKey(), new javax.crypto.spec.GCMParameterSpec(128, nonce));
        cipher.updateAAD(AuthorizationRequestCookieCodec.AAD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(tree));
        return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(java.nio.ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
    }
}
