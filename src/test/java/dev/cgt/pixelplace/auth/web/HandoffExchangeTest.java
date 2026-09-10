package dev.cgt.pixelplace.auth.web;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.HandoffCookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

import static dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 B1 서명·목적별 decoder와 교환 HTTP mapping 검증. C의 CORS/Resource Server 선행 거부는 별도 범위 */
class HandoffExchangeTest {
    private final MutableClock clock = new MutableClock();
    private final ServiceJwtTokens tokens = spy(new ServiceJwtTokens(PROPERTIES, clock));
    private final HandoffCookie cookie = spy(new HandoffCookie(PROPERTIES, ORIGINS));
    private final HandoffExchangeService exchange = spy(new HandoffExchangeService(tokens, clock));
    private final HandoffTokenController controller = new HandoffTokenController(ORIGINS, cookie, exchange, JsonMapper.builder().build());

    @Test
    void standalonePostReturnsOneNewInternalAccessWithExactFieldsAndDeletion() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Endpoint(controller)).build();
        String value = tokens.issueHandoff(42).getTokenValue();
        var response = mvc.perform(post(HandoffCookie.PATH).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .cookie(new Cookie(HandoffCookie.NAME, value)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.*").isArray()).andReturn().getResponse();
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(tokens.accessDecoder().decode(body.path("accessToken").stringValue()).getSubject()).isEqualTo("42");
        verify(tokens).issueAccess(42);
        assertHeadersAndDeletion(response);
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "null", "http://localhost:3000/", "https://localhost:3000", "http://evil.example", "http://localhost:3000,http://evil.example", "duplicate"})
    void untrustedOrMultipleOriginStopsBeforeCookieAndTokenHandling(String origin) throws Exception {
        var request = request("POST", HandoffCookie.PATH);
        request.setCookies(new Cookie(HandoffCookie.NAME, tokens.issueHandoff(42).getTokenValue()));
        if (!origin.equals("absent")) request.addHeader(HttpHeaders.ORIGIN, origin.equals("duplicate") ? "http://localhost:3000" : origin);
        if (origin.equals("duplicate")) request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
        clearInvocations(tokens);
        var response = new MockHttpServletResponse();
        controller.exchange(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"forbidden_origin\"}");
        verifyNoInteractions(cookie, exchange, tokens);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "malformed", "tampered", "expired", "access", "duplicate", "null-first"})
    void unauthenticatedHandoffDeletesCookieAndReturns401WithoutIssuingOrBearerChallenge(String kind) throws Exception {
        String value = tokens.issueHandoff(42).getTokenValue();
        var request = trusted(value);
        if (kind.equals("missing")) request.setCookies();
        if (kind.equals("malformed")) request.setCookies(new Cookie(HandoffCookie.NAME, "broken"));
        if (kind.equals("tampered")) request.setCookies(new Cookie(HandoffCookie.NAME, "x" + value));
        if (kind.equals("expired")) clock.now = NOW.plusSeconds(60);
        if (kind.equals("access")) request.setCookies(new Cookie(HandoffCookie.NAME, tokens.issueAccess(42).getTokenValue()));
        if (kind.equals("duplicate") || kind.equals("null-first")) request.setCookies(
                new Cookie(HandoffCookie.NAME, kind.equals("null-first") ? null : value), new Cookie(HandoffCookie.NAME, value));
        clearInvocations(tokens);
        var response = new MockHttpServletResponse();
        controller.exchange(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"invalid_handoff\"}");
        assertHeadersAndDeletion(response);
        verify(tokens, never()).issueAccess(anyLong());
    }

    @ParameterizedTest @ValueSource(longs = {0, 1200, 899000, 900000, -1000})
    void remainingSecondsReflectActualExpiryAndClockChangesWithoutFixedTtlFallback(long delayMillis) throws Exception {
        var request = trusted(tokens.issueHandoff(42).getTokenValue());
        doAnswer(invocation -> {
            var access = (Jwt) invocation.callRealMethod();
            clock.now = clock.now.plusMillis(delayMillis);
            return access;
        }).when(tokens).issueAccess(42);
        var response = new MockHttpServletResponse();
        controller.exchange(request, response);
        long expected = Duration.between(NOW.plusMillis(delayMillis), NOW.plusSeconds(900)).getSeconds();
        if (expected < 1 || expected > 900) {
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"token_exchange_failed\"}");
        } else {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(JsonMapper.builder().build().readTree(response.getContentAsString()).path("expiresInSeconds").longValue()).isEqualTo(expected);
        }
        assertHeadersAndDeletion(response);
    }

    @Test
    void fractionalIssueTimeUsesRemainingWholeSeconds() throws Exception {
        clock.now = NOW.plusNanos(123456789);
        var response = new MockHttpServletResponse();
        controller.exchange(trusted(tokens.issueHandoff(42).getTokenValue()), response);
        assertThat(JsonMapper.builder().build().readTree(response.getContentAsString()).path("expiresInSeconds").longValue()).isEqualTo(899);
    }

    @Test
    void validHandoffThenEncoderFailureIs500AndErrorPropagates() throws Exception {
        var request = trusted(tokens.issueHandoff(42).getTokenValue());
        doThrow(new JwtEncodingException("private-encoder-sentinel")).when(tokens).issueAccess(42);
        var response = new MockHttpServletResponse();
        controller.exchange(request, response);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"token_exchange_failed\"}");
        assertHeadersAndDeletion(response);
        var fatal = new AssertionError("fatal");
        doThrow(fatal).when(tokens).issueAccess(42);
        assertThatThrownBy(() -> controller.exchange(request, new MockHttpServletResponse())).isSameAs(fatal);
    }

    @Test
    void copiedValidHandoffCanBeExchangedAgainDuringItsLifetimeWithoutServerSession() throws Exception {
        var request = trusted(tokens.issueHandoff(42).getTokenValue());
        for (int i = 0; i < 2; i++) {
            var response = new MockHttpServletResponse(); controller.exchange(request, response);
            assertThat(response.getStatus()).isEqualTo(200); assertHeadersAndDeletion(response);
        }
        verify(tokens, times(2)).issueAccess(42);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void originCookieAuthenticationIssuanceAndDeletionFollowActualCallOrder() throws Exception {
        var decoder = spy(tokens.handoffDecoder());
        doReturn(decoder).when(tokens).handoffDecoder();
        var controller = new HandoffTokenController(ORIGINS, cookie,
                new HandoffExchangeService(tokens, clock), JsonMapper.builder().build());
        var request = spy(trusted(tokens.issueHandoff(42).getTokenValue()));
        var response = new MockHttpServletResponse();
        controller.exchange(request, response);
        var order = inOrder(request, cookie, decoder, tokens);
        order.verify(request).getHeaders(HttpHeaders.ORIGIN);
        order.verify(cookie).read(request);
        order.verify(decoder).decode(anyString());
        order.verify(tokens).issueAccess(42);
        order.verify(cookie).delete(response);
        assertThat(request.getSession(false)).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"issuer", "audience", "use", "subject", "iat", "exp", "lifetime"})
    void validSignatureWithInvalidHandoffClaimsRemains401BeforeAccessIssuance(String kind) throws Exception {
        var claims = JwtClaimsSet.builder().issuer(PROPERTIES.issuer()).audience(List.of(PROPERTIES.handoffAudience()))
                .subject("42").issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).claim("token_use", "handoff");
        switch (kind) {
            case "issuer" -> claims.issuer("wrong-issuer");
            case "audience" -> claims.audience(List.of("wrong-audience"));
            case "use" -> claims.claim("token_use", "access");
            case "subject" -> claims.subject("042");
            case "iat" -> claims.claims(map -> map.remove("iat"));
            case "exp" -> claims.claims(map -> map.remove("exp"));
            case "lifetime" -> claims.expiresAt(NOW.plusSeconds(61));
        }
        var encoder = NimbusJwtEncoder.withSecretKey(PROPERTIES.jwtSigningKey())
                .algorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        var response = new MockHttpServletResponse();
        controller.exchange(trusted(value), response);
        assertThat(response.getStatus()).isEqualTo(401);
        verify(tokens, never()).issueAccess(anyLong()); assertHeadersAndDeletion(response);
    }

    @Test
    void jsonPreparationFailureReturnsGeneralized500AndDeletesCookie() throws Exception {
        var mapper = spy(JsonMapper.builder().build());
        doThrow(new IllegalStateException("private-json-sentinel")).when(mapper).writeValueAsBytes(any(HandoffExchangeService.AccessResponse.class));
        var controller = new HandoffTokenController(ORIGINS, cookie, exchange, mapper);
        var response = new MockHttpServletResponse();
        controller.exchange(trusted(tokens.issueHandoff(42).getTokenValue()), response);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"token_exchange_failed\"}");
        assertHeadersAndDeletion(response);
    }

    private static MockHttpServletRequest trusted(String token) {
        var request = request("POST", HandoffCookie.PATH);
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
        request.setCookies(new Cookie(HandoffCookie.NAME, token)); return request;
    }
    private static void assertHeadersAndDeletion(MockHttpServletResponse response) {
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("pp_auth_handoff=;", "Max-Age=0", "Path=/api/auth/token", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=", "JSESSIONID");
    }
    /** standalone HTTP route의 소유자는 test fixture. main component scan 대상 아님 */
    @RestController
    @org.springframework.context.annotation.Profile("b2-login-fixture")
    static class Endpoint {
        private final HandoffTokenController controller;
        Endpoint(HandoffTokenController controller) { this.controller = controller; }
        @PostMapping(HandoffCookie.PATH)
        void exchange(HttpServletRequest request, HttpServletResponse response) throws IOException { controller.exchange(request, response); }
    }
    /** 발급·검증·응답 계산이 공유하는 조절 가능한 test Clock */
    private static class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
