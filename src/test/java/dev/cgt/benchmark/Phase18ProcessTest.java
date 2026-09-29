package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 부모/자식 JVM 종료·저장 실패·STOP 적용을 검증. production 앱 없이 수행 */
class Phase18ProcessTest {
    @TempDir Path temporary;
    @Test void actualProcessesPreserveFailuresBlockDependentsAndDrainOwnedWork() throws Exception {
        var passed = Phase18Tools.verify(temporary, Path.of(System.getProperty("java.home"), "bin/java.exe"), 1500, 4000, 65536);
        assertEquals(21, passed.size()); assertTrue(Phase18Process.unresolved().isEmpty());
        var eof = BenchmarkJson.read(temporary.resolve("closed-streams-child/owned/exit.json"));
        assertTrue(eof.path("unresolved").asBoolean()); assertTrue(eof.path("exit").isNull());
        assertTrue(BenchmarkJson.read(temporary.resolve("closed-streams-child/parent-return.json")).path("nextBlocked").asBoolean());
        assertTrue(Files.exists(temporary.resolve("closed-streams-child/child-self-exit")));
        for (String name : List.of("parent-safety", "collector-failure", "child-failure")) {
            var control = BenchmarkJson.read(temporary.resolve(name + "-control.json"));
            assertTrue(control.path("applicationConfirmed").asBoolean());
            assertTrue(control.path("verificationPassed").asBoolean());
            assertEquals("FAILED", control.path("runStatus").asText());
            var raw = Files.readAllLines(temporary.resolve(name + "/stdout.log"));
            assertEquals(2, raw.stream().filter(s -> s.startsWith("DISPATCH ")).count());
            int applied = java.util.stream.IntStream.range(0, raw.size()).filter(i -> raw.get(i).startsWith("APPLIED 2 ")).findFirst().orElseThrow();
            assertTrue(raw.subList(applied + 1, raw.size()).stream().noneMatch(s -> s.startsWith("DISPATCH ")));
            assertTrue(raw.contains("DRAINED 2")); assertEquals("DONE 2", raw.getLast());
            assertEquals(raw, Phase18Plan.JSON.convertValue(control.path("received"), List.class));
            assertFalse(Files.exists(temporary.resolve(name + "-next")));
        }
        assertFalse(BenchmarkJson.read(temporary.resolve("no-ack-control.json")).path("applicationConfirmed").asBoolean());
        for (String mode : List.of("no-ack", "delayed-ack")) {
            var control = BenchmarkJson.read(temporary.resolve(mode + "-control.json"));
            var raw = Files.readAllLines(temporary.resolve(mode + "/stdout.log"));
            assertTrue(control.path("verificationPassed").asBoolean()); assertEquals("FAILED", control.path("runStatus").asText());
            assertFalse(control.path("applicationConfirmed").asBoolean());
            assertTrue(control.path("controlFailureType").asText().endsWith("$AckUnconfirmed"));
            assertEquals(2, raw.stream().filter(s -> s.startsWith("DISPATCH ")).count());
            assertTrue(raw.contains("DRAINED 2")); assertEquals("DONE 2", raw.getLast());
            assertEquals(mode.equals("delayed-ack"), raw.stream().anyMatch(s -> s.startsWith("APPLIED ")));
            assertFalse(control.path("received").toString().contains("APPLIED"));
            assertEquals(0, BenchmarkJson.read(temporary.resolve(mode + "/exit.json")).path("exit").asInt(-1));
            assertFalse(Files.exists(temporary.resolve(mode + "/manifest.json")));
        }
        assertEquals(0, BenchmarkJson.read(temporary.resolve("timeout/exit.json")).path("exit").asInt());
        assertFalse(Files.exists(temporary.resolve("timeout/manifest.json")));
        assertFalse(Files.exists(temporary.resolve("late-log-failure/manifest.json")));
        for (String defect : List.of("missing-applied", "extra-dispatch", "missing-drained")) {
            var raw = Files.readString(temporary.resolve(defect + "/stdout.log"));
            assertFalse(BenchmarkJson.read(temporary.resolve(defect + "-control.json")).path("verificationPassed").asBoolean());
            if (defect.equals("missing-applied")) assertFalse(raw.contains("APPLIED"));
            if (defect.equals("extra-dispatch")) assertTrue(raw.contains("DISPATCH write 1"));
            if (defect.equals("missing-drained")) assertFalse(raw.contains("DRAINED"));
            assertFalse(Files.exists(temporary.resolve(defect + "-next")));
        }
    }

    @Test void exitDuringFailureEvidenceStillRequiresActualTerminationAndStreamReceipt() throws Exception {
        var command = Phase18Tools.command(Path.of(System.getProperty("java.home"), "bin/java.exe"), temporary.resolve("race.args"),
                temporary.resolve("race"), "linger", 1, 65536);
        assertThrows(TimeoutException.class, () -> Phase18Process.run(command, child -> child.awaitExit(1), (path, value) -> {
            // alive 판정과 최종 소유 해제 사이 실제 exit를 유도하여 순서 역전 검증
            if (path.getFileName().toString().equals("exit.json")) Thread.sleep(1200);
            BenchmarkJson.write(path, value);
        }));
        Phase18Process.observeRemaining(3000);
        var late = BenchmarkJson.read(temporary.resolve("race/late-exit.json"));
        assertEquals(0, late.path("exit").asInt(-1)); assertTrue(late.path("streamsClosed").asBoolean());
        assertTrue(late.path("failedEarlier").asBoolean()); assertFalse(late.path("nextTaskAllowed").asBoolean());
        assertTrue(Phase18Process.unresolved().isEmpty()); assertFalse(Files.exists(temporary.resolve("race/manifest.json")));
    }

    @Test void stopWaitsForDispatchHandoffAndBlocksAllLaterStarts() throws Exception {
        var gate = new Phase18Control(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var dispatch = executor.submit(() -> gate.dispatch(() -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; }));
            assertTrue(entered.await(3, TimeUnit.SECONDS)); var stop = executor.submit(gate::stop);
            assertFalse(stop.isDone()); release.countDown(); assertTrue(dispatch.get(3, TimeUnit.SECONDS)); stop.get(3, TimeUnit.SECONDS);
            assertFalse(gate.dispatch(() -> { fail("Started after stop application"); return null; }));
            assertEquals(1, gate.snapshot().active()); gate.complete(); assertEquals(0, gate.snapshot().active());
            assertThrows(IllegalStateException.class, gate::complete);
        }
    }

    @Test void earliestErrorAndOriginalExceptionIdentitySurviveSecondaryFailures() {
        var first = new IOException("first"); var secondary = new IOException("later"); var fatal = new AssertionError("fatal");
        assertSame(first, Phase18Process.preserve(first, secondary));
        assertSame(first, Phase18Process.preserve(first, secondary)); assertEquals(1, first.getSuppressed().length);
        assertSame(fatal, Phase18Process.preserve(first, fatal)); assertSame(first, fatal.getSuppressed()[0]);
        var laterError = new AssertionError("later fatal"); assertSame(fatal, Phase18Process.preserve(fatal, laterError));
    }

    @Test void synchronousCompletionAndPartialStartFailureKeepOwnership() throws Exception {
        var immediate = new Phase18Control();
        assertTrue(immediate.dispatch(() -> { immediate.complete(); return null; }));
        assertEquals(1, immediate.snapshot().completed()); assertEquals(0, immediate.snapshot().active());
        var partial = new Phase18Control(); var original = new IOException("partial-start");
        assertSame(original, assertThrows(IOException.class, () -> partial.dispatch(() -> { throw original; })));
        assertEquals(1, partial.snapshot().active()); assertFalse(partial.dispatch(() -> null));
        partial.complete(); assertEquals(0, partial.snapshot().active());
    }

    @Test void identityErrorOwnsStartedJvmAndPreservesCleanupEvidenceFailure() throws Exception {
        var fatal = new AssertionError("identity-error"); var secondary = new IOException("cleanup evidence");
        var command = Phase18Tools.command(Path.of(System.getProperty("java.home"), "bin/java.exe"), temporary.resolve("error.args"),
                temporary.resolve("error"), "control", 4000, 65536);
        assertSame(fatal, assertThrows(AssertionError.class, () -> Phase18Process.run(command, child -> fail("Body after identity error"),
                (path, value) -> {
                    if (path.getFileName().toString().equals("identity.json")) throw fatal;
                    if (path.getFileName().toString().equals("exit.json")) throw secondary;
                    BenchmarkJson.write(path, value);
                })));
        assertTrue(Arrays.asList(fatal.getSuppressed()).contains(secondary)); assertTrue(Phase18Process.unresolved().isEmpty());
    }
}
