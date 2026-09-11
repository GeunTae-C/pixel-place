package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.*;
import dev.cgt.pixelplace.user.infra.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import javax.crypto.Cipher;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** B1/B2의 실패 계약을 실제 production callback/start 경로에서도 검증. 정상 provider HTTP 이후 준비 실패까지 포함 */
@ExtendWith(OutputCaptureExtension.class)
class ProductionOAuthFailureTest {
    @ParameterizedTest @ValueSource(strings={"missing","tamper","malformed","expired","pkce-length","pkce-missing","pkce-characters","pkce-mismatch","pkce-plain","pkce-challenge-missing","missing-state","wrong-state"})
    void invalidCallbackStateNeverReachesCodeExchangeOrProvisioning(String kind) {
        ProductionOAuthFlowTest.runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();var codec=c.getBean(AuthorizationRequestCookieCodec.class);
            var begin=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH)).andReturn();
            String value=begin.getResponse().getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME).getValue();var authorization=codec.decode(value);
            if(kind.equals("tamper"))value=value.substring(0,10)+(value.charAt(10)=='A'?'B':'A')+value.substring(11);
            if(kind.equals("malformed"))value="v1.!";
            if(kind.equals("expired"))value=new AuthorizationRequestCookieCodec(c.getBean(AuthProperties.class),c.getBean(OriginPolicy.class),
                    Clock.offset(ProductionAuthTestSupport.CLOCK,Duration.ofSeconds(-180))).encode(authorization);
            if(kind.startsWith("pkce-"))value=invalidPkce(codec,value,kind);
            var request=get(CookieAuthorizationRequestRepository.COOKIE_PATH).param("code","c-invalid-callback-code");
            if(!kind.equals("missing"))request.cookie(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME,value));
            if(!kind.equals("missing-state"))request.param("state",kind.equals("wrong-state")?"c-wrong-state":authorization.getState());
            var result=mvc.perform(request).andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed"))
                    .andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
            ProductionOAuthFlowTest.assertCleanup(result);
            verifyNoInteractions(c.getBean(RestClientAuthorizationCodeTokenResponseClient.class),c.getBean(KakaoOAuth2UserService.class),c.getBean(UserJpaRepository.class));
            verify(c.getBean(ServiceJwtTokens.class),never()).issueHandoff(anyLong());
        });
    }
    @ParameterizedTest @ValueSource(strings={"provisioning","handoff-issuer","handoff-cookie"})
    void productionCallbackPreparationFailureCleansBothCookiesAndAllowsNextLogin(String kind,CapturedOutput output) {
        ProductionOAuthFlowTest.runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var start=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH)).andReturn();
            var cookie=start.getResponse().getCookie(CookieAuthorizationRequestRepository.COOKIE_NAME);
            var auth=c.getBean(AuthorizationRequestCookieCodec.class).decode(cookie.getValue());
            var rest=RestClient.builder().messageConverters(converters->{converters.clear();converters.add(new org.springframework.http.converter.FormHttpMessageConverter());
                converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());}).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
            var tokenHttp=MockRestServiceServer.bindTo(rest).build();c.getBean(RestClientAuthorizationCodeTokenResponseClient.class).setRestClient(rest.build());
            var userRest=new RestTemplate();userRest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());var userHttp=MockRestServiceServer.bindTo(userRest).build();
            c.getBean(DefaultOAuth2UserService.class).setRestOperations(userRest);
            tokenHttp.expect(requestTo("https://kauth.kakao.com/oauth/token")).andRespond(withSuccess("{\"access_token\":\"c-failure-provider-sentinel\",\"token_type\":\"Bearer\",\"expires_in\":300}",MediaType.APPLICATION_JSON));
            userHttp.expect(requestTo("https://kapi.kakao.com/v2/user/me")).andRespond(withSuccess("{\"id\":987654321}",MediaType.APPLICATION_JSON));
            var users=c.getBean(UserJpaRepository.class);var row=new UserEntity(987654321L);ReflectionTestUtils.setField(row,"id",42L);
            when(users.findByKakaoUserId(987654321)).thenReturn(Optional.of(row));
            if(kind.equals("provisioning"))doThrow(new IllegalStateException("c-provisioning-private-sentinel")).when(users).findByKakaoUserId(987654321);
            if(kind.equals("handoff-issuer"))doThrow(new IllegalStateException("c-issuer-private-sentinel")).when(c.getBean(ServiceJwtTokens.class)).issueHandoff(42);
            if(kind.equals("handoff-cookie"))doThrow(new IllegalStateException("c-cookie-private-sentinel")).when(c.getBean(HandoffCookie.class)).prepare(anyString());
            var result=mvc.perform(get(CookieAuthorizationRequestRepository.COOKIE_PATH).cookie(cookie).param("code","c-failure-code-sentinel").param("state",auth.getState()))
                    .andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed")).andReturn();
            ProductionOAuthFlowTest.assertCleanup(result);tokenHttp.verify();userHttp.verify();
            mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH)).andExpect(status().isFound()).andExpect(header().string("Referrer-Policy","no-referrer"));
        });
        for(String sentinel:List.of("c-failure-provider-sentinel","c-provisioning-private-sentinel","c-issuer-private-sentinel","c-cookie-private-sentinel","c-failure-code-sentinel"))
            assertFalse(output.getAll().contains(sentinel),"private callback input must not escape");
    }
    @ParameterizedTest @ValueSource(strings={"resolver","cookie"})
    void productionStartFailureUsesNoSaveCacheAndSharedCleanup(String kind) {
        ProductionOAuthFlowTest.runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            if(kind.equals("resolver"))doThrow(new OAuth2AuthenticationException("start_failed")).when(c.getBean(KakaoAuthorizationRequestResolver.class)).resolve(any());
            else doThrow(new OAuth2AuthenticationException("start_failed")).when(c.getBean(CookieAuthorizationRequestRepository.class)).saveAuthorizationRequest(any(),any(),any());
            var result=mvc.perform(get(KakaoAuthorizationRequestResolver.START_PATH)).andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed"))
                    .andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();ProductionOAuthFlowTest.assertCleanup(result);
        });
    }
    private static String invalidPkce(AuthorizationRequestCookieCodec codec,String value,String kind)throws Exception {
        byte[] packed=Base64.getUrlDecoder().decode(value.substring(3)),nonce=Arrays.copyOf(packed,12);
        Cipher decrypt=ReflectionTestUtils.invokeMethod(codec,"cipher",Cipher.DECRYPT_MODE,nonce);
        var mapper=JsonMapper.builder().build();var tree=(ObjectNode)mapper.readTree(decrypt.doFinal(packed,12,packed.length-12));
        var attributes=(ObjectNode)tree.path("request").path("attributes");var parameters=(ObjectNode)tree.path("request").path("additionalParameters");
        switch(kind) {
            case "pkce-length"->attributes.put("code_verifier","a".repeat(42));case "pkce-missing"->attributes.remove("code_verifier");
            case "pkce-characters"->attributes.put("code_verifier","!".repeat(43));case "pkce-mismatch"->attributes.put("code_verifier","a".repeat(43));
            case "pkce-plain"->parameters.put("code_challenge_method","plain");case "pkce-challenge-missing"->parameters.remove("code_challenge");
            default->throw new AssertionError("Unknown test input");
        }
        new java.security.SecureRandom().nextBytes(nonce);Cipher encrypt=ReflectionTestUtils.invokeMethod(codec,"cipher",Cipher.ENCRYPT_MODE,nonce);
        byte[] encrypted=encrypt.doFinal(mapper.writeValueAsBytes(tree));
        return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array());
    }
}
