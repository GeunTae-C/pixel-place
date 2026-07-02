package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.pixel.application.PixelBroadcastService;
import dev.cgt.pixelplace.pixel.application.PixelEventMessage;
import dev.cgt.pixelplace.pixel.websocket.PixelWebSocketSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/*
 * PixelBroadcastService WebSocket 구현체
 * 현재 연결된 /ws session에 단건 pixel event JSON 전파 담당
 */
@Component
public class WebSocketPixelBroadcastService implements PixelBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPixelBroadcastService.class);

    private final PixelWebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public WebSocketPixelBroadcastService(
            PixelWebSocketSessionRegistry sessionRegistry,
            ObjectMapper objectMapper
    ) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    /*
     * write 성공 이후 WebSocket fan-out
     * 개별 session 실패는 전체 broadcast 중단 사유가 아님
     */
    @Override
    public void broadcast(PixelEventMessage message) {
        String payload = serialize(message);
        TextMessage textMessage = new TextMessage(payload);

        for (WebSocketSession session : sessionRegistry.snapshot()) {
            sendToSession(session, textMessage, message.eventSeq());
        }
    }

    private String serialize(PixelEventMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JacksonException exception) {
            // 직렬화 실패는 command service에서 write rollback 없이 warning 처리할 후처리 실패
            throw new IllegalStateException("Pixel event message serialization failed.", exception);
        }
    }

    private void sendToSession(WebSocketSession session, TextMessage message, long eventSeq) {
        if (!session.isOpen()) {
            sessionRegistry.remove(session);
            return;
        }

        try {
            session.sendMessage(message);
        } catch (IOException exception) {
            sessionRegistry.remove(session);
            log.warn("Pixel WebSocket broadcast failed. sessionId={}, eventSeq={}", session.getId(), eventSeq, exception);
        }
    }
}
