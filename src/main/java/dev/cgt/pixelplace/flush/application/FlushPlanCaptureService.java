package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import static dev.cgt.pixelplace.measurement.PixelMeasurement.Operation.*;
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
    private final PixelMeasurement measurement;
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
            InMemoryTileBoard inMemoryTileBoard,
            PixelMeasurement measurement
    ) {
        this.serviceReadiness = serviceReadiness;
        this.checkpointReader = checkpointReader;
        this.tileMetadataReader = tileMetadataReader;
        this.dbBootstrapClassifier = dbBootstrapClassifier;
        this.flushBoundaryCoordinator = flushBoundaryCoordinator;
        this.walReplaySource = walReplaySource;
        this.dirtyTileTracker = dirtyTileTracker;
        this.inMemoryTileBoard = inMemoryTileBoard;
        this.measurement = measurement;
    }

    /*
     * 현재 DB fencing 값과 bootstrap row shape를 먼저 판정한 뒤 짧은 write boundary에서 plan capture
     * 반환 뒤 live WAL이나 memory를 다시 읽어 target 또는 snapshot을 확장하면 안 됨
     */
    public FlushPlan capturePlan() {
        serviceReadiness.requireReady();

        CheckpointSnapshot checkpoint = Objects.requireNonNull(
                measurement.observe(checkpoint_read, checkpointReader::readMainCheckpoint),
                "checkpointReader returned null"
        );
        List<TileKey> tileKeys = measurement.observe(metadata_read, tileMetadataReader::readAllTileKeys);
        DbBootstrapState bootstrapState = dbBootstrapClassifier.classify(
                checkpoint.lastFlushedEventSeq(),
                tileKeys
        );
        if (bootstrapState == DbBootstrapState.INCONSISTENT) {
            // partial DB를 자동 seed/삭제로 숨기지 않고 WAL·dirty 접근 전에 실패
            throw new IllegalStateException("DB checkpoint and tile metadata are inconsistent.");
        }

        long expectedLastFlushedEventSeq = checkpoint.lastFlushedEventSeq();
        FlushPlan plan = flushBoundaryCoordinator.capture(
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
                measurement.observe(capture_scan, () -> walReplaySource.readAfter(expectedLastFlushedEventSeq)),
                "walReplaySource returned null"
        );
        List<WalRecord> walRecords = validateWalBatch(expectedLastFlushedEventSeq, batch);
        long flushTargetEventSeq = batch.walLastEventSeq();
        measurement.captured(expectedLastFlushedEventSeq, flushTargetEventSeq, walRecords.size());

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
            Map<TileKey, FlushTileSnapshot> snapshotsByKey = measurement.observe(snapshot, () -> captureSnapshots(targetKeys));
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
            Throwable primaryFailure = restoreDrainedDirtyTiles(drainedDirtyTiles, captureFailure);
            if (primaryFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) primaryFailure;
        }
    }

    private List<WalRecord> validateWalBatch(long expectedLastFlushedEventSeq, WalReplayBatch batch) {
        long walLastEventSeq = batch.walLastEventSeq();
        if (walLastEventSeq < 0) {
            // durable WAL tail은 승인 eventSeq 또는 empty 0만 허용
            throw new IllegalStateException("WAL tail must not be negative. value=" + walLastEventSeq);
        }
        if (walLastEventSeq < expectedLastFlushedEventSeq) {
            // DB checkpoint가 WAL 원본보다 앞선 상태를 정상 no-op으로 숨기면 recovery record 유실 가능
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
                // tail까지 실제 record가 없으면 checkpoint 전진 payload를 구성할 수 없음
                throw new IllegalStateException("Empty WAL batch tail must equal the expected checkpoint.");
            }
        } else if (previousEventSeq != walLastEventSeq) {
            // 마지막 replay record와 durable tail 불일치는 plan target과 event payload 분리 위험
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
                // captured WAL target 이후 live write가 같은 plan snapshot에 섞였음을 뜻하는 boundary 위반
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

    private Throwable restoreDrainedDirtyTiles(
            List<DirtyTile> drainedDirtyTiles,
            Throwable captureFailure
    ) {
        try {
            // 실제 drain 목록만 복구하며 WAL affected/full bootstrap key synthetic 등록 금지
            dirtyTileTracker.restoreDirtyTiles(drainedDirtyTiles);
            return captureFailure;
        } catch (RuntimeException | Error restoreFailure) {
            return selectPrimaryFailure(captureFailure, restoreFailure);
        }
    }

    private Throwable selectPrimaryFailure(Throwable firstFailure, Throwable laterFailure) {
        if (firstFailure instanceof Error) {
            addSuppressed(firstFailure, laterFailure);
            return firstFailure;
        }
        if (laterFailure instanceof Error) {
            // RuntimeException 뒤 복구 Error를 숨기지 않고 최초 Error를 최종 주 예외로 승격
            addSuppressed(laterFailure, firstFailure);
            return laterFailure;
        }
        addSuppressed(firstFailure, laterFailure);
        return firstFailure;
    }

    private void addSuppressed(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    private void requireCanonicalKey(TileKey key, String source) {
        if (!CANONICAL_Z0_TILE_KEYS.contains(key)) {
            // canonical memory board 밖 key는 snapshot capture와 checkpoint 완료 의미를 구성할 수 없음
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
