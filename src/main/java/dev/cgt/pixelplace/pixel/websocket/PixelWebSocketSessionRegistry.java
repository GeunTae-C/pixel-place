package dev.cgt.pixelplace.pixel.websocket;

import jakarta.websocket.Session;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/*
 * /ws 연결마다 전송 wrapper 하나를 소유하는 단일 서버 registry
 * 현재 entry identity로만 제거하여 지연된 실패 callback이 같은 ID의 새 연결을 삭제하는 경쟁 방지
 */
@Component
public class PixelWebSocketSessionRegistry {
    static final String BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";
    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final int sendTimeLimit;
    private final int bufferSizeLimit;
    private final BiConsumer<WebSocketSession, Throwable> failureLogger;

    public PixelWebSocketSessionRegistry() {
        this(5_000, 65_536, (session, failure) -> LoggerFactory.getLogger(PixelWebSocketSessionRegistry.class)
                .warn("Pixel WebSocket connection terminated. sessionId={}", session.getId(), failure));
    }

    // 제한 경합과 logging 실패를 결정론적으로 검증하는 package-private seam
    PixelWebSocketSessionRegistry(int sendTimeLimit, int bufferSizeLimit,
                                  BiConsumer<WebSocketSession, Throwable> failureLogger) {
        this.sendTimeLimit = sendTimeLimit;
        this.bufferSizeLimit = bufferSizeLimit;
        this.failureLogger = failureLogger;
    }

    /* native blocking 제한 설정에 성공한 열린 session만 게시. 같은 entry의 반복 등록은 wrapper 재사용 */
    public void add(WebSocketSession session) {
        var replaced = new AtomicReference<Entry>();
        try {
            sessions.compute(session.getId(), (id, current) -> {
                if (current != null && current.matches(session)) return current;
                if (!session.isOpen()) {
                    throw new IllegalStateException("Cannot register a closed WebSocket session.");
                }
                WebSocketSession raw = WebSocketSessionDecorator.unwrap(session);
                if (!(raw instanceof NativeWebSocketSession nativeSession)) {
                    // 현재 embedded Tomcat에서 native 설정을 적용할 수 없는 연결은 무제한 전송으로 노출 금지
                    throw new IllegalStateException("Native WebSocket session is required.");
                }
                Session nativeDelegate = nativeSession.getNativeSession(Session.class);
                if (nativeDelegate == null) {
                    throw new IllegalStateException("Jakarta WebSocket session is required.");
                }
                // Spring attributes가 아닌 Tomcat의 실제 native userProperties 사용
                nativeDelegate.getUserProperties().put(BLOCKING_SEND_TIMEOUT, 5_000L);
                var wrapper = new ConcurrentWebSocketSessionDecorator(session, sendTimeLimit, bufferSizeLimit,
                        ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
                replaced.set(current);
                return new Entry(session, wrapper);
            });
        } catch (RuntimeException | Error failure) {
            closeAndReport(session, failure);
            throw failure;
        }
        if (replaced.get() != null) {
            closeAndReport(replaced.get().wrapper(), new IllegalStateException("WebSocket session entry replaced."));
        }
    }

    /* raw와 소유 wrapper 어느 쪽 callback도 현재 entry와 일치할 때만 제거 */
    public void remove(WebSocketSession session) { detach(session); }

    /* snapshot에는 소유 wrapper만 포함하며 broadcast 중 재등록이나 wrapper 재생성 없음 */
    public List<WebSocketSession> snapshot() {
        return sessions.values().stream().map(entry -> (WebSocketSession) entry.wrapper()).toList();
    }

    /* 실패 연결을 먼저 제거한 뒤 close·logging. 통상 실패는 원인에 부가하고 raw Error는 보존 */
    public void terminate(WebSocketSession session, Throwable failure) {
        Entry removed = detach(session);
        if (removed != null) closeAndReport(removed.wrapper(), failure);
        if (failure instanceof Error error) throw error;
    }

    private Entry detach(WebSocketSession session) {
        var removed = new AtomicReference<Entry>();
        sessions.computeIfPresent(session.getId(), (id, current) -> {
            if (!current.matches(session)) return current;
            removed.set(current);
            return null;
        });
        return removed.get();
    }

    private void closeAndReport(WebSocketSession session, Throwable failure) {
        Throwable primary = failure;
        try {
            if (session.isOpen()) session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException | RuntimeException | Error closeFailure) {
            primary = preserveFailure(primary, closeFailure);
        }
        try { failureLogger.accept(session, primary); }
        catch (RuntimeException | Error loggingFailure) { primary = preserveFailure(primary, loggingFailure); }
        if (primary instanceof Error error) throw error;
    }

    /* 기존 Error identity 우선, 일반 실패 뒤 새 Error는 숨기지 않고 원인을 suppressed로 보존 */
    public static Throwable preserveFailure(Throwable primary, Throwable secondary) {
        if (primary == secondary) return primary;
        if (!(primary instanceof Error) && secondary instanceof Error) {
            addDistinctSuppressed(secondary, primary);
            return secondary;
        }
        addDistinctSuppressed(primary, secondary);
        return primary;
    }

    private static void addDistinctSuppressed(Throwable primary, Throwable secondary) {
        for (Throwable existing : primary.getSuppressed()) if (existing == secondary) return;
        primary.addSuppressed(secondary);
    }

    // raw callback과 broadcast wrapper의 exact identity를 함께 소유
    private record Entry(WebSocketSession raw, ConcurrentWebSocketSessionDecorator wrapper) {
        boolean matches(WebSocketSession session) { return session == raw || session == wrapper; }
    }
}
