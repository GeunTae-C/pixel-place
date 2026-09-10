package dev.cgt.pixelplace.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.time.Clock;
import java.time.Duration;

/** 인증 기반만 production 등록. OAuth registration·decoder·chain은 C의 연결 책임 */
@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {
    private static final String PREFIX = "pixel-place.auth.";

    /** placeholder와 변환 실패의 원문/cause를 제거한 startup 검증 경계 */
    @Bean
    public AuthProperties authProperties(Environment environment) {
        try {
            fixed(environment, "issuer", "pixel-place");
            fixed(environment, "access-audience", "pixel-place-api");
            fixed(environment, "access-token-use", "access");
            fixed(environment, "handoff-audience", "pixel-place-auth-handoff");
            fixed(environment, "handoff-token-use", "handoff");
            if (!duration(environment, "handoff-token-ttl", "60s").equals(Duration.ofSeconds(60))
                    || !duration(environment, "authorization-request-ttl", "180s").equals(Duration.ofSeconds(180))) {
                throw new IllegalArgumentException();
            }
            return new AuthProperties(environment.getProperty(PREFIX + "jwt-secret"),
                    environment.getProperty(PREFIX + "oauth-cookie-secret"),
                    duration(environment, "access-token-ttl", "15m"));
        } catch (RuntimeException ignored) {
            // Spring binding의 rejected value와 secret 포함 placeholder 오류 차단
            throw new IllegalArgumentException("Invalid authentication configuration");
        }
    }

    @Bean
    public OriginPolicy originPolicy(Environment environment) {
        try {
            String secure = environment.getProperty(PREFIX + "cookie-secure", "false");
            if (!(secure.equals("true") || secure.equals("false"))) throw new IllegalArgumentException();
            return new OriginPolicy(environment.getProperty(PREFIX + "frontend-origin", "http://localhost:3000"),
                    environment.getProperty(PREFIX + "public-api-origin", "http://localhost:8080"),
                    environment.getProperty(PREFIX + "frontend-auth-callback-uri", "http://localhost:3000/"),
                    environment.getProperty(PREFIX + "provider-callback-uri", "http://localhost:8080/login/oauth2/code/kakao"),
                    Boolean.parseBoolean(secure));
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("Invalid authentication origin configuration");
        }
    }

    /** 후속 발급·만료 계산에서 같은 주입 시계를 사용하기 위한 UTC 기준 */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock authClock() { return Clock.systemUTC(); }

    private static void fixed(Environment environment, String key, String expected) {
        if (!expected.equals(environment.getProperty(PREFIX + key, expected))) throw new IllegalArgumentException();
    }

    private static Duration duration(Environment environment, String key, String fallback) {
        return DurationStyle.detectAndParse(environment.getProperty(PREFIX + key, fallback));
    }
}
