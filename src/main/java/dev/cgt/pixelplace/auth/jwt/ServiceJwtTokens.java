package dev.cgt.pixelplace.auth.jwt;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 서비스 토큰의 목적별 발급·검증 경계. B1에서는 명시적 조립만 허용, production bean 등록 책임 없음 */
public final class ServiceJwtTokens {
    private final AuthProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder handoffDecoder;

    public ServiceJwtTokens(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.encoder = NimbusJwtEncoder.withSecretKey(properties.jwtSigningKey()).algorithm(MacAlgorithm.HS256).build();
        accessDecoder = decoder(properties.accessAudience(), properties.accessTokenUse(), properties.accessTokenTtl(), false);
        handoffDecoder = decoder(properties.handoffAudience(), properties.handoffTokenUse(), properties.handoffTokenTtl(), true);
    }

    /** 내부 users.id만 subject로 발급, 외부 식별자나 caller 지정 audience 사용 금지 */
    public Jwt issueAccess(long userId) {
        return issue(userId, properties.accessAudience(), properties.accessTokenUse(), properties.accessTokenTtl());
    }

    /** 교환 전용 목적·수명 고정. Access 인증에서 수용하면 안 됨 */
    public Jwt issueHandoff(long userId) {
        return issue(userId, properties.handoffAudience(), properties.handoffTokenUse(), properties.handoffTokenTtl());
    }

    public JwtDecoder accessDecoder() { return accessDecoder; }
    public JwtDecoder handoffDecoder() { return handoffDecoder; }
    public long expiresInSeconds() { return properties.expiresInSeconds(); }

    private Jwt issue(long userId, String audience, String use, Duration ttl) {
        if (userId <= 0) {
            // canonical positive signed-long 식별자만 서비스 principal로 사용 가능
            throw new IllegalArgumentException("Invalid service user id");
        }
        var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(audience))
                .subject(Long.toString(userId)).issuedAt(issuedAt).expiresAt(issuedAt.plus(ttl))
                .claim("token_use", use).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims));
    }

    private JwtDecoder decoder(String audience, String use, Duration ttl, boolean handoff) {
        var decoder = NimbusJwtDecoder.withSecretKey(properties.jwtSigningKey()).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new ServiceJwtValidator(properties.issuer(), audience, use, ttl, handoff, clock));
        return new StrictServiceJwtDecoder(decoder);
    }
}
