package dev.cgt.pixelplace.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 비밀 없이 startup 실패·비노출과 후속 소비용 시간/key 계약 고정 */
@ExtendWith(OutputCaptureExtension.class)
class AuthConfigurationTest {
    static String key(int count, char value) {
        return Base64.getEncoder().encodeToString(String.valueOf(value).repeat(count).getBytes(StandardCharsets.UTF_8));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .withPropertyValues("pixel-place.auth.jwt-secret=" + key(32, 'a'),
                        "pixel-place.auth.oauth-cookie-secret=" + key(32, 'b'));
    }

    @Test
    void validConfigurationSuppliesDistinctPurposeKeysAndOneUtcClock() {
        runner().run(context -> {
            assertNull(context.getStartupFailure());
            AuthProperties properties = context.getBean(AuthProperties.class);
            assertEquals(Duration.ofMinutes(15), properties.accessTokenTtl());
            assertEquals(properties.accessTokenTtl().toSeconds(), properties.expiresInSeconds());
            assertEquals("HmacSHA256", properties.jwtSigningKey().getAlgorithm());
            assertEquals("AES", properties.oauthCookieKey().getAlgorithm());
            assertEquals(Duration.ofSeconds(60), properties.handoffTokenTtl());
            assertEquals(Duration.ofSeconds(180), properties.authorizationRequestTtl());
            assertEquals(1, context.getBeansOfType(Clock.class).size());
            assertEquals(ZoneOffset.UTC, context.getBean(Clock.class).getZone());
        });
        runner().withPropertyValues("pixel-place.auth.jwt-secret=" + key(64, 'c')).run(c -> assertNull(c.getStartupFailure()));
    }

    @Test
    void fixedTestClockReplacesDefaultWithoutAddingAnotherClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
        runner().withBean(Clock.class, () -> fixed).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(Clock.class).size());
            assertSame(fixed, context.getBean(Clock.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"jwt-secret", "oauth-cookie-secret"})
    void missingBlankMalformedAndShortKeysRejectStartupWithoutSecretInCauseOrLog(String name, CapturedOutput output) throws Exception {
        String sentinel = "phase13-private-sentinel";
        String encoded = Base64.getEncoder().encodeToString(sentinel.getBytes(StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sentinel.getBytes(StandardCharsets.UTF_8)));
        for (String value : new String[]{"", " ", sentinel + "!", encoded, "${MISSING_PHASE13_KEY}"}) {
            runner().withPropertyValues("pixel-place.auth." + name + "=" + value).run(c -> {
                assertNotNull(c.getStartupFailure());
                for (Throwable cause = c.getStartupFailure(); cause != null; cause = cause.getCause()) {
                    String message = String.valueOf(cause.getMessage());
                    assertFalse(message.contains(sentinel), "raw secret must not escape");
                    assertFalse(message.contains(encoded), "encoded secret must not escape");
                    assertFalse(message.contains(hash), "secret fingerprint must not escape");
                }
            });
        }
        new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .withPropertyValues("pixel-place.auth." + (name.equals("jwt-secret") ? "oauth-cookie-secret" : "jwt-secret") + "=" + key(32, 'b'))
                .run(c -> assertNotNull(c.getStartupFailure()));
        assertFalse(output.getAll().contains(sentinel), "log must not contain raw secret");
        assertFalse(output.getAll().contains(encoded), "log must not contain encoded secret");
        assertFalse(output.getAll().contains(hash), "log must not contain secret fingerprint");
    }

    @Test
    void CookieRequiresExactly32BytesAndDecodedKeyEqualityRejectsUnpaddedRepresentation() {
        for (int size : new int[]{31, 33, 64}) {
            runner().withPropertyValues("pixel-place.auth.oauth-cookie-secret=" + key(size, 'b'))
                    .run(c -> assertNotNull(c.getStartupFailure()));
        }
        runner().withPropertyValues("pixel-place.auth.oauth-cookie-secret=" + key(32, 'a').replace("=", ""))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"access-token-ttl=0s", "access-token-ttl=-1s", "access-token-ttl=899s",
            "access-token-ttl=PT900.1S", "access-token-ttl=900", "access-token-ttl=999999999999999999999s",
            "access-token-ttl=15x", "handoff-token-ttl=61s", "authorization-request-ttl=181s",
            "issuer=other", "access-audience=other", "access-token-use=other", "handoff-audience=other",
            "handoff-token-use=other", "cookie-secure=yes"})
    void InvalidLifetimeUnitRangeAndFixedPolicyOverridesRejectStartup(String setting) {
        runner().withPropertyValues("pixel-place.auth." + setting).run(c -> assertNotNull(c.getStartupFailure()));
    }
}
