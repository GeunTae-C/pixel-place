package dev.cgt.pixelplace.auth.jwt;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** 동일 서명 key를 쓰는 Access/handoff의 용도 격리와 정확한 TTL·시간 경계 담당 */
final class ServiceJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2TokenValidatorResult INVALID = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Invalid service token", null));
    private final String issuer;
    private final String audience;
    private final String use;
    private final Duration ttl;
    private final boolean handoff;
    private final Clock clock;
    private final Duration skew;
    private final JwtTimestampValidator timestamps;

    ServiceJwtValidator(String issuer, String audience, String use, Duration ttl, boolean handoff, Clock clock) {
        this.issuer = issuer;
        this.audience = audience;
        this.use = use;
        this.ttl = ttl;
        this.handoff = handoff;
        this.clock = clock;
        skew = handoff ? Duration.ZERO : Duration.ofSeconds(30);
        timestamps = new JwtTimestampValidator(skew);
        timestamps.setAllowEmptyExpiryClaim(false);
        timestamps.setClock(clock);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Object audiences = token.getClaims().get("aud");
            String subject = token.getSubject();
            var issuedAt = token.getIssuedAt();
            var expiresAt = token.getExpiresAt();
            if (!issuer.equals(token.getClaims().get("iss")) || !use.equals(token.getClaims().get("token_use"))
                    || !(audiences instanceof List<?> list) || !list.contains(audience)
                    || subject == null || !subject.matches("[1-9][0-9]*") || Long.parseLong(subject) <= 0
                    || issuedAt == null || expiresAt == null || !Duration.between(issuedAt, expiresAt).equals(ttl)) {
                return INVALID;
            }
            var now = clock.instant();
            // 표준 timestamp validator의 equality 허용을 handoff 만료 경계에서는 더 엄격하게 제한
            if (issuedAt.isAfter(now.plus(skew)) || (handoff && !now.isBefore(expiresAt))) return INVALID;
            return timestamps.validate(token).hasErrors() ? INVALID : OAuth2TokenValidatorResult.success();
        } catch (RuntimeException ignored) {
            // 잘못된 claim type·범위·시간 산술 오류가 인증 실패를 넘어 500으로 전파되면 안 됨
            return INVALID;
        }
    }
}
