package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.domain.TileState;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/*
 * DB persistence 없이 WAL source of truth와 memory authoritative state를 immutable FlushPlan으로 capture
 * checkpoint/metadata 조회는 boundary 밖, runtime WAL scan부터 plan 생성까지는 write와 같은 coordinator 안에서 수행
 */
@Service
@Profile("!stub")
public class FlushPlanCaptureService {

    private static final CanonicalZ0TileKeys CANONICAL_Z0_TILE_KEYS = new CanonicalZ0TileKeys();
    private static final Comparator<TileKey> TILE_KEY_ORDER = Comparator
            .comparingInt(TileKey::z)
            .thenComparingInt(TileKey::ty)
            .thenComparingInt(TileKey::tx);

    private final ServiceReadiness serviceReadiness;
    private final CheckpointReader checkpointReader;
    private final TileMetadataReader tileMetadataReader;
    private final DbBootstrapClassifier dbBootstrapClassifier;
    private final FlushBoundaryCoordinator flushBoundaryCoordinator;
    private final WalReplaySource walReplaySource;
    private final DirtyTileTracker dirtyTileTracker;
    private final InMemoryTileBoard inMemoryTileBoard;

    public FlushPlanCaptureService(
            ServiceReadiness serviceReadiness,
            CheckpointReader checkpointReader,
            TileMetadataReader tileMetadataReader,
            DbBootstrapClassifier dbBootstrapClassifier,
            FlushBoundaryCoordinator flushBoundaryCoordinator,
            WalReplaySource walReplaySource,
            DirtyTileTracker dirtyTileTracker,
            InMemoryTileBoard inMemoryTileBoard
    ) {
        this.serviceReadiness = serviceReadiness;
        this.checkpointReader = checkpointReader;
        this.tileMetadataReader = tileMetadataReader;
        this.dbBootstrapClassifier = dbBootstrapClassifier;
        this.flushBoundaryCoordinator = flushBoundaryCoordinator;
        this.walReplaySource = walReplaySource;
        this.dirtyTileTracker = dirtyTileTracker;
        this.inMemoryTileBoard = inMemoryTileBoard;
    }

    /*
     * 현재 DB fencing 값과 bootstrap row shape를 먼저 판정한 뒤 짧은 write boundary에서 plan capture
     * 반환 뒤 live WAL이나 memory를 다시 읽어 target 또는 snapshot을 확장하면 안 됨
     */
    public FlushPlan capturePlan() {
        serviceReadiness.requireReady();

        CheckpointSnapshot checkpoint = Objects.requireNonNull(
                checkpointReader.readMainCheckpoint(),
                "checkpointReader returned null"
        );
        List<TileKey> tileKeys = tileMetadataReader.readAllTileKeys();
        DbBootstrapState bootstrapState = dbBootstrapClassifier.classify(
                checkpoint.lastFlushedEventSeq(),
                tileKeys
        );
        if (bootstrapState == DbBootstrapState.INCONSISTENT) {
            // partial DB를 자동 seed/삭제로 숨기지 않고 WAL·dirty 접근 전에 실패
            throw new IllegalStateException("DB checkpoint and tile metadata are inconsistent.");
        }

        long expectedLastFlushedEventSeq = checkpoint.lastFlushedEventSeq();
        FlushPlan plan = flushBoundaryCoordinator.coordinate(
                () -> captureWithinBoundary(expectedLastFlushedEventSeq, bootstrapState)
        );
        return plan;
    }

    private FlushPlan captureWithinBoundary(
            long expectedLastFlushedEventSeq,
            DbBootstrapState bootstrapState
    ) {
        // coordinator 대기 중 fatal 전환된 경우 WAL scan과 dirty drain 전 차단
        serviceReadiness.requireReady();

        WalReplayBatch batch = Objects.requireNonNull(
                walReplaySource.readAfter(expectedLastFlushedEventSeq),
                "walReplaySource returned null"
        );
        List<WalRecord> walRecords = validateWalBatch(expectedLastFlushedEventSeq, batch);
        long flushTargetEventSeq = batch.walLastEventSeq();

        if (walRecords.isEmpty()) {
            // no-op은 dirty 상태가 아니라 checkpoint 이후 실제 WAL record 부재로만 판정
            return FlushPlan.noOp(expectedLastFlushedEventSeq, bootstrapState);
        }

        Set<TileKey> walAffectedKeys = calculateWalAffectedKeys(walRecords);
        List<DirtyTile> drainedDirtyTiles = Objects.requireNonNull(
                dirtyTileTracker.drainDirtyTiles(),
                "dirtyTileTracker returned null"
        );

        try {
            List<TileKey> targetKeys = determineTargetKeys(bootstrapState, walAffectedKeys, drainedDirtyTiles);
            Map<TileKey, FlushTileSnapshot> snapshotsByKey = captureSnapshots(targetKeys);
            validateDirtyInvariants(drainedDirtyTiles, flushTargetEventSeq, snapshotsByKey);
            return createNonNoOpPlan(
                    expectedLastFlushedEventSeq,
                    flushTargetEventSeq,
                    walRecords,
                    new ArrayList<>(snapshotsByKey.values()),
                    drainedDirtyTiles,
                    bootstrapState
            );
        } catch (RuntimeException | Error captureFailure) {
            restoreDrainedDirtyTiles(drainedDirtyTiles, captureFailure);
            throw captureFailure;
        }
    }

    private List<WalRecord> validateWalBatch(long expectedLastFlushedEventSeq, WalReplayBatch batch) {
        long walLastEventSeq = batch.walLastEventSeq();
        if (walLastEventSeq < 0) {
            throw new IllegalStateException("WAL tail must not be negative. value=" + walLastEventSeq);
        }
        if (walLastEventSeq < expectedLastFlushedEventSeq) {
            throw new IllegalStateException("WAL tail is behind the expected checkpoint.");
        }

        List<WalRecord> records = Objects.requireNonNull(batch.records(), "WAL records must not be null");
        long previousEventSeq = expectedLastFlushedEventSeq;
        for (WalRecord record : records) {
            WalRecord current = Objects.requireNonNull(record, "WAL records must not contain null");
            if (current.eventSeq() <= expectedLastFlushedEventSeq
                    || current.eventSeq() <= previousEventSeq) {
                // createdAt 비교 없이 eventSeq 중복·역순만 input contract 위반으로 거부
                throw new IllegalStateException("WAL eventSeq must be strictly increasing after checkpoint.");
            }
            previousEventSeq = current.eventSeq();
        }

        if (records.isEmpty()) {
            if (walLastEventSeq != expectedLastFlushedEventSeq) {
                throw new IllegalStateException("Empty WAL batch tail must equal the expected checkpoint.");
            }
        } else if (previousEventSeq != walLastEventSeq) {
            throw new IllegalStateException("Last WAL record eventSeq must equal the WAL tail.");
        }
        return List.copyOf(records);
    }

    private Set<TileKey> calculateWalAffectedKeys(List<WalRecord> walRecords) {
        Set<TileKey> affectedKeys = new HashSet<>();
        for (WalRecord record : walRecords) {
            TileKey key = new TileKey(record.z(), record.tx(), record.ty());
            requireCanonicalKey(key, "WAL record");
            affectedKeys.add(key);
        }
        return affectedKeys;
    }

    private List<TileKey> determineTargetKeys(
            DbBootstrapState bootstrapState,
            Set<TileKey> walAffectedKeys,
            List<DirtyTile> drainedDirtyTiles
    ) {
        validateDirtyKeys(drainedDirtyTiles);

        Collection<TileKey> targets;
        if (bootstrapState == DbBootstrapState.BOOTSTRAP_PENDING) {
            targets = CANONICAL_Z0_TILE_KEYS.orderedKeys();
        } else {
            Set<TileKey> incrementalTargets = new HashSet<>(walAffectedKeys);
            drainedDirtyTiles.stream().map(DirtyTile::tileKey).forEach(incrementalTargets::add);
            targets = incrementalTargets;
        }

        return targets.stream().sorted(TILE_KEY_ORDER).toList();
    }

    private void validateDirtyKeys(List<DirtyTile> drainedDirtyTiles) {
        for (DirtyTile dirtyTile : drainedDirtyTiles) {
            DirtyTile current = Objects.requireNonNull(
                    dirtyTile,
                    "drainedDirtyTiles must not contain null"
            );
            requireCanonicalKey(current.tileKey(), "Dirty tile");
        }
    }

    private Map<TileKey, FlushTileSnapshot> captureSnapshots(List<TileKey> targetKeys) {
        Map<TileKey, FlushTileSnapshot> snapshotsByKey = new LinkedHashMap<>();
        for (TileKey key : targetKeys) {
            TileState tileState = Objects.requireNonNull(
                    inMemoryTileBoard.getRequired(key),
                    "inMemoryTileBoard returned null"
            );
            FlushTileSnapshot snapshot = new FlushTileSnapshot(
                    key,
                    tileState.pixels(),
                    tileState.tileVersion()
            );
            snapshotsByKey.put(key, snapshot);
        }
        return snapshotsByKey;
    }

    private void validateDirtyInvariants(
            List<DirtyTile> drainedDirtyTiles,
            long flushTargetEventSeq,
            Map<TileKey, FlushTileSnapshot> snapshotsByKey
    ) {
        for (DirtyTile dirtyTile : drainedDirtyTiles) {
            if (dirtyTile.latestEventSeq() > flushTargetEventSeq) {
                throw new IllegalStateException("Dirty eventSeq exceeds the captured WAL target.");
            }
            FlushTileSnapshot snapshot = Objects.requireNonNull(
                    snapshotsByKey.get(dirtyTile.tileKey()),
                    "Captured snapshot is missing for a drained dirty tile."
            );
            if (dirtyTile.latestTileVersion() > snapshot.tileVersion()) {
                // live state 재조회 대신 같은 boundary에서 이미 capture한 snapshot version만 비교
                throw new IllegalStateException("Dirty tileVersion exceeds the captured snapshot version.");
            }
        }
    }

    private void restoreDrainedDirtyTiles(
            List<DirtyTile> drainedDirtyTiles,
            Throwable captureFailure
    ) {
        try {
            // 실제 drain 목록만 복구하며 WAL affected/full bootstrap key synthetic 등록 금지
            dirtyTileTracker.restoreDirtyTiles(drainedDirtyTiles);
        } catch (RuntimeException | Error restoreFailure) {
            if (restoreFailure != captureFailure) {
                captureFailure.addSuppressed(restoreFailure);
            }
        }
    }

    private void requireCanonicalKey(TileKey key, String source) {
        if (!CANONICAL_Z0_TILE_KEYS.contains(key)) {
            throw new IllegalStateException(source + " contains a non-canonical z=0 tile key. key=" + key);
        }
    }

    // plan factory 실패 뒤 dirty restore 계약을 독립적으로 검증하기 위한 package-private boundary
    FlushPlan createNonNoOpPlan(
            long expectedLastFlushedEventSeq,
            long flushTargetEventSeq,
            List<WalRecord> walRecords,
            List<FlushTileSnapshot> tileSnapshots,
            List<DirtyTile> drainedDirtyTiles,
            DbBootstrapState bootstrapState
    ) {
        return FlushPlan.nonNoOp(
                expectedLastFlushedEventSeq,
                flushTargetEventSeq,
                walRecords,
                tileSnapshots,
                drainedDirtyTiles,
                bootstrapState
        );
    }
}
