package dev.cgt.pixelplace.auth.oauth2;

import java.nio.charset.StandardCharsets;

/** encoded value와 완성 header의 독립 byte 경계. 부분 절단이나 필수 필드 삭제로 우회하면 안 됨 */
final class CookieByteLimits {
    private CookieByteLimits() {}

    static void value(String value) { check(value, 3800); }
    static void header(String header) { check(header, 4096); }

    private static void check(String text, int maximum) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw AuthorizationRequestCookieCodec.failure();
        }
    }
}
