package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.tile.application.DirtyTile;

import java.util.List;
import java.util.Objects;

/*
 * commit 여부가 불명확한 transaction의 exact reconciliation과 dirty 소유권만 보존하는 immutable 값
 * WAL/snapshot/full plan을 저장하지 않아 과거 plan 재실행 경로를 만들지 않음
 */
public final class PendingAmbiguousFlush {

    private final long expectedLastFlushedEventSeq;
    private final long flushTargetEventSeq;
    private final DbBootstrapState bootstrapState;
    private final List<DirtyTile> drainedDirtyTiles;

    public PendingAmbiguousFlush(
            long expectedLastFlushedEventSeq,
            long flushTargetEventSeq,
            DbBootstrapState bootstrapState,
            List<DirtyTile> drainedDirtyTiles
    ) {
        if (expectedLastFlushedEventSeq < 0) {
            throw new IllegalArgumentException("expected checkpoint must not be negative");
        }
        if (flushTargetEventSeq <= expectedLastFlushedEventSeq) {
            throw new IllegalArgumentException("flush target must be greater than expected checkpoint");
        }
        this.bootstrapState = requireUsableState(bootstrapState);
        if (this.bootstrapState == DbBootstrapState.BOOTSTRAP_PENDING
                && expectedLastFlushedEventSeq != 0L) {
            throw new IllegalArgumentException("Bootstrap pending transaction requires expected checkpoint zero.");
        }
        Objects.requireNonNull(drainedDirtyTiles, "drainedDirtyTiles must not be null");
        for (DirtyTile dirtyTile : drainedDirtyTiles) {
            Objects.requireNonNull(dirtyTile, "drainedDirtyTiles must not contain null");
        }

        this.expectedLastFlushedEventSeq = expectedLastFlushedEventSeq;
        this.flushTargetEventSeq = flushTargetEventSeq;
        this.drainedDirtyTiles = List.copyOf(drainedDirtyTiles);
    }

    /* transaction 시작 전 이미 검증된 plan에서 allocation과 copy를 완료하는 pending candidate 생성 */
    public static PendingAmbiguousFlush from(FlushPlan plan) {
        FlushPlan source = Objects.requireNonNull(plan, "plan must not be null");
        if (source.noOp()) {
            throw new IllegalArgumentException("No-op plan cannot become ambiguous pending state.");
        }
        return new PendingAmbiguousFlush(
                source.expectedLastFlushedEventSeq(),
                source.flushTargetEventSeq(),
                source.bootstrapState(),
                source.drainedDirtyTiles()
        );
    }

    public long expectedLastFlushedEventSeq() {
        return expectedLastFlushedEventSeq;
    }

    public long flushTargetEventSeq() {
        return flushTargetEventSeq;
    }

    public DbBootstrapState bootstrapState() {
        return bootstrapState;
    }

    public List<DirtyTile> drainedDirtyTiles() {
        return drainedDirtyTiles;
    }

    private static DbBootstrapState requireUsableState(DbBootstrapState state) {
        DbBootstrapState current = Objects.requireNonNull(state, "bootstrapState must not be null");
        if (current == DbBootstrapState.INCONSISTENT) {
            throw new IllegalArgumentException("INCONSISTENT state cannot be pending.");
        }
        return current;
    }
}
