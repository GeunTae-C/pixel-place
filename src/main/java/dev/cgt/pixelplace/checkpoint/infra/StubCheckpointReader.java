package dev.cgt.pixelplace.checkpoint.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// stub profile에서 startup recovery checkpoint 입력을 결정론적 0으로 대체하는 adapter
// production JpaCheckpointReader 미구현 대체가 아니며 runtime persistence 책임은 갖지 않음
@Component
@Profile("stub")
public class StubCheckpointReader implements CheckpointReader {

    // DB checkpoint 조회 없이 최초 bootstrap 입력 제공
    @Override
    public CheckpointSnapshot readMainCheckpoint() {
        return new CheckpointSnapshot(0L);
    }
}
