package dev.cgt.pixelplace.pixel.websocket;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;

/** WS 등록 경로에서 원본 Origin을 공용 정책으로 검사. JWT key·사용자 인증·readiness 책임 없음 */
public final class FrontendOriginHandshakeInterceptor implements HandshakeInterceptor {
    private final OriginPolicy origins;
    public FrontendOriginHandshakeInterceptor(OriginPolicy origins) { this.origins=origins; }
    /** Origin 없는 non-browser는 다음 handshake 검사로 위임. 불신 입력은 same-origin 우회 전에 거부 */
    @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes) {
        var values=request.getHeaders().get(HttpHeaders.ORIGIN);
        if(values!=null && (values.size()!=1 || !origins.allows(values.getFirst()))) {
            response.setStatusCode(HttpStatus.FORBIDDEN); return false;
        }
        return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception failure) { }
}
