package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import java.time.*;
import java.util.*;

/** 사용자 전원의 JWT 문자열을 보유하지 않는 유한 LRU. 시험 서명 비용·만료 임박 갱신은 발생기 시간에 포함 */
final class Phase18Tokens {
    private record Token(String value, Instant expires) { }
    private final LinkedHashMap<Long, Token> cache = new LinkedHashMap<>(16, .75f, true);
    private final ServiceJwtTokens issuer;
    private final Clock clock;
    private final int maximum;
    private long issued, nanos;
    Phase18Tokens(ServiceJwtTokens issuer, Clock clock, int maximum) {
        Phase18Plan.require(maximum > 0 && maximum <= 128, "token cache"); this.issuer = issuer; this.clock = clock; this.maximum = maximum;
    }
    synchronized String get(long user) {
        Token token = cache.get(user);
        if (token == null || !token.expires.isAfter(clock.instant().plusSeconds(90))) {
            long start = System.nanoTime(); var jwt = issuer.issueAccess(user);
            token = new Token(jwt.getTokenValue(), jwt.getExpiresAt()); cache.put(user, token);
            if (cache.size() > maximum) cache.remove(cache.keySet().iterator().next());
            nanos += System.nanoTime() - start; issued++;
        }
        return token.value;
    }
    synchronized Map<String, Object> report() { return Map.of("issued", issued, "preparationNanos", nanos, "cached", cache.size(), "maximum", maximum); }
}
