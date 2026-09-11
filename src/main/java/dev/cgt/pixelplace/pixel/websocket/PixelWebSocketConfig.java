package dev.cgt.pixelplace.pixel.websocket;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/*
 * pixel-place raw WebSocket endpoint 설정
 * 공개 broadcast 유지, 공용 frontend Origin 정책만 실제 handshake에 적용
 * JWT 인증·readiness gate·기존 연결 강제 종료 책임 없음
 */
@Configuration
@EnableWebSocket
public class PixelWebSocketConfig implements WebSocketConfigurer {

    private final PixelWebSocketHandler pixelWebSocketHandler;
    private final OriginPolicy origins;

    public PixelWebSocketConfig(PixelWebSocketHandler pixelWebSocketHandler, OriginPolicy origins) {
        this.pixelWebSocketHandler = pixelWebSocketHandler;
        this.origins = origins;
    }

    /*
     * /ws raw WebSocket endpoint 등록
     * 기본 same-origin 처리보다 앞선 공용 검사와 명시적 허용 표현 연결
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pixelWebSocketHandler, "/ws")
                .addInterceptors(new FrontendOriginHandshakeInterceptor(origins))
                .setAllowedOrigins(origins.allowedOrigins().toArray(String[]::new));
    }
}
