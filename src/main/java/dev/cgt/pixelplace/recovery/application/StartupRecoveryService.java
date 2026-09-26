package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
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
    private final PixelMeasurement measurement;
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
            ServiceReadiness serviceReadiness,
            PixelMeasurement measurement
    ) {
        this.dbViewCaptureService = dbViewCaptureService;
        this.dbBootstrapClassifier = dbBootstrapClassifier;
        this.canonicalZ0TileKeys = canonicalZ0TileKeys;
        this.walReplaySource = walReplaySource;
        this.inMemoryTileBoard = inMemoryTileBoard;
        this.eventSeqManager = eventSeqManager;
        this.serviceReadiness = serviceReadiness;
        this.measurement = measurement;
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
                measurement.observe(PixelMeasurement.Operation.recovery_scan, () -> walReplaySource.readAfter(checkpoint)),
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
                // 범위 밖 snapshot을 memory board에 넣으면 canonical ready 상태 구성 불가
                throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "snapshot-key-out-of-range");
            }
            if (snapshot.pixelCount() != BoardConstants.TILE_PIXEL_COUNT) {
                // 잘못된 BLOB shape를 load 전에 차단하여 기존 memory 상태의 부분 변경 방지
                throw dbViewViolation(checkpoint, bootstrapState, tileLoadResult, "snapshot-pixel-length");
            }
            if (snapshot.tileVersion() < 0) {
                // 음수 version은 unsigned DB 계약과 이후 증가 기준을 동시에 위반
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
            // DB unsigned checkpoint 위반을 WAL replay 기준으로 사용할 수 없음
            throw walViolation(checkpoint, walLastEventSeq, records.size(), "checkpoint-negative");
        }
        if (walLastEventSeq < checkpoint) {
            // checkpoint보다 뒤처진 WAL tail을 Math.max seed로 숨기면 WAL 유실 상태가 정상 기동됨
            throw walViolation(checkpoint, walLastEventSeq, records.size(), "tail-before-checkpoint");
        }
        if (records.isEmpty()) {
            if (walLastEventSeq != checkpoint) {
                // tail이 더 앞서는데 replay record가 없으면 memory가 승인 WAL 상태를 따라갈 수 없음
                throw walViolation(checkpoint, walLastEventSeq, 0, "empty-records-tail-mismatch");
            }
            return;
        }

        long previousEventSeq = checkpoint;
        for (WalRecord record : records) {
            long eventSeq = record.eventSeq();
            if (eventSeq <= checkpoint) {
                // loader의 readAfter 계약 위반을 중복 replay로 정상화하지 않음
                throw walViolation(checkpoint, walLastEventSeq, records.size(), "record-at-or-before-checkpoint");
            }
            if (eventSeq <= previousEventSeq) {
                // 중복·역순 WAL은 memory replay와 다음 발급 seed 순서를 확정할 수 없음
                throw walViolation(checkpoint, walLastEventSeq, records.size(), "records-not-strictly-increasing");
            }
            previousEventSeq = eventSeq;
        }

        if (previousEventSeq != walLastEventSeq) {
            // 마지막 replay record와 tail 불일치는 seed 또는 memory 반영 누락 가능성
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
