package dev.cgt.pixelplace.pixel.websocket;

import dev.cgt.pixelplace.pixel.application.PixelEventMessage;
import dev.cgt.pixelplace.pixel.infra.WebSocketPixelBroadcastService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/* 실제 registry wrapper를 두 broadcaster가 공유할 때의 전송·제한·오류 소유권 검증 */
class PixelWebSocketConcurrencyTest {
    private static final PixelEventMessage EVENT = new PixelEventMessage("pixel", 1, 2, 3, 10, 12);

    @Test
    void twoBroadcastersShareOneWrapperAndNeverOverlapNativeSends() throws Exception {
        var registry = new PixelWebSocketSessionRegistry();
        var raw = NativeSessionFixture.session("one"); registry.add(raw);
        var wrapper = registry.snapshot().getFirst();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var maximum = new AtomicInteger(); var sent = new AtomicInteger();
        doAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { if (sent.incrementAndGet() == 1) { entered.countDown(); await(release); } }
            finally { active.decrementAndGet(); }
            return null;
        }).when(raw).sendMessage(any());
        var first = new WebSocketPixelBroadcastService(registry, new ObjectMapper(), dev.cgt.pixelplace.measurement.Measurements.disabled());
        var second = new WebSocketPixelBroadcastService(registry, new ObjectMapper(), dev.cgt.pixelplace.measurement.Measurements.disabled());
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var send = pool.submit(() -> first.broadcast(EVENT)); await(entered);
                pool.submit(() -> second.broadcast(EVENT)).get(5, TimeUnit.SECONDS);
                assertSame(wrapper, registry.snapshot().getFirst()); assertEquals(1, sent.get());
                release.countDown(); send.get(5, TimeUnit.SECONDS);
                assertEquals(2, sent.get()); assertEquals(1, maximum.get());
            } finally { release.countDown(); }
        }
        registry.remove(raw); assertTrue(registry.snapshot().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bufferOrTimeLimitTerminatesSlowSessionAndContinuesHealthySession(boolean timeLimit) throws Exception {
        var registry = new PixelWebSocketSessionRegistry(timeLimit ? 1 : 5000, timeLimit ? 65_536 : 1, (s, f) -> { });
        var slow = NativeSessionFixture.session("slow"); var healthy = NativeSessionFixture.session("healthy");
        registry.add(slow); registry.add(healthy);
        var wrapper = (ConcurrentWebSocketSessionDecorator) registry.snapshot().stream().filter(s -> s.getId().equals("slow")).findFirst().orElseThrow();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); await(release); return null; }).when(slow).sendMessage(any());
        var service = new WebSocketPixelBroadcastService(registry, new ObjectMapper(), dev.cgt.pixelplace.measurement.Measurements.disabled());
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var first = pool.submit(() -> service.broadcast(EVENT)); await(entered);
                if (timeLimit) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (wrapper.getTimeSinceSendStarted() <= 2 && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertTrue(wrapper.getTimeSinceSendStarted() > 2);
                }
                service.broadcast(EVENT);
                assertEquals(1, registry.snapshot().size()); assertEquals("healthy", registry.snapshot().getFirst().getId());
                verify(slow).close(CloseStatus.SESSION_NOT_RELIABLE); verify(healthy, atLeastOnce()).sendMessage(any());
                release.countDown(); first.get(5, TimeUnit.SECONDS);
                assertEquals(1, registry.snapshot().size());
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendIOExceptionOrRuntimeAndCloseLoggingFailuresDoNotBlockOtherSessions(boolean runtime) throws Exception {
        var logFailure = new IllegalStateException("logger");
        var registry = new PixelWebSocketSessionRegistry(5000, 65_536, (s, f) -> { throw logFailure; });
        var bad = NativeSessionFixture.session("bad"); var good = NativeSessionFixture.session("good");
        registry.add(bad); registry.add(good);
        Throwable failure = runtime ? new IllegalStateException("send") : new IOException("send");
        var closeFailure = new IllegalStateException("close");
        doThrow(failure).when(bad).sendMessage(any()); doThrow(closeFailure).when(bad).close(any());
        assertDoesNotThrow(() -> new WebSocketPixelBroadcastService(registry, new ObjectMapper(), dev.cgt.pixelplace.measurement.Measurements.disabled()).broadcast(EVENT));
        verify(good).sendMessage(any()); verify(bad).close(CloseStatus.SESSION_NOT_RELIABLE);
        assertArrayEquals(new Throwable[]{closeFailure, logFailure}, failure.getSuppressed());
        assertEquals(1, registry.snapshot().size());
    }

    @Test
    void serializationFailureClosesWholeSnapshotAndPropagatesOriginal() throws Exception {
        var registry = new PixelWebSocketSessionRegistry();
        var first = NativeSessionFixture.session("1"); var second = NativeSessionFixture.session("2");
        registry.add(first); registry.add(second);
        var mapper = mock(ObjectMapper.class); var failure = new IllegalStateException("serialize");
        when(mapper.writeValueAsString(EVENT)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> new WebSocketPixelBroadcastService(registry, mapper, dev.cgt.pixelplace.measurement.Measurements.disabled()).broadcast(EVENT)));
        verify(first).close(any()); verify(second).close(any());
        verify(first, never()).sendMessage(any()); verify(second, never()).sendMessage(any());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void rawSendErrorIsPreservedWithCloseAndLoggingErrorsAfterMinimumCleanup() throws Exception {
        var error = new AssertionError("send"); var closeError = new AssertionError("close"); var logError = new AssertionError("log");
        var preexisting = new IllegalStateException("existing"); error.addSuppressed(preexisting);
        var registry = new PixelWebSocketSessionRegistry(5000, 65_536, (s, f) -> { throw logError; });
        var session = NativeSessionFixture.session("error"); registry.add(session);
        doThrow(error).when(session).sendMessage(any()); doThrow(closeError).when(session).close(any());
        assertSame(error, assertThrows(AssertionError.class, () -> new WebSocketPixelBroadcastService(registry, new ObjectMapper(), dev.cgt.pixelplace.measurement.Measurements.disabled()).broadcast(EVENT)));
        assertArrayEquals(new Throwable[]{preexisting, closeError, logError}, error.getSuppressed());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void serializationErrorStillCleansAllSessionsAndCleanupErrorIsNotSwallowed() throws Exception {
        var registry = new PixelWebSocketSessionRegistry(); var mapper = mock(ObjectMapper.class);
        var first = NativeSessionFixture.session("1"); var second = NativeSessionFixture.session("2");
        registry.add(first); registry.add(second);
        var error = new AssertionError("serialize"); when(mapper.writeValueAsString(EVENT)).thenThrow(error);
        doThrow(new AssertionError("close")).when(first).close(any());
        assertSame(error, assertThrows(AssertionError.class, () -> new WebSocketPixelBroadcastService(registry, mapper, dev.cgt.pixelplace.measurement.Measurements.disabled()).broadcast(EVENT)));
        verify(second).close(any()); assertTrue(registry.snapshot().isEmpty());
        var next = NativeSessionFixture.session("3"); registry.add(next);
        var closeError = new AssertionError("raw close"); doThrow(closeError).when(next).close(any());
        assertSame(closeError, assertThrows(AssertionError.class, () -> registry.terminate(next, new IOException("transport"))));
        assertTrue(registry.snapshot().isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
}
