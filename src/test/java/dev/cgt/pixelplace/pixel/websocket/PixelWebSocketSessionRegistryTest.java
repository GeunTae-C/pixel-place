package dev.cgt.pixelplace.pixel.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * WebSocket session registry 메모리 관리 규칙 검증
 * broadcast 대상 목록일 뿐 pixel state 저장소가 아님
 */
class PixelWebSocketSessionRegistryTest {

    private final PixelWebSocketSessionRegistry registry = new PixelWebSocketSessionRegistry();

    @Test
    void addIncludesSessionInSnapshot() {
        WebSocketSession session = session("session-1");

        registry.add(session);

        assertTrue(registry.snapshot().contains(session));
    }

    @Test
    void removeDeletesSessionFromSnapshot() {
        WebSocketSession session = session("session-1");
        registry.add(session);

        registry.remove(session);

        assertFalse(registry.snapshot().contains(session));
    }

    @Test
    void addReplacesSessionWhenSessionIdIsSame() {
        WebSocketSession first = session("session-1");
        WebSocketSession second = session("session-1");

        registry.add(first);
        registry.add(second);

        List<WebSocketSession> snapshot = registry.snapshot();
        assertSame(second, snapshot.get(0));
        assertFalse(snapshot.contains(first));
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
