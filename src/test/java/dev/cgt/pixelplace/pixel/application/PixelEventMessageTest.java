package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * PixelEventMessage 변환 규칙 검증
 * WebSocket payload는 eventSeq만 사용하고 tileVersion은 포함하지 않음
 */
class PixelEventMessageTest {

    @Test
    void fromCreatesPixelEventMessageWithoutTileVersion() {
        PixelWriteResult result = new PixelWriteResult(
                10L,
                new TileKey(BoardConstants.Z0_LEVEL, 3, 5),
                99L,
                768,
                1280,
                17
        );

        PixelEventMessage message = PixelEventMessage.from(result);

        assertAll(
                () -> assertEquals("pixel", message.type()),
                () -> assertEquals(result.x(), message.x()),
                () -> assertEquals(result.y(), message.y()),
                () -> assertEquals(result.color(), message.color()),
                () -> assertEquals(result.eventSeq(), message.eventSeq())
        );
    }
}
