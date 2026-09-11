package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.web.AuthenticationErrors;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CorsFilter;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 별도 허용 chain 없이 production filter의 API entry point·실서명 decoder·CORS 선행성 검증 */
class ProductionSecurityChainTest {
    @Test void productionChainUsesJsonApiErrorsAndPublicReadsWithExplicitBearerValidation() {
        ProductionAuthTestSupport.runner().withUserConfiguration(ProductionPixelBoundaryTest.Fixture.class).run(c -> {
            assertNull(c.getStartupFailure());
            assertEquals(1,c.getBeansOfType(SecurityFilterChain.class).size());
            var filters=c.getBean(SecurityFilterChain.class).getFilters();
            assertEquals(1,filters.stream().filter(CorsFilter.class::isInstance).count());
            assertTrue(c.getBeansOfType(CorsFilter.class).isEmpty());
            assertFalse(filters.stream().anyMatch(BasicAuthenticationFilter.class::isInstance));
            assertFalse(filters.stream().anyMatch(UsernamePasswordAuthenticationFilter.class::isInstance));
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            var assembly=c.getBean(ProductionPixelBoundaryTest.Assembly.class);
            assertTrue(assembly.readiness.isReady());assembly.clear();
            for(String accept:new String[]{"application/json","text/html","*/*",""}) {
                var request=post("/api/pixels").contentType(MediaType.APPLICATION_JSON).content("{\"x\":1,\"y\":2,\"color\":3}");
                if(!accept.isEmpty()) request.header(HttpHeaders.ACCEPT,accept);
                var result=mvc.perform(request).andExpect(status().isUnauthorized())
                        .andExpect(content().json("{\"message\":\"Unauthorized.\"}"))
                        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,"Bearer"))
                        .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
                assertNull(result.getRequest().getSession(false));
            }
            org.mockito.Mockito.verifyNoInteractions(assembly.command,assembly.core,assembly.wal,assembly.redis);
            mvc.perform(get("/api/board")).andExpect(status().isOk()).andExpect(jsonPath("$.boardSize").value(8192));
            var tokens=c.getBean(ServiceJwtTokens.class);
            mvc.perform(get("/api/board").header(HttpHeaders.AUTHORIZATION,"Bearer "+tokens.issueAccess(42).getTokenValue())).andExpect(status().isOk());
            for(String value:new String[]{"invalid",tokens.issueHandoff(42).getTokenValue()})
                mvc.perform(get("/api/board").header(HttpHeaders.AUTHORIZATION,"Bearer "+value)).andExpect(status().isUnauthorized())
                        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,"Bearer"));
            mvc.perform(get("/login").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/oauth2/authorization/kakao"))
                    .andExpect(header().string("Referrer-Policy","no-referrer"));
            mvc.perform(get("/login").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        });
    }

    @Test void actualCorsProcessorRejectsMalformedAndServerOriginBeforeAuthentication() {
        ProductionAuthTestSupport.runner().withUserConfiguration(ProductionPixelBoundaryTest.Fixture.class).run(c -> {
            var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity())
                    .defaultRequest(get("/").with(r->{r.setServerPort(8080);return r;})).build();
            for(String origin:new String[]{""," ","not-an-origin","http://localhost:","http://localhost:3000/path","http://localhost:3000/","null","http://localhost:8080","http://localhost:3001","https://localhost:3000","http://sub.localhost:3000","http://user@localhost:3000","http://localhost:3000?q=x","http://localhost:3000#x","http://localhost:3000, http://localhost:3000"}) {
                mvc.perform(get("/api/board").header(HttpHeaders.ORIGIN,origin)).andExpect(status().isForbidden())
                        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
                mvc.perform(options("/api/board").header(HttpHeaders.ORIGIN,origin).header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,"GET"))
                        .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
            }
            mvc.perform(get("/api/board").header(HttpHeaders.ORIGIN,"http://localhost:3000","http://localhost:3000")).andExpect(status().isForbidden());
            mvc.perform(options("/api/board").header(HttpHeaders.ORIGIN,"http://localhost:3000","http://localhost:3000")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,"GET")).andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
            mvc.perform(get("/api/board").header(HttpHeaders.ORIGIN,"HTTP://LOCALHOST:3000")).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,"HTTP://LOCALHOST:3000"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,"true"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,"X-Tile-Version"));
            mvc.perform(options("/api/pixels").header(HttpHeaders.ORIGIN,"http://localhost:3000")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,"POST").header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,"Authorization, Content-Type"))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,"GET,POST,OPTIONS"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,"Authorization, Content-Type"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,"http://localhost:3000"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,"true"));
        });
    }
}
