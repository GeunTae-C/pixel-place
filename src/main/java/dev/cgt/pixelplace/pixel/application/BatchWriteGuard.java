package dev.cgt.pixelplace.pixel.application;

import java.util.*;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/** memory→dirty→terminal과 abort의 단일 직렬화 경계. guard 보유 중 상위 잠금·I/O·future 게시 금지 */
public final class BatchWriteGuard {
    private final List<PixelWriteRequest> requests;
    private final BatchWriteOutcome[] outcomes;
    private final Object mutex;
    private final BiConsumer<Integer, BatchWriteOutcome> selected;
    private boolean started;
    private boolean aborted;

    public BatchWriteGuard(List<PixelWriteRequest> requests) {
        this(requests, null, (index, outcome) -> { });
    }

    // group 접수·permit 반환과 memory/terminal을 같은 guard에 연결. callback은 상태 변경만 허용
    BatchWriteGuard(List<PixelWriteRequest> requests, Object mutex, BiConsumer<Integer, BatchWriteOutcome> selected) {
        this.mutex = mutex == null ? this : mutex;
        this.selected = Objects.requireNonNull(selected);
        this.requests = List.copyOf(requests);
        if (this.requests.isEmpty()) throw new IllegalArgumentException("Write batch must not be empty");
        outcomes = new BatchWriteOutcome[this.requests.size()];
        Arrays.fill(outcomes, new BatchWriteOutcome(BatchWriteOutcome.State.PENDING, null, null));
    }

    public List<PixelWriteRequest> requests() { return requests; }

    /** 미확정 요청만 UNKNOWN 선택. 이미 완료한 prefix와 원래 원인은 덮어쓰지 않음 */
    public void abort(Throwable cause) {
        synchronized (mutex) {
            Objects.requireNonNull(cause, "abort cause");
            aborted = true;
            selectRemaining(BatchWriteOutcome.State.UNKNOWN, cause);
        }
    }

    public List<BatchWriteOutcome> snapshot() {
        synchronized (mutex) { return List.copyOf(Arrays.asList(outcomes)); }
    }

    boolean begin() {
        synchronized (mutex) {
            if (started) throw new IllegalStateException("Write batch already started");
            started = true;
            return !aborted;
        }
    }

    boolean isAborted() { synchronized (mutex) { return aborted; } }

    // core만 호출. terminal 선택 전에 memory·dirty 실행 전체를 abort와 직렬화
    BatchWriteOutcome complete(int index, Supplier<BatchWriteOutcome> apply) {
        synchronized (mutex) {
            if (outcomes[index].state() != BatchWriteOutcome.State.PENDING) return outcomes[index];
            try {
                outcomes[index] = Objects.requireNonNull(apply.get(), "terminal outcome");
            } catch (RuntimeException failure) {
                outcomes[index] = new BatchWriteOutcome(BatchWriteOutcome.State.FAILED, null, failure);
            } catch (Error failure) {
                outcomes[index] = new BatchWriteOutcome(BatchWriteOutcome.State.FAILED, null, failure);
                selected.accept(index, outcomes[index]);
                throw failure;
            }
            selected.accept(index, outcomes[index]);
            return outcomes[index];
        }
    }

    void failRemaining(Throwable cause) {
        synchronized (mutex) { selectRemaining(BatchWriteOutcome.State.FAILED, cause); }
    }

    private void selectRemaining(BatchWriteOutcome.State state, Throwable cause) {
        for (int i = 0; i < outcomes.length; i++) {
            if (outcomes[i].state() == BatchWriteOutcome.State.PENDING) {
                outcomes[i] = new BatchWriteOutcome(state, null, cause);
                selected.accept(i, outcomes[i]);
            }
        }
    }
}
