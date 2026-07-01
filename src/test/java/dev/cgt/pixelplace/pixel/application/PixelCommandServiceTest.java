package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
    private final PixelCommandService service = new PixelCommandService(pixelCooldown, pixelWriteService);

    @Test
    // cooldown check -> core write -> cooldown start 순서 고정
    void writePixelStartsCooldownAfterSuccessfulWrite() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        InOrder inOrder = inOrder(pixelCooldown, pixelWriteService);
        inOrder.verify(pixelCooldown).checkWritable(7L);
        inOrder.verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        inOrder.verify(pixelCooldown).startCooldown(7L);
    }

    @Test
    // cooldown 활성 상태면 eventSeq 발급/WAL append 경로 진입 금지
    void writePixelDoesNotCallWriteServiceWhenCooldownActive() {
        doThrow(new PixelCooldownActiveException(1000L))
                .when(pixelCooldown).checkWritable(7L);

        assertThrows(PixelCooldownActiveException.class, () -> service.writePixel(7L, 768, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verifyNoInteractions(pixelWriteService);
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
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // cooldown set 실패는 이미 성공한 WAL+memory write 결과를 깨지 않음
    void writePixelReturnsResultWhenCooldownStartFailsAfterSuccessfulWrite() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(new PixelCooldownUnavailableException("Pixel cooldown set failed.", new RuntimeException("redis down")))
                .when(pixelCooldown).startCooldown(7L);

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verify(pixelCooldown).startCooldown(7L);
    }

    @Test
    // userId 기본 검증 실패는 cooldown 저장소와 write service 모두 호출 금지
    void writePixelRejectsNonPositiveUserIdBeforeCooldownCheck() {
        assertThrows(IllegalArgumentException.class, () -> service.writePixel(0L, 768, 1280, 17));

        verifyNoInteractions(pixelCooldown, pixelWriteService);
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
