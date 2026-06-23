package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
 * startup recovery 실패가 readiness를 열지 않는 불변식 검증
 * 실패한 부분 복구 상태에서 HTTP API가 노출되지 않도록 상태 전환 순서 고정
 */
class StartupRecoveryServiceTest {

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
