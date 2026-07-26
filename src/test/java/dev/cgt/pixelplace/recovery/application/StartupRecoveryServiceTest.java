package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * startup recovery 실패가 readiness를 열지 않는 불변식 검증
 * 실패한 부분 복구 상태에서 HTTP API가 노출되지 않도록 상태 전환 순서 고정
 */
class StartupRecoveryServiceTest {

    @Test
    // recovery는 DirtyTileTracker 입력 없이 checkpoint 이후 WAL을 authoritative memory에만 replay
    void recoveryReplaysWalIntoMemoryWithoutDirtyTrackerDependency() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        EventSeqManager eventSeqManager = new EventSeqManager();
        ServiceReadiness serviceReadiness = new ServiceReadiness();
        WalRecord record = new WalRecord(
                1L,
                7L,
                BoardConstants.Z0_LEVEL,
                3,
                5,
                768,
                1280,
                17,
                LocalDateTime.of(2026, 4, 3, 6, 0)
        );
        StartupRecoveryService service = new StartupRecoveryService(
                () -> new CheckpointSnapshot(0L),
                TileLoadResult::allMissingResult,
                lastFlushedEventSeq -> new WalReplayBatch(List.of(record), record.eventSeq()),
                board,
                eventSeqManager,
                serviceReadiness
        );

        service.recover();

        TileKey tileKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        assertEquals((byte) 17, board.getRequired(tileKey).pixels()[0]);
        assertEquals(1L, board.getRequired(tileKey).tileVersion());
        assertEquals(1L, eventSeqManager.currentLastIssued());
        assertTrue(serviceReadiness.isReady());
    }

    @Test
    // recovery 시작 시 기존 ready 상태를 닫고, 중간 실패 후에도 false 유지
    void recoveryFailureLeavesServiceNotReady() {
        ServiceReadiness serviceReadiness = new ServiceReadiness();
        serviceReadiness.markReady();

        StartupRecoveryService service = new StartupRecoveryService(
                () -> {
                    throw new IllegalStateException("checkpoint read failed.");
                },
                TileLoadResult::allMissingResult,
                lastFlushedEventSeq -> new WalReplayBatch(List.of(), lastFlushedEventSeq),
                new InMemoryTileBoard(),
                new EventSeqManager(),
                serviceReadiness
        );

        assertThrows(IllegalStateException.class, service::recover);
        assertFalse(serviceReadiness.isReady());
    }
}
