package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.domain.WalRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/*
 * coordinator 해제 뒤 live WAL/memory를 다시 읽지 않고 DB transaction에 전달할 immutable flush plan
 * expected checkpoint는 fencing 기준, target은 capture boundary의 durable WAL tail로 서로 다른 값
 */
public final class FlushPlan {

    private static final CanonicalZ0TileKeys CANONICAL_Z0_TILE_KEYS = new CanonicalZ0TileKeys();
    private static final Comparator<FlushTileSnapshot> SNAPSHOT_ORDER = Comparator
            .comparingInt((FlushTileSnapshot snapshot) -> snapshot.tileKey().z())
            .thenComparingInt(snapshot -> snapshot.tileKey().ty())
            .thenComparingInt(snapshot -> snapshot.tileKey().tx());

    private final long expectedLastFlushedEventSeq;
    private final long flushTargetEventSeq;
    private final List<WalRecord> walRecords;
    private final List<FlushTileSnapshot> tileSnapshots;
    private final List<DirtyTile> drainedDirtyTiles;
    private final DbBootstrapState bootstrapState;
    private final boolean noOp;

    private FlushPlan(
            long expectedLastFlushedEventSeq,
            long flushTargetEventSeq,
            List<WalRecord> walRecords,
            List<FlushTileSnapshot> tileSnapshots,
            List<DirtyTile> drainedDirtyTiles,
            DbBootstrapState bootstrapState,
            boolean noOp
    ) {
        if (expectedLastFlushedEventSeq < 0) {
            throw new IllegalArgumentException(
                    "expectedLastFlushedEventSeq must not be negative. value=" + expectedLastFlushedEventSeq
            );
        }
        this.bootstrapState = requireUsableBootstrapState(bootstrapState);
        this.expectedLastFlushedEventSeq = expectedLastFlushedEventSeq;
        this.flushTargetEventSeq = flushTargetEventSeq;
        this.walRecords = List.copyOf(walRecords);
        this.tileSnapshots = immutableSortedSnapshots(tileSnapshots);
        this.drainedDirtyTiles = List.copyOf(drainedDirtyTiles);
        this.noOp = noOp;

        if (noOp) {
            validateNoOp();
        } else {
            validateNonNoOp();
        }
    }

    /* WAL record가 없는 plan만 생성 가능한 no-op factory */
    public static FlushPlan noOp(long expectedLastFlushedEventSeq, DbBootstrapState bootstrapState) {
        return new FlushPlan(
                expectedLastFlushedEventSeq,
                expectedLastFlushedEventSeq,
                List.of(),
                List.of(),
                List.of(),
                bootstrapState,
                true
        );
    }

    /* WAL과 captured snapshot을 persistence에 넘기는 non-no-op factory */
    public static FlushPlan nonNoOp(
            long expectedLastFlushedEventSeq,
            long flushTargetEventSeq,
            List<WalRecord> walRecords,
            List<FlushTileSnapshot> tileSnapshots,
            List<DirtyTile> drainedDirtyTiles,
            DbBootstrapState bootstrapState
    ) {
        return new FlushPlan(
                expectedLastFlushedEventSeq,
                flushTargetEventSeq,
                Objects.requireNonNull(walRecords, "walRecords must not be null"),
                Objects.requireNonNull(tileSnapshots, "tileSnapshots must not be null"),
                Objects.requireNonNull(drainedDirtyTiles, "drainedDirtyTiles must not be null"),
                bootstrapState,
                false
        );
    }

    public long expectedLastFlushedEventSeq() {
        return expectedLastFlushedEventSeq;
    }

    public long flushTargetEventSeq() {
        return flushTargetEventSeq;
    }

    public List<WalRecord> walRecords() {
        return walRecords;
    }

    public List<FlushTileSnapshot> tileSnapshots() {
        return tileSnapshots;
    }

    public List<DirtyTile> drainedDirtyTiles() {
        return drainedDirtyTiles;
    }

    public DbBootstrapState bootstrapState() {
        return bootstrapState;
    }

    public boolean noOp() {
        return noOp;
    }

    private void validateNoOp() {
        if (flushTargetEventSeq != expectedLastFlushedEventSeq
                || !walRecords.isEmpty()
                || !tileSnapshots.isEmpty()
                || !drainedDirtyTiles.isEmpty()) {
            throw new IllegalArgumentException("No-op plan must not contain persistence payload.");
        }
    }

    private void validateNonNoOp() {
        if (flushTargetEventSeq <= expectedLastFlushedEventSeq) {
            throw new IllegalArgumentException("flushTargetEventSeq must be greater than expected checkpoint.");
        }
        if (walRecords.isEmpty()) {
            throw new IllegalArgumentException("Non-no-op plan requires WAL records.");
        }
        if (tileSnapshots.isEmpty()) {
            throw new IllegalArgumentException("Non-no-op plan requires tile snapshots.");
        }

        Set<TileKey> walAffectedKeys = validateWalRecords();
        Set<TileKey> snapshotKeys = validateSnapshotKeys();
        Set<TileKey> dirtyKeys = validateDirtyTiles();

        if (bootstrapState == DbBootstrapState.BOOTSTRAP_PENDING) {
            if (!CANONICAL_Z0_TILE_KEYS.exactlyMatches(snapshotKeys)) {
                // 최초 persistence가 partial DB shape를 만들지 못하도록 전체 1,024개 강제
                throw new IllegalArgumentException("Bootstrap plan requires all canonical z=0 snapshots.");
            }
            return;
        }

        Set<TileKey> expectedSnapshotKeys = new HashSet<>(walAffectedKeys);
        expectedSnapshotKeys.addAll(dirtyKeys);
        if (!snapshotKeys.equals(expectedSnapshotKeys)) {
            // 필수 WAL affected 누락과 capture 이후 임의 snapshot 확장을 모두 금지하는 exact target 계약
            throw new IllegalArgumentException(
                    "Initialized plan snapshots must exactly match WAL affected and drained dirty keys."
            );
        }
    }

    private Set<TileKey> validateWalRecords() {
        Set<TileKey> affectedKeys = new HashSet<>();
        long previousEventSeq = expectedLastFlushedEventSeq;
        for (WalRecord record : walRecords) {
            WalRecord current = Objects.requireNonNull(record, "walRecords must not contain null");
            if (current.eventSeq() <= expectedLastFlushedEventSeq
                    || current.eventSeq() <= previousEventSeq) {
                // createdAt은 순서 판정에 사용하지 않고 eventSeq 중복·역순만 거부
                throw new IllegalArgumentException("WAL eventSeq must be strictly increasing after checkpoint.");
            }
            TileKey affectedKey = new TileKey(current.z(), current.tx(), current.ty());
            if (!CANONICAL_Z0_TILE_KEYS.contains(affectedKey)) {
                // persistence/recovery가 지원하지 않는 key를 checkpoint 완료 범위에 포함할 수 없음
                throw new IllegalArgumentException("WAL record contains a non-canonical tile key.");
            }
            affectedKeys.add(affectedKey);
            previousEventSeq = current.eventSeq();
        }
        if (previousEventSeq != flushTargetEventSeq) {
            // 마지막 실제 WAL record와 target이 다르면 checkpoint가 plan payload와 다른 경계를 가리킴
            throw new IllegalArgumentException("Last WAL record eventSeq must match flush target.");
        }
        return affectedKeys;
    }

    private Set<TileKey> validateSnapshotKeys() {
        Set<TileKey> snapshotKeys = new HashSet<>();
        for (FlushTileSnapshot snapshot : tileSnapshots) {
            FlushTileSnapshot current = Objects.requireNonNull(
                    snapshot,
                    "tileSnapshots must not contain null"
            );
            if (!snapshotKeys.add(current.tileKey())) {
                throw new IllegalArgumentException("tileSnapshots must not contain duplicate keys.");
            }
        }
        return snapshotKeys;
    }

    private Set<TileKey> validateDirtyTiles() {
        Set<TileKey> dirtyKeys = new HashSet<>();
        for (DirtyTile dirtyTile : drainedDirtyTiles) {
            DirtyTile current = Objects.requireNonNull(
                    dirtyTile,
                    "drainedDirtyTiles must not contain null"
            );
            if (!CANONICAL_Z0_TILE_KEYS.contains(current.tileKey())) {
                throw new IllegalArgumentException("Dirty tile contains a non-canonical tile key.");
            }
            dirtyKeys.add(current.tileKey());
        }
        return dirtyKeys;
    }

    private static List<FlushTileSnapshot> immutableSortedSnapshots(List<FlushTileSnapshot> snapshots) {
        List<FlushTileSnapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort(SNAPSHOT_ORDER);
        return List.copyOf(sorted);
    }

    private static DbBootstrapState requireUsableBootstrapState(DbBootstrapState bootstrapState) {
        DbBootstrapState state = Objects.requireNonNull(bootstrapState, "bootstrapState must not be null");
        if (state == DbBootstrapState.INCONSISTENT) {
            throw new IllegalArgumentException("INCONSISTENT bootstrap state cannot be captured in a plan.");
        }
        return state;
    }
}
