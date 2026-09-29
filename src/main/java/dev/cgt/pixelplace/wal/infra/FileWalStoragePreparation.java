package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalStoragePreparation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 일반 startup의 검증 batch를 reader/writer와 같은 storage에 전달. 파일 경로를 application에 노출하지 않음 */
@Component
@Profile("!stub")
public class FileWalStoragePreparation implements WalStoragePreparation {
    private final SegmentedWalStorage storage;
    public FileWalStoragePreparation(SegmentedWalStorage storage) { this.storage = storage; }
    @Override
    public void prepareForRecovery(WalReplayBatch validatedBatch) { storage.prepareForRecovery(validatedBatch); }
}
