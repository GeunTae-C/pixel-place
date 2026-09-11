package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.*;
import dev.cgt.pixelplace.auth.web.OAuthSurfaceGuard;
import dev.cgt.pixelplace.user.infra.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.system.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.*;
import org.springframework.web.filter.CorsFilter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** production chain·실제 cookie/JWT/HTTP client 사용. provider HTTP와 users 저장소만 대체하고 호출 경계 관찰 */
@ExtendWith(OutputCaptureExtension.class)
class ProductionOAuthFlowTest {
    static org.springframework.boot.test.context.runner.WebApplicationContextRunner runner() {
        return ProductionAuthTestSupport.runner().withBean("authObservers",BeanPostProcessor.class,()->new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                // Spring client의 this-bound converter는 원본을 캡처하므로 spy 복사 대신 원본 위임으로 관찰
                if(bean instanceof RestClientAuthorizationCodeTokenResponseClient)
                    return mock(RestClientAuthorizationCodeTokenResponseClient.class,org.mockito.AdditionalAnswers.delegatesTo(bean));
                return bean instanceof CookieAuthorizationRequestRepository || bean instanceof KakaoAuthorizationRequestResolver
                        || bean instanceof KakaoOAuth2UserService || bean instanceof NoOpAuthorizedClientRepository
                        || bean instanceof ServiceJwtTokens || bean instanceof HandoffCookie || bean instanceof OAuthLoginFailureHandler ? spy(bean):bean;
            }
        });
    }

    @Test void surfaceGuardRejectsEveryWrongMethodAndNamespaceBeforeAnyOAuthCollaborator() {
        runner().run(c -> {
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var requests=c.getBean(CookieAuthorizationRequestRepository.class);
            var resolver=c.getBean(KakaoAuthorizationRequestResolver.class);
            var users=c.getBean(KakaoOAuth2UserService.class);
            var tokenClient=c.getBean(RestClientAuthorizationCodeTokenResponseClient.class);
            var filters=c.getBean(SecurityFilterChain.class).getFilters();
            int cors=-1,guard=-1,start=-1,callback=-1;
            for(int i=0;i<filters.size();i++) {
                if(filters.get(i) instanceof CorsFilter) cors=i;
                if(filters.get(i) instanceof OAuthSurfaceGuard) guard=i;
                if(filters.get(i) instanceof OAuth2AuthorizationRequestRedirectFilter) start=i;
                if(filters.get(i) instanceof OAuth2LoginAuthenticationFilter) callback=i;
            }
            assertTrue(cors<guard && guard<start && start<callback);
            clearInvocations(requests,resolver,users,tokenClient);
            for(String path:List.of(KakaoAuthorizationRequestResolver.START_PATH,CookieAuthorizationRequestRepository.COOKIE_PATH)) {
                for(HttpMethod method:List.of(HttpMethod.HEAD,HttpMethod.POST,HttpMethod.PUT,HttpMethod.PATCH,HttpMethod.DELETE,HttpMethod.OPTIONS))
                    mvc.perform(request(method,path).accept(MediaType.TEXT_HTML)).andExpect(status().isMethodNotAllowed())
                            .andExpect(header().string("Allow","GET")).andExpect(header().doesNotExist("Location"))
                            .andExpect(header().doesNotExist("Set-Cookie"));
                for(String wrong:List.of(path+"/",path+"/extra",path.replace("kakao","other")))
                    mvc.perform(get(wrong).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound())
                            .andExpect(header().doesNotExist("Location")).andExpect(header().doesNotExist("Set-Cookie"));
                mvc.perform(options(path).header("Origin","http://localhost:3000")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow","GET"));
                mvc.perform(options(path).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","GET"))
                        .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
                mvc.perform(options(path).header("Origin","http://localhost:8080").header("Access-Control-Request-Method","GET"))
                        .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
                for(String origin:List.of("http://localhost:3000/","http://localhost:8080","null"))
                    mvc.perform(get(path).header("Origin",origin)).andExpect(status().isForbidden())
                            .andExpect(header().doesNotExist("Location")).andExpect(header().doesNotExist("Set-Cookie"))
                            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
                mvc.perform(options(path).header("Origin","http://localhost:3000","http://localhost:3000")
                                .header("Access-Control-Request-Method","GET")).andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist("Location")).andExpect(header().doesNotExist("Set-Cookie"));
            }
            verifyNoInteractions(requests,resolver,users,tokenClient,c.getBean(UserJpaRepository.class));
        });
    }

    @Test void productionLoginUsesRestoredPkceInternalPrincipalAndNoProviderTokenStorage(CapturedOutput output) {
        runner().withUserConfiguration(ProductionPixelBoundaryTest.Fixture.class).run(c -> {
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var codec=c.getBean(AuthorizationRequestCookieCodec.class);
            var callbackFilter=c.getBean(SecurityFilterChain.class).getFilters().stream()
                    .filter(OAuth2LoginAuthenticationFilter.class::isInstance).findFirst().orElseThrow();
            Object manager=ReflectionTestUtils.getField(callbackFilter,"authenticationManager");
            if(!(manager instanceof org.springframework.security.authentication.ProviderManager)) manager=ReflectionTestUtils.getField(manager,"delegate");
            var provider=((org.springframework.security.authentication.ProviderManager)manager).getProviders().stream()
                    .filter(org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationProvider.class::isInstance).findFirst().orElseThrow();
            var codeProvider=ReflectionTestUtils.getField(provider,"authorizationCodeAuthenticationProvider");
            assertSame(c.getBean(RestClientAuthorizationCodeTokenResponseClient.class),ReflectionTestUtils.getField(codeProvider,"accessTokenResponseClient"));
            var users=c.getBean(UserJpaRepository.class);
            var row=new UserEntity(987654321L); ReflectionTestUtils.setField(row,"id",42L);
            when(users.findByKakaoUserId(987654321L)).thenReturn(Optional.of(row));
            var rest=RestClient.builder().messageConverters(converters->{converters.clear();
                converters.add(new org.springframework.http.converter.FormHttpMessageConverter());
                converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());}).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
            var tokenHttp=MockRestServiceServer.bindTo(rest).build();
            c.getBean(RestClientAuthorizationCodeTokenResponseClient.class).setRestClient(rest.build());
            var userRest=new RestTemplate(); userRest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
            var userHttp=MockRestServiceServer.bindTo(userRest).build();
            c.getBean(DefaultOAuth2UserService.class).setRestOperations(userRest);
            var begin=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH).header("Authorization","Bearer invalid"))
                    .andExpect(status().isFound()).andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
            var cookie=begin.getResponse().getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME);
            assertNotNull(cookie); var auth=codec.decode(cookie.getValue());
            var again=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH)).andReturn();
            var other=codec.decode(again.getResponse().getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME).getValue());
            assertFalse(auth.getAttribute("code_verifier").equals(other.getAttribute("code_verifier")));
            tokenHttp.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().formDataContains(Map.of(
                            "code_verifier",auth.getAttribute("code_verifier"),"client_secret","c-test-secret")))
                    .andRespond(withSuccess("{\"access_token\":\"c-provider-sentinel\",\"refresh_token\":\"c-refresh-sentinel\",\"token_type\":\"Bearer\",\"expires_in\":300}",MediaType.APPLICATION_JSON));
            userHttp.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization","Bearer c-provider-sentinel"))
                    .andRespond(withSuccess("{\"id\":987654321}",MediaType.APPLICATION_JSON));
            var login=mvc.perform(get(CookieAuthorizationRequestRepository.COOKIE_PATH).cookie(cookie)
                            .with(request->{request.setServerPort(8080);return request;})
                            .param("code","c-incoming-code-sentinel").param("state",auth.getState()).header("Authorization","Bearer invalid"))
                    .andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
            if(!"http://localhost:3000/".equals(login.getResponse().getRedirectedUrl())) {
                var captured=org.mockito.ArgumentCaptor.forClass(org.springframework.security.core.AuthenticationException.class);
                verify(c.getBean(OAuthLoginFailureHandler.class)).onAuthenticationFailure(any(),any(),captured.capture());
                var error=captured.getValue();
                String causes="";
                for(Throwable cause=error.getCause();cause!=null;cause=cause.getCause()) causes+="/"+cause.getClass().getSimpleName();
                tokenHttp.verify();
                fail("callback failure type="+error.getClass().getSimpleName()+" causes="+causes+
                        (error instanceof OAuth2AuthenticationException oauth?" code="+oauth.getError().getErrorCode():""));
            }
            var live=Arrays.stream(login.getResponse().getCookies()).filter(x->x.getName().equals(HandoffCookie.NAME)&&x.getMaxAge()>0).findFirst().orElseThrow();
            assertEquals("42",c.getBean(ServiceJwtTokens.class).handoffDecoder().decode(live.getValue()).getSubject());
            var exchanged=mvc.perform(post(HandoffCookie.PATH).header("Origin","http://localhost:3000").cookie(live)).andExpect(status().isOk()).andReturn();
            var access=c.getBean(tools.jackson.databind.json.JsonMapper.class).readTree(exchanged.getResponse().getContentAsString()).path("accessToken").stringValue();
            assertEquals("42",c.getBean(ServiceJwtTokens.class).accessDecoder().decode(access).getSubject());
            var pixel=mvc.perform(post("/api/pixels").header("Authorization","Bearer "+access).header("X-User-Id","999")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}")).andExpect(status().isOk()).andReturn();
            var a=c.getBean(ProductionPixelBoundaryTest.Assembly.class);
            verify(a.command).writePixel(42,1,2,3);verify(a.core).writePixel(42,1,2,3);
            var wal=org.mockito.ArgumentCaptor.forClass(dev.cgt.pixelplace.wal.domain.WalRecord.class);
            verify(a.wal).appendAndFsync(wal.capture());assertEquals(42,wal.getValue().userId());
            verify(a.redis).getExpire("cooldown:user:42",java.util.concurrent.TimeUnit.MILLISECONDS);
            verify(users).findByKakaoUserId(987654321L);
            verify(c.getBean(NoOpAuthorizedClientRepository.class)).saveAuthorizedClient(any(),any(),any(),any());
            assertNull(c.getBean(OAuth2AuthorizedClientService.class).loadAuthorizedClient("kakao","42"));
            for(var result:List.of(begin,again,login,exchanged,pixel)) {
                assertNull(result.getRequest().getSession(false));
                assertTrue(result.getResponse().getHeaders("Set-Cookie").stream().noneMatch(h->h.startsWith("JSESSIONID=")));
            }
            tokenHttp.verify(); userHttp.verify();
        });
        for(String sentinel:List.of("c-provider-sentinel","c-refresh-sentinel","c-incoming-code-sentinel"))
            assertFalse(output.getAll().contains(sentinel),"incoming/provider data must not appear in application logs");
    }

    @Test void productionCallbackAndStartFailuresCleanCookiesWithoutTokenRequestOrSession(CapturedOutput output) {
        runner().run(c -> {
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var start=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH)).andReturn();
            var good=start.getResponse().getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME);
            var tokenClient=c.getBean(RestClientAuthorizationCodeTokenResponseClient.class);
            var users=c.getBean(KakaoOAuth2UserService.class);
            for(var cookie:List.of(new jakarta.servlet.http.Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME,"v1.invalid"),good)) {
                var failed=mvc.perform(get(CookieAuthorizationRequestRepository.COOKIE_PATH).cookie(cookie)
                                .param("code","c-query-code-sentinel").param("state","c-query-state-sentinel")
                                .param("error_description","c-query-description-sentinel"))
                        .andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed"))
                        .andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
                assertCleanup(failed);
            }
            var absent=mvc.perform(get(CookieAuthorizationRequestRepository.COOKIE_PATH).param("error","c-query-error-sentinel"))
                    .andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed")).andReturn();
            assertCleanup(absent);
            doThrow(new OAuth2AuthenticationException("start_failed")).when(c.getBean(KakaoAuthorizationRequestResolver.class)).resolve(any());
            var failedStart=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH))
                    .andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed"))
                    .andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
            assertCleanup(failedStart);
            verifyNoInteractions(tokenClient,users,c.getBean(UserJpaRepository.class));
        });
        for(String sentinel:List.of("c-query-code-sentinel","c-query-state-sentinel","c-query-description-sentinel","c-query-error-sentinel"))
            assertFalse(output.getAll().contains(sentinel),"callback data must not appear in application logs");
    }

    static void assertCleanup(org.springframework.test.web.servlet.MvcResult result) {
        assertNull(result.getRequest().getSession(false));
        for(String name:List.of(CookieAuthorizationRequestRepository.COOKIE_NAME,HandoffCookie.NAME)) {
            var matches=Arrays.stream(result.getResponse().getCookies()).filter(c->c.getName().equals(name)).toList();
            assertFalse(matches.isEmpty()); assertTrue(matches.stream().allMatch(c->c.getMaxAge()==0&&c.isHttpOnly()));
        }
    }

    @Test void expiredAccessCannotPreemptExactOAuthStartOrCallback() {
        runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var oldTokens=new ServiceJwtTokens(c.getBean(AuthProperties.class),
                    java.time.Clock.offset(ProductionAuthTestSupport.CLOCK,java.time.Duration.ofSeconds(-931)));
            String bearer="Bearer "+oldTokens.issueAccess(42).getTokenValue();
            // 공개 read에서는 실제 만료 거부, 같은 입력의 OAuth GET에서는 해당 필터가 먼저 소유
            mvc.perform(get("/api/board").header("Authorization",bearer)).andExpect(status().isUnauthorized());
            var begin=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH).header("Authorization",bearer))
                    .andExpect(status().isFound()).andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
            assertTrue(begin.getResponse().getRedirectedUrl().startsWith("https://kauth.kakao.com/oauth/authorize?"));
            var cookie=begin.getResponse().getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME);
            var callback=mvc.perform(get(CookieAuthorizationRequestRepository.COOKIE_PATH).header("Authorization",bearer)
                            .cookie(cookie).param("code","expired-bearer-test-code").param("state","wrong-test-state"))
                    .andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed"))
                    .andExpect(header().doesNotExist("WWW-Authenticate")).andReturn();
            assertCleanup(callback);assertNull(begin.getRequest().getSession(false));
            verifyNoInteractions(c.getBean(RestClientAuthorizationCodeTokenResponseClient.class),c.getBean(KakaoOAuth2UserService.class),c.getBean(UserJpaRepository.class));
        });
    }
}
