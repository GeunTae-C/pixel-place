package dev.cgt.benchmark;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 잠금 내부에서는 비민감 유한 메타데이터만 수집. 관측 오류가 실제 호출/close를 대체하지 않는 경계 */
public final class Phase17CTrace {
    public record Event(long order, long nanos, long call, long parent, String thread, String operation, String target, String phase, String result) { }
    private final List<Event> events = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final ThreadLocal<Deque<Long>> stack = ThreadLocal.withInitial(ArrayDeque::new);
    private final int maximumEvents; private final long maximumBytes;
    private long bytes; private volatile boolean incomplete;
    public Phase17CTrace(int maximumEvents, long maximumBytes) {
        if (maximumEvents <= 0 || maximumBytes <= 0) throw new IllegalArgumentException("Positive trace budget required");
        this.maximumEvents = maximumEvents; this.maximumBytes = maximumBytes;
    }
    public long begin(String operation, String target) {
        long id = ids.incrementAndGet();
        try { var s = stack.get(); add(id, s.isEmpty() ? 0 : s.peek(), operation, target, "enter", ""); s.push(id); }
        catch (RuntimeException failure) { incomplete = true; }
        return id;
    }
    public void end(long call, String operation, String target, Object result, Throwable failure) {
        try {
            var s = stack.get(); if (s.isEmpty() || s.pop() != call) incomplete = true;
            add(call, s.isEmpty() ? 0 : s.peek(), operation, target, failure == null ? "return" : "failure",
                    failure == null ? String.valueOf(result) : failure.getClass().getName());
        } catch (RuntimeException problem) { incomplete = true; }
    }
    private synchronized void add(long call, long parent, String op, String target, String phase, String result) {
        long size = 192L + 6L * (op.length() + target.length() + result.length() + Thread.currentThread().getName().length());
        if (events.size() >= maximumEvents || bytes + size > maximumBytes) { incomplete = true; return; }
        events.add(new Event(events.size() + 1L, System.nanoTime(), call, parent, Thread.currentThread().getName(), op, target, phase, result)); bytes += size;
    }
    public void incomplete() { incomplete = true; }
    public boolean complete() { return !incomplete; }
    public synchronized List<Event> events() { return List.copyOf(events); }
    public long count(String operation, String phase) { return events().stream().filter(e -> e.operation.equals(operation) && e.phase.equals(phase)).count(); }
    public synchronized Map<String, Object> report() { return Map.of("complete", complete(), "events", List.copyOf(events), "chargedBytes", bytes); }
}
