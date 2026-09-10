package dev.cgt.pixelplace.auth.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

/** 검증된 수명과 목적별 key 보유. 원문 secret·binding 오류·toString 노출 방지 */
public final class AuthProperties {
    private final SecretKey jwtSigningKey;
    private final SecretKey oauthCookieKey;
    private final Duration accessTokenTtl;

    public AuthProperties(String jwtSecret, String cookieSecret, Duration accessTokenTtl) {
        if (!Duration.ofMinutes(15).equals(accessTokenTtl)) {
            throw new IllegalArgumentException("Invalid access token lifetime");
        }
        this.accessTokenTtl = accessTokenTtl;
        byte[] jwt = decode(jwtSecret);
        byte[] cookie = null;
        try {
            cookie = decode(cookieSecret);
            if (jwt.length < 32 || cookie.length != 32 || MessageDigest.isEqual(jwt, cookie)) {
                // 길이와 decoded byte 상이성 검사. key 값이나 hash는 오류에 포함하지 않음
                throw new IllegalArgumentException("Invalid authentication key configuration");
            }
            jwtSigningKey = new SecretKeySpec(jwt, "HmacSHA256");
            oauthCookieKey = new SecretKeySpec(cookie, "AES");
        } finally {
            Arrays.fill(jwt, (byte) 0);
            if (cookie != null) Arrays.fill(cookie, (byte) 0);
        }
    }

    private static byte[] decode(String value) {
        try {
            if (value == null || value.isBlank()) throw new IllegalArgumentException();
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Invalid authentication key configuration");
        }
    }

    public SecretKey jwtSigningKey() { return jwtSigningKey; }
    public SecretKey oauthCookieKey() { return oauthCookieKey; }
    public Duration accessTokenTtl() { return accessTokenTtl; }
    public long expiresInSeconds() { return accessTokenTtl.toSeconds(); }
    public String issuer() { return "pixel-place"; }
    public String accessAudience() { return "pixel-place-api"; }
    public String accessTokenUse() { return "access"; }
    public String handoffAudience() { return "pixel-place-auth-handoff"; }
    public String handoffTokenUse() { return "handoff"; }
    public Duration handoffTokenTtl() { return Duration.ofSeconds(60); }
    public Duration authorizationRequestTtl() { return Duration.ofSeconds(180); }
}
