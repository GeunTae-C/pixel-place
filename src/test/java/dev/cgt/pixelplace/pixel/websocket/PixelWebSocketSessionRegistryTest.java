package dev.cgt.pixelplace.pixel.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

        var wrapper = assertInstanceOf(ConcurrentWebSocketSessionDecorator.class, registry.snapshot().getFirst());
        assertSame(session, wrapper.getDelegate());
        registry.add(session);
        registry.add(wrapper);
        assertSame(wrapper, registry.snapshot().getFirst());
        assertEquals(5_000, wrapper.getSendTimeLimit());
        assertEquals(65_536, wrapper.getBufferSizeLimit());
        assertEquals(ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE, wrapper.getOverflowStrategy());
        var nativeSession = ((org.springframework.web.socket.adapter.NativeWebSocketSession) session).getNativeSession(jakarta.websocket.Session.class);
        assertEquals(5_000L, nativeSession.getUserProperties().get(PixelWebSocketSessionRegistry.BLOCKING_SEND_TIMEOUT));
        assertInstanceOf(Long.class, nativeSession.getUserProperties().get(PixelWebSocketSessionRegistry.BLOCKING_SEND_TIMEOUT));
    }

    @Test
    void removeDeletesSessionFromSnapshot() {
        WebSocketSession session = session("session-1");
        registry.add(session);

        registry.remove(session);

        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void addReplacesSessionWhenSessionIdIsSame() {
        WebSocketSession first = session("session-1");
        WebSocketSession second = session("session-1");

        registry.add(first);
        WebSocketSession oldWrapper = registry.snapshot().getFirst();
        registry.add(second);

        List<WebSocketSession> snapshot = registry.snapshot();
        assertSame(second, ((ConcurrentWebSocketSessionDecorator) snapshot.get(0)).getDelegate());
        assertFalse(snapshot.contains(first));
        registry.remove(first);
        registry.terminate(oldWrapper, new IllegalStateException("late failure"));
        assertSame(snapshot.getFirst(), registry.snapshot().getFirst());
        registry.remove(snapshot.getFirst());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void incompatibleNativeOrFailedPropertySettingClosesWithoutPublishing() throws Exception {
        var raw = mock(WebSocketSession.class);
        when(raw.getId()).thenReturn("invalid"); when(raw.isOpen()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> registry.add(raw));
        verify(raw).close(org.springframework.web.socket.CloseStatus.SESSION_NOT_RELIABLE);
        var missing = NativeSessionFixture.session("missing");
        when(missing.getNativeSession(jakarta.websocket.Session.class)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> registry.add(missing));
        verify(missing).close(org.springframework.web.socket.CloseStatus.SESSION_NOT_RELIABLE);
        var denied = NativeSessionFixture.session("denied");
        when(denied.getNativeSession(jakarta.websocket.Session.class).getUserProperties()).thenReturn(java.util.Map.of());
        assertThrows(UnsupportedOperationException.class, () -> registry.add(denied));
        verify(denied).close(org.springframework.web.socket.CloseStatus.SESSION_NOT_RELIABLE);
        assertTrue(registry.snapshot().isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.add(denied));
    }

    private WebSocketSession session(String id) {
        return NativeSessionFixture.session(id);
    }
}
