package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

// 실제 WAL 구현 전이므로 빈 replay 결과와 기본 walLastEventSeq를 주는 stub다.
@Component
@Profile("stub")
// 실제 인프라를 붙이지 않는 테스트/개발 profile에서만 사용하는 임시 stub다.
public class StubWalReplaySource implements WalReplaySource {

    @Override
    public WalReplayBatch readAfter(long lastFlushedEventSeq) {
        return new WalReplayBatch(List.of(), 0L);
    }
}
