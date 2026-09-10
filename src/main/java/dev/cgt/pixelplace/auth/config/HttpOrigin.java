package dev.cgt.pixelplace.auth.config;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** 설정과 요청 Origin의 문법·동등성 경계. 금지 요소 제거 후 허용하는 정규화 금지 */
public record HttpOrigin(String scheme, String host, int port) {
    public HttpOrigin {
        if (scheme == null || host == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid HTTP origin");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || host.isBlank()) {
            throw new IllegalArgumentException("Invalid HTTP origin");
        }
        URI canonical = checkedUri(scheme + "://" + host + ":" + port);
        if (!canonical.getRawPath().isEmpty() || !host.equals(canonical.getHost())) {
            // public 생성자 직접 호출도 parse와 같은 host 불변식 유지
            throw new IllegalArgumentException("Invalid HTTP origin host");
        }
    }

    /** 요청값도 설정과 동일한 엄격한 URI 문법으로 검증 */
    public static HttpOrigin parse(String value) {
        URI uri = checkedUri(value);
        if (!uri.getRawPath().isEmpty()) {
            // 경로를 버리면 허용되지 않은 Origin이 allowlist에 합류할 수 있음
            throw new IllegalArgumentException("Origin must not contain a path");
        }
        return fromUri(uri);
    }

    static URI checkedUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (uri.isOpaque() || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception ignored) {
            // URI parser의 입력 포함 message/cause를 외부로 전달하지 않음
            throw new IllegalArgumentException("Invalid HTTP URI");
        }
    }

    static HttpOrigin fromUri(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return new HttpOrigin(scheme, uri.getHost(), uri.getPort() == -1 ? defaultPort(scheme) : uri.getPort());
    }

    private static int defaultPort(String scheme) { return scheme.equals("https") ? 443 : 80; }

    /** 기본 port의 생략·명시 표현만 파생. 비기본 port 확대 허용 금지 */
    public List<String> allowedRepresentations() {
        String base = scheme + "://" + host;
        return port == defaultPort(scheme) ? List.of(base, base + ":" + port) : List.of(base + ":" + port);
    }

    boolean isLoopback() { return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"); }
}
