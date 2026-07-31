package dev.cgt.pixelplace.checkpoint.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 기본 profile의 실제 checkpoint JPA adapter, DB 조회와 도메인 변환 경계 담당
// stub profile과 상호 배타 활성화하여 CheckpointReader bean 유일성 보장
@Component
@Profile("!stub")
public class JpaCheckpointReader implements CheckpointReader {

    private static final String MAIN_CHECKPOINT_NAME = "main";

    private final WalCheckpointJpaRepository walCheckpointJpaRepository;

    public JpaCheckpointReader(WalCheckpointJpaRepository walCheckpointJpaRepository) {
        this.walCheckpointJpaRepository = walCheckpointJpaRepository;
    }

    // startup recovery의 기준 checkpoint 조회, 누락 시 잘못된 WAL replay 범위 방지를 위한 fail-fast
    @Override
    public CheckpointSnapshot readMainCheckpoint() {
        // "main" checkpoint row는 recovery 시작 기준점
        // 누락을 기본값으로 숨기면 WAL replay 시작 범위가 틀어질 수 있으므로 즉시 실패시킴
        WalCheckpointEntity checkpoint = walCheckpointJpaRepository.findById(MAIN_CHECKPOINT_NAME)
                .orElseThrow(() -> new IllegalStateException("Main WAL checkpoint row is missing."));

        return new CheckpointSnapshot(checkpoint.getLastFlushedEventSeq());
    }
}
