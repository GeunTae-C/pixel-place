package dev.cgt.pixelplace.checkpoint.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 실제 DB checkpoint 조회 구현 전까지 기본값을 제공하는 stub
// 이후 wal_checkpoint 실제 조회 구현으로 교체될 자리
@Component
@Profile("stub")
// 실제 인프라를 붙이지 않는 테스트/개발 profile에서만 사용하는 임시 stub
public class StubCheckpointReader implements CheckpointReader {

    @Override
    public CheckpointSnapshot readMainCheckpoint() {
        return new CheckpointSnapshot(0L);
    }
}
