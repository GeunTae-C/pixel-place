package dev.cgt.pixelplace.checkpoint.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/*
 * main checkpoint row lock과 expected compare-and-set을 제공하는 default profile adapter
 * single-flight를 대체하지 않고 stale plan과 아직 resolve 중인 transaction의 DB 경계만 직렬화
 */
@Component
@Profile("!stub")
public class JpaCheckpointFence implements CheckpointFence {

    private final WalCheckpointJpaRepository walCheckpointJpaRepository;

    public JpaCheckpointFence(WalCheckpointJpaRepository walCheckpointJpaRepository) {
        this.walCheckpointJpaRepository = walCheckpointJpaRepository;
    }

    /* flush transaction과 reconciliation probe가 공유하는 main row write-lock 진입 */
    @Override
    public long lockMainCheckpoint() {
        WalCheckpointEntity checkpoint = walCheckpointJpaRepository.lockMainCheckpoint()
                .orElseThrow(() -> new IllegalStateException("Main WAL checkpoint row is missing."));
        return checkpoint.getLastFlushedEventSeq();
    }

    /* 선행 event/tile flush 뒤 expected가 그대로인 경우만 checkpoint target 공개 */
    @Override
    public void advanceMainCheckpoint(long expectedLastFlushedEventSeq, long flushTargetEventSeq) {
        if (expectedLastFlushedEventSeq < 0) {
            // unsigned DB checkpoint 계약 밖 expected로 conditional update를 시도할 수 없음
            throw new IllegalArgumentException("expected checkpoint must not be negative");
        }
        if (flushTargetEventSeq <= expectedLastFlushedEventSeq) {
            // 동일 값 update와 checkpoint 후퇴를 성공으로 숨기면 flush 완료 경계가 깨짐
            throw new IllegalArgumentException("flush target must be greater than expected checkpoint");
        }

        int affectedRows = walCheckpointJpaRepository.advanceMainCheckpoint(
                expectedLastFlushedEventSeq,
                flushTargetEventSeq
        );
        if (affectedRows != 1) {
            // row 누락·stale expected·비정상 다중 영향 모두 transaction rollback 대상
            throw new IllegalStateException(
                    "Checkpoint conditional advance must affect exactly one row. affectedRows=" + affectedRows
            );
        }
    }
}
