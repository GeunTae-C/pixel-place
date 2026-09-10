package dev.cgt.pixelplace.auth.web;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import dev.cgt.pixelplace.auth.oauth2.HandoffCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Map;

/** 교환 HTTP 입출력 담당. B2에서는 annotation·scan 등록 없이 명시적 fixture만 사용 */
public final class HandoffTokenController {
    private final OriginPolicy origins;
    private final HandoffCookie cookie;
    private final HandoffExchangeService exchange;
    private final JsonMapper json;

    public HandoffTokenController(OriginPolicy origins, HandoffCookie cookie, HandoffExchangeService exchange, JsonMapper json) {
        this.origins = origins; this.cookie = cookie; this.exchange = exchange; this.json = json;
    }

    /** trusted Origin → cookie → handoff 인증 → Access/JSON 준비 → 삭제·게시 순서. route 활성화는 C 책임 */
    public void exchange(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var values = request.getHeaders(HttpHeaders.ORIGIN);
        String origin = values != null && values.hasMoreElements() ? values.nextElement() : null;
        if (origin == null || values.hasMoreElements() || !origins.allows(origin)) {
            // Origin 거부에서는 cookie 해석·decoder·발급을 시작하면 안 됨
            write(response, 403, json.writeValueAsBytes(Map.of("error", "forbidden_origin")));
            return;
        }
        int status;
        byte[] body;
        try {
            body = json.writeValueAsBytes(exchange.exchange(cookie.read(request)));
            status = 200;
        } catch (HandoffExchangeService.InvalidHandoff failure) {
            body = json.writeValueAsBytes(Map.of("error", "invalid_handoff"));
            status = 401;
        } catch (RuntimeException failure) {
            // 유효 handoff 뒤 발급·시간·직렬화 실패는 일반화된 500. 부분 token 응답 금지
            body = json.writeValueAsBytes(Map.of("error", "token_exchange_failed"));
            status = 500;
        }
        cookie.delete(response);
        write(response, status, body);
    }

    private static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        // 전송은 준비 실패 catch 바깥. commit 뒤 오류에서 응답 재작성 금지
        response.getOutputStream().write(body);
    }
}
