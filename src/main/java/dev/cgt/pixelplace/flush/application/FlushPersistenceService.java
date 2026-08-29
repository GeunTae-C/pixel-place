package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import dev.cgt.pixelplace.pixel.application.PixelEventWriter;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.application.TileSnapshotWriter;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/*
 * immutable FlushPlan만 입력으로 events, captured tiles, checkpoint를 transaction body에 반영하는 service
 * checkpoint advance를 마지막 application-level DB write로 고정하여 완전 flush 경계만 노출
 */
@Service
@Profile("!stub")
public class FlushPersistenceService {

    private final CheckpointFence checkpointFence;
    private final TileMetadataReader tileMetadataReader;
    private final DbBootstrapClassifier dbBootstrapClassifier;
    private final PixelEventWriter pixelEventWriter;
    private final TileSnapshotWriter tileSnapshotWriter;

    public FlushPersistenceService(
            CheckpointFence checkpointFence,
            TileMetadataReader tileMetadataReader,
            DbBootstrapClassifier dbBootstrapClassifier,
            PixelEventWriter pixelEventWriter,
            TileSnapshotWriter tileSnapshotWriter
    ) {
        this.checkpointFence = checkpointFence;
        this.tileMetadataReader = tileMetadataReader;
        this.dbBootstrapClassifier = dbBootstrapClassifier;
        this.pixelEventWriter = pixelEventWriter;
        this.tileSnapshotWriter = tileSnapshotWriter;
    }

    /*
     * executor가 연 새 physical transaction 안에서 plan expected와 DB mode를 재검증한 뒤 순차 반영
     * capture 이후 readiness/fatal 변화로 이미 안전한 plan을 취소하거나 live state로 확장하지 않음
     */
    public void persist(FlushPlan plan) {
        FlushPlan currentPlan = Objects.requireNonNull(plan, "plan must not be null");
        if (currentPlan.noOp()) {
            throw new IllegalArgumentException("No-op plan must not enter persistence.");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 우연한 non-transactional 호출에서 부분 DB 반영이 생기기 전 fail-fast
            throw new IllegalStateException("An actual transaction is required for flush persistence.");
        }
        if (currentPlan.flushTargetEventSeq() <= currentPlan.expectedLastFlushedEventSeq()) {
            throw new IllegalArgumentException("Flush target must be greater than expected checkpoint.");
        }

        long dbCheckpoint = checkpointFence.lockMainCheckpoint();
        if (dbCheckpoint != currentPlan.expectedLastFlushedEventSeq()) {
            // stale plan은 metadata와 writer에 진입하기 전에 checkpoint fence에서 차단
            throw new IllegalStateException(
                    "DB checkpoint does not match plan expected. expected="
                            + currentPlan.expectedLastFlushedEventSeq()
                            + ", actual=" + dbCheckpoint
            );
        }

        List<TileKey> tileKeys = tileMetadataReader.readAllTileKeys();
        DbBootstrapState dbState = dbBootstrapClassifier.classify(dbCheckpoint, tileKeys);
        if (dbState == DbBootstrapState.INCONSISTENT) {
            // partial DB를 transaction 안에서 자동 보정하지 않고 모든 write 전 실패
            throw new IllegalStateException("DB checkpoint and tile metadata are inconsistent.");
        }
        if (dbState != currentPlan.bootstrapState()) {
            // capture한 mode와 다른 DB shape에 같은 plan을 적용하면 최초 full/incremental target 의미가 뒤바뀜
            throw new IllegalStateException(
                    "DB bootstrap state changed after plan capture. plan="
                            + currentPlan.bootstrapState() + ", actual=" + dbState
            );
        }

        pixelEventWriter.appendAll(currentPlan.walRecords());
        tileSnapshotWriter.writeAll(currentPlan.tileSnapshots());
        // checkpoint 이후 추가 repository write 금지, 앞선 두 flush 완료 지점만 target으로 공개
        checkpointFence.advanceMainCheckpoint(
                currentPlan.expectedLastFlushedEventSeq(),
                currentPlan.flushTargetEventSeq()
        );
    }
}
