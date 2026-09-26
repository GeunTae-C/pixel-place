package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalAppender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ServiceReadinessTest {

    @Test
    void temporaryNotReadyCanReturnToReadyAndPassesFatalOnlyCheck() {
        ServiceReadiness readiness = new ServiceReadiness();

        assertFalse(readiness.isReady());
        assertDoesNotThrow(readiness::requireNotFatal);

        readiness.markReady();
        assertTrue(readiness.isReady());

        readiness.markNotReady();
        assertFalse(readiness.isReady());
        assertDoesNotThrow(readiness::requireNotFatal);

        readiness.markReady();
        assertTrue(readiness.isReady());
    }

    @Test
    // pending 설치 확인 불가 뒤 같은 process의 정상 전환과 protected write 재개 금지
    void fatalNotReadyIsIdempotentIrreversibleAndBlocksProtectedWrite() {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();

        readiness.markFatalNotReady();
        readiness.markFatalNotReady();
        readiness.markNotReady();
        readiness.markReady();

        assertFalse(readiness.isReady());
        assertThrows(ServiceNotReadyException.class, readiness::requireReady);
        assertThrows(ServiceNotReadyException.class, readiness::requireNotFatal);

        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        WalAppender walAppender = mock(WalAppender.class);
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        PixelWriteService writeService = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());

        assertThrows(
                ServiceNotReadyException.class,
                () -> writeService.writePixel(7L, 0, 0, 17)
        );
        verifyNoInteractions(eventSeqManager, walAppender, board);
    }
}
