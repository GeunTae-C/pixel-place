package dev.cgt.pixelplace.pixel.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * /ws lifecycle handler registry 갱신 규칙 검증
 * inbound message write 경로는 이번 단계 책임 아님
 */
class PixelWebSocketHandlerTest {

    private final PixelWebSocketSessionRegistry sessionRegistry = mock(PixelWebSocketSessionRegistry.class);
    private final PixelWebSocketHandler handler = new PixelWebSocketHandler(sessionRegistry);

    @Test
    void afterConnectionEstablishedAddsSessionToRegistry() {
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionEstablished(session);

        verify(sessionRegistry).add(session);
    }

    @Test
    void afterConnectionClosedRemovesSessionFromRegistry() {
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(sessionRegistry).remove(session);
    }

    @Test
    void handleTransportErrorRemovesAndClosesOpenSession() throws IOException {
        var registry = new PixelWebSocketSessionRegistry();
        var realHandler = new PixelWebSocketHandler(registry);
        WebSocketSession session = NativeSessionFixture.session("transport");
        realHandler.afterConnectionEstablished(session);

        realHandler.handleTransportError(session, new RuntimeException("network error"));
        realHandler.handleTransportError(session, new RuntimeException("duplicate"));
        realHandler.afterConnectionClosed(session, CloseStatus.NORMAL);

        org.junit.jupiter.api.Assertions.assertTrue(registry.snapshot().isEmpty());
        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void handleTransportErrorDoesNotPropagateCloseFailure() throws IOException {
        var registry = new PixelWebSocketSessionRegistry();
        var realHandler = new PixelWebSocketHandler(registry);
        WebSocketSession session = NativeSessionFixture.session("close-failed");
        registry.add(session);
        doThrow(new IOException("close failed")).when(session).close(CloseStatus.SESSION_NOT_RELIABLE);

        assertDoesNotThrow(() -> realHandler.handleTransportError(session, new RuntimeException("network error")));

        org.junit.jupiter.api.Assertions.assertTrue(registry.snapshot().isEmpty());
        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
    }
}
