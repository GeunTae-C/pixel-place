package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

// stub profile에서 startup recovery WAL replay 입력을 빈 결과로 대체하는 adapter
// production FileWalReplaySource 미구현 대체가 아니며 runtime flush 입력으로 사용하면 안 됨
@Component
@Profile("stub")
public class StubWalReplaySource implements WalReplaySource {

    // active WAL 조회 없이 replay record와 walLastEventSeq의 결정론적 초기값 제공
    @Override
    public WalReplayBatch readAfter(long lastFlushedEventSeq) {
        return new WalReplayBatch(List.of(), 0L);
    }
}
