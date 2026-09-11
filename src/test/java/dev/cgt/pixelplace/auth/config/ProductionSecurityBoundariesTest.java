package dev.cgt.pixelplace.auth.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.*;
import dev.cgt.pixelplace.auth.web.AuthenticationErrors;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 production 필터의 signed claim·Origin 동등성·재dispatch·간접 OAuth 경계 검증 */
class ProductionSecurityBoundariesTest {
    @Test void signedSubjectAndTimestampFailuresBecome401BeforePixelController() {
        ProductionAuthTestSupport.runner().withUserConfiguration(ProductionPixelBoundaryTest.Fixture.class).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var properties=c.getBean(AuthProperties.class);var a=c.getBean(ProductionPixelBoundaryTest.Assembly.class);a.clear();
            for(String subject:Arrays.asList(null,"","abc","0","-1","+1","01"," 1","1 ","１","9223372036854775808")) {
                var claims=claims();if(subject==null)claims.remove("sub");else claims.put("sub",subject);
                mvc.perform(post("/api/pixels").header("Authorization","Bearer "+signed(properties,claims)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":1,\"y\":2,\"color\":3}")).andExpect(status().isUnauthorized())
                        .andExpect(content().json("{\"message\":\"Unauthorized.\"}")).andExpect(header().string("WWW-Authenticate","Bearer"));
            }
            for(String field:List.of("exp","iat","aud","iss","token_use")) {
                var claims=claims();claims.remove(field);
                mvc.perform(get("/api/board").header("Authorization","Bearer "+signed(properties,claims))).andExpect(status().isUnauthorized());
            }
            for(Object timestamp:List.of("invalid",Double.MAX_VALUE,Long.MAX_VALUE)) {
                var claims=claims();claims.put("exp",timestamp);
                mvc.perform(get("/api/board").header("Authorization","Bearer "+signed(properties,claims))).andExpect(status().isUnauthorized());
            }
            // 수명을 유지한 채 exp+30초 경계만 변경해 다른 검증이 결과를 가리지 않도록 구성
            for(int offset:new int[]{0,-900,-929,-930,-931}) {
                var claims=claims();long issued=ProductionAuthTestSupport.NOW.plusSeconds(offset).getEpochSecond();
                claims.put("iat",issued);claims.put("exp",issued+900);
                mvc.perform(get("/api/board").header("Authorization","Bearer "+signed(properties,claims)))
                        .andExpect(status().is(offset==-931?401:200));
            }
            for(String subject:List.of("1",Long.toString(Long.MAX_VALUE))) {
                var claims=claims();claims.put("sub",subject);
                mvc.perform(get("/api/board").header("Authorization","Bearer "+signed(properties,claims))).andExpect(status().isOk());
            }
            verifyNoInteractions(a.command,a.core,a.wal,a.redis);
        });
    }

    @ParameterizedTest @ValueSource(strings={"http://localhost","http://localhost:80","https://localhost","https://localhost:443","http://localhost:8080"})
    void actualCorsPreservesDefaultPortEquivalenceWithoutBroadeningSameOriginOrHeaders(String frontend) {
        boolean https=frontend.startsWith("https:");String api=https?"https://localhost:8443":"http://localhost:8080";
        ProductionAuthTestSupport.runner().withPropertyValues("pixel-place.auth.frontend-origin="+frontend,
                "pixel-place.auth.frontend-auth-callback-uri="+frontend+"/","pixel-place.auth.public-api-origin="+api,
                "pixel-place.auth.provider-callback-uri="+api+"/login/oauth2/code/kakao","pixel-place.auth.cookie-secure="+https).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).defaultRequest(get("/").with(r->{r.setScheme(https?"https":"http");r.setSecure(https);r.setServerPort(https?8443:8080);return r;})).build();
            var origins=c.getBean(OriginPolicy.class);
            for(String origin:origins.allowedOrigins()) {
                var actual=mvc.perform(get("/api/board").header("Origin",origin)).andExpect(status().isOk()).andReturn();
                if(!origins.frontendOrigin().equals(origins.publicApiOrigin())) assertEquals(origin,actual.getResponse().getHeader("Access-Control-Allow-Origin"));
                var preflight=mvc.perform(options("/api/pixels").header("Origin",origin).header("Access-Control-Request-Method","POST")
                        .header("Access-Control-Request-Headers","Authorization, Content-Type")).andExpect(status().isOk()).andReturn();
                if(!origins.frontendOrigin().equals(origins.publicApiOrigin())) {
                    assertEquals("true",actual.getResponse().getHeader("Access-Control-Allow-Credentials"));
                    assertEquals(origin,preflight.getResponse().getHeader("Access-Control-Allow-Origin"));
                    assertEquals("true",preflight.getResponse().getHeader("Access-Control-Allow-Credentials"));
                    assertEquals("Authorization, Content-Type",preflight.getResponse().getHeader("Access-Control-Allow-Headers"));
                }
            }
            if(!origins.allows(api)) {
                mvc.perform(get("/api/board").header("Origin",api)).andExpect(status().isForbidden());
                mvc.perform(options("/api/board").header("Origin",api).header("Access-Control-Request-Method","GET")).andExpect(status().isForbidden());
            }
            for(String origin:List.of(frontend+"/",(https?"https":"http")+"://localhost:"+(origins.frontendOrigin().port()+1)))
                mvc.perform(get("/api/board").header("Origin",origin)).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            // same-origin에서는 브라우저 CORS preflight 자체가 없으므로 교차 Origin 구성에서 method/header 거부 확인
            if(!origins.frontendOrigin().equals(origins.publicApiOrigin())) {
                mvc.perform(options("/api/pixels").header("Origin",frontend).header("Access-Control-Request-Method","DELETE")).andExpect(status().isForbidden());
                mvc.perform(options("/api/pixels").header("Origin",frontend).header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","X-Untrusted"))
                        .andExpect(status().isForbidden());
            }
        });
    }

    @Test void nestedForwardFromPublicRouteCannotBypassAuthorizationAndSessionCannotRestoreLogin() {
        ProductionAuthTestSupport.runner().run(c->{
            var proxy=c.getBean("springSecurityFilterChain",FilterChainProxy.class);
            var request=new MockHttpServletRequest("GET","/api/board");request.setServletPath("/api/board");
            var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
            proxy.doFilter(request,response,(r,s)->{
                request.setMethod("POST");request.setRequestURI("/api/pixels");request.setServletPath("/api/pixels");request.setDispatcherType(DispatcherType.FORWARD);
                proxy.doFilter(request,response,(forwarded,output)->reached.set(true));
            });
            assertFalse(reached.get());assertEquals(401,response.getStatus());
            var session=new MockHttpSession();
            var securityContext=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(c.getBean(ServiceJwtTokens.class).issueAccess(42)));
            session.setAttribute("SPRING_SECURITY_CONTEXT",securityContext);
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            mvc.perform(post("/api/pixels").session(session)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        });
    }

    @Test void indirectAuthorizationNeverStartsProviderOrSavesRequest() {
        ProductionOAuthFlowTest.runner().withPropertyValues("spring.profiles.active=c-indirect-fixture")
                .withBean(IndirectEndpoint.class,IndirectEndpoint::new).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var result=mvc.perform(get("/api/tiles/c-test/indirect")).andExpect(redirectedUrl("http://localhost:3000/?error=oauth_login_failed")).andReturn();
            ProductionOAuthFlowTest.assertCleanup(result);
            verify(c.getBean(KakaoAuthorizationRequestResolver.class)).resolve(any(),eq("kakao"));
            verify(c.getBean(CookieAuthorizationRequestRepository.class),never()).saveAuthorizationRequest(any(),any(),any());
            verify(c.getBean(ServiceJwtTokens.class),never()).issueHandoff(anyLong());
        });
    }
    /** 공개 Tile namespace의 간접 예외 재현용 route. production component scan 밖에서만 등록 */
    @RestController @org.springframework.context.annotation.Profile("c-indirect-fixture")
    static class IndirectEndpoint {
        @GetMapping("/api/tiles/c-test/indirect") void indirect(){throw new ClientAuthorizationRequiredException("kakao");}
    }

    @Test void actualDeniedFilterUsesProduction403HandlerWithoutAddingRoleApi() {
        ProductionAuthTestSupport.runner().withUserConfiguration(DeniedFixture.class).run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            mvc.perform(get("/c-test-forbidden").header("Authorization","Bearer "+c.getBean(ServiceJwtTokens.class).issueAccess(42).getTokenValue()))
                    .andExpect(status().isForbidden()).andExpect(content().json("{\"message\":\"Forbidden.\"}"));
        });
    }
    /** 오류 handler 검증에만 사용하는 좁은 test chain. production의 단일 chain 계약과 구분 */
    @TestConfiguration(proxyBeanMethods=false)
    static class DeniedFixture {
        @Bean @Order(0) SecurityFilterChain denied(HttpSecurity http,@Qualifier("accessJwtDecoder") JwtDecoder decoder,AuthenticationErrors errors)throws Exception {
            return http.securityMatcher("/c-test-forbidden").authorizeHttpRequests(r->r.anyRequest().denyAll())
                    .oauth2ResourceServer(r->r.jwt(j->j.decoder(decoder)).accessDeniedHandler(errors)).exceptionHandling(e->e.accessDeniedHandler(errors)).build();
        }
    }
    static Map<String,Object> claims() {
        var result=new LinkedHashMap<String,Object>();result.put("iss","pixel-place");result.put("aud",List.of("pixel-place-api"));
        result.put("token_use","access");result.put("sub","42");result.put("iat",ProductionAuthTestSupport.NOW.getEpochSecond());
        result.put("exp",ProductionAuthTestSupport.NOW.plusSeconds(900).getEpochSecond());return result;
    }
    static String signed(AuthProperties properties,Map<String,Object> claims)throws Exception {
        var jwt=new JWSObject(new JWSHeader(JWSAlgorithm.HS256),new Payload(JsonMapper.builder().build().writeValueAsString(claims)));
        jwt.sign(new MACSigner(properties.jwtSigningKey().getEncoded()));return jwt.serialize();
    }
}
