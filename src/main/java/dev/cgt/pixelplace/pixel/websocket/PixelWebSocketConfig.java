package dev.cgt.pixelplace.pixel.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/*
 * pixel-place raw WebSocket endpoint 설정
 * 현재 handler/config 자체에 application-level 인증 로직 없음
 * 실제 /ws handshake 접근 제한은 현재 Spring Security filter chain에 따름
 * 최종 WebSocket 인증 정책은 13단계 확정 대상
 */
@Configuration
@EnableWebSocket
public class PixelWebSocketConfig implements WebSocketConfigurer {

    private final PixelWebSocketHandler pixelWebSocketHandler;

    public PixelWebSocketConfig(PixelWebSocketHandler pixelWebSocketHandler) {
        this.pixelWebSocketHandler = pixelWebSocketHandler;
    }

    /*
     * /ws raw WebSocket endpoint 등록
     * origin 정책은 개발/MVP 범위 임시 허용, 이후 security/JWT 단계에서 축소 대상
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pixelWebSocketHandler, "/ws")
                .setAllowedOriginPatterns("*");
    }
}
