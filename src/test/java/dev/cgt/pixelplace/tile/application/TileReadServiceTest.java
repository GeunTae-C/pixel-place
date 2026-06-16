package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// z=0 tile read use case 검증
// authoritative state는 DB가 아니라 InMemoryTileBoard라는 경계 고정
class TileReadServiceTest {

    @Test
    // write 이후 메모리 tile 상태를 raw bytes와 tileVersion으로 읽는 계약
    void readTileReturnsRawBytesAndTileVersionForValidZ0Tile() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        TileReadService service = new TileReadService(board);

        board.applyPixel(768, 1280, 17);

        TileReadResult result = service.readTile(BoardConstants.Z0_LEVEL, 3, 5);

        assertEquals(BoardConstants.TILE_PIXEL_COUNT, result.rawBytes().length);
        assertEquals((byte) 17, result.rawBytes()[0]);
        assertEquals(1L, result.tileVersion());
    }

    @Test
    // pre-init된 미수정 tile의 all-white 상태와 version 0 보존
    void readTileReturnsAllWhiteBytesAndVersionZeroForUntouchedTile() {
        TileReadService service = new TileReadService(new InMemoryTileBoard());

        TileReadResult result = service.readTile(BoardConstants.Z0_LEVEL, 0, 0);

        assertEquals(BoardConstants.TILE_PIXEL_COUNT, result.rawBytes().length);
        assertEquals(BoardConstants.DEFAULT_COLOR_INDEX, result.rawBytes()[0]);
        assertEquals(0L, result.tileVersion());
    }

    @Test
    // MVP z=0 제한과 tile 좌표 범위 밖 요청 차단
    void readTileRejectsUnsupportedZLevelsAndOutOfRangeCoordinates() {
        TileReadService service = new TileReadService(new InMemoryTileBoard());

        assertThrows(IllegalArgumentException.class, () -> service.readTile(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.readTile(2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.readTile(BoardConstants.Z0_LEVEL, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.readTile(BoardConstants.Z0_LEVEL, BoardConstants.Z0_TILE_COUNT_PER_AXIS, 0));
        assertThrows(IllegalArgumentException.class, () -> service.readTile(BoardConstants.Z0_LEVEL, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> service.readTile(BoardConstants.Z0_LEVEL, 0, BoardConstants.Z0_TILE_COUNT_PER_AXIS));
    }

    @Test
    // read 결과 객체가 raw byte 배열 소유권을 외부에 넘기지 않음
    void tileReadResultProtectsRawBytesFromExternalMutation() {
        byte[] rawBytes = new byte[BoardConstants.TILE_PIXEL_COUNT];
        rawBytes[0] = 7;
        TileReadResult result = new TileReadResult(rawBytes, 3L);

        rawBytes[0] = 9;
        byte[] returned = result.rawBytes();
        returned[0] = 11;

        assertEquals((byte) 7, result.rawBytes()[0]);
    }

    @Test
    // 잘못된 tile payload는 HTTP 계층으로 넘어가기 전 실패
    void tileReadResultRejectsInvalidRawBytes() {
        assertThrows(IllegalArgumentException.class, () -> new TileReadResult(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> new TileReadResult(new byte[1], 0L));
    }
}
