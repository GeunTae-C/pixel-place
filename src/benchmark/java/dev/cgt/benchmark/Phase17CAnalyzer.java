package dev.cgt.benchmark;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import java.util.*;

/** seq 연속성을 가정하지 않는 C 정합성/순서 판정. 합성 검증과 실제 관측이 같은 경계를 사용 */
public final class Phase17CAnalyzer {
    private Phase17CAnalyzer() { }
    public static void retained(List<WalRecord> database, List<WalRecord> wal, long checkpoint, long tail) {
        if (wal.isEmpty() || database.isEmpty() || checkpoint != tail || wal.getLast().eventSeq() != tail
                || database.getLast().eventSeq() != checkpoint) throw new IllegalStateException("Empty or inconsistent retained WAL");
        increasing(database); increasing(wal);
        long start = wal.getFirst().eventSeq();
        var expected = database.stream().filter(e -> e.eventSeq() >= start && e.eventSeq() <= tail).toList();
        if (expected.size() != wal.size()) throw new IllegalStateException("Record missing in retained WAL interval");
        for (int i = 0; i < wal.size(); i++) if (!same(expected.get(i), wal.get(i))) throw new IllegalStateException("Retained DB/WAL order or payload mismatch");
    }
    private static void increasing(List<WalRecord> records) {
        long previous = 0;
        for (var r : records) { if (r.eventSeq() <= previous) throw new IllegalStateException("Duplicate or reversed event"); previous = r.eventSeq(); }
    }
    private static boolean same(WalRecord a, WalRecord b) {
        return a.eventSeq() == b.eventSeq() && a.userId() == b.userId() && a.z() == b.z() && a.tx() == b.tx()
                && a.ty() == b.ty() && a.x() == b.x() && a.y() == b.y() && a.color() == b.color();
    }
    public static void coverage(PixelMeasurement.WalCounts c, long events, String mode) {
        if (c.failedForces() != 0 || c.completedRecords() != events || c.coveredRecords() != events || c.recordForces() <= 0
                || c.emptyForces() != c.rotations() + 1) throw new IllegalStateException("C JVM force coverage mismatch");
        if (mode.equals("single") && (c.singleCalls() != events || c.batchCalls() != 0 || c.recordForces() != events)
                || mode.equals("group") && (c.singleCalls() != 0 || c.batchCalls() <= 0 || c.batchRecords() != events))
            throw new IllegalStateException("C mode append coverage mismatch");
        if (c.batchSizes() == null || c.batchSizes().values().stream().mapToLong(Long::longValue).sum() != c.batchCalls()
                || c.batchSizes().entrySet().stream().mapToLong(e -> e.getKey() * e.getValue()).sum() != c.batchRecords())
            throw new IllegalStateException("C batch distribution mismatch");
    }
    /** trace에서 역산하지 않는 action/검증된 initial 입력의 startup 기대값 */
    public record StartupExpectation(String memoryOperation, int replayCount, long seed, boolean recovery) {
        public StartupExpectation {
            if (!Set.of("initializeAllWhite", "loadAll").contains(memoryOperation) || replayCount < 0 || seed < 0)
                throw new IllegalArgumentException("Invalid startup expectation");
        }
    }
    private record CompletedCall(Phase17CTrace.Event enter, Phase17CTrace.Event returned) { }

    /** 필수 호출의 존재·단일성·성공 반환과 직렬 완료 순서 검증. READY 이후 추가 호출도 허용하지 않음 */
    public static void trace(Phase17CTrace trace, StartupExpectation expected) {
        integrity(trace);
        var events = trace.events();
        var prepared = completed(events, "prepareForRecovery", 1).getFirst();
        var memory = completed(events, expected.memoryOperation(), 1).getFirst();
        completed(events, expected.memoryOperation().equals("loadAll") ? "initializeAllWhite" : "loadAll", 0);
        var replay = completed(events, "applyReplayRecord", expected.replayCount());
        var seed = completed(events, "initializeLastIssued", 1).getFirst();
        var ready = completed(events, "markReady", 1).getFirst();
        after(prepared, memory);
        var previous = memory;
        for (var record : replay) { after(previous, record); previous = record; }
        after(previous, seed); after(seed, ready);
        // smoke 종료 후 전진한 seq 대신 startup 호출 당시 관측 인수 대조
        if (!seed.returned().result().equals(Long.toString(expected.seed()))) throw new IllegalStateException("Startup seed argument mismatch");
        if (expected.recovery() && (trace.count("R", "return") == 0 || trace.count("S", "return") == 0)) throw new IllegalStateException("Recovery R/S absent");
    }
    private static List<CompletedCall> completed(List<Phase17CTrace.Event> events, String operation, int count) {
        var entries = events.stream().filter(e -> e.operation().equals(operation) && e.phase().equals("enter")).toList();
        if (entries.size() != count) throw new IllegalStateException("Startup call count mismatch: " + operation);
        var result = new ArrayList<CompletedCall>();
        for (var entry : entries) {
            var ends = events.stream().filter(e -> e.call() == entry.call() && !e.phase().equals("enter")).toList();
            // 최솟값/최댓값 선택으로 실패·중복·미반환을 숨기지 않는 대응 경계
            if (ends.size() != 1 || !ends.getFirst().phase().equals("return") || !ends.getFirst().operation().equals(operation)
                    || !ends.getFirst().target().equals(entry.target()) || ends.getFirst().order() <= entry.order())
                throw new IllegalStateException("Startup successful return missing: " + operation);
            result.add(new CompletedCall(entry, ends.getFirst()));
        }
        return result;
    }
    private static void after(CompletedCall previous, CompletedCall next) {
        if (previous.returned().order() >= next.enter().order())
            throw new IllegalStateException("Startup completion order: " + previous.enter().operation() + " -> " + next.enter().operation());
    }
    public static void integrity(Phase17CTrace trace) {
        if (!trace.complete()) throw new IllegalStateException("Trace incomplete or overflow");
        var events = trace.events(); var open = new HashMap<Long, Phase17CTrace.Event>();
        var handles = new HashMap<String, Long>();
        long ready = entered(events, "markReady");
        for (var e : events) {
            if (e.phase().equals("enter")) { if (open.put(e.call(), e) != null) throw new IllegalStateException("Duplicate call"); }
            else {
                var before = open.remove(e.call());
                if (before == null || !before.operation().equals(e.operation()) || !before.target().equals(e.target())) throw new IllegalStateException("Unpaired trace");
                if (e.phase().equals("failure") && !(e.operation().equals("flushOnce") && e.result().endsWith("ServiceNotReadyException") && (ready == 0 || before.order() < ready)))
                    throw new IllegalStateException("Actual operation failed: " + e.operation());
                if (e.operation().equals("native.open") && handles.put(e.result(), e.order()) != null) throw new IllegalStateException("Native handle reused before observed close");
                if (e.operation().equals("native.close")) handles.remove(e.target());
            }
        }
        if (!open.isEmpty() || !handles.isEmpty()) throw new IllegalStateException("Missing close/return evidence");
    }
    /** snapshot의 모든 R과 그 뒤 마지막 동일 WAL 폴더 S를 같은 호출 구간에 대조 */
    public static void preparation(Phase17CTrace trace, Map<String, String> files, java.nio.file.Path wal) {
        var events = trace.events();
        var begin = events.stream().filter(e -> e.operation().equals("prepareForRecovery") && e.phase().equals("enter")).findFirst().orElseThrow();
        long end = returned(events, "prepareForRecovery");
        var inside = events.stream().filter(e -> e.order() > begin.order() && e.order() < end).toList();
        var synced = inside.stream().filter(e -> e.operation().equals("R") && e.phase().equals("return")).toList();
        var expected = new TreeSet<>(files.keySet());
        var actual = new TreeSet<String>();
        for (var e : synced) {
            if (!java.nio.file.Path.of(e.target()).getParent().equals(wal.getParent()) || !actual.add(java.nio.file.Path.of(e.target()).getFileName().toString())) throw new IllegalStateException("Recovery file identity repeated/mismatched");
            nativeSync(inside, e);
        }
        if (!actual.equals(expected)) throw new IllegalStateException("Recovery did not sync every captured file");
        var last = inside.stream().filter(e -> e.operation().equals("native.S") && e.phase().equals("return") && e.target().equals(wal.getParent().toString())).reduce((a,b)->b).orElseThrow();
        if (synced.stream().anyMatch(e -> e.order() >= last.order())) throw new IllegalStateException("Last S must follow every R");
        nativeSync(inside, last);
    }
    private static void nativeSync(List<Phase17CTrace.Event> events, Phase17CTrace.Event completed) {
        var calls = events.stream().filter(e -> e.parent() == completed.call() && e.phase().equals("return")).toList();
        var flushes = calls.stream().filter(e -> e.operation().equals("native.flush")).toList();
        if (flushes.size() != 1) throw new IllegalStateException("Native sync flush evidence absent/duplicated");
        var flush = flushes.getFirst();
        if (calls.stream().noneMatch(e -> e.operation().equals("native.open") && e.result().equals(flush.target()) && e.order() < flush.order())
                || calls.stream().noneMatch(e -> e.operation().equals("native.close") && e.target().equals(flush.target()) && e.order() > flush.order()))
            throw new IllegalStateException("Native sync open/flush/close order incomplete");
    }
    /** 소진·close 이후 물리 파일 전환과 적격 삭제 반환 수를 실제 경로의 Q/S/close에 연결 */
    public static void physical(Phase17CTrace trace) {
        integrity(trace); var events = trace.events(); long previousClose = 0; int generated = 0;
        for (var e : events) if (e.phase().equals("return")) {
            if (e.operation().equals("native.S")) nativeSync(events, e);
            if (e.operation().equals("S") && events.stream().filter(n -> n.operation().equals("native.S") && n.phase().equals("return")
                    && n.parent() == e.call() && n.target().equals(e.target())).count() != 1)
                throw new IllegalStateException("Directory S lacks actual native completion");
        }
        for (var opened : events.stream().filter(e -> Set.of("file.create","file.adopt").contains(e.operation()) && e.phase().equals("return")).toList()) {
            if (opened.order() <= previousClose) throw new IllegalStateException("Next writer opened before old channel closed");
            var close = events.stream().filter(e -> e.operation().equals("file.close") && e.phase().equals("return") && e.target().equals(opened.target()) && e.order() > opened.order()).findFirst().orElseThrow();
            String force = opened.operation().equals("file.create") ? "empty_force" : "adoption_force";
            if (force.equals("empty_force")) generated++;
            var forced = events.stream().filter(e -> e.operation().equals(force) && e.phase().equals("return") && e.target().equals(opened.target()) && e.order() > opened.order() && e.order() < close.order()).findFirst().orElseThrow();
            String parent = java.nio.file.Path.of(opened.target()).getParent().toString();
            var sync = events.stream().filter(e -> e.operation().equals("S") && e.phase().equals("return") && e.target().equals(parent) && e.order() > forced.order() && e.order() < close.order()).findFirst().orElseThrow();
            for (var e : events) if (e.operation().equals("record_force") && e.phase().equals("enter") && e.target().equals(opened.target()) && e.order() < sync.order() && e.order() > opened.order()) throw new IllegalStateException("Record force precedes candidate S");
            previousClose = close.order();
        }
        if (generated > 128) throw new IllegalStateException("Cumulative segment generation budget");
        for (var retention : events.stream().filter(e -> e.operation().equals("deleteCommittedPrefix") && e.phase().equals("return")).toList()) {
            var calls = events.stream().filter(e -> e.parent() == retention.call() && e.phase().equals("return") && Set.of("file.delete","Q","S").contains(e.operation())).toList();
            if (calls.size() % 4 != 0 || !retention.result().equals("deleted=" + calls.size()/4 + ";delayed=false")) throw new IllegalStateException("Retention count/operation mismatch");
            for (int i = 0; i < calls.size(); i += 4) {
                var d=calls.get(i);var q1=calls.get(i+1);var s=calls.get(i+2);var q2=calls.get(i+3);
                if (!d.operation().equals("file.delete") || !q1.operation().equals("Q") || !s.operation().equals("S") || !q2.operation().equals("Q")
                        || !d.target().equals(q1.target()) || !d.target().equals(q2.target()) || !s.target().equals(java.nio.file.Path.of(d.target()).getParent().toString())
                        || !q1.result().equals("ABSENT:parentSafe=true") || !q2.result().equals("ABSENT:parentSafe=true")) throw new IllegalStateException("Deletion Qbefore/S/Qafter not proved");
            }
        }
    }
    public static long returned(List<Phase17CTrace.Event> events, String operation) {
        return events.stream().filter(e -> e.operation().equals(operation) && e.phase().equals("return")).mapToLong(Phase17CTrace.Event::order).max().orElse(0);
    }
    public static long entered(List<Phase17CTrace.Event> events, String operation) {
        return events.stream().filter(e -> e.operation().equals(operation) && e.phase().equals("enter")).mapToLong(Phase17CTrace.Event::order).min().orElse(0);
    }
}
