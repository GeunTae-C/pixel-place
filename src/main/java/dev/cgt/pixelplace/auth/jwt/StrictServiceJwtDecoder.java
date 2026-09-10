package dev.cgt.pixelplace.auth.jwt;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;

/** Nimbus 서명 검증 후 원본 JSON type 확인. claim 변환의 숫자/문자열 coercion·시간 overflow 수용 방지 */
final class StrictServiceJwtDecoder implements JwtDecoder {
    private final JwtDecoder delegate;
    private final JsonMapper json = JsonMapper.builder().build();

    StrictServiceJwtDecoder(JwtDecoder delegate) { this.delegate = delegate; }

    @Override
    public Jwt decode(String token) {
        try {
            Jwt jwt = delegate.decode(token);
            JsonNode raw = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.", -1)[1]));
            require(raw.isObject());
            for (String claim : new String[]{"iss", "sub", "token_use"}) require(raw.path(claim).isString());
            JsonNode aud = raw.path("aud");
            // RFC audience의 단일 문자열·문자열 배열만 허용. 목적 일치는 전용 validator 책임
            if (aud.isArray()) {
                require(!aud.isEmpty());
                for (JsonNode member : aud) require(member.isString());
            } else require(aud.isString());
            require(numericDate(raw.path("iat")).equals(jwt.getIssuedAt()));
            require(numericDate(raw.path("exp")).equals(jwt.getExpiresAt()));
            if (raw.has("nbf")) require(numericDate(raw.path("nbf")).equals(jwt.getNotBefore()));
            return jwt;
        } catch (RuntimeException ignored) {
            // 원문 claim·토큰·parser 메시지를 외부 오류나 로그에 보존하지 않음
            throw new BadJwtException("Invalid service token");
        }
    }

    private static Instant numericDate(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToLong());
        long seconds = value.longValue();
        Math.multiplyExact(seconds, 1000); // Nimbus Date 변환의 밀리초 overflow 차단
        return Instant.ofEpochSecond(seconds);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid service token");
    }
}
