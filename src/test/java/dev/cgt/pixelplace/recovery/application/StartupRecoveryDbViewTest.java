package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/* transaction 종료 뒤 domain/application 값만 보유하는 immutable recovery DB view 계약 검증 */
class StartupRecoveryDbViewTest {

    @Test
    void nullComponentsAreRejected() {
        TileLoadResult tiles = TileLoadResult.allMissingResult();

        assertThrows(NullPointerException.class, () -> new StartupRecoveryDbView(null, tiles));
        assertThrows(
                NullPointerException.class,
                () -> new StartupRecoveryDbView(new CheckpointSnapshot(0L), null)
        );
    }

    @Test
    void checkpointAndImmutableTileResultArePreserved() {
        CheckpointSnapshot checkpoint = new CheckpointSnapshot(7L);
        TileLoadResult tiles = TileLoadResult.allMissingResult();

        StartupRecoveryDbView view = new StartupRecoveryDbView(checkpoint, tiles);

        assertSame(checkpoint, view.checkpoint());
        assertSame(tiles, view.tileLoadResult());
    }
}
