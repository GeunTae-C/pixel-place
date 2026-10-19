package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import org.springframework.context.annotation.*;
import java.time.Clock;

/** benchmark source set의 명시적 앱 기동에만 추가되는 측정 인증 조립. production component scan 밖에 위치 */
@Configuration(proxyBeanMethods=false)
@Profile("benchmark")
class Phase18K6Auth {
    @Bean @Primary
    ServiceJwtTokens benchmarkAccessTokens(AuthProperties properties, Clock clock) {
        return ServiceJwtTokens.benchmarkAccess(properties, clock);
    }
}
