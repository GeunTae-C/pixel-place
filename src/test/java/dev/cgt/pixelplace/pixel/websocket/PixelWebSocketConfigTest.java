package dev.cgt.pixelplace.pixel.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * /ws raw WebSocket endpoint 등록 검증
 * STOMP/SockJS/broker 설정 없이 단일 handler만 등록하는 MVP 경계
 */
class PixelWebSocketConfigTest {

    @Test
    void registerWebSocketHandlersRegistersRawWsEndpoint() {
        PixelWebSocketHandler handler = mock(PixelWebSocketHandler.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(handler, "/ws")).thenReturn(registration);
        PixelWebSocketConfig config = new PixelWebSocketConfig(handler);

        config.registerWebSocketHandlers(registry);

        verify(registry).addHandler(handler, "/ws");
        verify(registration).setAllowedOriginPatterns("*");
    }
}
