package dev.cgt.benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** A-1의 실제 JVM 기능 fixture. HTTP/collector/production 통합 성공으로 해석하면 안 됨 */
public final class Phase18Tools {
    private Phase18Tools() { }

    /** 제공 action의 실제 runner 경계 검사. 예상 실패도 각 원래 process/증거 결과 보존 */
    public static List<String> verify(Path root, Path java, long ackMillis, long cleanupMillis, long logLimit) throws Exception {
        return verify(root, java, ackMillis, cleanupMillis, logLimit, () -> null);
    }

    static List<String> verify(Path root, Path java, long ackMillis, long cleanupMillis, long logLimit, Callable<Void> budget) throws Exception {
        var passed = new ArrayList<String>();
        budget.call();
        check(root, java, "exit0", false, null, cleanupMillis, logLimit); passed.add("actual-exit0");
        check(root, java, "exit7", true, null, cleanupMillis, logLimit); passed.add("actual-exit7-next-blocked");
        check(root, root.resolve("missing.exe"), "missing", true, null, cleanupMillis, logLimit); passed.add("missing-command-next-blocked");
        Path bad = root.resolve("invalid.exe"); Files.writeString(bad, "not an executable", StandardOpenOption.CREATE_NEW);
        check(root, bad, "invalid", true, null, cleanupMillis, logLimit); passed.add("actual-start-failure");
        for (String name : List.of("identity.json", "manifest.json")) {
            budget.call();
            IOException injected = new IOException("fixture evidence failure");
            Phase18Process.Sink sink = (path, value) -> { if (path.getFileName().toString().equals(name)) throw injected; BenchmarkJson.write(path, value); };
            Throwable failure = check(root, java, name.startsWith("identity") ? "identity-failure" : "manifest-failure",
                    true, sink, cleanupMillis, logLimit);
            require(failure == injected, "Original evidence error identity"); passed.add(name + "-failure-owned");
        }
        // exit 0 이후 필수 로그 게시가 실패하는 실제 저장 경계
        Phase18Process.Sink logFailure = (path, value) -> {
            if (path.getFileName().toString().equals("identity.json")) Files.createDirectory(path.getParent().resolve("required-log.txt"));
            if (path.getFileName().toString().equals("manifest.json")) Files.writeString(path.getParent().resolve("required-log.txt"), "required");
            BenchmarkJson.write(path, value);
        };
        check(root, java, "late-log-failure", true, logFailure, cleanupMillis, logLimit); passed.add("exit0-required-log-failure");
        check(root, java, "timeout", true, null, cleanupMillis, logLimit); passed.add("timeout-late-exit0-still-failed");
        check(root, java, "interrupt", true, null, cleanupMillis, logLimit); passed.add("interrupt-owned-normal-exit");
        budget.call();
        unresolved(root, java, logLimit); passed.add("cleanup-deadline-retains-ownership");
        closedStreamsOwnership(root, java, logLimit); passed.add("closed-streams-parent-return-retains-ownership");
        check(root, java, "flood", true, null, cleanupMillis, Math.min(logLimit, 1024)); passed.add("bounded-log-overflow");
        for (String mode : List.of("parent-safety", "collector-failure", "child-failure", "no-ack", "delayed-ack")) {
            budget.call();
            control(root, java, mode, ackMillis, cleanupMillis, logLimit); passed.add(mode);
        }
        for (String defect : List.of("missing-applied", "extra-dispatch", "missing-drained")) {
            budget.call();
            boolean rejected = false;
            try { control(root, java, defect, ackMillis, cleanupMillis, logLimit); }
            catch (IOException expected) { rejected = true; }
            require(rejected && !BenchmarkJson.read(root.resolve(defect + "-control.json")).path("verificationPassed").asBoolean(),
                    "Broken STOP protocol accepted: " + defect);
            passed.add(defect + "-verification-rejected");
        }
        Path marker = root.resolve("shell-marker");
        String literal = "space 한글 & | > % ! \" " + marker;
        Path args = root.resolve("quoted.args");
        var command = command(java, args, root.resolve("quoted"), "echo", cleanupMillis, logLimit, literal);
        Phase18Process.run(command, child -> require(child.receive(3000).equals(literal), "Argument bytes changed"));
        require(!Files.exists(marker), "Shell interpretation"); passed.add("safe-argument-array");
        require(Phase18Process.unresolved().isEmpty(), "Remaining owned process");
        budget.call();
        return List.copyOf(passed);
    }

    /** 테스트 자체도 실제 부모 Process를 소유. 실패 시 run의 정리/생존 소유 경로 유지 */
    static void closedStreamsOwnership(Path root, Path java, long logLimit) throws Exception {
        Path nested = root.resolve("closed-streams-child");
        var command = command(java, root.resolve("closed-streams-parent.args"), root.resolve("closed-streams-parent"),
                "ownership-parent", 8000, logLimit, nested.toString());
        Phase18Process.run(command, parent -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(nested.resolve("parent-return.json")) || !Files.exists(nested.resolve("streams-closed"))) {
                if (System.nanoTime() >= deadline) throw new TimeoutException("Parent return/EOF observation deadline");
                Thread.sleep(10);
            }
            Thread.sleep(300);
            require(parent.isAlive() && !Files.exists(nested.resolve("child-self-exit")), "Parent lost child after EOF/main return");
            var returned = BenchmarkJson.read(nested.resolve("parent-return.json"));
            require(returned.path("remaining").size() == 1 && returned.path("remaining").get(0).path("alive").asBoolean(), "Actual retained identity");
            BenchmarkJson.write(root.resolve("closed-streams-observation.json"), Map.of("parentAliveAfterMainReturnAndEof", parent.isAlive(),
                    "parentPid", parent.pid(), "childStillRunning", !Files.exists(nested.resolve("child-self-exit")),
                    "observedUtc", Instant.now().toString()));
            parent.awaitExit(8000);
        });
        var late = BenchmarkJson.read(nested.resolve("owned/late-exit.json"));
        var ended = BenchmarkJson.read(nested.resolve("parent-final.json"));
        require(late.path("exit").asInt(-1) == 0 && late.path("failedEarlier").asBoolean()
                && late.path("failureType").asText().equals(TimeoutException.class.getName())
                && late.path("streamsClosed").asBoolean(), "Late exit erased original timeout");
        require(ended.path("remaining").isEmpty() && ended.path("selfExitObserved").asBoolean(), "Final ownership release");
        require(!Files.exists(nested.resolve("next")) && !Files.exists(nested.resolve("owned/manifest.json")), "Failed child success restoration");
    }

    private static void unresolved(Path root, Path java, long logLimit) throws Exception {
        var command = command(java, root.resolve("unresolved.args"), root.resolve("unresolved"), "linger", 1, logLimit);
        Throwable original = null;
        try {
            Phase18Process.run(command, child -> child.awaitExit(1));
        } catch (Exception | Error failure) { original = failure; }
        try {
            require(original instanceof TimeoutException && !Phase18Process.unresolved().isEmpty(), "Cleanup deadline ownership");
            boolean blocked = false;
            try { Phase18Process.sequence(List.of(() -> { Files.writeString(root.resolve("unresolved-next"), "wrong"); return null; })); }
            catch (IllegalStateException expected) { blocked = true; }
            require(blocked && !Files.exists(root.resolve("unresolved-next")), "Unresolved next task started");
        } finally { Phase18Process.observeRemaining(3000); }
        require(!Files.exists(root.resolve("unresolved/manifest.json")), "Late exit restored success");
    }

    private static Throwable check(Path root, Path java, String name, boolean expectedFailure, Phase18Process.Sink sink,
            long cleanupMillis, long logLimit) throws Exception {
        Path evidence = root.resolve(name), marker = root.resolve(name + "-next");
        String mode = switch (name) { case "timeout", "interrupt" -> "linger"; case "identity-failure" -> "control";
            case "exit7", "flood" -> name; default -> "exit0"; };
        var command = command(java, root.resolve(name + ".args"), evidence, mode, cleanupMillis, logLimit);
        Throwable observed = null;
        try {
            Phase18Process.sequence(List.of(() -> {
                Phase18Process.Body body = child -> {
                    if (name.equals("interrupt")) { Thread.currentThread().interrupt(); child.awaitExit(3000); }
                    else if (name.equals("timeout")) child.awaitExit(1);
                };
                if (sink == null) Phase18Process.run(command, body); else Phase18Process.run(command, body, sink);
                return null;
            }, () -> { Files.writeString(marker, "next", StandardOpenOption.CREATE_NEW); return null; }));
        } catch (Exception | Error failure) { observed = failure; }
        if (name.equals("interrupt")) require(Thread.interrupted(), "Interrupt flag not restored");
        require((observed != null) == expectedFailure, "Unexpected fixture outcome: " + name);
        require(Files.exists(marker) != expectedFailure, "Dependent task was not blocked: " + name);
        require(Phase18Process.unresolved().isEmpty(), "Fixture ownership unresolved: " + name);
        if (Files.exists(evidence.resolve("identity.json"))) {
            var identity = BenchmarkJson.read(evidence.resolve("identity.json"));
            long pid = identity.path("pid").asLong();
            require(ProcessHandle.of(pid).filter(h -> h.info().startInstant().map(t -> t.toString().equals(identity.path("startUtc").asText())).orElse(false))
                    .filter(ProcessHandle::isAlive).isEmpty(), "Owned identity still running");
        }
        return observed;
    }

    /** 부모 발생·전송·수신 확인 시간과 child 적용 시간을 별도 clock으로 기록 */
    static void control(Path root, Path java, String mode, long ackMillis, long cleanupMillis, long logLimit) throws Exception {
        Path evidence = root.resolve(mode); var events = new ArrayList<Map<String, Object>>();
        var observed = new ControlObservation();
        var command = command(java, root.resolve(mode + ".args"), evidence, mode, cleanupMillis, logLimit);
        boolean noAck = mode.equals("no-ack") || mode.equals("delayed-ack"); Throwable problem = null;
        InterruptedRun original = new InterruptedRun(mode);
        try {
            Phase18Process.sequence(List.of(() -> {
                Phase18Process.run(command, child -> {
                    require(observed.receive(child, 3000).equals("READY"), "READY"); child.send("START");
                    require(observed.receive(child, 3000).equals("DISPATCH write 0"), "owned write");
                    require(observed.receive(child, 3000).equals("DISPATCH read 0"), "owned read");
                    require(observed.receive(child, 3000).equals("WAITING_MEASURE"), "normal control wait");
                    if (mode.equals("child-failure")) require(observed.receive(child, 3000).equals("FAILURE generator"), "child failure notification");
                    events.add(stamp("stop-occurrence", mode));
                    try {
                    if (!mode.equals("child-failure")) { child.send("STOP"); events.add(stamp("stop-notified", mode)); }
                    else events.add(stamp("stop-notified-by-child", mode));
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(mode.equals("delayed-ack") ? 100 : ackMillis);
                    long drainDeadline = 0;
                    while (!observed.done) {
                        long remaining = TimeUnit.NANOSECONDS.toMillis((observed.applied ? drainDeadline : deadline) - System.nanoTime());
                        if (remaining <= 0) {
                            if (!observed.applied) throw new AckUnconfirmed("Stop application deadline");
                            throw new TimeoutException("Owned work drain deadline");
                        }
                        String response;
                        try { response = observed.receive(child, Math.max(1, remaining)); }
                        catch (TimeoutException | EOFException missing) {
                            if (!observed.applied) throw new AckUnconfirmed("Stop application unconfirmed", missing);
                            throw missing;
                        }
                        if (System.nanoTime() >= (observed.applied ? drainDeadline : deadline)) {
                            if (!observed.applied) throw new AckUnconfirmed("Late stop application");
                            throw new TimeoutException("Late drain/completion response");
                        }
                        if (response.matches("APPLIED 2 [0-9]+")) {
                            require(!observed.applied, "Duplicate application ack");
                            // 기한 이후 수신은 실제 payload가 있어도 당시 적용 확인 실패
                            if (System.nanoTime() >= deadline) throw new AckUnconfirmed("Late stop application");
                            Long.parseLong(response.substring("APPLIED 2 ".length()));
                            observed.applied = true;
                            drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupMillis);
                            events.add(stamp("stop-application-confirmed", response));
                            child.send("MEASURE");
                        } else if (response.equals("DRAINED 2")) {
                            require(!observed.drained, "Duplicate drain"); observed.drained = true;
                            events.add(stamp("owned-work-drained", response));
                        } else if (response.equals("DONE 2")) {
                            observed.done = true;
                            if (!observed.applied) throw new AckUnconfirmed("DONE without application ack");
                        }
                        else throw new IOException("Unexpected dispatch/control after stop");
                    }
                    require(observed.applied && observed.drained, "Stop/drain unconfirmed");
                    // 중단 소진 확인은 실행 성공으로 복원할 근거가 아님
                    } catch (Exception | Error secondary) {
                        observed.failure = secondary;
                        events.add(stamp("application-or-drain-unconfirmed", secondary.getClass().getName()));
                        Phase18Process.rethrow(Phase18Process.preserve(original, secondary));
                    }
                    throw original;
                }); return null;
            }, () -> { Files.writeString(root.resolve(mode + "-next"), "next", StandardOpenOption.CREATE_NEW); return null; }));
        } catch (Exception | Error failure) { problem = failure; }
        boolean expectedControl = noAck ? !observed.applied && observed.failure instanceof AckUnconfirmed
                : observed.failure == null && observed.applied && observed.drained && observed.done;
        // 원래 중단 identity만으로 통과 금지. 정리 오류와 DONE 뒤 추가 출력도 별도 실패
        boolean rawMatches = false;
        try { rawMatches = noAck || Files.readAllLines(evidence.resolve("stdout.log")).equals(observed.messages); }
        catch (Exception | Error secondary) { problem = Phase18Process.preserve(problem, secondary); }
        boolean passed = expectedControl && problem == original
                && original.getSuppressed().length == (noAck ? 1 : 0)
                && rawMatches
                && Phase18Process.unresolved().isEmpty() && !Files.exists(root.resolve(mode + "-next"));
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 2); report.put("fixtureOnly", true); report.put("parentPid", ProcessHandle.current().pid());
        report.put("events", events); report.put("received", observed.messages);
        report.put("applicationConfirmed", observed.applied); report.put("drained", observed.drained); report.put("done", observed.done);
        report.put("unconfirmedAtFailure", !observed.applied); report.put("remainingAfterCleanup", Phase18Process.unresolved());
        report.put("runStatus", "FAILED"); report.put("verificationPassed", passed);
        report.put("expectedOutcome", noAck ? "STOP_ACK_UNCONFIRMED" : "INTERRUPTED_AND_DRAINED");
        report.put("controlFailureType", observed.failure == null ? "" : observed.failure.getClass().getName());
        report.put("failureType", problem == null ? "" : problem.getClass().getName());
        try { BenchmarkJson.write(root.resolve(mode + "-control.json"), report); }
        catch (Exception | Error secondary) { passed = false; problem = Phase18Process.preserve(problem, secondary); }
        if (!passed) Phase18Process.rethrow(Phase18Process.preserve(problem, new IOException("Control verification failed: " + mode)));
    }

    /** 수신 사실과 검증 실패를 예상된 실행 중단 예외와 분리하여 보유 */
    private static final class ControlObservation {
        final List<String> messages = new ArrayList<>();
        boolean applied, drained, done;
        Throwable failure;
        String receive(Phase18Process.Owned child, long millis) throws Exception {
            String response = child.receive(millis); messages.add(response); return response;
        }
    }
    private static final class AckUnconfirmed extends IOException {
        AckUnconfirmed(String reason) { super(reason); }
        AckUnconfirmed(String reason, Throwable cause) { super(reason, cause); }
    }

    private static final class InterruptedRun extends IOException {
        InterruptedRun(String reason) { super(reason); }
    }

    static Map<String, Object> stamp(String event, String detail) {
        return Map.of("event", event, "detail", detail, "clockPid", ProcessHandle.current().pid(),
                "nanoTime", System.nanoTime(), "utc", Instant.now().toString());
    }

    static Phase18Process.Command command(Path java, Path argfile, Path evidence, String mode,
            long cleanupMillis, long logLimit, String... extra) throws Exception {
        var cp = new LinkedHashSet<String>();
        for (ClassLoader loader = Phase18Tools.class.getClassLoader(); loader != null; loader = loader.getParent())
            if (loader instanceof java.net.URLClassLoader urls) for (var u : urls.getURLs()) cp.add(Path.of(u.toURI()).toString());
        if (cp.isEmpty()) cp.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        var args = new ArrayList<>(List.of("-Xmx128m", "-Djava.io.tmpdir=" + evidence.getParent(), "-cp", String.join(File.pathSeparator, cp),
                Phase18FixtureChild.class.getName(), mode)); args.addAll(List.of(extra));
        Files.write(argfile, args.stream().map(Phase18Tools::quoteJavaArgument).toList(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return new Phase18Process.Command(List.of(java.toString(), "@" + argfile), Path.of("").toAbsolutePath(), evidence, 10_000, cleanupMillis, logLimit);
    }

    static String quoteJavaArgument(String value) {
        if (value.contains("\n") || value.contains("\r") || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Java argument line");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
