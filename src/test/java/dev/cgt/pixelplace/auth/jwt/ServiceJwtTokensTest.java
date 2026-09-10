package dev.cgt.pixelplace.auth.jwt;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import dev.cgt.pixelplace.auth.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/** 실제 서명 토큰으로 목적 격리·canonical principal·Clock 시간 경계를 고정 */
class ServiceJwtTokensTest {
    private static final Instant ISSUED = Instant.parse("2030-01-02T03:04:05Z");
    private static final AuthProperties PROPERTIES = properties(1);

    @Test
    void issuingUsesSharedPropertiesClockAndOnlyServiceClaims() {
        var tokens = tokens(ISSUED.plusNanos(999_999_999));
        for (boolean handoff : List.of(false, true)) {
            var issued = handoff ? tokens.issueHandoff(Long.MAX_VALUE) : tokens.issueAccess(Long.MAX_VALUE);
            var decoded = (handoff ? tokens.handoffDecoder() : tokens.accessDecoder()).decode(issued.getTokenValue());
            assertThat(decoded.getClaims().keySet()).containsExactlyInAnyOrder("iss", "aud", "token_use", "sub", "iat", "exp");
            assertThat(decoded.getSubject()).isEqualTo(Long.toString(Long.MAX_VALUE));
            assertThat(decoded.getIssuedAt()).isEqualTo(ISSUED);
            assertThat(decoded.getExpiresAt()).isEqualTo(ISSUED.plus(handoff ? PROPERTIES.handoffTokenTtl() : PROPERTIES.accessTokenTtl()));
            assertThat((String) decoded.getClaim("iss")).isEqualTo(PROPERTIES.issuer());
            assertThat(decoded.getAudience()).containsExactly(handoff ? PROPERTIES.handoffAudience() : PROPERTIES.accessAudience());
            assertThat((String) decoded.getClaim("token_use")).isEqualTo(handoff ? PROPERTIES.handoffTokenUse() : PROPERTIES.accessTokenUse());
        }
        assertThat(tokens.expiresInSeconds()).isEqualTo(PROPERTIES.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void issuingRejectsNonPositiveInternalIds() {
        for (long id : new long[]{0, -1, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> tokens(ISSUED).issueAccess(id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tokens(ISSUED).issueHandoff(id)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void accessAndHandoffCannotAuthenticateAsEachOther() {
        var tokens = tokens(ISSUED);
        reject(tokens.handoffDecoder(), tokens.issueAccess(1).getTokenValue());
        reject(tokens.accessDecoder(), tokens.issueHandoff(1).getTokenValue());
    }

    @Test
    void rejectsWrongKeyAlgorithmAndDamagedSignature() throws Exception {
        var claims = claims(false);
        reject(tokens(ISSUED).accessDecoder(), signed(claims, properties(3).jwtSigningKey().getEncoded(), JWSAlgorithm.HS256));
        reject(tokens(ISSUED).accessDecoder(), signed(claims, new byte[64], JWSAlgorithm.HS512));
        String valid = signed(claims);
        String[] pieces = valid.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(pieces[2]);
        signature[0] ^= 1;
        reject(tokens(ISSUED).accessDecoder(), pieces[0] + "." + pieces[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
        for (String malformed : List.of("", "x.y.z", "a.b", "eyJhbGciOiJub25lIn0.e30.")) reject(tokens(ISSUED).accessDecoder(), malformed);
    }

    @Test
    void algorithmRestrictionRejectsOtherHmacAlgorithmsEvenWithTheCorrectKey() throws Exception {
        byte[] key = new byte[64];
        Arrays.fill(key, (byte) 5);
        var properties = new AuthProperties(Base64.getEncoder().encodeToString(key),
                Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(15));
        var tokens = new ServiceJwtTokens(properties, Clock.fixed(ISSUED, ZoneOffset.UTC));
        for (boolean handoff : List.of(false, true)) {
            var decoder = handoff ? tokens.handoffDecoder() : tokens.accessDecoder();
            assertThat(decoder.decode(signed(claims(handoff), key, JWSAlgorithm.HS256))).isNotNull();
            for (JWSAlgorithm algorithm : List.of(JWSAlgorithm.HS384, JWSAlgorithm.HS512)) {
                // key 불일치가 알고리즘 제한 누락을 가리지 않도록 동일한 충분한 길이의 key로 서명
                reject(decoder, signed(claims(handoff), key, algorithm));
            }
            var unsigned = new com.nimbusds.jwt.PlainJWT(com.nimbusds.jwt.JWTClaimsSet.parse(claims(handoff)));
            reject(decoder, unsigned.serialize());
        }
    }

    static Stream<Object[]> invalidClaims() {
        List<Object[]> cases = new ArrayList<>();
        for (boolean handoff : List.of(false, true)) {
            for (String name : List.of("iss", "aud", "token_use", "sub", "iat", "exp")) {
                cases.add(new Object[]{handoff, name, null, true});
                cases.add(new Object[]{handoff, name, null, false});
                cases.add(new Object[]{handoff, name, Map.of("value", "wrong"), false});
                cases.add(new Object[]{handoff, name, true, false});
            }
            for (String name : List.of("iss", "aud", "token_use")) cases.add(new Object[]{handoff, name, "wrong", false});
            cases.add(new Object[]{handoff, "aud", List.of(), false});
            cases.add(new Object[]{handoff, "aud", List.of(handoff ? PROPERTIES.handoffAudience() : PROPERTIES.accessAudience(), 1), false});
            for (Object sub : Arrays.asList("", "abc", "0", "-1", "+1", "01", " 1", "1 ", "１", "١", "9223372036854775808", 1)) {
                cases.add(new Object[]{handoff, "sub", sub, false});
            }
            for (String name : List.of("iat", "exp", "nbf")) {
                for (Object time : List.of("1893553445", 1893553445.0, 1893553445.5, Long.MAX_VALUE, Long.MIN_VALUE, List.of(1))) {
                    cases.add(new Object[]{handoff, name, time, false});
                }
            }
            cases.add(new Object[]{handoff, "nbf", null, false});
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "claim case {index}")
    @MethodSource("invalidClaims")
    void malformedClaimsBecomeFixedAuthenticationFailure(boolean handoff, String name, Object value, boolean remove) throws Exception {
        var claims = claims(handoff);
        if (remove) claims.remove(name); else claims.put(name, value);
        var decoder = handoff ? tokens(ISSUED).handoffDecoder() : tokens(ISSUED).accessDecoder();
        reject(decoder, signed(claims));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "9223372036854775807"})
    void acceptsCanonicalSubjectAndSingleOrArrayAudience(String subject) throws Exception {
        for (boolean handoff : List.of(false, true)) {
            var claims = claims(handoff);
            claims.put("sub", subject);
            String audience = handoff ? PROPERTIES.handoffAudience() : PROPERTIES.accessAudience();
            for (Object aud : List.of(audience, List.of("another-audience", audience))) {
                claims.put("aud", aud);
                var decoder = handoff ? tokens(ISSUED).handoffDecoder() : tokens(ISSUED).accessDecoder();
                assertThat(decoder.decode(signed(claims)).getSubject()).isEqualTo(subject);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {899, 900, 901})
    void accessRequiresExactlyConfiguredLifetime(long seconds) throws Exception {
        var claims = claims(false);
        claims.put("exp", ISSUED.plusSeconds(seconds).getEpochSecond());
        if (seconds == 900) assertThat(tokens(ISSUED).accessDecoder().decode(signed(claims))).isNotNull();
        else reject(tokens(ISSUED).accessDecoder(), signed(claims));
    }

    @ParameterizedTest
    @ValueSource(longs = {59, 60, 61})
    void handoffRequiresExactlyConfiguredLifetime(long seconds) throws Exception {
        var claims = claims(true);
        claims.put("exp", ISSUED.plusSeconds(seconds).getEpochSecond());
        if (seconds == 60) assertThat(tokens(ISSUED).handoffDecoder().decode(signed(claims))).isNotNull();
        else reject(tokens(ISSUED).handoffDecoder(), signed(claims));
    }

    @Test
    void accessAllowsExpiryThroughThirtySecondsButNotOneNanosecondLater() throws Exception {
        String token = signed(claims(false));
        Instant expires = ISSUED.plusSeconds(900);
        for (Instant now : List.of(expires.minusNanos(1), expires, expires.plusSeconds(29), expires.plusSeconds(30))) {
            assertThat(tokens(now).accessDecoder().decode(token)).isNotNull();
        }
        reject(tokens(expires.plusSeconds(30).plusNanos(1)).accessDecoder(), token);
    }

    @Test
    void handoffRejectsAtExpiryAndAfterWithZeroSkew() throws Exception {
        String token = signed(claims(true));
        Instant expires = ISSUED.plusSeconds(60);
        assertThat(tokens(expires.minusNanos(1)).handoffDecoder().decode(token)).isNotNull();
        reject(tokens(expires).handoffDecoder(), token);
        reject(tokens(expires.plusNanos(1)).handoffDecoder(), token);
    }

    @Test
    void futureIssuedAtAndOptionalNotBeforeUsePurposeSpecificSkew() throws Exception {
        for (boolean handoff : List.of(false, true)) {
            long skew = handoff ? 0 : 30;
            var claims = claims(handoff);
            // 미래 iat 검증은 exp와 함께 이동해 TTL 오류로 우연히 통과하는 테스트 방지
            claims.put("iat", ISSUED.plusSeconds(skew).getEpochSecond());
            claims.put("exp", ISSUED.plusSeconds(skew + (handoff ? 60 : 900)).getEpochSecond());
            claims.put("nbf", ISSUED.plusSeconds(skew).getEpochSecond());
            var service = tokens(ISSUED);
            var decoder = handoff ? service.handoffDecoder() : service.accessDecoder();
            assertThat(decoder.decode(signed(claims))).isNotNull();
            var earlier = tokens(ISSUED.minusNanos(1));
            reject(handoff ? earlier.handoffDecoder() : earlier.accessDecoder(), signed(claims));
            claims = claims(handoff);
            claims.put("nbf", ISSUED.plusSeconds(skew + 1).getEpochSecond());
            reject(decoder, signed(claims));
            claims = claims(handoff);
            claims.put("iat", ISSUED.plusSeconds(skew + 1).getEpochSecond());
            claims.put("exp", ISSUED.plusSeconds(skew + 1 + (handoff ? 60 : 900)).getEpochSecond());
            reject(decoder, signed(claims));
        }
    }

    @Test
    void notBeforeAndIssuedAtNanosecondEdgesAreValidatedIndependently() throws Exception {
        for (boolean handoff : List.of(false, true)) {
            long skew = handoff ? 0 : 30;
            for (String claim : List.of("iat", "nbf")) {
                var claims = claims(handoff);
                claims.put(claim, ISSUED.plusSeconds(skew).getEpochSecond());
                if (claim.equals("iat")) claims.put("exp", ISSUED.plusSeconds(skew + (handoff ? 60 : 900)).getEpochSecond());
                else {
                    // 미래 iat에 의한 실패가 nbf 오류를 가리지 않도록 iat를 충분히 이전으로 이동
                    claims.put("iat", ISSUED.minusSeconds(10).getEpochSecond());
                    claims.put("exp", ISSUED.plusSeconds((handoff ? 60 : 900) - 10).getEpochSecond());
                }
                String token = signed(claims);
                var exact = tokens(ISSUED);
                assertThat((handoff ? exact.handoffDecoder() : exact.accessDecoder()).decode(token)).isNotNull();
                var before = tokens(ISSUED.minusNanos(1));
                reject(handoff ? before.handoffDecoder() : before.accessDecoder(), token);
            }
        }
    }

    @Test
    void duplicateClaimsAndTrailingJsonCannotChangeAuthenticatedMeaning() throws Exception {
        String raw = JsonMapper.builder().build().writeValueAsString(claims(false));
        for (String malformed : List.of(raw + " {}", raw.replace("\"sub\":\"1\"", "\"sub\":\"2\",\"sub\":\"1\""))) {
            var jwt = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(malformed));
            jwt.sign(new MACSigner(PROPERTIES.jwtSigningKey().getEncoded()));
            reject(tokens(ISSUED).accessDecoder(), jwt.serialize());
        }
    }

    private static void reject(org.springframework.security.oauth2.jwt.JwtDecoder decoder, String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessage("Invalid service token");
    }

    private static ServiceJwtTokens tokens(Instant now) { return new ServiceJwtTokens(PROPERTIES, Clock.fixed(now, ZoneOffset.UTC)); }

    private static AuthProperties properties(int marker) {
        byte[] jwt = new byte[32];
        byte[] cookie = new byte[32];
        Arrays.fill(jwt, (byte) marker);
        Arrays.fill(cookie, (byte) (marker + 1));
        return new AuthProperties(Base64.getEncoder().encodeToString(jwt), Base64.getEncoder().encodeToString(cookie), Duration.ofMinutes(15));
    }

    private static Map<String, Object> claims(boolean handoff) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", PROPERTIES.issuer());
        claims.put("aud", List.of(handoff ? PROPERTIES.handoffAudience() : PROPERTIES.accessAudience()));
        claims.put("token_use", handoff ? PROPERTIES.handoffTokenUse() : PROPERTIES.accessTokenUse());
        claims.put("sub", "1");
        claims.put("iat", ISSUED.getEpochSecond());
        claims.put("exp", ISSUED.plusSeconds(handoff ? 60 : 900).getEpochSecond());
        return claims;
    }

    private static String signed(Map<String, Object> claims) throws Exception { return signed(claims, PROPERTIES.jwtSigningKey().getEncoded(), JWSAlgorithm.HS256); }

    private static String signed(Map<String, Object> claims, byte[] key, JWSAlgorithm algorithm) throws Exception {
        var jwt = new JWSObject(new JWSHeader(algorithm), new Payload(JsonMapper.builder().build().writeValueAsString(claims)));
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }
}
