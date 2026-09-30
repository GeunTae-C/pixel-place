package dev.cgt.benchmark;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 읽기 전용 Plan, 제어 VerifyTools, 제한 production VerifyTransport 진입점. 후속 action은 부수 효과 전 거부 */
public final class Phase18Main {
    private Phase18Main() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].equals("--action") || !args[2].equals("--plan"))
            throw new IllegalArgumentException("Usage: --action Plan|VerifyTools|VerifyTransport --plan <absolute.json>");
        String action = args[1];
        if (!Set.of("Plan", "VerifyTools", "VerifyTransport").contains(action))
            throw new IllegalArgumentException("Action unavailable: collector/analyzer/integrated PILOT require A-3/A-4");
        var loaded = Phase18Plan.read(Path.of(args[3])); var plan = loaded.plan();
        Phase18Runtime.verify(plan);
        if (action.equals("VerifyTransport")) {
            Phase18Transport.run(loaded, Phase18Paths.checked(args[3]), Phase18Paths.checked(System.getProperty("phase18.environment", "")));
            System.out.println("A2_TRANSPORT_VERIFIED"); return;
        }
        if (action.equals("Plan")) {
            var result = new LinkedHashMap<String, Object>();
            result.put("schemaVersion", Phase18Plan.VERSION); result.put("phase", plan.phase()); result.put("stage", plan.stage());
            result.put("planHash", loaded.planHash()); result.put("state", plan.state());
            boolean available;
            try {
                if(plan.stage().equals("A-2"))Phase18Transport.requireFixture(plan);else plan.requireTools();
                Path root = Path.of(plan.ownership().evidenceRoot());
                available = !Files.exists(root.resolve(plan.cases().getFirst().runId()), LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(root.resolve("a1.lock"), LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(root.resolve("a2.lock"), LinkOption.NOFOLLOW_LINKS)
                        && plan.ownership().owner().equals(System.getProperty("user.name"))
                        && Runtime.version().feature() == 21
                        && Path.of(System.getProperty("java.home"), "bin", "java.exe").equals(Path.of(plan.ownership().java()));
            } catch (IllegalArgumentException unavailable) { available = false; }
            result.put("executionAvailable", available);
            result.put("availableActions",List.of("Plan","VerifyTools","VerifyTransport"));
            result.put("runs", plan.cases().stream().map(c -> Map.of("caseId", c.caseId(), "runId", c.runId(), "counts", plan.counts(c))).toList());
            result.put("unresolved", List.of("fresh production ownership/live proof required for VerifyTransport", "collector/analyzer", "integrated PILOT"));
            System.out.println(Phase18Plan.JSON.writeValueAsString(result)); return;
        }
        plan.requireTools();
        var c = plan.cases().getFirst(); var w = c.workload(); var b = plan.bounds();
        Phase18Plan.require(Path.of(System.getProperty("java.home"), "bin", "java.exe").equals(Path.of(plan.ownership().java()))
                && Runtime.version().feature() == 21, "actual JDK21 launcher");
        Path root = Path.of(plan.ownership().evidenceRoot()), output = root.resolve(c.runId());
        Phase18Paths.checked(output.toString());
        Phase18Plan.require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS), "fresh run output");
        Phase18Plan.require(plan.ownership().owner().equals(System.getProperty("user.name")), "actual owner account");
        // CREATE_NEW lock는 동시 실행과 이전 미완료 재진입을 동시에 차단
        try (var lock = FileChannel.open(root.resolve("a1.lock"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            boolean finished = false; Throwable failure = null;
            try {
                Files.createDirectory(output);
                BenchmarkJson.write(output.resolve("runtime.json"), Map.of("schemaVersion", 1, "planHash", loaded.planHash(),
                        "parentPid", ProcessHandle.current().pid(), "parentStartUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                        "java", plan.ownership().java(), "javaVersion", System.getProperty("java.version"),
                        "user", System.getProperty("user.name"), "startedUtc", Instant.now().toString(), "fixtureOnly", true));
                long deadline = System.nanoTime() + Math.multiplyExact(b.totalSeconds(), 1_000_000_000L);
                var cases = Phase18Tools.verify(output, Path.of(plan.ownership().java()), c.observation().stopAckMillis(), w.drainMillis(), b.logBytesPerStream(), () -> {
                    Phase18Plan.require(System.nanoTime() < deadline && BenchmarkPaths.bytes(root) <= 64L * 1024 * 1024
                            && BenchmarkPaths.bytes(Path.of(plan.ownership().sessionRoot())) <= b.buildBytes()
                            && Phase18Paths.usable(root) >= b.minimumFreeBytes()
                            && Phase18Paths.usable(Path.of(plan.ownership().sessionRoot())) >= b.minimumFreeBytes(), "live fixture budget");
                    return null;
                });
                plan.verifyInputs();
                Phase18Runtime.verify(plan);
                Phase18Plan.require(BenchmarkPaths.bytes(output) <= b.outputBytes(), "actual output bytes");
                BenchmarkJson.write(output.resolve("tools.json"), Map.of("schemaVersion", 1, "planHash", loaded.planHash(),
                        "checks", cases, "passed", true, "fixtureOnly", true, "remaining", Phase18Process.unresolved()));
                finished = true;
            } catch (Exception | Error problem) { failure = problem;
            } finally {
                try { if (Phase18Process.unresolved().isEmpty()) { lock.close(); Files.delete(root.resolve("a1.lock")); } }
                catch (Exception | Error secondary) { failure = Phase18Process.preserve(failure, secondary); }
                if (!finished) System.err.println("A-1 task failed; dependent actions blocked; evidence=" + output);
            }
            if (failure != null) {
                try { BenchmarkJson.write(output.resolve("failure.json"), Map.of("failureType", failure.getClass().getName(),
                        "nextTaskAllowed", false, "remaining", Phase18Process.unresolved())); }
                catch (Exception | Error secondary) { failure = Phase18Process.preserve(failure, secondary); }
            }
            Phase18Process.rethrow(failure);
        }
        System.out.println("A1_TOOLS_VERIFIED evidence=" + output);
    }
}
