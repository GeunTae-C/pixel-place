package dev.cgt.pixelplace.measurement;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 유한한 메모리 집계만 수행하는 계측 경계. 저장소·lock·network 호출 및 authoritative 상태 변경 책임 없음 */
@Component
public final class PixelMeasurement {
    public enum Phase { prepare, warmup, measurement, drain }
    public enum Outcome { success, failure, skipped, committed, rollback, ambiguous }
    public enum Operation {
        command, core_call, core_inside, batch_core_call, batch_core_inside, group_request, queue_wait,
        append, storage_append, record_force, empty_force,
        write_wait, write_held, capture_wait, capture_held, retention_wait, retention_held,
        recovery_scan, capture_scan, retention_scan, first_write_scan, snapshot,
        checkpoint_read, metadata_read, transaction, reconciliation, flush_cycle, retention,
        redis_check, redis_start, broadcast, overview
    }
    public record Scope(Operation operation, Phase phase, long startedNanos) { }
    public record Capture(long observedNanos, long checkpoint, long tail, int records) { }
    public record Cycle(long startedNanos, long previousStartedNanos, long completedNanos, Outcome outcome) { }
    public record WalCounts(long singleCalls, long batchCalls, long batchRecords, long completedRecords,
                            long recordForces, long coveredRecords, long emptyForces, long failedForces,
                            long rotations, java.util.Map<Integer, Long> batchSizes) { }

    private final boolean enabled;
    private final Timer[][][] timers;
    private final AtomicInteger activeCommands = new AtomicInteger();
    private final AtomicLong observerFailures = new AtomicLong();
    private final AtomicLong broadcastFailures = new AtomicLong();
    private long singleCalls, batchCalls, batchRecords, completedRecords, recordForces,
            coveredRecords, emptyForces, failedForces, rotations;
    private final long[] batchSizes = new long[130];
    private volatile Phase phase = Phase.prepare;
    private volatile Capture lastCapture;
    private final java.util.concurrent.atomic.AtomicReference<Cycle> lastCycle = new java.util.concurrent.atomic.AtomicReference<>();

    public PixelMeasurement(@Value("${pixel-place.measurement.enabled:false}") boolean enabled, MeterRegistry registry) {
        this.enabled = enabled;
        timers = new Timer[Operation.values().length][Phase.values().length][Outcome.values().length];
        if (!enabled) return;
        // registry의 최초 등록은 bean 생성 때 완료. 데이터 lock 안에서 tag 생성·외부 callback 없음
        for (Operation operation : Operation.values()) for (Phase p : Phase.values()) for (Outcome outcome : Outcome.values()) {
            try {
                timers[operation.ordinal()][p.ordinal()][outcome.ordinal()] = Timer.builder("pixel.place.operation")
                        .tag("operation", operation.name()).tag("phase", p.name()).tag("outcome", outcome.name())
                        .distributionStatisticExpiry(Duration.ofMinutes(1)).distributionStatisticBufferLength(3)
                        .register(registry);
            } catch (RuntimeException observerFailure) { observerFailures.incrementAndGet(); }
        }
    }

    /** 진입 시 phase를 고정하여 경계 도중 종료돼도 시작 구간에 귀속 */
    public Scope begin(Operation operation) { return enabled ? new Scope(operation, phase, System.nanoTime()) : null; }
    public void end(Scope scope, Outcome outcome) {
        endAt(scope, outcome, System.nanoTime());
    }
    /** claim 시각처럼 경계 안에서 얻은 완료 시각을 사용하여 게시/계측 지연을 queue wait에서 제외 */
    public void endAt(Scope scope, Outcome outcome, long completedNanos) {
        if (scope == null) return;
        try {
            Timer timer = timers[scope.operation.ordinal()][scope.phase.ordinal()][outcome.ordinal()];
            if (timer == null) observerFailures.incrementAndGet();
            else timer.record(Math.max(0, completedNanos - scope.startedNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException observerFailure) { observerFailures.incrementAndGet(); }
    }
    /** 원래 raw Error identity를 보존하며 계측 Error를 정상 결과로 숨기지 않음 */
    public void endPreserving(Scope scope, Outcome outcome, Throwable primary) {
        try { end(scope, outcome); }
        catch (Error observerError) {
            if (primary instanceof Error && primary != observerError) {
                if (java.util.Arrays.stream(primary.getSuppressed()).noneMatch(t -> t == observerError)) primary.addSuppressed(observerError);
            } else throw observerError;
        }
    }
    public <T> T observe(Operation operation, Supplier<T> action) {
        Scope scope = begin(operation); Throwable failure = null;
        try { return action.get(); }
        catch (RuntimeException | Error problem) { failure = problem; throw problem; }
        finally { endPreserving(scope, failure == null ? Outcome.success : Outcome.failure, failure); }
    }
    public void observe(Operation operation, Runnable action) { observe(operation, () -> { action.run(); return null; }); }
    public void phase(Phase next) { phase = java.util.Objects.requireNonNull(next); }
    public Phase phase() { return phase; }
    public boolean enabled() { return enabled; }
    public void commandEntered() { activeCommands.incrementAndGet(); }
    public void commandExited() { activeCommands.decrementAndGet(); }
    public int activeCommands() { return activeCommands.get(); }
    public long observerFailures() { return observerFailures.get(); }
    public void broadcastFailure() { if (enabled) broadcastFailures.incrementAndGet(); }
    public long broadcastFailures() { return broadcastFailures.get(); }
    /** 정상 capture가 이미 검증한 batch의 실제 건수만 게시. 추가 scan이나 seq 차이 계산 없음 */
    public void captured(long checkpoint, long tail, int records) {
        lastCapture = new Capture(System.nanoTime(), checkpoint, tail, records);
    }
    public Capture lastCapture() { return lastCapture; }
    /** 실제 storage 호출 단위. admitted/seq 차이가 아닌 검증된 append 입력 record 수 사용 */
    public synchronized void walStarted(boolean batch, int records) {
        if (batch) { batchCalls++; batchRecords += records; if (enabled) batchSizes[Math.min(129, records)]++; }
        else singleCalls++;
    }
    public synchronized void walCompleted(int records) { completedRecords += records; }
    /** 마지막 성공 force 뒤 새로 완성된 line 수. 파일 identity를 무한 tag로 등록하지 않음 */
    public synchronized void walForced(int records, boolean success) {
        if (!success) failedForces++;
        else if (records == 0) emptyForces++;
        else { recordForces++; coveredRecords += records; }
    }
    public synchronized void walRotated() { rotations++; }
    /** off에도 내구성 대조용 계수 유지. 선택 비활성 분포는 0 대신 null */
    public synchronized WalCounts walCounts() {
        java.util.Map<Integer, Long> sizes = null;
        if (enabled) {
            sizes = new java.util.TreeMap<>();
            for (int i = 1; i < batchSizes.length; i++) if (batchSizes[i] > 0) sizes.put(i, batchSizes[i]);
            sizes = java.util.Map.copyOf(sizes);
        }
        return new WalCounts(singleCalls, batchCalls, batchRecords, completedRecords,
                recordForces, coveredRecords, emptyForces, failedForces, rotations, sizes);
    }
    /** timer의 완료 count와 별도로 실제 cycle 시작 간격 게시. 마지막 관측값이며 전체 histogram 아님 */
    public void cycleCompleted(Scope scope, Outcome outcome) {
        if (scope == null) return;
        lastCycle.updateAndGet(current -> current != null && current.startedNanos() == scope.startedNanos()
                ? new Cycle(current.startedNanos(), current.previousStartedNanos(), System.nanoTime(), outcome) : current);
    }
    public void cycleStarted(Scope scope) {
        if (scope != null) lastCycle.updateAndGet(previous -> new Cycle(scope.startedNanos(), previous == null ? 0 : previous.startedNanos(), 0, null));
    }
    public Cycle lastCycle() { return lastCycle.get(); }
}
