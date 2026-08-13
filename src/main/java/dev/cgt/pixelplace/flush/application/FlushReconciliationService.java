package dev.cgt.pixelplace.flush.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;

/*
 * pending expected/target과 lock으로 안정화된 DB checkpoint/mode의 exact equality만 판정하는 service
 * 과거 WAL, snapshot 또는 immutable plan을 재실행하거나 DB state를 수정하지 않음
 */
@Service
@Profile("!stub")
public class FlushReconciliationService {

    private final FlushDbStateProbe flushDbStateProbe;

    public FlushReconciliationService(FlushDbStateProbe flushDbStateProbe) {
        this.flushDbStateProbe = flushDbStateProbe;
    }

    public FlushReconciliationDecision reconcile(PendingAmbiguousFlush pending) {
        PendingAmbiguousFlush current = Objects.requireNonNull(pending, "pending must not be null");
        FlushDbState observed = Objects.requireNonNull(
                flushDbStateProbe.probe(),
                "flushDbStateProbe returned null"
        );

        if (current.bootstrapState() == DbBootstrapState.BOOTSTRAP_PENDING) {
            if (observed.lastFlushedEventSeq() == current.flushTargetEventSeq()
                    && observed.bootstrapState() == DbBootstrapState.INITIALIZED) {
                return FlushReconciliationDecision.COMMIT_CONFIRMED;
            }
            if (observed.lastFlushedEventSeq() == current.expectedLastFlushedEventSeq()
                    && observed.bootstrapState() == DbBootstrapState.BOOTSTRAP_PENDING) {
                return FlushReconciliationDecision.ROLLBACK_CONFIRMED;
            }
            throw unresolved(current, observed);
        }

        if (current.bootstrapState() == DbBootstrapState.INITIALIZED) {
            if (observed.bootstrapState() == DbBootstrapState.INITIALIZED
                    && observed.lastFlushedEventSeq() == current.flushTargetEventSeq()) {
                return FlushReconciliationDecision.COMMIT_CONFIRMED;
            }
            if (observed.bootstrapState() == DbBootstrapState.INITIALIZED
                    && observed.lastFlushedEventSeq() == current.expectedLastFlushedEventSeq()) {
                return FlushReconciliationDecision.ROLLBACK_CONFIRMED;
            }
            throw unresolved(current, observed);
        }

        throw unresolved(current, observed);
    }

    private UnresolvedFlushCommitException unresolved(
            PendingAmbiguousFlush pending,
            FlushDbState observed
    ) {
        // checkpoint >= target 같은 범위 판정은 다른 flush 결과를 이전 transaction에 귀속할 수 있음
        return new UnresolvedFlushCommitException(
                "Ambiguous flush could not be reconciled exactly. expected="
                        + pending.expectedLastFlushedEventSeq()
                        + ", target=" + pending.flushTargetEventSeq()
                        + ", pendingState=" + pending.bootstrapState()
                        + ", observedCheckpoint=" + observed.lastFlushedEventSeq()
                        + ", observedState=" + observed.bootstrapState()
        );
    }
}
