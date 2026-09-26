package dev.cgt.pixelplace.pixel.websocket;

import jakarta.websocket.Session;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;

/* native userProperties와 close 상태를 가진 test-only session. 실제 네트워크 검증은 embedded 테스트 담당 */
public final class NativeSessionFixture {
    public static NativeWebSocketSession session(String id) {
        var session = mock(NativeWebSocketSession.class);
        var nativeSession = mock(Session.class);
        var open = new AtomicBoolean(true);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(call -> open.get());
        when(session.getNativeSession(Session.class)).thenReturn(nativeSession);
        when(nativeSession.getUserProperties()).thenReturn(new HashMap<>());
        try { doAnswer(call -> { open.set(false); return null; }).when(session).close(any()); }
        catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
        return session;
    }

    private NativeSessionFixture() { }
}
