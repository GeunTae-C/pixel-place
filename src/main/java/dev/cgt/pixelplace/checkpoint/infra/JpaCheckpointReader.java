package dev.cgt.pixelplace.checkpoint.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

// CheckpointReader 포트의 실제 JPA adapter다.
// recovery 서비스가 DB read side를 JPA repository에 직접 묶지 않도록, checkpoint 조회와 도메인 변환 경계를 이 구현체가 맡는다.
@Primary
@Component
public class JpaCheckpointReader implements CheckpointReader {

    private static final String MAIN_CHECKPOINT_NAME = "main";

    private final WalCheckpointJpaRepository walCheckpointJpaRepository;

    public JpaCheckpointReader(WalCheckpointJpaRepository walCheckpointJpaRepository) {
        this.walCheckpointJpaRepository = walCheckpointJpaRepository;
    }

    // @Primary는 기존 stub 구현과 빈 충돌 없이 실제 JPA 구현을 recovery에 우선 연결하기 위한 선택이다.
    @Override
    public CheckpointSnapshot readMainCheckpoint() {
        // "main" checkpoint row는 recovery 시작 기준점이다.
        // 누락을 기본값으로 숨기면 WAL replay 시작 범위가 틀어질 수 있으므로 즉시 실패시킨다.
        WalCheckpointEntity checkpoint = walCheckpointJpaRepository.findById(MAIN_CHECKPOINT_NAME)
                .orElseThrow(() -> new IllegalStateException("Main WAL checkpoint row is missing."));

        return new CheckpointSnapshot(checkpoint.getLastFlushedEventSeq());
    }
}
