package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.flush.application.DbBootstrapState;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileStateSnapshot;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/*
 * immutable DB view 검증 뒤에만 WAL과 authoritative memory를 연결하는 startup recovery 오케스트레이터
 * DB transaction 종료, fail-fast 검증, memory load/replay, eventSeq seed, readiness 순서 고정
 */
@Service
public class StartupRecoveryService {

    private final StartupRecoveryDbViewCaptureService dbViewCaptureService;
    private final DbBootstrapClassifier dbBootstrapClassifier;
    private final CanonicalZ0TileKeys canonicalZ0TileKeys;
    private final WalReplaySource walReplaySource;
    private final InMemoryTileBoard inMemoryTileBoard;
    private final EventSeqManager eventSeqManager;
    private final ServiceReadiness serviceReadiness;

    public StartupRecoveryService(
            StartupRecoveryDbViewCaptureService dbViewCaptureService,
            DbBootstrapClassifier dbBootstrapClassifier,
            CanonicalZ0TileKeys canonicalZ0TileKeys,
            WalReplaySource walReplaySource,
            InMemoryTileBoard inMemoryTileBoard,
            EventSeqManager eventSeqManager,
            ServiceReadiness serviceReadiness
    ) {
        this.dbViewCaptureService = dbViewCaptureService;
        this.dbBootstrapClassifier = dbBootstrapClassifier;
        this.canonicalZ0TileKeys = canonicalZ0TileKeys;
        this.walReplaySource = walReplaySource;
        this.inMemoryTileBoard = inMemoryTileBoard;
        this.eventSeqManager = eventSeqManager;
        this.serviceReadiness = serviceReadiness;
    }

    /* 모든 입력·WAL invariant와 memory 복구 성공 뒤에만 ready 전환 */
    public void recover() {
        serviceReadiness.markNotReady();

        StartupRecoveryDbView dbView = dbViewCaptureService.capture();
        CheckpointSnapshot checkpointSnapshot = dbView.checkpoint();
        TileLoadResult tileLoadResult = dbView.tileLoadResult();
        long checkpoint = checkpointSnapshot.lastFlushedEventSeq();

        DbBootstrapState bootstrapState = Objects.requireNonNull(
                dbBootstrapClassifier.classify(checkpoint, tileLoadResult.databaseTileKeys()),
                "bootstrapState must not be null."
        );
        validateDbView(checkpoint, bootstrapState, tileLoadResult);

        WalReplayBatch replayBatch = Objects.requireNonNull(
                walReplaySource.readAfter(checkpoint),
                "walReplayBatch must not be null."
        );
        validateWalReplayBatch(checkpoint, replayBatch);

        if (bootstrapState == DbBootstrapState.BOOTSTRAP_PENDING) {
            inMemoryTileBoard.initializeAllWhite();
        } else {
            inMemoryTileBoard.loadAll(tileLoadResult.snapshots());
        }

        for (WalRecord record : replayBatch.records()) {
            inMemoryTileBoard.applyReplayRecord(record.x(), record.y(), record.color());
        }

        eventSeqManager.initializeLastIssued(replayBatch.walLastEventSeq());
        serviceReadiness.markReady();
    }

    private void validateDbView(
            long checkpoint,
            DbBootstrapState bootstrapState,
            TileLoadResult tileLoadResult
    ) {
        if (bootstrapState == DbBootstrapState.INCONSISTENT) {
            // partial/invalid DB shape를 white tile이나 checkpoint 0으로 보정하지 않는 fail-fast 경계
            throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "classifier-inconsistent");
        }

        if (bootstrapState == DbBootstrapState.BOOTSTRAP_PENDING) {
            if (!tileLoadResult.snapshots().isEmpty()) {
                // empty key와 snapshot 불일치는 loader 결과 손상 가능성이 있어 WAL read 전 실패
                throw dbViewViolation(
                        checkpoint,
                        bootstrapState,
                        tileLoadResult,
                        "bootstrap-pending-with-snapshots"
                );
            }
            return;
        }

        if (!canonicalZ0TileKeys.exactlyMatches(tileLoadResult.databaseTileKeys())) {
            // classifier 결과와 전달 view의 재검증 불일치는 memory 변경 전에 차단
            throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "database-keys-not-canonical");
        }

        List<TileKey> snapshotKeys = new ArrayList<>(tileLoadResult.snapshots().size());
        for (TileStateSnapshot snapshot : tileLoadResult.snapshots()) {
            if (!canonicalZ0TileKeys.contains(snapshot.key())) {
                throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "snapshot-key-out-of-range");
            }
            if (snapshot.pixelCount() != BoardConstants.TILE_PIXEL_COUNT) {
                throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "snapshot-pixel-length");
            }
            if (snapshot.tileVersion() < 0) {
                throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "snapshot-version-negative");
            }
            snapshotKeys.add(snapshot.key());
        }

        if (!canonicalZ0TileKeys.exactlyMatches(snapshotKeys)) {
            // count만 맞는 duplicate·누락·추가 row를 loadAll에 전달하기 전 차단
            throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "snapshot-keys-not-canonical");
        }
    }

    private void validateWalReplayBatch(long checkpoint, WalReplayBatch replayBatch) {
        long walLastEventSeq = replayBatch.walLastEventSeq();
        List<WalRecord> records = replayBatch.records();

        if (checkpoint < 0) {
            throw walViolation(checkpoint, walLastEventSeq, records.size(), "checkpoint-negative");
        }
        if (walLastEventSeq < checkpoint) {
            // checkpoint보다 뒤처진 WAL tail을 Math.max seed로 숨기면 WAL 유실 상태가 정상 기동됨
            throw walViolation(checkpoint, walLastEventSeq, records.size(), "tail-before-checkpoint");
        }
        if (records.isEmpty()) {
            if (walLastEventSeq != checkpoint) {
                throw walViolation(checkpoint, walLastEventSeq, 0, "empty-records-tail-mismatch");
            }
            return;
        }

        long previousEventSeq = checkpoint;
        for (WalRecord record : records) {
            long eventSeq = record.eventSeq();
            if (eventSeq <= checkpoint) {
                throw walViolation(checkpoint, walLastEventSeq, records.size(), "record-at-or-before-checkpoint");
            }
            if (eventSeq <= previousEventSeq) {
                throw walViolation(checkpoint, walLastEventSeq, records.size(), "records-not-strictly-increasing");
            }
            previousEventSeq = eventSeq;
        }

        if (previousEventSeq != walLastEventSeq) {
            throw walViolation(checkpoint, walLastEventSeq, records.size(), "last-record-tail-mismatch");
        }
    }

    private IllegalStateException dbViewViolation(
            long checkpoint,
            DbBootstrapState bootstrapState,
            TileLoadResult tileLoadResult,
            String violation
    ) {
        return new IllegalStateException(
                "Startup recovery DB view is inconsistent. checkpoint=" + checkpoint
                        + ", databaseKeyCount=" + tileLoadResult.databaseTileKeys().size()
                        + ", snapshotCount=" + tileLoadResult.snapshots().size()
                        + ", classifierState=" + bootstrapState
                        + ", violation=" + violation
        );
    }

    private IllegalStateException walViolation(
            long checkpoint,
            long walLastEventSeq,
            int recordCount,
            String violation
    ) {
        return new IllegalStateException(
                "Startup recovery WAL batch is inconsistent. checkpoint=" + checkpoint
                        + ", walLastEventSeq=" + walLastEventSeq
                        + ", recordCount=" + recordCount
                        + ", violation=" + violation
        );
    }
}
