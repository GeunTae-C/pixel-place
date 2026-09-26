package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.pixel.application.PixelBroadcastService;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.pixel.application.PixelEventMessage;
import dev.cgt.pixelplace.pixel.websocket.PixelWebSocketSessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/*
 * PixelBroadcastService WebSocket 구현체
 * 현재 연결된 /ws session에 단건 pixel event JSON 전파 담당
 */
@Component
public class WebSocketPixelBroadcastService implements PixelBroadcastService {

    private final PixelWebSocketSessionRegistry sessionRegistry;
    private final PixelMeasurement measurement;
    private final ObjectMapper objectMapper;

    public WebSocketPixelBroadcastService(
            PixelWebSocketSessionRegistry sessionRegistry,
            ObjectMapper objectMapper,
            PixelMeasurement measurement
    ) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.measurement = measurement;
    }

    /*
     * write 성공 이후 WebSocket fan-out
     * 개별 session 실패는 전체 broadcast 중단 사유가 아님
     */
    @Override
    public void broadcast(PixelEventMessage message) {
        List<WebSocketSession> targets = sessionRegistry.snapshot();
        TextMessage textMessage;
        try {
            textMessage = new TextMessage(objectMapper.writeValueAsString(message));
        } catch (RuntimeException | Error failure) {
            // 확보한 전체 대상은 이 이벤트를 못 받으므로 같은 연결을 유지하지 않음
            Throwable primary = failure;
            for (WebSocketSession session : targets) {
                measurement.broadcastFailure();
                try { sessionRegistry.terminate(session, failure); }
                catch (Error cleanupFailure) { primary = PixelWebSocketSessionRegistry.preserveFailure(primary, cleanupFailure); }
            }
            if (primary instanceof Error error) throw error;
            throw failure;
        }
        for (WebSocketSession session : targets) {
            sendToSession(session, textMessage);
        }
    }

    private void sendToSession(WebSocketSession session, TextMessage message) {
        try {
            if (!session.isOpen()) {
                sessionRegistry.remove(session);
                return;
            }
            session.sendMessage(message);
        } catch (IOException | RuntimeException exception) {
            measurement.broadcastFailure();
            sessionRegistry.terminate(session, exception);
        } catch (Error failure) {
            measurement.broadcastFailure();
            // JVM-level 실패를 통상적인 session 실패로 삼키지 않으며 원래 instance로 전파
            sessionRegistry.terminate(session, failure);
            throw failure;
        }
    }
}
