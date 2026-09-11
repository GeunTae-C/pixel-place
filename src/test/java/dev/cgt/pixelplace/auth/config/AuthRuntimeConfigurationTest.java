package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.*;
import dev.cgt.pixelplace.auth.web.HandoffExchangeService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** production bean의 목적별 decoder와 no-save 저장소 연결 검증. HTTP 요청 경계는 production chain 테스트에서 확인 */
class AuthRuntimeConfigurationTest {
    @Test void runtimeBeansShareValidatedClockAndUseDistinctExplicitDecoders() {
        ProductionAuthTestSupport.runner().run(c -> {
            assertNull(c.getStartupFailure());
            var tokens=c.getBean(ServiceJwtTokens.class);
            var access=c.getBean("accessJwtDecoder",JwtDecoder.class);
            var handoff=c.getBean("handoffJwtDecoder",JwtDecoder.class);
            assertEquals(2,c.getBeansOfType(JwtDecoder.class).size());
            assertSame(tokens.accessDecoder(),access); assertSame(tokens.handoffDecoder(),handoff);
            var exchange=c.getBean(HandoffExchangeService.class);
            assertSame(handoff,ReflectionTestUtils.getField(exchange,"handoffDecoder"));
            assertSame(ProductionAuthTestSupport.CLOCK,ReflectionTestUtils.getField(exchange,"clock"));
            assertSame(ProductionAuthTestSupport.CLOCK,ReflectionTestUtils.getField(tokens,"clock"));
            var h=tokens.issueHandoff(42).getTokenValue();
            assertEquals("42",access.decode(exchange.exchange(h).accessToken()).getSubject());
            assertThrows(JwtException.class,()->access.decode(h));
            assertThrows(JwtException.class,()->handoff.decode(tokens.issueAccess(42).getTokenValue()));
            assertNotNull(c.getBean(NullSecurityContextRepository.class));
            assertNotNull(c.getBean(NullRequestCache.class));
            assertNotNull(c.getBean(NoOpAuthorizedClientRepository.class));
            assertNotNull(c.getBean(KakaoOAuth2UserService.class));
            assertNotNull(c.getBean(CookieAuthorizationRequestRepository.class));
            assertNotNull(c.getBean(OAuthLoginSuccessHandler.class));
        });
    }
}
