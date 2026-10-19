package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 서명과 고정 Clock으로 측정/서비스 수명·목적·skew 경계 검증. 외부 서비스 사용 없음 */
class Phase18K6AuthTest {
    private final Instant now=Instant.parse("2030-01-01T00:00:00Z");
    private final BenchmarkEnvironment.Keys keys=BenchmarkEnvironment.keys();
    private AuthProperties properties(BenchmarkEnvironment.Keys k){return new AuthProperties(k.jwt(),k.cookie(),Duration.ofMinutes(15));}
    private Clock at(Instant time){return Clock.fixed(time,ZoneOffset.UTC);}
    @Test void fixedSixHourTokensRemainRejectedByProductionWithSameKey() {
        var p=properties(keys);var benchmark=ServiceJwtTokens.benchmarkAccess(p,at(now));
        var production=new ServiceJwtTokens(p,at(now));var token=benchmark.issueAccess(Long.MAX_VALUE);
        assertEquals(Duration.ofHours(6),Duration.between(token.getIssuedAt(),token.getExpiresAt()));
        assertEquals(21600,benchmark.expiresInSeconds());assertEquals(900,production.expiresInSeconds());
        assertEquals(Duration.ofMinutes(15),p.accessTokenTtl());
        assertEquals(Long.toString(Long.MAX_VALUE),benchmark.accessDecoder().decode(token.getTokenValue()).getSubject());
        assertThrows(JwtException.class,()->production.accessDecoder().decode(token.getTokenValue()));
        assertThrows(JwtException.class,()->benchmark.accessDecoder().decode(production.issueAccess(1).getTokenValue()));
        assertThrows(IllegalArgumentException.class,()->new AuthProperties(keys.jwt(),keys.cookie(),Duration.ofHours(6)));
        assertEquals(60,p.handoffTokenTtl().toSeconds());assertEquals(180,p.authorizationRequestTtl().toSeconds());
    }
    @Test void signaturePurposeExpiryAndThirtySecondSkewRemainStrict() {
        var p=properties(keys);var issued=ServiceJwtTokens.benchmarkAccess(p,at(now));var token=issued.issueAccess(1).getTokenValue();
        assertThrows(JwtException.class,()->ServiceJwtTokens.benchmarkAccess(properties(BenchmarkEnvironment.keys()),at(now)).accessDecoder().decode(token));
        assertThrows(JwtException.class,()->issued.accessDecoder().decode(issued.issueHandoff(1).getTokenValue()));
        assertThrows(JwtException.class,()->issued.handoffDecoder().decode(token));
        for(long seconds:new long[]{-30,21630})assertDoesNotThrow(()->ServiceJwtTokens.benchmarkAccess(p,at(now.plusSeconds(seconds))).accessDecoder().decode(token));
        for(long seconds:new long[]{-31,21631})assertThrows(JwtException.class,()->ServiceJwtTokens.benchmarkAccess(p,at(now.plusSeconds(seconds))).accessDecoder().decode(token));
        assertThrows(IllegalArgumentException.class,()->issued.issueAccess(0));
    }
    @Test void benchmarkBeanRequiresExplicitConfigurationAndProfile() {
        for(boolean benchmark:new boolean[]{false,true})try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(benchmark?"benchmark":"production");
            context.registerBean(AuthProperties.class,()->properties(keys));context.registerBean(Clock.class,()->at(now));
            context.registerBean("serviceJwtTokens",ServiceJwtTokens.class,()->new ServiceJwtTokens(properties(keys),at(now)));
            context.register(Phase18K6Auth.class);context.refresh();
            assertEquals(benchmark?21600:900,context.getBean(ServiceJwtTokens.class).expiresInSeconds());
        }
    }
}
