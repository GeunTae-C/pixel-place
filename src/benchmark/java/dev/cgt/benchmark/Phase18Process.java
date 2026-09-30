package dev.cgt.benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 시작된 자식의 실제 identity·pipe·종료를 한 소유자에 묶음. 기한 초과는 성공으로 복원 불가 */
public final class Phase18Process {
    public record Command(List<String> arguments, Path cwd, Path evidence, long observationMillis,
            long cleanupMillis, long logLimit) {
        public Command { arguments = List.copyOf(arguments); }
    }
    public record Receipt(long pid, String startUtc, Integer exit, boolean evidenceSaved,
            boolean unresolved, String failureType) { }
    @FunctionalInterface public interface Body { void run(Owned child) throws Exception; }
    @FunctionalInterface interface Sink { void write(Path path, Object value) throws Exception; }
    private static final ConcurrentMap<Long, Owned> OWNED = new ConcurrentHashMap<>();
    private Phase18Process() { }

    /** shell을 거치지 않는 인자 배열. 임의 shell/Gradle 실행은 이 fixture API의 책임 밖 */
    public static Receipt run(Command command, Body body) throws Exception {
        return run(command, body, BenchmarkJson::write);
    }

    /** 시험 JWT 키만 자식 환경에 주입. command/receipt/log에는 키 값을 보존하지 않음 */
    static Receipt runWithKeys(Command command, BenchmarkEnvironment.Keys keys, Body body) throws Exception {
        return run(command, body, BenchmarkJson::write, Map.of("PIXEL_PLACE_BENCH_JWT_KEY", keys.jwt(),
                "PIXEL_PLACE_BENCH_COOKIE_KEY", keys.cookie()), null);
    }

    static Receipt runWithKeys(Command command, BenchmarkEnvironment.Keys keys, Phase18ParentControl control, Body body) throws Exception {
        return run(command, body, BenchmarkJson::write, Map.of("PIXEL_PLACE_BENCH_JWT_KEY", keys.jwt(),
                "PIXEL_PLACE_BENCH_COOKIE_KEY", keys.cookie()), control);
    }

    static synchronized Receipt run(Command command, Body body, Sink sink) throws Exception {
        return run(command, body, sink, Map.of(), null);
    }
    static synchronized Receipt run(Command command, Body body, Sink sink, Map<String, String> environment, Phase18ParentControl control) throws Exception {
        validate(command);
        Files.createDirectory(command.evidence);
        Owned child = new Owned(command, sink, environment);
        Throwable failure = null;
        boolean interrupted = false;
        boolean closeRequested = false;
        try {
            var inputHashes = new TreeMap<String, String>();
            for (String arg : command.arguments) if (arg.startsWith("@")) {
                Path file = Phase18Paths.checked(arg.substring(1)); inputHashes.put(file.toString(), BenchmarkJson.hash(file));
            }
            sink.write(command.evidence.resolve("command.json"), Map.of("arguments", command.arguments, "argumentFileHashes", inputHashes,
                    "cwd", command.cwd.toString(), "observationMillis", command.observationMillis,
                    "cleanupMillis", command.cleanupMillis, "logLimit", command.logLimit));
            if (control == null) child.start();
            else control.start(child, () -> { child.start(); return null; });
            body.run(child);
            child.awaitExit(command.observationMillis, control == null ? () -> { } : control::check);
        } catch (Exception | Error problem) {
            failure = problem; interrupted = problem instanceof InterruptedException;
            if (control == null && child.started() && child.isAlive()) {
                // 최초 실패 직후 중단 통보. 진단 저장 지연 때문에 신규 실행 차단을 미루지 않음
                closeRequested = true; long requested = System.nanoTime();
                boolean sent = false;
                try { child.send("CLOSE"); sent = true; }
                catch (Exception | Error secondary) { failure = preserve(failure, secondary); }
                try { sink.write(command.evidence.resolve("control-request.json"), Map.of("message", "CLOSE",
                        "requestedNanos", requested, "sendSucceeded", sent, "cause", problem.getClass().getName(),
                        "causeMessage", String.valueOf(problem.getMessage()), "nextTaskAllowed", false)); }
                catch (Exception | Error secondary) { failure = preserve(failure, secondary); }
            }
            if (control != null && child.started()) {
                try { control.settle(child, problem); }
                catch (Exception | Error secondary) {
                    // settle이 통합한 원인을 다시 붙여 순환시키지 않되, 관측 자체의 추가 실패도 보존
                    failure = secondary == control.failure() ? secondary : preserve(failure, secondary);
                }
            }
        } finally {
            try {
            // interrupt는 정상 종료 관측을 취소하지 않음. 정리 후 원래 상태 복원
            interrupted |= Thread.interrupted();
            if (failure != null && child.process != null) {
                try { sink.write(command.evidence.resolve("failure-observed.json"), Map.of("pid", child.process.pid(),
                        "startUtc", child.startUtc, "aliveAtFailure", child.process.isAlive(), "observedUtc", Instant.now().toString(),
                        "failureType", failure.getClass().getName(), "nextTaskAllowed", false)); }
                catch (Exception | Error secondary) { failure = preserve(failure, secondary); }
            }
            if (child.process != null) {
                if (child.process.isAlive()) {
                    if (!closeRequested && control == null) try { child.send("CLOSE"); } catch (Exception | Error secondary) { failure = preserve(failure, secondary); }
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(command.cleanupMillis);
                    while (child.process.isAlive() && System.nanoTime() < deadline) {
                        try { child.process.waitFor(20, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException secondary) { interrupted = true; failure = preserve(failure, secondary); }
                    }
                    if (child.process.isAlive()) failure = preserve(failure, new IOException("Owned process remains unresolved"));
                }
                if (!child.process.isAlive()) {
                    child.exit = child.process.exitValue();
                    if (child.exit != 0) failure = preserve(failure, new IOException("Process exit " + child.exit));
                    try { child.joinPumps(command.cleanupMillis); }
                    catch (Exception | Error secondary) { interrupted |= secondary instanceof InterruptedException; failure = preserve(failure, secondary); }
                }
                failure = preserve(failure, child.streamFailure);
            }
            boolean unresolved = child.process != null && (child.process.isAlive() || !child.pumpsDone());
            var receipt = new Receipt(child.process == null ? -1 : child.process.pid(), child.startUtc,
                    child.exit, false, unresolved, failure == null ? "" : failure.getClass().getName());
            try {
                var exitEvidence = new LinkedHashMap<String, Object>();
                exitEvidence.put("pid", receipt.pid); exitEvidence.put("startUtc", receipt.startUtc);
                exitEvidence.put("endObservedUtc", Instant.now().toString()); exitEvidence.put("exit", receipt.exit);
                exitEvidence.put("unresolved", unresolved); exitEvidence.put("failureType", receipt.failureType);
                sink.write(command.evidence.resolve("exit.json"), exitEvidence);
                if (failure == null) sink.write(command.evidence.resolve("manifest.json"), Map.of(
                        "schemaVersion", 1, "processExit", child.exit, "normalExit", true, "evidenceSaved", true,
                        "identity", "identity.json", "stdout", "stdout.log", "stderr", "stderr.log"));
            } catch (Exception | Error secondary) { failure = preserve(failure, secondary); }
            if (failure != null) {
                try { sink.write(command.evidence.resolve("failure.json"), Map.of("failureType", failure.getClass().getName(),
                        "suppressedTypes", Arrays.stream(failure.getSuppressed()).map(t -> t.getClass().getName()).toList(),
                        "nextTaskAllowed", false, "unresolved", unresolved,
                        "pid", receipt.pid, "startUtc", receipt.startUtc)); }
                catch (Exception | Error secondary) { failure = preserve(failure, secondary); }
            }
            if (interrupted) Thread.currentThread().interrupt();
            } finally {
                // 호출자 반환이나 pipe EOF와 무관하게 실제 Process 소유자에게 인계
                child.failure = failure;
                if (child.process != null && child.exit != null && child.streamsClosed) OWNED.remove(child.process.pid(), child);
                child.handoff.countDown();
            }
        }
        rethrow(failure);
        return new Receipt(child.process.pid(), child.startUtc, child.exit, true, false, "");
    }

    private static void validate(Command c) throws Exception {
        if (!OWNED.isEmpty()) throw new IllegalStateException("Prior process/stream ownership unresolved");
        Phase18Paths.checked(c.cwd.toString()); Phase18Paths.checked(c.evidence.toString());
        Phase18Paths.checked(c.arguments.getFirst());
        Phase18Plan.require(c.arguments.size() <= 32 && c.arguments.stream().allMatch(a -> a != null
                && a.length() <= 32_000 && a.indexOf('\0') < 0 && !a.contains("\n") && !a.contains("\r")), "command arguments");
        Phase18Plan.require(c.observationMillis > 0 && c.observationMillis <= 600_000
                && c.cleanupMillis > 0 && c.cleanupMillis <= 600_000 && c.logLimit > 0 && c.logLimit <= 1_048_576, "process bounds");
        Phase18Plan.require(!Files.exists(c.evidence, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(c.evidence.getParent()), "new process evidence");
    }

    /** 동기 순차 실행. 실패 예외가 다음 body 호출까지 전파되어 자동 재시도 없음 */
    public static void sequence(List<Callable<Void>> tasks) throws Exception {
        for (Callable<Void> task : List.copyOf(tasks)) {
            if (!OWNED.isEmpty()) throw new IllegalStateException("Unresolved owned process");
            task.call();
        }
    }

    public static List<Map<String, Object>> unresolved() {
        return OWNED.values().stream().map(c -> Map.<String, Object>of("pid", c.process.pid(),
                "startUtc", c.startUtc, "alive", c.process.isAlive(), "evidence", c.command.evidence.toString())).toList();
    }

    /** 이전 실패를 바꾸지 않고 이 JVM이 보유 중인 실제 Process만 추가 관측. 임의 PID 인수 없음 */
    public static synchronized void observeRemaining(long millis) throws Exception {
        Phase18Plan.require(millis > 0 && millis <= 600_000, "remaining observation deadline");
        for (Owned child : List.copyOf(OWNED.values())) {
            try { child.released.get(millis, TimeUnit.MILLISECONDS); }
            catch (ExecutionException failed) { rethrow(failed.getCause()); }
        }
    }

    /** 유한 관측 실패 이후에도 앱 서버가 실제 자식보다 먼저 닫히지 않도록 소유 완료 대기 */
    static void awaitRemaining() throws Exception {
        for (Owned child : List.copyOf(OWNED.values())) {
            try { child.released.get(); }
            catch (ExecutionException failed) { rethrow(failed.getCause()); }
        }
    }

    static Throwable preserve(Throwable first, Throwable later) {
        if (later == null || later == first) return first;
        if (first == null) return later;
        // JVM Error를 일반 실패 아래 숨기지 않음. 먼저 받은 Error와 suppressed identity 보존
        if (later instanceof Error && !(first instanceof Error)) { later.addSuppressed(first); return later; }
        if (Arrays.stream(first.getSuppressed()).noneMatch(x -> x == later)) first.addSuppressed(later);
        return first;
    }
    static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error e) throw e;
        if (failure instanceof Exception e) throw e;
    }

    /** Process 참조는 start 직후 설치. identity 저장 실패도 정상 정리 경로를 통과 */
    public static final class Owned {
        private final Command command;
        private final Sink sink;
        private final Map<String, String> environment;
        private Process process;
        private String startUtc = "unavailable";
        private Integer exit;
        private BufferedWriter stdin;
        private Thread out, err;
        private final CountDownLatch handoff = new CountDownLatch(1);
        private final CompletableFuture<Void> released = new CompletableFuture<>();
        private volatile Throwable failure;
        private boolean streamsClosed;
        private final BlockingQueue<String> messages = new ArrayBlockingQueue<>(128);
        private volatile Throwable streamFailure;
        private long processDeadline;

        private Owned(Command command, Sink sink, Map<String, String> environment) { this.command = command; this.sink = sink; this.environment = environment; }
        private void start() throws Exception {
            var builder = new ProcessBuilder(command.arguments).directory(command.cwd.toFile());
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
            builder.environment().putAll(environment);
            // 시작 전에 생존 보장 설치. identity/reader 설치 실패에도 부모 종료로 Process를 유실하지 않음
            Thread owner = new Thread(this::retainUntilExit, "phase18-process-owner");
            owner.setDaemon(false); owner.start();
            process = builder.start();
            processDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(command.observationMillis);
            OWNED.put(process.pid(), this);
            stdin = process.outputWriter(StandardCharsets.UTF_8);
            out = pump(process.getInputStream(), "stdout.log", true);
            err = pump(process.getErrorStream(), "stderr.log", false);
            startUtc = process.info().startInstant().orElseThrow(() -> new IOException("Missing process start identity")).toString();
            sink.write(command.evidence.resolve("identity.json"), Map.of("pid", process.pid(), "startUtc", startUtc,
                    "observedUtc", Instant.now().toString(), "parentPid", ProcessHandle.current().pid()));
        }

        /** 관측 실패 뒤의 소유 수명. 자동 kill/재시도 없이 실제 종료와 필수 증거 처리를 기다림 */
        private void retainUntilExit() {
            boolean interrupted = false;
            while (true) {
                try { handoff.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (process == null || !OWNED.containsKey(process.pid())) { released.complete(null); return; }
            try {
                while (true) {
                    try { process.waitFor(); break; }
                    catch (InterruptedException secondary) { interrupted = true; failure = preserve(failure, secondary); }
                }
                // 이미 기록된 유한 관측 실패는 유지. 생존 소유 자체에는 EOF/시간 기반 해제 없음
                while (!pumpsDone()) {
                    try { if (out != null) out.join(); if (err != null) err.join(); }
                    catch (InterruptedException secondary) { interrupted = true; failure = preserve(failure, secondary); }
                }
                joinPumps(1);
                failure = preserve(failure, streamFailure);
                // 호출자 반환 뒤 발생한 stream 실패도 완료 future의 성공 아래 숨기면 안 됨
                rethrow(streamFailure);
                exit = process.exitValue();
                if (exit != 0) failure = preserve(failure, new IOException("Late process exit " + exit));
                sink.write(command.evidence.resolve("late-exit.json"), Map.of("pid", process.pid(),
                        "startUtc", startUtc, "exit", exit, "failedEarlier", true, "nextTaskAllowed", false,
                        "failureType", failure == null ? "" : failure.getClass().getName(),
                        "suppressedTypes", failure == null ? List.of() : Arrays.stream(failure.getSuppressed()).map(t -> t.getClass().getName()).toList(),
                        "observedUtc", Instant.now().toString(), "streamsClosed", true));
                OWNED.remove(process.pid(), this);
                released.complete(null);
            } catch (Exception | Error secondary) {
                failure = preserve(failure, secondary);
                try { sink.write(command.evidence.resolve("late-failure.json"), Map.of("pid", process.pid(),
                        "alive", process.isAlive(), "failureType", failure.getClass().getName(), "nextTaskAllowed", false)); }
                catch (Exception | Error saving) { failure = preserve(failure, saving); }
                // 증거 처리 실패는 잔여 registry와 호출자의 실패로 남김. 성공으로 재시도하지 않음
                released.completeExceptionally(failure);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }

        private Thread pump(InputStream input, String filename, boolean protocol) {
            Thread t = new Thread(() -> {
                OutputStream output = null;
                try { output = Files.newOutputStream(command.evidence.resolve(filename), StandardOpenOption.CREATE_NEW); }
                catch (Exception | Error e) { streamFailed(e); }
                try (input) {
                    long count = 0; var line = new ByteArrayOutputStream(512); boolean oversized = false;
                    byte[] buffer = new byte[2048]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (count + n > command.logLimit) { streamFailed(new IOException("Log budget exceeded")); }
                        else if (output != null) {
                            try { output.write(buffer, 0, n); }
                            catch (Exception | Error e) {
                                streamFailed(e);
                                try { output.close(); } catch (Exception | Error close) { streamFailed(close); }
                                output = null;
                            }
                        }
                        count = Math.min(command.logLimit + 1, count + n);
                        if (protocol) for (int i = 0; i < n; i++) {
                            int b = buffer[i];
                            if (b == '\n') {
                                if (!oversized && !messages.offer(line.toString(StandardCharsets.UTF_8).stripTrailing()))
                                    streamFailed(new IOException("Protocol queue overflow"));
                                line.reset(); oversized = false;
                            } else if (line.size() < 512) line.write(b);
                            else { oversized = true; streamFailed(new IOException("Protocol line overflow")); }
                        }
                    }
                    if (protocol && (line.size() != 0 || oversized)) streamFailed(new IOException("Incomplete protocol line"));
                } catch (Exception | Error e) { streamFailed(e); }
                finally { if (output != null) try { output.close(); } catch (Exception | Error e) { streamFailed(e); } }
            }, "phase18-" + filename + "-" + process.pid());
            // pipe 소유와 실제 Process 소유 스레드의 수명은 독립
            t.start(); return t;
        }
        private synchronized void streamFailed(Throwable e) {
            // 무제한 suppressed 증가 방지. 최초 stream 실패만 보존
            if (streamFailure == null) streamFailure = e;
            else if (e instanceof Error && !(streamFailure instanceof Error)) streamFailure = preserve(streamFailure, e);
        }

        public synchronized void send(String message) throws IOException {
            if (message.length() > 128 || message.contains("\n") || message.contains("\r")) throw new IllegalArgumentException("Control message shape");
            stdin.write(message); stdin.newLine(); stdin.flush();
        }

        /** reader는 항상 동작. 정상 제어 응답을 기다려도 부모가 STOP을 보낼 수 있음 */
        public String receive(long millis) throws Exception {
            return receive(millis, () -> { });
        }
        @FunctionalInterface interface Check { void run() throws Exception; }
        String receive(long millis, Check check) throws Exception {
            long deadline = Math.min(processDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
            return receiveUntil(deadline, check);
        }
        /** 실패 후 정리 전용 절대 기한. processDeadline 갱신이나 정상 실행 연장 없음 */
        String receiveCleanup(long deadline) throws Exception {
            return receiveUntil(deadline, () -> { });
        }
        private String receiveUntil(long deadline, Check check) throws Exception {
            while (System.nanoTime() < deadline) {
                check.run();
                rethrow(streamFailure);
                String message = messages.poll(Math.min(20, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))), TimeUnit.MILLISECONDS);
                if (message != null) return message;
                if (!process.isAlive() && pumpsDone()) throw new EOFException("Child ended before control response");
            }
            throw new TimeoutException("Control observation deadline");
        }

        public void awaitExit(long millis) throws Exception {
            awaitExit(millis, () -> { });
        }
        private void awaitExit(long millis, Check check) throws Exception {
            long deadline = Math.min(processDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
            while (process.isAlive()) {
                check.run();
                rethrow(streamFailure);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Process observation deadline");
                process.waitFor(Math.min(20, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
            }
            check.run();
            exit = process.exitValue();
            if (exit != 0) throw new IOException("Process exit " + exit);
        }
        public long pid() { return process.pid(); }
        boolean started() { return process != null; }
        public boolean isAlive() { return process.isAlive(); }
        private boolean pumpsDone() { return (out == null || !out.isAlive()) && (err == null || !err.isAlive()); }
        private void joinPumps(long millis) throws Exception {
            if (out != null) out.join(millis); if (err != null) err.join(millis);
            if (!pumpsDone()) throw new TimeoutException("Log stream observation deadline");
            if (stdin != null) stdin.close();
            if (out == null) process.getInputStream().close();
            if (err == null) process.getErrorStream().close();
            streamsClosed = true;
        }
    }
}
