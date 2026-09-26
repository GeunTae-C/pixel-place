package dev.cgt.benchmark;

import tools.jackson.databind.JsonNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 한 trial의 정해진 제어 순서만 허용하는 UTF-8 JSON pipe. 파일 polling·credential 전달 없음 */
final class BenchmarkProtocol implements AutoCloseable {
    private static final String[] ORDER = {"START", "READY", "WARMUP", "WARMUP_DONE", "MEASURE", "SEND_DONE", "DONE"};
    private static final String[] PHASE = {"prepare", "prepare", "warmup", "warmup", "measurement", "drain", "drain"};
    private final BlockingQueue<Object> input = new ArrayBlockingQueue<>(16);
    private final BufferedWriter output;
    private final Thread reader;
    private final String trial;
    private int controlTimeoutSeconds = 15;
    private int position;
    final List<Map<String, Object>> transitions = new ArrayList<>();

    BenchmarkProtocol(InputStream input, OutputStream output, String trial) {
        this.output = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)); this.trial = trial;
        reader = Thread.ofPlatform().daemon(true).name("benchmark-control-reader").start(() -> {
            try {
                var in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)); String line;
                while ((line = boundedLine(in)) != null) {
                    if (!this.input.offer(BenchmarkJson.MAPPER.readTree(line))) throw new IllegalStateException("Protocol queue overflow");
                }
                this.input.offer(new EOFException("Control stream ended"));
            } catch (Exception failure) { this.input.offer(new IOException("Invalid control input")); }
        });
    }
    static String boundedLine(BufferedReader in) throws IOException {
        var text = new StringBuilder(); int next;
        while ((next = in.read()) != -1 && next != '\n') {
            if (text.length() >= 8 * 1024 * 1024) throw new IOException("Control line limit");
            if (next != '\r') text.append((char) next);
        }
        return next == -1 && text.isEmpty() ? null : text.toString();
    }
    synchronized void send(String type, Map<String, Object> payload) throws IOException {
        String phase = expectedPhase(type);
        var message = BenchmarkJson.stamp(type, trial, phase); message.putAll(payload);
        validate(BenchmarkJson.MAPPER.valueToTree(message), type);
        String line = BenchmarkJson.MAPPER.writeValueAsString(message);
        boundedOutput(() -> { output.write(line); output.newLine(); output.flush(); });
        transitions.add(BenchmarkJson.stamp("sent_" + type, trial, phase));
    }
    void controlTimeoutSeconds(int seconds) {
        if (seconds <= 0) throw new IllegalArgumentException("Positive control deadline required");
        controlTimeoutSeconds = seconds;
    }
    private void boundedOutput(IoAction action) throws IOException {
        var task = new FutureTask<Void>(() -> { action.run(); return null; });
        Thread.ofPlatform().daemon(true).name("benchmark-control-writer").start(task);
        try { task.get(controlTimeoutSeconds, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw new IOException("Control output failed", failure.getCause());
        } catch (TimeoutException failure) {
            // pipe write의 native 중단을 보장하지 않음. trial 실패 뒤 소유 프로세스 종료로 해소
            throw new IOException("Control output deadline exceeded");
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Control output interrupted"); }
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    JsonNode receive(String type, long timeoutSeconds) throws Exception {
        Object message = input.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (!(message instanceof JsonNode json)) throw new IOException(message == null ? "Control deadline exceeded" : "Control stream failed");
        synchronized (this) {
            validate(json, type); transitions.add(BenchmarkJson.stamp("received_" + type, trial, json.path("phase").asText()));
        }
        return json;
    }
    private String expectedPhase(String type) {
        if (position >= ORDER.length || !ORDER[position].equals(type)) throw new IllegalStateException("Unexpected local protocol transition");
        return PHASE[position];
    }
    void validate(JsonNode message, String expected) {
        String phase = expectedPhase(expected);
        if (!trial.equals(message.path("trialId").asText()) || !expected.equals(message.path("type").asText()) || !phase.equals(message.path("phase").asText()))
            throw new IllegalStateException("Unexpected remote protocol transition");
        position++;
    }
    @Override public void close() throws IOException { try { boundedOutput(output::close); } finally { reader.interrupt(); } }
    void awaitReaderEnd(int seconds) throws InterruptedException {
        reader.join(TimeUnit.SECONDS.toMillis(seconds));
        if (reader.isAlive()) throw new IllegalStateException("Control reader still alive");
    }
}
