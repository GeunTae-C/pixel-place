package dev.cgt.pixelplace.auth.oauth2;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.web.*;
import dev.cgt.pixelplace.user.application.UserProvisioningService;
import dev.cgt.pixelplace.user.infra.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;
import static dev.cgt.pixelplace.auth.oauth2.LoginTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 명시적 test chain에서 실제 로그인→provisioning→handoff→Access 연결과 session/no-save 검증 */
class OAuthStatelessWiringTest {
    @Test
    void assembledChainUsesInternalIdEndToEndAndNeverRestoresServerLoginState() {
        new WebApplicationContextRunner().withPropertyValues("spring.profiles.active=b2-login-fixture")
                .withUserConfiguration(Fixture.class).run(context -> {
            var a = context.getBean(Assembly.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var entity = new UserEntity(987654321L);
            ReflectionTestUtils.setField(entity, "id", 42L);
            when(a.users.findByKakaoUserId(987654321L)).thenReturn(Optional.of(entity));
            var begin = mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH)).andExpect(status().isFound())
                    .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn();
            var authCookie = begin.getResponse().getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME);
            var authRequest = a.codec.decode(authCookie.getValue());
            a.tokenHttp.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                            .formDataContains(Map.of("code_verifier", authRequest.getAttribute("code_verifier"), "client_secret", "dummy-secret")))
                    .andRespond(withSuccess("{\"access_token\":\"provider-token-sentinel\",\"refresh_token\":\"provider-refresh-sentinel\",\"token_type\":\"Bearer\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
            a.userHttp.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                    .andRespond(withSuccess("{\"id\":987654321}", MediaType.APPLICATION_JSON));
            var login = mvc.perform(get(CookieAuthorizationRequestRepository.COOKIE_PATH).cookie(authCookie)
                            .param("code", "incoming-code-sentinel").param("state", authRequest.getState()).header("X-User-Id", "999"))
                    .andExpect(redirectedUrl(ORIGINS.frontendAuthCallbackUri().toString())).andReturn();
            var live = Arrays.stream(login.getResponse().getCookies())
                    .filter(c -> c.getName().equals(HandoffCookie.NAME) && c.getMaxAge() > 0).findFirst().orElseThrow();
            assertThat(a.tokens.handoffDecoder().decode(live.getValue()).getSubject()).isEqualTo("42");
            var exchanged = mvc.perform(post(HandoffCookie.PATH).header(HttpHeaders.ORIGIN, "http://localhost:3000").cookie(live))
                    .andExpect(status().isOk()).andReturn();
            var body = JsonMapper.builder().build().readTree(exchanged.getResponse().getContentAsString());
            assertThat(a.tokens.accessDecoder().decode(body.path("accessToken").stringValue()).getSubject()).isEqualTo("42");
            verify(a.users).findByKakaoUserId(987654321L);
            verify(a.clients).saveAuthorizedClient(any(), any(), any(), any());
            var failure = mvc.perform(get(CookieAuthorizationRequestRepository.COOKIE_PATH).param("code", "incoming-code-sentinel").param("state", "incoming-state-sentinel"))
                    .andExpect(redirectedUrl(ORIGINS.frontendAuthCallbackUri() + "?error=oauth_login_failed")).andReturn();
            // 로그인 성공 뒤 cookie만 재제출해도 서버 인증 복원·saved request 생성 없음
            var protectedApi = mvc.perform(get("/fixture/protected").cookie(live, authCookie)).andExpect(status().isUnauthorized()).andReturn();
            for (var result : List.of(begin, login, exchanged, failure, protectedApi)) {
                assertThat(result.getRequest().getSession(false)).isNull();
                assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith("JSESSIONID="));
            }
            a.tokenHttp.verify(); a.userHttp.verify();
        });
    }

    /** C의 production 연결을 앞당기지 않는 test-only 조립. 실제 HTTP endpoint·Origin guard 확대 없음 */
    @TestConfiguration(proxyBeanMethods = false) @EnableWebSecurity @EnableWebMvc
    @org.springframework.context.annotation.Profile("b2-login-fixture")
    static class Fixture {
        @Bean Assembly assembly() { return new Assembly(); }
        @Bean Endpoint endpoint(Assembly a) { return new Endpoint(a.controller); }
        @Bean SecurityFilterChain chain(HttpSecurity http, Assembly a) throws Exception {
            http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .securityContext(s -> s.securityContextRepository(new NullSecurityContextRepository()))
                    .requestCache(c -> c.requestCache(new NullRequestCache()))
                    .authorizeHttpRequests(r -> r.requestMatchers("/fixture/protected").authenticated().anyRequest().permitAll())
                    .exceptionHandling(e -> e.authenticationEntryPoint((request, response, failure) -> response.setStatus(401)))
                    .oauth2Login(o -> o.clientRegistrationRepository(a.registrations).authorizedClientRepository(a.clients)
                            .authorizationEndpoint(e -> e.authorizationRequestRepository(a.requests).authorizationRequestResolver(a.resolver))
                            .tokenEndpoint(e -> e.accessTokenResponseClient(a.tokenClient)).userInfoEndpoint(e -> e.userService(a.userService))
                            .successHandler(a.success).failureHandler(a.failure)
                            .withObjectPostProcessor(new OAuthStartFilterConfigurer(a.failure)));
            return http.build();
        }
    }
    /** test-only route adapter. production controller를 component로 등록하지 않음 */
    @RestController @org.springframework.context.annotation.Profile("b2-login-fixture")
    static class Endpoint {
        private final HandoffTokenController controller;
        Endpoint(HandoffTokenController controller) { this.controller = controller; }
        @PostMapping(HandoffCookie.PATH) void exchange(HttpServletRequest request, HttpServletResponse response) throws IOException {
            controller.exchange(request, response);
        }
    }
    /** 실제 암호화·JWT·Spring HTTP client 사용, users repository와 provider HTTP만 대체 */
    static class Assembly {
        final ServiceJwtTokens tokens = new ServiceJwtTokens(PROPERTIES, CLOCK);
        final AuthorizationRequestCookieCodec codec = new AuthorizationRequestCookieCodec(PROPERTIES, ORIGINS, CLOCK);
        final CookieAuthorizationRequestRepository requests = new CookieAuthorizationRequestRepository(codec, PROPERTIES, ORIGINS);
        final HandoffCookie handoff = new HandoffCookie(PROPERTIES, ORIGINS);
        final OAuthLoginFailureHandler failure = new OAuthLoginFailureHandler(requests, handoff, ORIGINS);
        final OAuthLoginSuccessHandler success = new OAuthLoginSuccessHandler(tokens, requests, handoff, failure, ORIGINS);
        final NoOpAuthorizedClientRepository clients = spy(new NoOpAuthorizedClientRepository());
        final InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration());
        final KakaoAuthorizationRequestResolver resolver = new KakaoAuthorizationRequestResolver(registrations, ORIGINS);
        final UserJpaRepository users = mock(UserJpaRepository.class);
        final RestClientAuthorizationCodeTokenResponseClient tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
        final MockRestServiceServer tokenHttp;
        final MockRestServiceServer userHttp;
        final KakaoOAuth2UserService userService;
        final HandoffTokenController controller = new HandoffTokenController(ORIGINS, handoff,
                new HandoffExchangeService(tokens, CLOCK), JsonMapper.builder().build());
        Assembly() {
            var rest = RestClient.builder().messageConverters(converters -> {
                converters.clear(); converters.add(new org.springframework.http.converter.FormHttpMessageConverter());
                converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
            }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
            tokenHttp = MockRestServiceServer.bindTo(rest).build(); tokenClient.setRestClient(rest.build());
            var userRest = new RestTemplate(); userRest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
            userHttp = MockRestServiceServer.bindTo(userRest).build();
            var delegate = new DefaultOAuth2UserService(); delegate.setRestOperations(userRest);
            userService = new KakaoOAuth2UserService(delegate, new UserProvisioningService(users));
        }
    }
}
