package dev.cgt.pixelplace.pixel.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/*
 * pixel-place raw WebSocket endpoint 설정
 * MVP에서는 읽기 broadcast 채널로 보고 /ws 연결을 인증 없이 허용
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
