package dev.cgt.pixelplace.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Origin 문법과 cookie topology 단위 검증. actual CORS/WS wiring 증거는 C 책임 */
class OriginPolicyTest {
    private OriginPolicy local() {
        return new OriginPolicy("http://localhost:3000", "http://localhost:8080", "http://localhost:3000/",
                "http://localhost:8080/login/oauth2/code/kakao", false);
    }

    @Test
    void directOriginConstructionCannotBypassHostGrammar() {
        for (String host : List.of("localhost/path", "u@localhost", "localhost?x", "localhost:3000", "localhost#f")) {
            assertThrows(IllegalArgumentException.class, () -> new HttpOrigin("http", host, 80));
        }
    }

    @Test
    void explicitLoopbackHostsAndDifferentPortsAreAllowedWithoutDnsLookup() {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            OriginPolicy policy = new OriginPolicy("http://" + host + ":3000", "http://" + host + ":8080",
                    "http://" + host + ":3000/", "http://" + host + ":8080/login/oauth2/code/kakao", false);
            assertTrue(policy.allows("HTTP://" + host.toUpperCase() + ":3000"));
            assertFalse(policy.allows("http://" + host + ":3001"));
        }
    }

    @Test
    void defaultPortRepresentationsAreSymmetricAndNonDefaultPortsRemainExact() {
        for (String scheme : List.of("http", "https")) {
            int port = scheme.equals("https") ? 443 : 80;
            for (String suffix : List.of("", ":" + port)) {
                HttpOrigin origin = HttpOrigin.parse(scheme + "://LOCALHOST" + suffix);
                assertEquals(HttpOrigin.parse(scheme + "://localhost"), origin);
                assertEquals(List.of(scheme + "://localhost", scheme + "://localhost:" + port), origin.allowedRepresentations());
            }
        }
        assertEquals(List.of("http://localhost:3000"), local().allowedOrigins());
        OriginPolicy https = new OriginPolicy("https://EXAMPLE.com:443", "https://example.com:8443",
                "https://example.com/", "https://example.com:8443/login/oauth2/code/kakao", true);
        assertTrue(https.allows("HTTPS://example.COM"));
        assertFalse(https.allows("https://example.com:444"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "http://localhost:3000/", "http://u@localhost:3000", "http://localhost:3000?q=1",
            "http://localhost:3000#f", "http://localhost:3000 http://localhost:3000", "http://localhost:3000,http://localhost:3000",
            "http://localhost:", "http://localhost:0", "http://localhost:65536", "http://localhost:-1", "http://localhost:abc",
            "//localhost:3000", "file://localhost", "http://localhost:3000\\", "http://[::1", " http://localhost:3000"})
    void malformedOriginsAreRejectedBeforeNormalization(String input) {
        assertThrows(IllegalArgumentException.class, () -> HttpOrigin.parse(input));
        assertFalse(local().allows(input));
        assertFalse(local().allows(null));
    }

    @Test
    void mixedSchemesDifferentHostsAndUnsupportedSecureCombinationsAreRejected() {
        for (String frontend : List.of("http://example.com", "http://localhost.localdomain", "http://sub.localhost", "https://localhost")) {
            assertThrows(IllegalArgumentException.class, () -> new OriginPolicy(frontend, "http://localhost:8080",
                    frontend + "/", "http://localhost:8080/login/oauth2/code/kakao", false));
        }
        for (String scheme : List.of("http", "https")) {
            assertThrows(IllegalArgumentException.class, () -> new OriginPolicy(scheme + "://localhost", scheme + "://localhost:8080",
                    scheme + "://localhost/", scheme + "://localhost:8080/login/oauth2/code/kakao", scheme.equals("http")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/pixels", "/oauth2/authorization/kakao", "/login", "/login/oauth2/code/kakao",
            "/auth/handoff", "/ws", "/actuator", "/a/../login", "/%6cogin", "/login;x", "/?q=x", "/#x", ""})
    void callbackRejectsEntryPointLoopsAndForbiddenUriParts(String path) {
        assertThrows(IllegalArgumentException.class, () -> new OriginPolicy("http://localhost:3000", "http://localhost:8080",
                "http://localhost:3000" + path, "http://localhost:8080/login/oauth2/code/kakao", false));
    }

    @Test
    void callbacksMustMatchConfiguredOriginsAndExactProviderPath() {
        for (String callback : List.of("http://localhost:8081/login/oauth2/code/kakao", "http://localhost:8080/login/oauth2/code/kakao/",
                "http://localhost:8080/login/oauth2/code/other", "http://u@localhost:8080/login/oauth2/code/kakao",
                "http://localhost:8080/login/oauth2/code/kakao?x=1", "http://localhost:8080/login/oauth2/code/kakao#f")) {
            assertThrows(IllegalArgumentException.class, () -> new OriginPolicy("http://localhost:3000", "http://localhost:8080",
                    "http://localhost:3000/", callback, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new OriginPolicy("http://localhost:3000", "http://localhost:8080",
                "http://localhost:3001/", "http://localhost:8080/login/oauth2/code/kakao", false));
    }
}
