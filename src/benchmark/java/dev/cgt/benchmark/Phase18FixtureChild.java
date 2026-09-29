package dev.cgt.benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** HTTP 없는 실제 child JVM. 유한 synthetic 작업과 별도 제어 reader로 STOP 경계 검증 */
public final class Phase18FixtureChild {
    private Phase18FixtureChild() { }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("ownership-parent")) { ownershipParent(Path.of(args[1])); return; }
        if (mode.equals("closed-streams")) {
            Path root = Path.of(args[1]);
            say("STREAMS_CLOSING"); System.out.close(); System.err.close();
            Files.writeString(root.resolve("streams-closed"), "closed", StandardOpenOption.CREATE_NEW);
            Thread.sleep(3000);
            Files.writeString(root.resolve("child-self-exit"), "normal", StandardOpenOption.CREATE_NEW); return;
        }
        if (mode.equals("exit7")) { System.exit(7); return; }
        if (mode.equals("exit0")) { System.out.println("EXIT0"); return; }
        if (mode.equals("echo")) { System.out.println(args[1]); return; }
        if (mode.equals("linger")) { Thread.sleep(800); System.out.println("LATE_EXIT0"); return; }
        if (mode.equals("flood")) { for (int i = 0; i < 2000; i++) System.out.println("x".repeat(100)); return; }
        var input = new ArrayBlockingQueue<String>(32);
        Thread reader = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() > 128 || !input.offer(line)) { input.clear(); input.offer("CLOSE"); return; }
                }
                input.offer("CLOSE");
            } catch (IOException e) { input.offer("CLOSE"); }
        }, "phase18-fixture-control");
        reader.setDaemon(true); reader.start();
        var gate = new Phase18Control();
        long start = System.nanoTime(), completeAt = Long.MAX_VALUE, ackAt = Long.MAX_VALUE;
        boolean waiting = false, stopped = false, done = false;
        say("READY");
        // 독립 상한. parent가 종료해도 fixture가 무한 생존하지 않음
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8)) {
            String message = input.poll(10, TimeUnit.MILLISECONDS);
            if ("START".equals(message)) {
                if (gate.dispatch(() -> null)) { completeAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350); say("DISPATCH write 0"); }
                if (gate.dispatch(() -> null)) say("DISPATCH read 0");
                waiting = true; say("WAITING_MEASURE");
                if (mode.equals("child-failure")) {
                    gate.stop(); stopped = true;
                    say("FAILURE generator"); say("APPLIED " + gate.snapshot().dispatched() + " " + gate.snapshot().appliedNanos());
                }
            }
            if ("STOP".equals(message) || "CLOSE".equals(message)) {
                gate.stop(); stopped = true;
                if (mode.equals("delayed-ack")) ackAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
                else if (!mode.equals("no-ack") && !mode.equals("missing-applied")) say("APPLIED " + gate.snapshot().dispatched() + " " + gate.snapshot().appliedNanos());
                // 정상 MEASURE 대기 상태에서도 즉시 중단 적용
                if (gate.dispatch(() -> null)) throw new AssertionError("Dispatch after STOP");
                if (gate.snapshot().active() == 0 && !mode.equals("delayed-ack")) { done = true; break; }
            }
            if ("MEASURE".equals(message) && waiting) {
                // 정상 검사기가 계약 위반을 실제로 탐지하는지 확인하는 제한 결함 주입
                if (mode.equals("extra-dispatch")) say("DISPATCH write 1");
                if (gate.dispatch(() -> null)) { say("DISPATCH write 1"); gate.complete(); }
            }
            if (gate.snapshot().active() > 0 && System.nanoTime() >= completeAt) {
                while (gate.snapshot().active() > 0) gate.complete();
                if (!mode.equals("missing-drained")) say("DRAINED " + gate.snapshot().completed());
            }
            if (stopped && gate.snapshot().active() == 0 && (!mode.equals("delayed-ack") || System.nanoTime() >= ackAt)) {
                if (mode.equals("delayed-ack")) say("APPLIED " + gate.snapshot().dispatched() + " " + gate.snapshot().appliedNanos());
                done = true; break;
            }
        }
        if (!done) throw new IllegalStateException("Fixture own deadline");
        say("DONE " + gate.snapshot().dispatched());
    }

    /** main 반환 이후에도 실제 자식 소유가 JVM 생존을 유지하는지 검증하는 별도 부모 */
    private static void ownershipParent(Path root) throws Exception {
        Files.createDirectory(root);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { BenchmarkJson.write(root.resolve("parent-final.json"), Map.of("remaining", Phase18Process.unresolved(),
                    "selfExitObserved", Files.exists(root.resolve("child-self-exit")))); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }, "phase18-fixture-final-observer"));
        var command = Phase18Tools.command(Path.of(System.getProperty("java.home"), "bin/java.exe"), root.resolve("child.args"),
                root.resolve("owned"), "closed-streams", 100, 65536, root.toString());
        Throwable failure = null;
        try { Phase18Process.run(command, child -> {
            Phase18Tools.require(child.receive(3000).equals("STREAMS_CLOSING"), "EOF fixture ready");
            child.awaitExit(1);
        }); } catch (Exception | Error problem) { failure = problem; }
        boolean blocked = false;
        try { Phase18Process.sequence(List.of(() -> { Files.writeString(root.resolve("next"), "wrong"); return null; })); }
        catch (IllegalStateException expected) { blocked = true; }
        Phase18Tools.require(failure instanceof TimeoutException && blocked && !Phase18Process.unresolved().isEmpty(), "Retained parent ownership");
        BenchmarkJson.write(root.resolve("parent-return.json"), Map.of("failureType", failure.getClass().getName(),
                "remaining", Phase18Process.unresolved(), "nextBlocked", blocked, "mainReturning", true));
        // 이후 대기/reader에 의존하지 않고 main 반환. 실제 Process 소유 스레드만 생존 책임
    }
    private static void say(String value) { System.out.println(value); System.out.flush(); }
}
