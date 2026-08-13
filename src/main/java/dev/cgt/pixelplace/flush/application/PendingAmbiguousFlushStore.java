package dev.cgt.pixelplace.flush.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/*
 * 같은 process의 unresolved ambiguous flush 하나만 보존하는 memory-only CAS store
 * production write는 차단하지 않으며 process restart 이후 startup recovery를 대체하지 않음
 */
@Component
@Profile("!stub")
public class PendingAmbiguousFlushStore {

    private final AtomicReference<PendingAmbiguousFlush> pending = new AtomicReference<>();

    public Optional<PendingAmbiguousFlush> current() {
        return Optional.ofNullable(pending.get());
    }

    /* 기존 pending을 새 transaction candidate로 덮어쓰지 않는 설치 경계 */
    public void installIfAbsent(PendingAmbiguousFlush candidate) {
        PendingAmbiguousFlush current = Objects.requireNonNull(candidate, "pending must not be null");
        if (!pending.compareAndSet(null, current)) {
            throw new IllegalStateException("An ambiguous flush is already pending.");
        }
    }

    /* reconciliation이 처리한 동일 instance만 clear하여 다른 pending 손실 차단 */
    public void clearIfSame(PendingAmbiguousFlush expected) {
        PendingAmbiguousFlush current = Objects.requireNonNull(expected, "pending must not be null");
        if (!pending.compareAndSet(current, null)) {
            throw new IllegalStateException("Pending ambiguous flush changed before clear.");
        }
    }
}
