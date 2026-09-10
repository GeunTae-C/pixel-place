package dev.cgt.pixelplace.auth.config;

import java.net.URI;
import java.util.List;

/** SameSite=Lax 지원 topology와 callback 경계. WS에는 이 정책만 주입 가능 */
public final class OriginPolicy {
    private final HttpOrigin frontend;
    private final HttpOrigin api;
    private final URI frontendCallback;
    private final URI providerCallback;
    private final boolean cookieSecure;

    public OriginPolicy(String frontend, String api, String frontendCallback, String providerCallback, boolean cookieSecure) {
        this.frontend = HttpOrigin.parse(frontend);
        this.api = HttpOrigin.parse(api);
        this.frontendCallback = HttpOrigin.checkedUri(frontendCallback);
        this.providerCallback = HttpOrigin.checkedUri(providerCallback);
        this.cookieSecure = cookieSecure;
        if (!this.frontend.equals(HttpOrigin.fromUri(this.frontendCallback))
                || !this.api.equals(HttpOrigin.fromUri(this.providerCallback))
                || !this.providerCallback.getRawPath().equals("/login/oauth2/code/kakao")) {
            throw new IllegalArgumentException("Callback origin or path mismatch");
        }
        if (!this.frontend.scheme().equals(this.api.scheme()) || !this.frontend.host().equals(this.api.host())) {
            // 포트는 달라도 cookie의 host·scheme 경계는 같아야 함
            throw new IllegalArgumentException("Unsupported cookie topology");
        }
        boolean https = this.frontend.scheme().equals("https");
        if (cookieSecure != https || (!https && !this.frontend.isLoopback())) {
            throw new IllegalArgumentException("Unsupported cookie transport");
        }
        String path = this.frontendCallback.getRawPath();
        if (path.isEmpty() || !path.startsWith("/") || path.contains("%") || path.contains(";")
                || !this.frontendCallback.normalize().getRawPath().equals(path)
                || path.startsWith("//") || protectedPath(path)) {
            // API·OAuth entry point로 callback이 돌아가 로그인 루프를 만드는 설정 거부
            throw new IllegalArgumentException("Frontend callback must be an HTML entry route");
        }
    }

    private static boolean protectedPath(String path) {
        return List.of("/api", "/oauth2", "/login", "/auth", "/ws", "/actuator", "/logout")
                .stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /** 금지 요소가 있는 요청은 정규화 전에 거부. HTTP/WS 연결은 후속 C 책임 */
    public boolean allows(String origin) {
        try { return frontend.equals(HttpOrigin.parse(origin)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    public List<String> allowedOrigins() { return frontend.allowedRepresentations(); }
    public HttpOrigin frontendOrigin() { return frontend; }
    public HttpOrigin publicApiOrigin() { return api; }
    public URI frontendAuthCallbackUri() { return frontendCallback; }
    public URI providerCallbackUri() { return providerCallback; }
    public boolean cookieSecure() { return cookieSecure; }
}
