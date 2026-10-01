package dev.cgt.benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 상위 중단과 실제 자식 시작 인계의 경계. stdout 소비는 실행 스레드 하나만 담당 */
final class Phase18ParentControl {
    private final Phase18Control gate = new Phase18Control();
    private final long ackMillis, cleanupMillis;
    private final Phase18Deadline execution;
    private volatile Throwable failure;
    private record Notice(String message, Throwable cause, long receivedNanos) { }
    private final AtomicReference<Notice> notice = new AtomicReference<>();
    private Phase18Process.Owned child;
    private long forwardedNanos, ackDeadline, cleanupDeadline, appliedDispatches = -1, appliedNanos;
    private boolean timelyApplied, drained, done;
    private boolean stopSent;
    private Map<String,Object> unconfirmed = Map.of();
    private final List<String> observed = new ArrayList<>();

    Phase18ParentControl(long ackMillis, long cleanupMillis) {
        this(ackMillis,cleanupMillis,null);
    }
    Phase18ParentControl(long ackMillis,long cleanupMillis,Phase18Deadline execution) {
        this.ackMillis = ackMillis; this.cleanupMillis = cleanupMillis;this.execution=execution;
    }

    /** stdin만 소비. 준비/정상 응답/발생기 결과 대기의 lock을 획득하지 않음 */
    void listen(InputStream input) {
        Thread.ofPlatform().daemon(true).name("phase18-app-control").start(() -> {
            try {
                var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                var line = new StringBuilder(); int b;
                while ((b = reader.read()) != -1) {
                    if (b == '\n') {
                        String message = line.toString(); line.setLength(0);
                        if (!Set.of("CLOSE", "STOP").contains(message)) throw new IOException("Unexpected parent control");
                        stop(message, new Stopped("Upstream " + message)); return;
                    }
                    if (b != '\r') line.append((char)b);
                    if (line.length() > 128) throw new IOException("Parent control size");
                }
                stop("EOF", new EOFException("Upstream control EOF"));
            } catch (Exception | Error problem) { stop("CONTROL_FAILURE", problem); }
        });
    }

    /** 상태를 먼저 고정. 시작 중이면 실제 Process 소유 인계 직후 STOP 전달 */
    void stop(String message, Throwable problem) {
        notice.compareAndSet(null, new Notice(message, problem, System.nanoTime()));
        synchronized (gate) {
            if (failure == null) failure = notice.get().cause();
            failure = Phase18Process.preserve(failure, problem);
            gate.stop();
            if (child != null && forwardedNanos == 0) {
                forwardedNanos = System.nanoTime();
                ackDeadline = forwardedNanos + TimeUnit.MILLISECONDS.toNanos(ackMillis);
                cleanupDeadline = forwardedNanos + TimeUnit.MILLISECONDS.toNanos(cleanupMillis);
                try { child.send("STOP"); stopSent = true; }
                catch (Exception | Error secondary) { failure = Phase18Process.preserve(failure, secondary); }
            }
        }
    }

    void check() throws Exception {
        // 수신과 lock 획득 순서가 뒤집혀도 최초 통보 원인/identity 보존
        Phase18Process.rethrow(failure());
        if(execution!=null)execution.check();
    }

    /** start/등록만 직렬화. body/receive/ready/exit 대기는 이 lock 밖에서 수행 */
    void start(Phase18Process.Owned owned, Callable<Void> start) throws Exception {
        synchronized (gate) {
            check();
            try { start.call(); }
            finally { if (owned.started()) child = owned; }
        }
        check();
    }

    /** 짧은 비동기 송신 인계 전용. 동기 HTTP/DB/native 대기를 넣으면 안 됨 */
    <T> T handoff(Callable<T> action) throws Exception {
        synchronized (gate) { check(); return action.call(); }
    }

    String receive(Phase18Process.Owned owned, long millis) throws Exception {
        String message = owned.receive(millis, this::check);
        observe(message); check(); return message;
    }

    private void observe(String message) {
        synchronized (gate) {
        if (observed.size() >= 128) throw new IllegalStateException("Stop protocol bound");
        observed.add(message);
        if (message.startsWith("APPLIED ") && forwardedNanos != 0 && appliedDispatches < 0) {
            String[] fields = message.split(" ");
            Phase18Plan.require(fields.length == 4, "producer APPLIED shape");
            appliedDispatches = Long.parseLong(fields[1]); appliedNanos = Long.parseLong(fields[3]);
            timelyApplied = System.nanoTime() <= ackDeadline;
            if (!timelyApplied) unconfirmed();
        }
        if (message.startsWith("DRAINED ")) {
            drained = appliedDispatches >= 0 && message.equals("DRAINED " + appliedDispatches);
        }
        if (message.startsWith("DONE ")) done = true;
        }
    }

    /** 실행 기한을 복원하지 않는 별도 유한 정리 관측. 동일 stdout 소비자가 APPLIED/소진/저장을 대조 */
    void settle(Phase18Process.Owned owned, Throwable original) throws Exception {
        synchronized (gate) {
            if (failure == null) stop("APP_FAILURE", original);
            else if (original != failure) failure = Phase18Process.preserve(failure, original);
        }
        Throwable secondary = null;
        try {
            while (!done) {
                long boundary = appliedDispatches < 0 && secondary == null ? Math.min(ackDeadline, cleanupDeadline) : cleanupDeadline;
                try { observe(owned.receiveCleanup(boundary)); }
                catch (TimeoutException missing) {
                    unconfirmed();
                    if (boundary == cleanupDeadline) throw missing;
                    secondary = new IOException("Producer APPLIED unconfirmed at stop deadline", missing);
                }
            }
        } catch (Exception | Error problem) { secondary = Phase18Process.preserve(secondary, problem); }
        if (!timelyApplied) {
            unconfirmed(); secondary = Phase18Process.preserve(secondary, new IOException("Producer STOP application unconfirmed or late"));
        }
        if (!drained || !done) secondary = Phase18Process.preserve(secondary, new IOException("Producer callback drain/raw save unconfirmed"));
        failure = Phase18Process.preserve(failure, secondary);
        Phase18Process.rethrow(failure);
    }

    private void unconfirmed() {
        if (unconfirmed.isEmpty()) unconfirmed = Map.of("observedNanos", System.nanoTime(), "ackDeadlineNanos", ackDeadline,
                "applicationConfirmed", false, "producerAlive", child != null && child.isAlive(), "observed", List.copyOf(observed));
    }

    boolean stopped() { return notice.get() != null; }
    Throwable failure() { var first=notice.get(); return failure != null ? failure : first == null ? null : first.cause(); }
    Map<String,Object> report() {
        synchronized (gate) {
            var report = new LinkedHashMap<String,Object>();
            var first=notice.get();
            report.put("notification", first == null ? "" : first.message()); report.put("receivedAppNanos", first == null ? 0 : first.receivedNanos());
            report.put("forwardedMessage", forwardedNanos == 0 ? "" : "STOP"); report.put("forwardedAppNanos", forwardedNanos);
            report.put("stopSendSucceeded", stopSent);
            report.put("unconfirmedAtDeadline", unconfirmed);
            report.put("producerPid", child == null ? -1 : child.pid()); report.put("producerAlive", child != null && child.isAlive());
            report.put("timelyApplied", timelyApplied); report.put("appliedDispatches", appliedDispatches); report.put("appliedProducerNanos", appliedNanos);
            report.put("callbacksDrained", drained); report.put("rawSaved", done); report.put("observed", List.copyOf(observed));
            report.put("nextTaskAllowed", false); report.put("ownedRemaining", Phase18Process.unresolved());
            report.put("failures", failure == null ? List.of() : failures(failure)); return report;
        }
    }
    static List<String> failures(Throwable problem) {
        var values = new ArrayList<String>(); values.add(problem.getClass().getName() + ": " + problem.getMessage());
        for (Throwable secondary : problem.getSuppressed()) values.addAll(failures(secondary));
        return values;
    }
    static final class Stopped extends IOException { Stopped(String message) { super(message); } }
}
