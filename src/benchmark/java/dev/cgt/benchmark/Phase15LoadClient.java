package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dev.cgt.benchmark.BenchmarkResults.*;

/** 독립 JVM의 open-loop HTTP/WS 발생기. 시도 하나당 terminal outcome 하나, 자동 재시도·보충 burst 없음 */
public final class Phase15LoadClient {
    private final BenchmarkSpec spec;
    private final BenchmarkSpec.Case loadCase;
    private final List<Long> users;
    private final Path evidence;
    private final String endpoint;
    private final BenchmarkProtocol protocol;
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient http;
    private final ServiceJwtTokens tokens;
    private final AtomicReferenceArray<Token> preparedTokens;
    private final BenchmarkUsers pool;
    private final Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
    private final List<Map<String, Object>> resources = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> wsFailures = Collections.synchronizedList(new ArrayList<>());
    private final List<WebSocket> sockets = new ArrayList<>();
    private final List<Receiver> receivers = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong wsMessages = new AtomicLong(), tokenNanos = new AtomicLong(), tokenCount = new AtomicLong();
    private volatile boolean closing;
    private volatile String backgroundFailure;
    private long nextRequest = 1, measurementStart, measurementEnd;
    private String phase = "prepare";
    private record Token(String value, Instant expires) { }

    private Phase15LoadClient(BenchmarkProtocol protocol, JsonNode start) throws Exception {
        this.protocol = protocol;
        spec = BenchmarkJson.MAPPER.treeToValue(start.path("spec"), BenchmarkSpec.class); spec.validate();
        protocol.controlTimeoutSeconds(spec.controlTimeoutSeconds());
        loadCase = spec.cases().get(start.path("caseIndex").asInt());
        users = new ArrayList<>(); for (var id : start.path("fixtureUsers")) users.add(id.longValue());
        BenchmarkSpec.require(users.size() == spec.plan(loadCase).fixtureUsers(), "fixture map size");
        evidence = Path.of(start.path("evidence").asText()).toAbsolutePath().normalize();
        BenchmarkGuards.rejectLinks(evidence);
        endpoint = start.path("endpoint").asText();
        BenchmarkSpec.require(endpoint.matches("http://127\\.0\\.0\\.1:[0-9]+"), "loopback endpoint");
        tokens = new ServiceJwtTokens(new AuthProperties(System.getenv("PIXEL_PLACE_BENCH_JWT_KEY"), System.getenv("PIXEL_PLACE_BENCH_COOKIE_KEY"), Duration.ofMinutes(15)), Clock.systemUTC());
        preparedTokens = new AtomicReferenceArray<>(users.size());
        pool = new BenchmarkUsers(users.size(), spec.userMode().equals("once"), spec.reuseMarginMillis());
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(spec.requestTimeoutSeconds())).executor(httpExecutor)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    public static void main(String[] args) {
        int exit = 0; Phase15LoadClient client = null;
        try (var protocol = new BenchmarkProtocol(System.in, System.out, args[0])) {
            JsonNode start = protocol.receive("START", 120);
            client = new Phase15LoadClient(protocol, start); client.run();
        } catch (Throwable failure) {
            System.err.println("CLIENT_FAILURE kind=" + failure.getClass().getSimpleName()); exit = 1;
        } finally {
            if (client != null) {
                try { client.shutdown(); client.save(exit == 0); }
                catch (Throwable failure) { System.err.println("CLIENT_CLEANUP_FAILURE kind=" + failure.getClass().getSimpleName()); exit = 1; }
            }
        }
        System.exit(exit);
    }
    private void run() throws Exception {
        refreshTokens();
        var sampler = new BenchmarkObserver.JvmResources();
        background.scheduleWithFixedDelay(() -> {
            try {
                if (resources.size() >= spec.plan(loadCase).maximumSamples()) throw new IllegalStateException("Client sample budget");
                var row = sampler.sample(); row.put("inFlight", inFlight.get()); row.put("wsMessages", wsMessages.get());
                row.put("tokenPreparationNanos", tokenNanos.get()); resources.add(row); refreshTokens();
            } catch (RuntimeException problem) { backgroundFailure = problem.getClass().getSimpleName(); }
            catch (Error problem) { backgroundFailure = problem.getClass().getSimpleName(); throw problem; }
        }, 0, spec.sampleIntervalMillis(), TimeUnit.MILLISECONDS);
        for (int i = 0; i < loadCase.webSocketSessions(); i++) {
            var receiver = new Receiver(); receivers.add(receiver);
            sockets.add(http.newWebSocketBuilder().header("Origin", "http://localhost:3000")
                    .connectTimeout(Duration.ofSeconds(spec.controlTimeoutSeconds()))
                    .buildAsync(URI.create(endpoint.replace("http:", "ws:") + "/ws"), receiver)
                    .get(spec.controlTimeoutSeconds(), TimeUnit.SECONDS));
        }
        protocol.send("READY", Map.of("webSocketSessions", sockets.size(), "tokenCount", tokenCount.get(), "tokenPreparationNanos", tokenNanos.get()));
        protocol.receive("WARMUP", spec.controlTimeoutSeconds()); phase = "warmup";
        sendWindow(spec.warmupSeconds(), false); awaitInflight(spec.maxDrainSeconds());
        awaitMessages();
        protocol.send("WARMUP_DONE", Map.of("sent", sent(), "unknown", attempts.stream().filter(Attempt::unknown).count(),
                "latestAcceptedSeq", attempts.stream().filter(a -> a.eventSeq() != null).mapToLong(Attempt::eventSeq).max().orElse(0)));
        protocol.receive("MEASURE", spec.maxDrainSeconds() + spec.controlTimeoutSeconds()); phase = "measurement";
        sendWindow(spec.measurementSeconds(), true);
        protocol.send("SEND_DONE", Map.of("windowStartNanos", measurementStart, "windowEndNanos", measurementEnd));
        awaitInflight(spec.maxDrainSeconds());
        awaitMessages();
        if (!wsFailures.isEmpty() || backgroundFailure != null) throw new IllegalStateException("Client observation incomplete");
        // DONE은 표본 저장 이후 전송. parent는 파일을 받아도 child 종료 확인까지 수행
        saveResults();
        protocol.send("DONE", Map.of("samples", "client-results.jsonl", "sent", sent(), "windowStartNanos", measurementStart,
                "windowEndNanos", measurementEnd, "webSocketSessions", sockets.size(), "wsMessages", wsMessages.get()));
    }
    private void refreshTokens() {
        Instant cutoff = Instant.now().plusSeconds(90);
        for (int i = 0; i < users.size(); i++) {
            Token existing = preparedTokens.get(i);
            if (existing != null && existing.expires.isAfter(cutoff)) continue;
            long started = System.nanoTime(); var jwt = tokens.issueAccess(users.get(i));
            preparedTokens.set(i, new Token(jwt.getTokenValue(), jwt.getExpiresAt()));
            tokenNanos.addAndGet(System.nanoTime() - started); tokenCount.incrementAndGet();
        }
    }
    private void sendWindow(int seconds, boolean measurement) throws Exception {
        long start = System.nanoTime(), end = Math.addExact(start, TimeUnit.SECONDS.toNanos(seconds));
        if (measurement) { measurementStart = start; measurementEnd = end; }
        int planned = (int) Math.ceil(seconds * loadCase.offeredRate());
        long interval = Math.max(1, (long) (1e9 / loadCase.offeredRate()));
        for (int i = 0; i < planned; i++) {
            long due = start + (long) (i * 1e9 / loadCase.offeredRate());
            BenchmarkPacer.await(due);
            long ordinal = nextRequest++, now = System.nanoTime();
            if (now >= end || now - due >= interval) { notSent(ordinal, due, "arrival_late"); continue; }
            if (inFlight.get() >= spec.maxInFlight()) { notSent(ordinal, due, "inflight_capacity"); continue; }
            var assignment = pool.acquire(now);
            if (assignment == null) { notSent(ordinal, due, "user_pool"); continue; }
            var token = preparedTokens.get(assignment.ordinal());
            if (token == null || token.expires.isBefore(Instant.now().plusSeconds(1))) {
                pool.complete(assignment, now, true); notSent(ordinal, due, "token_not_ready", assignment); continue;
            }
            int[] pixel = pixel(loadCase, spec.seed(), ordinal);
            String body = "{\"x\":" + pixel[0] + ",\"y\":" + pixel[1] + ",\"color\":" + pixel[2] + "}";
            var request = HttpRequest.newBuilder(URI.create(endpoint + "/api/pixels")).timeout(Duration.ofSeconds(spec.requestTimeoutSeconds()))
                    .header("Authorization", "Bearer " + token.value).header("Origin", "http://localhost:3000")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            long sent = System.nanoTime(); String requestPhase = phase;
            if (sent >= end || sent - due >= interval) {
                pool.complete(assignment, sent, false); notSent(ordinal, due, "request_preparation_late", assignment); continue;
            }
            inFlight.incrementAndGet();
            try {
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, failure) -> {
                    try {
                        Attempt result = response(ordinal, assignment, requestPhase, due, sent, response, failure, pixel);
                        attempts.add(result); pool.complete(assignment, result.completedNanos(), result.unknown());
                    } finally { inFlight.decrementAndGet(); }
                });
            } catch (RuntimeException failure) {
                Attempt result = response(ordinal, assignment, requestPhase, due, sent, null, failure, pixel);
                attempts.add(result); pool.complete(assignment, result.completedNanos(), true); inFlight.decrementAndGet();
            }
        }
        BenchmarkPacer.await(end);
    }
    static Attempt response(long id, BenchmarkUsers.Assignment user, String phase, long due, long sent,
                            HttpResponse<String> response, Throwable failure, int[] expected) {
        long completed = System.nanoTime(); Integer status = response == null ? null : response.statusCode();
        Outcome outcome; Long seq = null, version = null;
        if (failure != null) {
            boolean timedOut = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof HttpTimeoutException) timedOut = true;
            outcome = timedOut ? Outcome.timeout : Outcome.network_error;
        } else if (status == 200) {
            try {
                JsonNode body = BenchmarkJson.MAPPER.readTree(response.body());
                BenchmarkSpec.require(body.isObject() && body.size() == 6 && body.path("accepted").isBoolean() && body.path("accepted").booleanValue(), "200 accepted");
                for (String key : List.of("eventSeq", "tileVersion", "x", "y", "color"))
                    BenchmarkSpec.require(body.path(key).isIntegralNumber() && body.path(key).canConvertToLong(), "200 fields");
                BenchmarkSpec.require(body.path("eventSeq").longValue() > 0 && body.path("tileVersion").longValue() > 0
                        && body.path("x").longValue() == expected[0] && body.path("y").longValue() == expected[1] && body.path("color").longValue() == expected[2], "200 values");
                seq = body.path("eventSeq").longValue(); version = body.path("tileVersion").longValue(); outcome = Outcome.accepted;
            } catch (RuntimeException malformed) { outcome = Outcome.malformed_200; }
        } else if (status >= 400 && status < 500) outcome = Outcome.policy_4xx;
        else if (status >= 500 && status < 600) outcome = Outcome.server_5xx;
        else outcome = Outcome.unexpected_status;
        String reason = null;
        if (Objects.equals(status, 503)) {
            reason = "service_unavailable";
            try {
                String message = BenchmarkJson.MAPPER.readTree(response.body()).path("message").asText();
                if (message.equals("Pixel write is busy. Please retry later.")) reason = "write_busy";
                else if (message.equals("Pixel write outcome is unknown.")) reason = "write_unknown";
            } catch (RuntimeException ignored) { /* 비민감 유한 label만 보존, 응답 원문 저장 금지 */ }
        }
        return new Attempt(id, user.ordinal(), user.attemptOrdinal(), phase, due, sent, completed, outcome, status, seq, version, reason);
    }
    private void notSent(long id, long due, String reason) { attempts.add(new Attempt(id, null, null, phase, due, null, System.nanoTime(), Outcome.client_not_sent, null, null, null, reason)); }
    private void notSent(long id, long due, String reason, BenchmarkUsers.Assignment user) { attempts.add(new Attempt(id, user.ordinal(), user.attemptOrdinal(), phase, due, null, System.nanoTime(), Outcome.client_not_sent, null, null, null, reason)); }
    private long sent() { return attempts.stream().filter(a -> a.sentNanos() != null).count(); }
    private void awaitMessages() throws Exception {
        long perConnection = attempts.stream().filter(a -> a.outcome() == Outcome.accepted).count();
        long expected = perConnection * sockets.size();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.controlTimeoutSeconds());
        while (wsMessages.get() < expected && wsFailures.isEmpty() && System.nanoTime() < until) Thread.sleep(5);
        if (wsMessages.get() != expected || !wsFailures.isEmpty() || receivers.stream().anyMatch(r -> r.received.get() != perConnection))
            throw new IllegalStateException("WebSocket delivery incomplete");
    }
    private void awaitInflight(int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (inFlight.get() != 0 && System.nanoTime() < until) Thread.sleep(5);
        if (inFlight.get() != 0) throw new IllegalStateException("Client drain deadline exceeded");
    }
    private void saveResults() throws Exception {
        Path file = evidence.resolve("client-results.jsonl");
        try (var writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
            for (Attempt result : attempts.stream().sorted(Comparator.comparingLong(Attempt::requestId)).toList()) {
                writer.write(BenchmarkJson.MAPPER.writeValueAsString(result)); writer.newLine();
            }
        }
    }
    private void save(boolean success) throws Exception {
        if (!Files.exists(evidence.resolve("client-results.jsonl"))) saveResults();
        var report = new LinkedHashMap<String, Object>(); report.put("success", success); report.put("inFlight", inFlight.get());
        report.put("resources", resources); report.put("webSocketFailures", wsFailures); report.put("backgroundFailure", backgroundFailure);
        report.put("webSocketMessages", wsMessages.get()); report.put("tokenPreparationNanos", tokenNanos.get()); report.put("tokenCount", tokenCount.get());
        report.put("messagesPerConnection", receivers.stream().map(r -> r.received.get()).toList());
        report.put("runtime", Map.of("pid", ProcessHandle.current().pid(), "javaVersion", System.getProperty("java.runtime.version"),
                "maxHeap", Runtime.getRuntime().maxMemory(), "os", System.getProperty("os.name"),
                "gc", java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().map(g -> g.getName()).toList()));
        report.put("controlTransitions", protocol.transitions); report.put("windowStartNanos", measurementStart); report.put("windowEndNanos", measurementEnd);
        if (measurementEnd > measurementStart) report.put("summary", aggregate(new ArrayList<>(attempts), measurementStart, measurementEnd));
        BenchmarkJson.write(evidence.resolve("client-summary.json"), report);
        if (success && backgroundFailure != null) throw new IllegalStateException("Client observation incomplete at shutdown");
    }
    private void shutdown() throws Exception {
        closing = true;
        for (var socket : sockets) { try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(spec.controlTimeoutSeconds(), TimeUnit.SECONDS); } catch (Exception failure) { socket.abort(); } }
        background.shutdownNow(); http.shutdown();
        boolean httpStopped = http.awaitTermination(Duration.ofSeconds(spec.controlTimeoutSeconds()));
        httpExecutor.shutdown();
        if (!background.awaitTermination(spec.controlTimeoutSeconds(), TimeUnit.SECONDS)
                || !httpStopped
                || !httpExecutor.awaitTermination(spec.controlTimeoutSeconds(), TimeUnit.SECONDS))
            throw new IllegalStateException("Client resources still alive");
    }
    private final class Receiver implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();
        private final AtomicLong received = new AtomicLong();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (message.length() + data.length() > 2048) { failed("message_limit"); socket.abort(); return null; }
            message.append(data);
            if (last) {
                try { var json = BenchmarkJson.MAPPER.readTree(message.toString()); if (!"pixel".equals(json.path("type").asText()) || json.path("tileVersion").asLong() <= 0) failed("invalid_message"); }
                catch (RuntimeException invalid) { failed("invalid_message"); }
                message.setLength(0); received.incrementAndGet(); wsMessages.incrementAndGet();
            }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { if (!closing) failed("close_" + status); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { if (!closing) failed(error.getClass().getSimpleName()); }
        private void failed(String reason) { if (wsFailures.size() < 10000) wsFailures.add(Map.of("nanoTime", System.nanoTime(), "reason", reason)); }
    }
}
