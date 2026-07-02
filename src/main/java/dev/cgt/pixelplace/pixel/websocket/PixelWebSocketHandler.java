package dev.cgt.pixelplace.pixel.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/*
 * /ws raw WebSocket lifecycle 처리
 * 연결 session 관리만 담당하며 pixel write, WAL, cooldown 책임은 갖지 않음
 */
@Component
public class PixelWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PixelWebSocketHandler.class);

    private final PixelWebSocketSessionRegistry sessionRegistry;

    public PixelWebSocketHandler(PixelWebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /*
     * 새 WebSocket 연결 등록
     * MVP broadcast 대상 목록 갱신 목적
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.add(session);
    }

    /*
     * 정상/비정상 close 이후 session registry 정리
     * 끊긴 session으로 후속 broadcast 전송 시도 방지
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.remove(session);
    }

    /*
     * transport 오류 session 정리
     * 열린 session은 close 시도, 실패해도 write 결과와 무관한 lifecycle 후처리
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessionRegistry.remove(session);

        if (!session.isOpen()) {
            return;
        }

        try {
            session.close();
        } catch (IOException closeException) {
            log.warn("Pixel WebSocket session close failed. sessionId={}", session.getId(), closeException);
        }
    }
}
