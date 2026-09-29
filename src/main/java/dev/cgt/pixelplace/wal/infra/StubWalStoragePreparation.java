package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalStoragePreparation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** stub profile의 startup 준비만 대체. 실제 storage의 내구성 adapter나 runtime flush를 대체하지 않음 */
@Component
@Profile("stub")
public class StubWalStoragePreparation implements WalStoragePreparation {
    @Override
    public void prepareForRecovery(WalReplayBatch validatedBatch) { java.util.Objects.requireNonNull(validatedBatch); }
}
