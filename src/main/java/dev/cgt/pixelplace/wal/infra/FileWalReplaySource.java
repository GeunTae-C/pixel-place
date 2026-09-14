package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * default profile의 startup recovery·runtime flush 공통 다중 segment 입력 adapter
 * 파일군·record 검증은 writer와 같은 storage에 위임하며 coordinator·readiness 관리 책임 없음
 */
@Component
@Profile("!stub")
public class FileWalReplaySource implements WalReplaySource {
    private final SegmentedWalStorage storage;

    public FileWalReplaySource(SegmentedWalStorage storage) {
        this.storage = storage;
    }

    /** 전체 파일군 검증 후 checkpoint 초과 record와 마지막 실제 record의 tail 반환 */
    @Override
    public WalReplayBatch readAfter(long lastFlushedEventSeq) {
        return storage.readAfter(lastFlushedEventSeq);
    }
}
