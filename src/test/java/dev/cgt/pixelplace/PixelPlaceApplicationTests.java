package dev.cgt.pixelplace;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryService;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.cgt.pixelplace.common.constant.BoardConstants.Z0_TILE_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 이 테스트는 Spring context 통합 테스트가 아니라 skeleton 불변식 검증용 단위 테스트
class PixelPlaceApplicationTests {

    @Test
    // 부팅 직후 z=0 타일 1024개가 메모리에 항상 존재한다는 불변식을 보장함
    void inMemoryTileBoardPreInitializesAllZ0Tiles() {
        InMemoryTileBoard board = new InMemoryTileBoard();

        assertEquals(Z0_TILE_COUNT, board.size());
    }

    @Test
    // recovery가 마지막 발급 eventSeq 초기화와 ready 전환까지 완료하는지 확인함
    void recoverInitializesEventSeqManagerAndMarksReady() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        ServiceReadiness serviceReadiness = new ServiceReadiness();
        StartupRecoveryService service = new StartupRecoveryService(
                checkpointReader(3L),
                allMissingTileLoader(),
                replaySource(7L),
                new InMemoryTileBoard(),
                eventSeqManager,
                serviceReadiness
        );

        service.recover();

        assertEquals(7L, eventSeqManager.currentLastIssued());
        assertTrue(serviceReadiness.isReady());
    }

    @Test
    // recovery seed는 DB flush 완료 지점과 WAL 파일 마지막 eventSeq 중 큰 값이어야 함
    void recoverUsesMaxOfLastFlushedEventSeqAndWalLastEventSeqAsSeed() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        StartupRecoveryService service = new StartupRecoveryService(
                checkpointReader(11L),
                allMissingTileLoader(),
                replaySource(7L),
                new InMemoryTileBoard(),
                eventSeqManager,
                new ServiceReadiness()
        );

        service.recover();

        assertEquals(11L, eventSeqManager.currentLastIssued());
    }

    private CheckpointReader checkpointReader(long lastFlushedEventSeq) {
        return () -> new CheckpointSnapshot(lastFlushedEventSeq);
    }

    private TileSnapshotLoader allMissingTileLoader() {
        return TileLoadResult::allMissingResult;
    }

    private WalReplaySource replaySource(long walLastEventSeq) {
        return lastFlushedEventSeq -> new WalReplayBatch(List.of(), walLastEventSeq);
    }
}
