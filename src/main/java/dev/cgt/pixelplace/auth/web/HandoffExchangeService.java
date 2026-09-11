package dev.cgt.pixelplace.auth.web;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Clock;
import java.time.Duration;

/** handoff 인증과 새 Access 준비의 분리 경계. 검증 실패와 발급 후 서버 실패를 혼동하면 안 됨 */
public final class HandoffExchangeService {
    private final JwtDecoder handoffDecoder;
    private final ServiceJwtTokens tokens;
    private final Clock clock;

    public HandoffExchangeService(ServiceJwtTokens tokens, Clock clock) {
        this(tokens.handoffDecoder(), tokens, clock);
    }

    /** production의 목적별 decoder 주입 경계. API Access decoder와 혼합 금지 */
    public HandoffExchangeService(JwtDecoder handoffDecoder, ServiceJwtTokens tokens, Clock clock) {
        this.tokens = tokens; this.clock = clock;
        this.handoffDecoder = handoffDecoder;
    }

    /** Origin 확인 이후에만 호출. 실제 exp에서 응답 계산 시각까지 남은 완전한 초 반환 */
    public AccessResponse exchange(String value) {
        if (value == null || value.isBlank()) throw new InvalidHandoff();
        final long internalId;
        try {
            Jwt handoff = handoffDecoder.decode(value);
            internalId = Long.parseLong(handoff.getSubject());
        } catch (JwtException | IllegalArgumentException failure) {
            // decoder 인증·claim 변환 실패만 401 분기. provider/token 원문을 담은 cause 노출 금지
            throw new InvalidHandoff();
        }
        Jwt access = tokens.issueAccess(internalId);
        long remaining = Duration.between(clock.instant(), access.getExpiresAt()).getSeconds();
        if (remaining < 1 || remaining > tokens.expiresInSeconds()) {
            // 지연·시계 이동으로 깨진 만료값을 고정 TTL로 감추지 않고 서버 실패로 전파
            throw new IllegalStateException("Invalid access response lifetime");
        }
        return new AccessResponse(access.getTokenValue(), "Bearer", remaining);
    }

    /** 전송용 DTO. 진단 문자열에는 bearer 원문을 포함하지 않음 */
    public record AccessResponse(String accessToken, String tokenType, long expiresInSeconds) {
        @Override public String toString() { return "AccessResponse[redacted]"; }
    }

    /** HTTP 401 mapping 전용 비민감 실패 */
    public static final class InvalidHandoff extends RuntimeException {
        private InvalidHandoff() { super("Invalid handoff"); }
    }
}
