package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/* checkpoint와 전체 tile 관측을 하나의 read-only repeatable-read DB view로 묶는 proxy 경계 */
@Service
public class StartupRecoveryDbViewCaptureService {

    private final CheckpointReader checkpointReader;
    private final TileSnapshotLoader tileSnapshotLoader;

    public StartupRecoveryDbViewCaptureService(
            CheckpointReader checkpointReader,
            TileSnapshotLoader tileSnapshotLoader
    ) {
        this.checkpointReader = checkpointReader;
        this.tileSnapshotLoader = tileSnapshotLoader;
    }

    /* checkpoint → 전체 key → z=0 snapshot 순서의 동일 DB snapshot capture */
    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRED
    )
    public StartupRecoveryDbView capture() {
        CheckpointSnapshot checkpoint = checkpointReader.readMainCheckpoint();
        TileLoadResult tileLoadResult = tileSnapshotLoader.loadZ0Tiles();
        return new StartupRecoveryDbView(checkpoint, tileLoadResult);
    }
}
