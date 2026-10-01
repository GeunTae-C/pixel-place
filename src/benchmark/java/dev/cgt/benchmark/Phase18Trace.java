package dev.cgt.benchmark;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 부하용 유한 집계와 파일 수명 journal. 매 record force는 집계만 보유, payload/무제한 경로 보유 금지 */
public final class Phase18Trace {
    private record Active(long id, long parent, long started, String phase, String operation) { }
    private final ThreadLocal<Deque<Active>> active = ThreadLocal.withInitial(ArrayDeque::new);
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, long[]> totals = new TreeMap<>();
    private final List<Map<String,Object>> journal = new ArrayList<>();
    private final int maximum; private final long byteLimit;
    private long bytes, loss; private volatile String phase = "startup";
    public Phase18Trace(int maximum, long byteLimit) { this.maximum = maximum; this.byteLimit = byteLimit; }
    public void phase(String value) { phase = value; }
    public long begin(String operation, String target) {
        long id = sequence.incrementAndGet();
        try { var stack = active.get(); if (stack.size() >= 64) { incomplete(); return -1; }
            stack.push(new Active(id,stack.isEmpty()?0:stack.peek().id(),System.nanoTime(),phase,operation));
        } catch (RuntimeException failure) { incomplete(); }
        return id;
    }
    public void end(long id, String operation, String target, Object result, Throwable failure) {
        if (id < 0) return;
        try {
            var stack = active.get(); var call = stack.poll();
            if (call == null || call.id() != id) { incomplete(); return; }
            long now = System.nanoTime();
            boolean lifetime=stack.stream().anyMatch(parent->Set.of("R","S","native.S").contains(parent.operation()));
            append(call, now, operation, target, failure == null ? String.valueOf(result) : failure.getClass().getName(), failure != null,lifetime);
        } catch (RuntimeException failureInObserver) { incomplete(); }
        catch (Error observerError) { preserveError(failure,observerError); }
    }
    /** Process.preserve와 같은 Error 우선순위. 원래 Error identity 또는 먼저 발생한 일반 실패를 함께 보존 */
    public static void preserveError(Throwable primary,Error observerError){
        if(primary instanceof Error original){
            if(original!=observerError&&Arrays.stream(original.getSuppressed()).noneMatch(t->t==observerError))original.addSuppressed(observerError);
        }else {
            // 새 Error를 일반 예외 아래 숨기지 않되, finally에서 밀려나는 작업 실패도 잃으면 안 됨
            if(primary!=null&&Arrays.stream(observerError.getSuppressed()).noneMatch(t->t==primary))observerError.addSuppressed(primary);
            throw observerError;
        }
    }
    private synchronized void append(Active call, long now, String op, String target, String result, boolean failed,boolean lifetime) {
        // native handle는 재사용되고 무한히 늘 수 있어 집계 key에서 제외. 개별 수명은 bounded journal에서만 관측
        String key = call.phase() + "/" + op + "/" + (op.startsWith("native.") ? "native" : target);
        if (key.length() > 1024 || result.length() > 1024) { loss++; return; }
        long[] value = totals.get(key);
        if (value == null) {
            long charge = 128 + key.length() * 2L;
            if (totals.size() >= maximum || bytes + charge > byteLimit) { loss++; return; }
            value = new long[3]; totals.put(key, value); bytes += charge;
        }
        value[0]++; value[1] += now - call.started(); if (failed) value[2]++;
        if (Set.of("record_force", "file.read", "file.read.close").contains(op)) return;
        // 매 write/capture의 identity 검사용 native open/close는 집계만 유지. R/S의 실제 close 순서는 journal 보존
        if(op.startsWith("native.")&&!op.equals("native.S")&&!lifetime)return;
        long charge = 256 + 2L * (key.length() + target.length() + result.length());
        if (journal.size() >= maximum || bytes + charge > byteLimit) { loss++; return; }
        journal.add(Map.of("call", call.id(), "parent",call.parent(), "startedNanos", call.started(), "completedNanos", now,
                "phase", call.phase(), "operation", op, "target", target, "result", result, "failed", failed)); bytes += charge;
    }
    public synchronized void incomplete() { loss++; }
    public synchronized boolean complete() { return loss == 0; }
    public synchronized Map<String,Object> report() {
        var snapshot = new TreeMap<String,long[]>(); totals.forEach((k,v) -> snapshot.put(k,v.clone()));
        return Map.of("schemaVersion",1,"clockId",Phase18Raw.clock(),"complete",complete(),"loss",loss,
                "chargedBytes",bytes,"aggregateUnits",List.of("calls","elapsedNanos","failures"),"totals",snapshot,"journal",List.copyOf(journal));
    }
}
