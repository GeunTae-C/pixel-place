package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/*
 * PixelCommandService cooldown orchestration 검증
 * Redis 정책은 core write 바깥에 두고 WAL-first 순서는 PixelWriteService에 보존
 */
class PixelCommandServiceTest {

    private final PixelCooldown pixelCooldown = mock(PixelCooldown.class);
    private final PixelWriteService pixelWriteService = mock(PixelWriteService.class);
    private final DirtyTileTracker dirtyTileTracker = mock(DirtyTileTracker.class);
    private final PixelBroadcastService pixelBroadcastService = mock(PixelBroadcastService.class);
    private final PixelCommandService service = new PixelCommandService(
            pixelCooldown,
            pixelWriteService,
            dirtyTileTracker,
            pixelBroadcastService
    );

    @Test
    // cooldown check -> core write -> dirty mark -> cooldown start -> broadcast 순서 고정
    void writePixelMarksDirtyAndBroadcastsAfterCooldownStartWhenCoreWriteSucceeds() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        InOrder inOrder = inOrder(pixelCooldown, pixelWriteService, dirtyTileTracker, pixelBroadcastService);
        inOrder.verify(pixelCooldown).checkWritable(7L);
        inOrder.verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        inOrder.verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        inOrder.verify(pixelCooldown).startCooldown(7L);
        ArgumentCaptor<PixelEventMessage> messageCaptor = ArgumentCaptor.forClass(PixelEventMessage.class);
        inOrder.verify(pixelBroadcastService).broadcast(messageCaptor.capture());

        PixelEventMessage message = messageCaptor.getValue();
        assertAll(
                () -> assertEquals("pixel", message.type()),
                () -> assertEquals(result.x(), message.x()),
                () -> assertEquals(result.y(), message.y()),
                () -> assertEquals(result.color(), message.color()),
                () -> assertEquals(result.eventSeq(), message.eventSeq())
        );
    }

    @Test
    // dirty mark 인자는 flush checkpoint 기준 eventSeq와 tile snapshot 기준 tileVersion 모두 필요
    void writePixelPassesTileKeyEventSeqAndTileVersionToDirtyTracker() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);

        service.writePixel(7L, 768, 1280, 17);

        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
    }

    @Test
    // cooldown 활성 상태면 eventSeq 발급/WAL append 경로 진입 금지
    void writePixelDoesNotCallWriteServiceWhenCooldownActive() {
        doThrow(new PixelCooldownActiveException(1000L))
                .when(pixelCooldown).checkWritable(7L);

        assertThrows(PixelCooldownActiveException.class, () -> service.writePixel(7L, 768, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verifyNoInteractions(pixelWriteService);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // cooldown check 실패면 승인 여부 판단 불가로 core write 진입 금지
    void writePixelDoesNotCallWriteServiceWhenCooldownCheckFails() {
        doThrow(new PixelCooldownUnavailableException("Pixel cooldown check failed.", new RuntimeException("redis down")))
                .when(pixelCooldown).checkWritable(7L);

        assertThrows(PixelCooldownUnavailableException.class, () -> service.writePixel(7L, 768, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verifyNoInteractions(pixelWriteService);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // core validation 실패는 승인된 write가 아니므로 cooldown 시작 금지
    void writePixelDoesNotStartCooldownWhenCoreWriteFailsWithIllegalArgumentException() {
        when(pixelWriteService.writePixel(7L, -1, 1280, 17))
                .thenThrow(new IllegalArgumentException("x coordinate is out of board range. x=-1"));

        assertThrows(IllegalArgumentException.class, () -> service.writePixel(7L, -1, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, -1, 1280, 17);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // WAL fsync 등 core 실패는 cooldown 시작 금지
    void writePixelDoesNotStartCooldownWhenCoreWriteFailsWithIllegalStateException() {
        when(pixelWriteService.writePixel(7L, 768, 1280, 17))
                .thenThrow(new IllegalStateException("WAL fsync failed."));

        assertThrows(IllegalStateException.class, () -> service.writePixel(7L, 768, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // dirty mark 실패는 flush 정합성 후처리 실패이므로 cooldown/broadcast 진행 금지
    void writePixelPropagatesIllegalStateExceptionWhenDirtyMarkFails() {
        PixelWriteResult result = result();
        RuntimeException dirtyFailure = new IllegalArgumentException("dirty invalid");
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(dirtyFailure)
                .when(dirtyTileTracker)
                .markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.writePixel(7L, 768, 1280, 17)
        );

        assertAll(
                () -> assertEquals(
                        "Dirty tile mark failed after successful write. eventSeq=1",
                        exception.getMessage()
                ),
                () -> assertSame(dirtyFailure, exception.getCause()),
                () -> assertInstanceOf(IllegalArgumentException.class, exception.getCause())
        );
        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        verify(pixelCooldown, never()).startCooldown(7L);
        verifyNoInteractions(pixelBroadcastService);
    }

    @Test
    // dirty mark 성공 후 cooldown set 실패는 이미 성공한 WAL+memory write 결과를 깨지 않고 broadcast는 계속 시도
    void writePixelBroadcastsAndReturnsResultWhenCooldownStartFailsAfterSuccessfulWrite() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(new PixelCooldownUnavailableException("Pixel cooldown set failed.", new RuntimeException("redis down")))
                .when(pixelCooldown).startCooldown(7L);

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        verify(pixelCooldown).startCooldown(7L);
        verify(pixelBroadcastService).broadcast(any(PixelEventMessage.class));
    }

    @Test
    // dirty mark 성공 후 broadcast 실패는 이미 성공한 WAL+memory write 결과를 깨지 않음
    void writePixelReturnsResultWhenBroadcastFailsAfterSuccessfulWrite() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(new RuntimeException("websocket down"))
                .when(pixelBroadcastService).broadcast(any(PixelEventMessage.class));

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        verify(pixelCooldown).startCooldown(7L);
        verify(pixelBroadcastService).broadcast(any(PixelEventMessage.class));
    }

    @Test
    // userId 기본 검증 실패는 cooldown 저장소와 write service 모두 호출 금지
    void writePixelRejectsNonPositiveUserIdBeforeCooldownCheck() {
        assertThrows(IllegalArgumentException.class, () -> service.writePixel(0L, 768, 1280, 17));

        verifyNoInteractions(pixelCooldown, pixelWriteService, dirtyTileTracker, pixelBroadcastService);
    }

    private PixelWriteResult result() {
        return new PixelWriteResult(
                1L,
                new TileKey(BoardConstants.Z0_LEVEL, 3, 5),
                1L,
                768,
                1280,
                17
        );
    }
}
