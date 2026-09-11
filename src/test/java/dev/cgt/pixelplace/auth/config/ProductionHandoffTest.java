package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.HandoffCookie;
import dev.cgt.pixelplace.auth.web.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** production POST 연결에서 CORS→Resource Server→Origin→cookie→전용 decoder→발급의 순서와 실패 분리 검증 */
class ProductionHandoffTest {
    static org.springframework.boot.test.context.runner.WebApplicationContextRunner runner() {
        return ProductionAuthTestSupport.runner().withBean("handoffObservers",BeanPostProcessor.class,()->new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                return bean instanceof HandoffCookie || bean instanceof HandoffTokenController || bean instanceof HandoffExchangeService
                        || bean instanceof JwtDecoder || bean instanceof ServiceJwtTokens ? spy(bean):bean;
            }
        });
    }
    @Test void productionExchangeOrdersCallsUsesRealPurposeDecoderAndAllowsValidReplayWithinLifetime() {
        runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var tokens=c.getBean(ServiceJwtTokens.class); var cookie=c.getBean(HandoffCookie.class);
            var decoder=c.getBean("handoffJwtDecoder",JwtDecoder.class);
            var live=new Cookie(HandoffCookie.NAME,tokens.issueHandoff(42).getTokenValue());
            clearInvocations(tokens,cookie,decoder);
            for(int attempt=0;attempt<2;attempt++) {
                var result=mvc.perform(post(HandoffCookie.PATH).header("Origin","http://localhost:3000").cookie(live))
                        .andExpect(status().isOk()).andExpect(content().contentType("application/json;charset=UTF-8"))
                        .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Pragma","no-cache"))
                        .andExpect(header().doesNotExist("WWW-Authenticate")).andExpect(jsonPath("$.tokenType").value("Bearer"))
                        .andExpect(jsonPath("$.expiresInSeconds").value(900)).andReturn();
                var body=c.getBean(JsonMapper.class).readTree(result.getResponse().getContentAsString());
                assertEquals(3,body.size());
                assertEquals("42",c.getBean("accessJwtDecoder",JwtDecoder.class).decode(body.path("accessToken").stringValue()).getSubject());
                assertEquals(0,result.getResponse().getCookie(HandoffCookie.NAME).getMaxAge());
                assertNull(result.getRequest().getSession(false));
            }
            var order=inOrder(cookie,decoder,tokens);
            for(int attempt=0;attempt<2;attempt++) {
                order.verify(cookie).read(any()); order.verify(decoder).decode(anyString());
                order.verify(tokens).issueAccess(42); order.verify(cookie).delete(any());
            }
            verify(tokens,times(2)).issueAccess(42);
        });
    }
    @Test void corsAndMissingOriginStopBeforeCookieWhileInvalidAccessStopsBeforeEndpoint() {
        runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var controller=c.getBean(HandoffTokenController.class); var cookie=c.getBean(HandoffCookie.class);
            var decoder=c.getBean("handoffJwtDecoder",JwtDecoder.class); var tokens=c.getBean(ServiceJwtTokens.class);
            clearInvocations(controller,cookie,decoder,tokens);
            for(String origin:List.of("http://localhost:3000/","http://localhost:8080","null"))
                mvc.perform(post(HandoffCookie.PATH).header("Origin",origin)).andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist("Access-Control-Allow-Origin")).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            verifyNoInteractions(controller,cookie,decoder,tokens);
            mvc.perform(post(HandoffCookie.PATH)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden_origin"));
            verifyNoInteractions(cookie,decoder,tokens);
            clearInvocations(controller);
            mvc.perform(post(HandoffCookie.PATH).header("Origin","http://localhost:3000").header("Authorization","Bearer invalid"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"));
            verifyNoInteractions(controller,cookie,decoder,tokens);
        });
    }
    @Test void handoffAuthenticationFailuresDeleteCookieWithoutBearerChallengeOrAccessIssuance() {
        runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var tokens=c.getBean(ServiceJwtTokens.class);
            var expired=new ServiceJwtTokens(c.getBean(AuthProperties.class),Clock.offset(ProductionAuthTestSupport.CLOCK,Duration.ofSeconds(-60)));
            var invalidValues=List.of("","invalid",tokens.issueAccess(42).getTokenValue(),expired.issueHandoff(42).getTokenValue());
            clearInvocations(tokens);
            for(String value:invalidValues) {
                var request=post(HandoffCookie.PATH).header("Origin","http://localhost:3000");
                if(!value.isEmpty())request.cookie(new Cookie(HandoffCookie.NAME,value));
                var result=mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"))
                        .andExpect(jsonPath("$.error").value("invalid_handoff")).andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn();
                assertEquals(0,result.getResponse().getCookie(HandoffCookie.NAME).getMaxAge());
            }
            verify(tokens,never()).issueAccess(anyLong());
        });
    }
    @Test void accessPreparationFailuresAre500AndRemainingTimeComesFromActualExpiry() {
        runner().run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var tokens=c.getBean(ServiceJwtTokens.class); var live=new Cookie(HandoffCookie.NAME,tokens.issueHandoff(42).getTokenValue());
            for(long remaining:new long[]{0,-1,901,850}) {
                var jwt=Jwt.withTokenValue("test-response-value").header("alg","HS256").subject("42")
                        .issuedAt(ProductionAuthTestSupport.NOW.minusSeconds(900))
                        .expiresAt(ProductionAuthTestSupport.NOW.plusSeconds(remaining)).build();
                doReturn(jwt).when(tokens).issueAccess(42);
                var result=mvc.perform(post(HandoffCookie.PATH).header("Origin","http://localhost:3000").cookie(live))
                        .andExpect(status().is(remaining==850?200:500)).andExpect(header().doesNotExist("WWW-Authenticate")).andReturn();
                var body=c.getBean(JsonMapper.class).readTree(result.getResponse().getContentAsString());
                if(remaining==850)assertEquals(850,body.path("expiresInSeconds").intValue());
                else assertFalse(body.has("accessToken"));
                assertEquals(0,result.getResponse().getCookie(HandoffCookie.NAME).getMaxAge());
            }
            doThrow(new IllegalStateException("test-only-issuer-failure")).when(tokens).issueAccess(42);
            mvc.perform(post(HandoffCookie.PATH).header("Origin","http://localhost:3000").cookie(live)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("token_exchange_failed")).andExpect(jsonPath("$.accessToken").doesNotExist());
        });
    }
}
