package dev.cgt.benchmark;

import java.util.concurrent.Callable;

/** 중단 적용과 신규 dispatch의 동일 lock 경계. 기존 요청 완료는 중단 뒤에도 소유 */
public final class Phase18Control {
    public enum State { OPEN, STOPPED }
    private State state = State.OPEN;
    private long dispatched, completed, active, appliedNanos;

    /** 작업 시작 인계까지 lock 안에서 수행하여 STOP 적용 뒤 시작하는 틈 차단 */
    public synchronized boolean dispatch(Callable<Void> start) throws Exception {
        if (state != State.OPEN) return false;
        dispatched++; active++;
        try { start.call(); return true; }
        catch (Exception | Error failure) {
            // 부분 시작 여부가 불명확하므로 소유량을 되돌리지 않고 후속 dispatch 차단
            stop(); throw failure;
        }
    }
    public synchronized long stop() {
        if (state == State.OPEN) { state = State.STOPPED; appliedNanos = System.nanoTime(); }
        return appliedNanos;
    }
    public synchronized void complete() {
        if (active <= 0) throw new IllegalStateException("Completion without owned dispatch");
        active--; completed++;
    }
    public synchronized Snapshot snapshot() { return new Snapshot(state, dispatched, completed, active, appliedNanos); }
    public record Snapshot(State state, long dispatched, long completed, long active, long appliedNanos) { }
}
