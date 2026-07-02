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
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        handler.handleTransportError(session, new RuntimeException("network error"));

        verify(sessionRegistry).remove(session);
        verify(session).close();
    }

    @Test
    void handleTransportErrorDoesNotPropagateCloseFailure() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doThrow(new IOException("close failed")).when(session).close();

        assertDoesNotThrow(() -> handler.handleTransportError(session, new RuntimeException("network error")));

        verify(sessionRegistry).remove(session);
        verify(session).close();
    }
}
