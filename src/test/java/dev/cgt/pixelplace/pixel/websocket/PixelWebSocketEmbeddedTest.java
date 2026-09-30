package dev.cgt.pixelplace.pixel.websocket;

import dev.cgt.pixelplace.auth.config.OriginPolicy;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.pixel.application.PixelEventMessage;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
import dev.cgt.pixelplace.pixel.infra.WebSocketPixelBroadcastService;
import dev.cgt.pixelplace.pixel.support.PixelReceiverModel;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.TileReadService;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.web.TileController;
import dev.cgt.pixelplace.wal.application.WalAppender;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;
import org.apache.tomcat.websocket.WsSession;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/* 실제 embedded Tomcat·production /ws 등록·Tile controller 경계 검증. 외부 DB/provider 사용 없음 */
@Timeout(40)
class PixelWebSocketEmbeddedTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"single", "group"})
    void eachCommandModeBroadcastReachesActualWebSocketAndHttpTileVersion(String mode) throws Exception {
        try(var server=new Server(directory)) {
            var client=HttpClient.newHttpClient();var messages=new Messages();
            var ws=client.newWebSocketBuilder().header("Origin","http://localhost:3000")
                    .buildAsync(URI.create("ws://localhost:"+server.port()+"/ws"),messages).get(5,TimeUnit.SECONDS);
            try {
                awaitSessions(server.registry,1);
                var measure=dev.cgt.pixelplace.measurement.Measurements.disabled();
                var dirty=mock(dev.cgt.pixelplace.tile.application.DirtyTileTracker.class);
                var cooldown=mock(dev.cgt.pixelplace.pixel.application.PixelCooldown.class);
                // 기존 single 송신 경계와 복원된 group 경계를 같은 실제 WS/HTTP 관측으로 유지
                var boundary=new dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator(measure);
                dev.cgt.pixelplace.pixel.application.PixelWriteExecutor selected=mode.equals("group")
                        ? new dev.cgt.pixelplace.pixel.application.GroupPixelWriteExecutor(boundary,server.core,dirty,
                            server.readiness,new dev.cgt.pixelplace.pixel.application.WriteExecutionProperties(),measure)
                        : new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(boundary,server.core,dirty,server.readiness,measure);
                try(var executor=selected) {
                    var command=new dev.cgt.pixelplace.pixel.application.PixelCommandService(cooldown,executor,server.broadcaster,
                            server.readiness,new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),measure);
                    var result=command.writePixel(42,1,2,17);var event=messages.next();
                    assertEquals(result.eventSeq(),event.eventSeq());assertEquals(result.tileVersion(),event.tileVersion());assertEquals(17,event.color());
                    var snapshot=getSnapshot(client,server.port());assertEquals(result.tileVersion(),snapshot.version());assertEquals(17,snapshot.bytes()[513]);
                    org.mockito.Mockito.verify(cooldown).startCooldown(42);org.mockito.Mockito.verify(dirty).markDirty(result.tileKey(),result.eventSeq(),result.tileVersion());
                }
            }finally{ws.abort();}
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({PixelWebSocketConfig.class, PixelWebSocketHandler.class, PixelWebSocketSessionRegistry.class, TileController.class, TileReadService.class})
    static class Fixture {
        // 실제 TileController의 공유 계측 의존을 가진 제한 embedded graph. 관측 off와 HTTP/WS 의미 유지
        @Bean dev.cgt.pixelplace.measurement.PixelMeasurement measurement() { return dev.cgt.pixelplace.measurement.Measurements.disabled(); }
        @Bean OriginPolicy origins() { return new OriginPolicy("http://localhost:3000", "http://localhost:8080",
                "http://localhost:3000/", "http://localhost:8080/login/oauth2/code/kakao", false); }
        @Bean InMemoryTileBoard board() { return new InMemoryTileBoard(); }
    }

    @Test
    void realHandlerPublishesNativeTimeoutAndSingleBlockedSendTimesOut() throws Exception {
        try (var server = new Server(directory); var socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5000);
            String request = "GET /ws HTTP/1.1\r\nHost: localhost:" + server.port()
                    + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13"
                    + "\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            var response = new StringBuilder();
            while (!response.toString().endsWith("\r\n\r\n") && response.length() < 8192) {
                int next = socket.getInputStream().read(); assertNotEquals(-1, next); response.append((char) next);
            }
            assertTrue(response.toString().startsWith("HTTP/1.1 101"));
            awaitSessions(server.registry, 1);
            var wrapper = assertInstanceOf(ConcurrentWebSocketSessionDecorator.class, server.registry.snapshot().getFirst());
            var raw = assertInstanceOf(NativeWebSocketSession.class, wrapper.getDelegate());
            var nativeSession = raw.getNativeSession(jakarta.websocket.Session.class);
            assertInstanceOf(WsSession.class, nativeSession);
            assertEquals(5000L, nativeSession.getUserProperties().get(PixelWebSocketSessionRegistry.BLOCKING_SEND_TIMEOUT));
            assertInstanceOf(Long.class, nativeSession.getUserProperties().get(PixelWebSocketSessionRegistry.BLOCKING_SEND_TIMEOUT));
            assertFalse(raw.getAttributes().containsKey(PixelWebSocketSessionRegistry.BLOCKING_SEND_TIMEOUT));
            assertEquals(5000, wrapper.getSendTimeLimit()); assertEquals(65_536, wrapper.getBufferSizeLimit());
            // 작은 수신 window와 읽지 않는 peer로 실제 native send 정체 구성. payload는 wire 압박용 test-only 입력
            var event = new PixelEventMessage("x".repeat(16 * 1024 * 1024), 1, 2, 3, 1, 1);
            long start = System.nanoTime();
            server.broadcaster.broadcast(event);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(server.registry.snapshot().isEmpty(), "Timed-out session must be removed");
            assertFalse(nativeSession.isOpen());
            // OS socket buffer와 close 비용을 포함하는 관측 구간이며 정확한 5초 hard deadline 주장 금지
            assertTrue(elapsedMillis >= 3500 && elapsedMillis <= 20_000, "Observed native timeout milliseconds=" + elapsedMillis);
            System.out.println("NATIVE_TIMEOUT container=" + ServerInfo.getServerNumber() + " elapsedMillis=" + elapsedMillis
                    + " toleranceMillis=3500..20000 receiveBuffer=" + socket.getReceiveBufferSize() + " os=" + System.getProperty("os.name"));
        }
    }

    @Test
    void realWebSocketThenGzipSnapshotAndReverseEventsMergeToBoardAcrossReconnect() throws Exception {
        try (var server = new Server(directory); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var receiver = new PixelReceiverModel(); var messages = new Messages();
            var ws = client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"), messages).get(5, TimeUnit.SECONDS);
            awaitSessions(server.registry, 1);
            long epoch = receiver.connect(); var request = receiver.request(0, 0);
            var snapshot = getSnapshot(client, server.port());
            var first = server.core.writePixel(1, 1, 2, 3); var second = server.core.writePixel(2, 2, 2, 4);
            server.broadcaster.broadcast(PixelEventMessage.from(second)); server.broadcaster.broadcast(PixelEventMessage.from(first));
            receiver.receive(epoch, messages.next()); receiver.receive(epoch, messages.next());
            assertTrue(receiver.install(request, snapshot.bytes(), snapshot.version()));
            assertArrayEquals(server.board.getRequired(new TileKey(0, 0, 0)).pixels(), receiver.pixels(0, 0));
            receiver.disconnect(epoch); assertFalse(receiver.synchronizedTile(0, 0));
            ws.abort(); awaitSessions(server.registry, 0);
            var reconnectedMessages = new Messages();
            ws = client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"), reconnectedMessages).get(5, TimeUnit.SECONDS);
            try {
                awaitSessions(server.registry, 1); long newEpoch = receiver.connect(); var fresh = receiver.request(0, 0);
                var nextSnapshot = getSnapshot(client, server.port());
                var third = server.core.writePixel(3, 1, 2, 5); server.broadcaster.broadcast(PixelEventMessage.from(third));
                receiver.receive(newEpoch, reconnectedMessages.next());
                receiver.receive(epoch, new PixelEventMessage("pixel", 1, 2, 99, 999, 999));
                assertFalse(receiver.install(request, snapshot.bytes(), snapshot.version()));
                assertTrue(receiver.install(fresh, nextSnapshot.bytes(), nextSnapshot.version()));
                assertArrayEquals(server.board.getRequired(new TileKey(0, 0, 0)).pixels(), receiver.pixels(0, 0));
            } finally { ws.abort(); }
        }
    }

    private static Snapshot getSnapshot(HttpClient client, int port) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tiles/0/0/0"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElseThrow());
        assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            byte[] bytes = gzip.readAllBytes(); assertEquals(65_536, bytes.length);
            return new Snapshot(bytes, Long.parseLong(response.headers().firstValue("X-Tile-Version").orElseThrow()));
        }
    }

    private static void awaitSessions(PixelWebSocketSessionRegistry registry, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.snapshot().size() != count && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        assertEquals(count, registry.snapshot().size());
    }

    private record Snapshot(byte[] bytes, long version) { }

    private static final class Messages implements WebSocket.Listener {
        private final BlockingQueue<PixelEventMessage> queue = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) { queue.add(new ObjectMapper().readValue(fragments.toString(), PixelEventMessage.class)); fragments.setLength(0); }
            webSocket.request(1); return null;
        }
        PixelEventMessage next() throws InterruptedException { var event = queue.poll(5, TimeUnit.SECONDS); assertNotNull(event); return event; }
    }

    private static final class Server implements AutoCloseable {
        final Tomcat tomcat = new Tomcat();
        final AnnotationConfigWebApplicationContext spring = new AnnotationConfigWebApplicationContext();
        final PixelWebSocketSessionRegistry registry;
        final InMemoryTileBoard board;
        final PixelWriteService core;
        final WebSocketPixelBroadcastService broadcaster;
        final ServiceReadiness readiness=new ServiceReadiness();

        Server(Path directory) throws Exception {
            tomcat.setBaseDir(directory.toString()); tomcat.setPort(0); tomcat.getConnector();
            var context = tomcat.addContext("", directory.toString());
            context.addServletContainerInitializer(new WsSci(), Set.of());
            spring.register(Fixture.class);
            var servlet = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(spring));
            servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/", "dispatcher");
            tomcat.start();
            registry = spring.getBean(PixelWebSocketSessionRegistry.class); board = spring.getBean(InMemoryTileBoard.class);
            readiness.markReady();
            core = new PixelWriteService(new EventSeqManager(), mock(WalAppender.class), board, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());
            broadcaster = new WebSocketPixelBroadcastService(registry, new ObjectMapper(), dev.cgt.pixelplace.measurement.Measurements.disabled());
        }
        int port() { return tomcat.getConnector().getLocalPort(); }
        public void close() throws Exception {
            try { tomcat.stop(); } finally { try { tomcat.destroy(); } finally { spring.close(); } }
        }
    }
}
