package dev.cgt.pixelplace;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryDbView;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryDbViewCaptureService;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryService;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static dev.cgt.pixelplace.common.constant.BoardConstants.Z0_TILE_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/* Spring context 대신 skeleton의 memory pre-init과 recovery 완료 불변식만 확인하는 단위 테스트 */
class PixelPlaceApplicationTests {

    @Test
    void inMemoryTileBoardPreInitializesAllZ0Tiles() {
        assertEquals(Z0_TILE_COUNT, new InMemoryTileBoard().size());
    }

    @Test
    void bootstrapRecoverySeedsValidatedWalTailAndMarksReady() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        ServiceReadiness readiness = new ServiceReadiness();
        InMemoryTileBoard board = new InMemoryTileBoard();
        CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        when(capture.capture()).thenReturn(new StartupRecoveryDbView(
                new CheckpointSnapshot(0L),
                TileLoadResult.allMissingResult()
        ));
        WalRecord record = new WalRecord(
                7L,
                3L,
                0,
                0,
                0,
                0,
                0,
                17,
                LocalDateTime.of(2026, 4, 3, 6, 0)
        );
        StartupRecoveryService service = new StartupRecoveryService(
                capture,
                new DbBootstrapClassifier(canonicalKeys),
                canonicalKeys,
                checkpoint -> new WalReplayBatch(List.of(record), 7L),
                board,
                eventSeqManager,
                readiness
        );

        service.recover();

        assertEquals(7L, eventSeqManager.currentLastIssued());
        assertTrue(readiness.isReady());
    }
}
