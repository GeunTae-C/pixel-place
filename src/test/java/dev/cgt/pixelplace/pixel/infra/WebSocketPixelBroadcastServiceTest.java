package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.pixel.application.PixelEventMessage;
import dev.cgt.pixelplace.pixel.websocket.PixelWebSocketSessionRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * WebSocket broadcast adapter 전송 규칙 검증
 * session별 실패는 다른 session 전파와 core write 결과에 영향 없음
 */
class WebSocketPixelBroadcastServiceTest {

    private final PixelWebSocketSessionRegistry sessionRegistry = mock(PixelWebSocketSessionRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebSocketPixelBroadcastService service = new WebSocketPixelBroadcastService(
            sessionRegistry,
            objectMapper
    );

    @Test
    void broadcastSendsPixelEventJsonToOpenSessionWithoutSeqOrTileVersion() throws Exception {
        WebSocketSession session = session("session-1", true);
        when(sessionRegistry.snapshot()).thenReturn(List.of(session));
        PixelEventMessage message = new PixelEventMessage("pixel", 768, 1280, 17, 10L);

        service.broadcast(message);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(
                messageCaptor.getValue().getPayload(),
                Map.class
        );
        assertAll(
                () -> assertEquals("pixel", payload.get("type")),
                () -> assertEquals(768, payload.get("x")),
                () -> assertEquals(1280, payload.get("y")),
                () -> assertEquals(17, payload.get("color")),
                () -> assertEquals(10, payload.get("eventSeq")),
                () -> assertFalse(payload.containsKey("seq")),
                () -> assertFalse(payload.containsKey("tileVersion"))
        );
    }

    @Test
    void broadcastRemovesClosedSessionWithoutSending() throws IOException {
        WebSocketSession session = session("session-1", false);
        when(sessionRegistry.snapshot()).thenReturn(List.of(session));

        service.broadcast(new PixelEventMessage("pixel", 768, 1280, 17, 10L));

        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        verify(sessionRegistry).remove(session);
    }

    @Test
    void broadcastContinuesWhenOneSessionSendFails() throws IOException {
        WebSocketSession failedSession = session("session-1", true);
        WebSocketSession successSession = session("session-2", true);
        when(sessionRegistry.snapshot()).thenReturn(List.of(failedSession, successSession));
        doThrow(new IOException("send failed"))
                .when(failedSession).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));

        assertDoesNotThrow(() -> service.broadcast(new PixelEventMessage("pixel", 768, 1280, 17, 10L)));

        verify(sessionRegistry).remove(failedSession);
        verify(successSession).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void broadcastDoesNothingWhenSessionSnapshotIsEmpty() {
        when(sessionRegistry.snapshot()).thenReturn(List.of());

        assertDoesNotThrow(() -> service.broadcast(new PixelEventMessage("pixel", 768, 1280, 17, 10L)));
    }

    private WebSocketSession session(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        return session;
    }
}
