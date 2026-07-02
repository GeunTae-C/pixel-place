package dev.cgt.pixelplace.pixel.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/*
 * /ws 연결 session 보관소
 * 단일 서버 MVP 기준 메모리 session registry이며 authoritative pixel state가 아님
 */
@Component
public class PixelWebSocketSessionRegistry {

    private final ConcurrentMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void remove(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    public List<WebSocketSession> snapshot() {
        return List.copyOf(sessions.values());
    }
}
