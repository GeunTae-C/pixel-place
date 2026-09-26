package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import static dev.cgt.pixelplace.pixel.application.ExecutionSnapshot.State.*;

/** bounded FIFO의 단일 처리 owner. caller gate는 caller가 terminal을 받은 뒤에만 해제 */
public final class GroupPixelWriteExecutor implements PixelWriteExecutor {
    private final Object state = new Object();
    private final FlushBoundaryCoordinator coordinator;
    private final PixelWriteService core;
    private final DirtyTileTracker dirty;
    private final ServiceReadiness readiness;
    private final PixelMeasurement measurement;
    private final int maxBatchSize;
    private final int maxOutstanding;
    private final long timeoutNanos;
    private final long graceNanos;
    private final LongSupplier nanoTime;
    private final Consumer<Thread> starter;
    private final Deque<Submission> queue = new ArrayDeque<>();
    private final Set<Submission> unpublished = new LinkedHashSet<>();
    private ExecutionSnapshot.State lifecycle = RUNNING;
    private int outstanding;
    private int claimed;
    private boolean inFlight;
    private boolean workerEnded;
    private Thread worker;
    private BatchWriteGuard currentBatch;

    public GroupPixelWriteExecutor(FlushBoundaryCoordinator coordinator, PixelWriteService core,
                                  DirtyTileTracker dirty, ServiceReadiness readiness,
                                  WriteExecutionProperties properties, PixelMeasurement measurement) {
        this(coordinator, core, dirty, readiness, properties, measurement, System::nanoTime, Thread::start);
    }

    // 시각 경계와 thread 시작 실패를 결정론적으로 검증하는 seam. production은 단조 시각/실제 start 사용
    GroupPixelWriteExecutor(FlushBoundaryCoordinator coordinator, PixelWriteService core,
                            DirtyTileTracker dirty, ServiceReadiness readiness,
                            WriteExecutionProperties properties, PixelMeasurement measurement, LongSupplier nanoTime, Consumer<Thread> starter) {
        properties.validate();
        this.coordinator = coordinator;
        this.core = core;
        this.dirty = dirty;
        this.readiness = readiness;
        this.measurement = measurement;
        this.nanoTime = nanoTime;
        this.starter = starter;
        var group = properties.getGroup();
        maxBatchSize = group.getMaxBatchSize();
        maxOutstanding = group.getMaxOutstanding();
        timeoutNanos = group.getQueueTimeout().toNanos();
        graceNanos = group.getShutdownGrace().toNanos();
    }

    /** QUEUED 게시가 접수 시점. state를 반환한 뒤에만 결과 대기, 취소는 claim 이전에만 허용 */
    @Override
    public PixelWriteResult execute(long userId, int x, int y, int color) {
        return measurement.observe(PixelMeasurement.Operation.group_request, () -> submit(userId, x, y, color));
    }

    private PixelWriteResult submit(long userId, int x, int y, int color) {
        PixelWriteService.validateWriteRequest(userId, x, y, color);
        Submission submission;
        Throwable startFailure = null;
        synchronized (state) {
            if (lifecycle != RUNNING || !readiness.isReady() || outstanding >= maxOutstanding)
                throw new PixelWriteBusyException();
            submission = new Submission(new PixelWriteRequest(userId, x, y, color), nanoTime.getAsLong(), timeoutNanos,
                    measurement.begin(PixelMeasurement.Operation.queue_wait));
            queue.addLast(submission);
            outstanding++;
            if (worker == null) {
                try {
                    worker = Thread.ofPlatform().name("pixel-group-write-worker").daemon(false).unstarted(this::runWorker);
                    starter.accept(worker);
                }
                catch (RuntimeException | Error failure) {
                    // 시작 실패도 재시작 금지. 이미 게시한 접수와 permit을 정리한 뒤 원래 원인 전파
                    failLocked(failure);
                    workerEnded = true;
                    startFailure = failure;
                }
            }
            state.notifyAll();
        }
        publishSelected();
        if (startFailure != null) rethrow(startFailure);
        return awaitResult(submission);
    }

    private PixelWriteResult awaitResult(Submission submission) {
        boolean interrupted = false;
        try {
            for (;;) {
                long remaining;
                synchronized (state) {
                    long elapsed = nanoTime.getAsLong() - submission.enqueuedAtNanos;
                    remaining = submission.timeoutNanos - elapsed;
                    if (submission.phase == Phase.QUEUED && elapsed >= submission.timeoutNanos)
                        cancelLocked(submission, Phase.FAILED_BUSY);
                    if (submission.phase != Phase.QUEUED) remaining = 0;
                }
                publishSelected();
                try {
                    BatchWriteOutcome outcome = remaining > 0
                            ? submission.result.get(remaining, TimeUnit.NANOSECONDS) : submission.result.get();
                    if (outcome.failure() != null) rethrow(outcome.failure());
                    return outcome.result();
                } catch (TimeoutException timeout) {
                    // 실제 판정은 다음 회차의 공유 접수 시각 차이로만 수행
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    synchronized (state) {
                        if (submission.phase == Phase.QUEUED) cancelLocked(submission, Phase.CANCELLED);
                    }
                    // claim 승자는 취소하지 않으며 terminal 게시까지 caller gate 유지
                } catch (ExecutionException impossible) {
                    rethrow(impossible.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void runWorker() {
        try {
            for (;;) {
                BatchWriteGuard batch;
                List<Submission> members = new ArrayList<>(maxBatchSize);
                synchronized (state) {
                    while (queue.isEmpty() && lifecycle == RUNNING) state.wait();
                    if (lifecycle == FAILED || (queue.isEmpty() && lifecycle == STOPPING)) break;
                    while (!queue.isEmpty() && members.size() < maxBatchSize) {
                        Submission next = queue.getFirst();
                        // caller가 아직 timeout을 실행하지 않았더라도 만료된 요청의 claim 금지
                        if (nanoTime.getAsLong() - next.enqueuedAtNanos >= next.timeoutNanos) {
                            cancelLocked(next, Phase.FAILED_BUSY);
                        } else {
                            queue.removeFirst();
                            next.phase = Phase.CLAIMED;
                            next.claimedAtNanos = System.nanoTime();
                            claimed++;
                            members.add(next);
                        }
                    }
                    batch = members.isEmpty() ? null : new BatchWriteGuard(
                            members.stream().map(member -> member.request).toList(), state,
                            (index, outcome) -> selectLocked(members.get(index), outcome));
                    currentBatch = batch;
                    inFlight = batch != null;
                }
                publishSelected();
                if (batch == null) continue;
                // 실제 claim 완료 뒤 queue wait 관측. state 밖에서 종료하여 registry callback과 잠금 소유 분리
                for (Submission member : members) measurement.endAt(member.queueScope, PixelMeasurement.Outcome.success, member.claimedAtNanos);
                coordinator.coordinate(() -> {
                    try {
                        BatchWriteResult result = measurement.observe(PixelMeasurement.Operation.batch_core_call,
                                () -> core.writeBatch(batch,
                                value -> dirty.markDirty(value.tileKey(), value.eventSeq(), value.tileVersion())));
                        if (result.fatalFailure() != null) {
                            synchronized (state) { failLocked(result.fatalFailure()); }
                        }
                        return null;
                    } catch (RuntimeException | Error failure) {
                        // boundary 반환 직후 capture가 진입해도 새 WAL-only plan을 만들 틈 차단
                        synchronized (state) { failLocked(failure); }
                        throw failure;
                    }
                });
                publishSelected();
                synchronized (state) {
                    currentBatch = null;
                    inFlight = false;
                    state.notifyAll();
                }
            }
        } catch (InterruptedException interruption) {
            // 외부 interrupt로 worker 재생성/요청 소실 금지. 종료 소유자는 interrupt를 사용하지 않음
            synchronized (state) { failLocked(new IllegalStateException("Group worker interrupted", interruption)); }
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error failure) {
            synchronized (state) { failLocked(failure); }
            publishSelected();
            if (failure instanceof Error error) throw error;
        } finally {
            publishSelected();
            synchronized (state) {
                currentBatch = null;
                inFlight = false;
                workerEnded = true;
                if (lifecycle != FAILED) lifecycle = STOPPED;
                state.notifyAll();
            }
        }
    }

    // state 보유 경로 전용. WRITE_FAILED는 정상 기존 pending reconciliation을 막지 않음
    private void failLocked(Throwable failure) {
        readiness.markWriteFailed();
        lifecycle = FAILED;
        if (currentBatch != null) currentBatch.failRemaining(failure);
        while (!queue.isEmpty()) cancelLocked(queue.getFirst(), Phase.FAILED_BUSY);
        state.notifyAll();
    }

    private void cancelLocked(Submission submission, Phase phase) {
        queue.remove(submission);
        selectLocked(submission, new BatchWriteOutcome(BatchWriteOutcome.State.FAILED, null, new PixelWriteBusyException()));
        submission.phase = phase;
    }

    private void selectLocked(Submission submission, BatchWriteOutcome outcome) {
        if (submission.outcome != null) return;
        if (submission.phase == Phase.CLAIMED) claimed--;
        submission.phase = Phase.TERMINAL;
        submission.outcome = outcome;
        outstanding--;
        unpublished.add(submission);
        state.notifyAll();
    }

    // 게시 권한만 mutex 아래 획득. completion은 게시 스레드 자신의 모든 write/state 잠금 밖
    private void publishSelected() {
        for (;;) {
            Submission selected;
            synchronized (state) {
                selected = unpublished.stream().filter(item -> !item.publishing).findFirst().orElse(null);
                if (selected == null) return;
                selected.publishing = true;
            }
            selected.result.complete(selected.outcome);
            synchronized (state) {
                unpublished.remove(selected);
                state.notifyAll();
            }
        }
    }

    @Override
    public ExecutionSnapshot snapshot() {
        synchronized (state) {
            return new ExecutionSnapshot(queue.size(), claimed, inFlight ? 1 : 0, outstanding,
                    unpublished.size(), lifecycle, inFlight ? 1 : 0, worker != null && worker.isAlive());
        }
    }

    /** grace는 소진 유예. UNKNOWN 게시 뒤에도 실제 worker 반환을 기다려 기존 storage owner의 close 선행 방지 */
    @Override
    public void close() {
        boolean interrupted = false;
        long started = System.nanoTime();
        Thread toJoin;
        synchronized (state) {
            if (lifecycle == RUNNING) lifecycle = STOPPING;
            if (worker == null) { if (lifecycle != FAILED) lifecycle = STOPPED; return; }
            state.notifyAll();
            while (!workerEnded && lifecycle != FAILED) {
                long remaining = graceNanos - (System.nanoTime() - started);
                if (remaining <= 0) {
                    // 같은 guard에서 abort를 먼저 설치. 파일 I/O와 상위 잠금의 반환 대기 없이 결과 선택
                    readiness.markWriteFailed();
                    lifecycle = FAILED;
                    if (currentBatch != null) currentBatch.abort(new PixelWriteUnknownException());
                    while (!queue.isEmpty()) cancelLocked(queue.getFirst(), Phase.FAILED_BUSY);
                    break;
                }
                try { TimeUnit.NANOSECONDS.timedWait(state, remaining); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
            toJoin = worker;
        }
        publishSelected();
        for (;;) {
            try { toJoin.join(); break; }
            catch (InterruptedException interruption) { interrupted = true; }
        }
        synchronized (state) {
            while (!unpublished.isEmpty()) {
                try { state.wait(); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException("Group write failed", failure);
    }

    private enum Phase { QUEUED, CLAIMED, CANCELLED, FAILED_BUSY, TERMINAL }

    /** servlet/security 객체 없이 검증된 값과 공유 접수 시각만 보관. future는 외부에 노출하지 않음 */
    private static final class Submission {
        final PixelWriteRequest request;
        final long enqueuedAtNanos;
        final long timeoutNanos;
        final CompletableFuture<BatchWriteOutcome> result = new CompletableFuture<>();
        final PixelMeasurement.Scope queueScope;
        Phase phase = Phase.QUEUED;
        BatchWriteOutcome outcome;
        boolean publishing;
        long claimedAtNanos;
        Submission(PixelWriteRequest request, long enqueuedAtNanos, long timeoutNanos, PixelMeasurement.Scope queueScope) {
            this.request = request;
            this.enqueuedAtNanos = enqueuedAtNanos;
            this.timeoutNanos = timeoutNanos;
            this.queueScope = queueScope;
        }
    }
}
