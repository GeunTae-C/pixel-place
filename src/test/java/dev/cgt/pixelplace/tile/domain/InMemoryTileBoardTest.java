package dev.cgt.pixelplace.tile.domain;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// InMemoryTileBoard는 DB가 아닌 실시간 authoritative state이므로 write/replay 공통 mutation 규칙을 직접 검증한다.
class InMemoryTileBoardTest {

    @Test
    // z=0 전체 타일 pre-init은 이후 read/write/replay가 타일 부재를 정상 흐름으로 오해하지 않기 위한 기본 상태다.
    void initializeAllWhiteCreatesAllZ0Tiles() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        board.initializeAllWhite();

        assertEquals(BoardConstants.Z0_TILE_COUNT, board.size());
    }

    @Test
    // applyPixel은 전역 좌표를 z=0 타일 내부 좌표로 바꿔 해당 1 byte 팔레트 인덱스만 변경한다.
    void applyPixelChangesRequestedPixelColor() {
        InMemoryTileBoard board = initializedBoard();

        board.applyPixel(768, 1280, 17);

        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        byte[] pixels = board.getRequired(key).pixels();
        assertEquals((byte) 17, pixels[0]);
    }

    @Test
    // 타일 내용이 바뀌면 클라이언트 정합성 기준인 tileVersion도 함께 증가해야 한다.
    void applyPixelIncrementsTileVersion() {
        InMemoryTileBoard board = initializedBoard();

        board.applyPixel(768, 1280, 17);

        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        assertEquals(1L, board.getRequired(key).tileVersion());
    }

    @Test
    // 변경 결과는 이후 응답, 캐시 무효화, dirty tracking이 같은 타일과 버전을 기준으로 움직이게 하는 계약이다.
    void applyPixelReturnsMutatedTileKeyAndVersion() {
        InMemoryTileBoard board = initializedBoard();
        TileKey expectedKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);

        TileMutationResult result = board.applyPixel(768, 1280, 17);

        assertEquals(expectedKey, result.key());
        assertEquals(1L, result.tileVersion());
    }

    @Test
    // 같은 타일에 대한 연속 변경은 해당 타일 버전을 변경 횟수만큼 누적해야 한다.
    void applyPixelTwiceOnSameTileIncrementsVersionToTwo() {
        InMemoryTileBoard board = initializedBoard();

        board.applyPixel(768, 1280, 17);
        board.applyPixel(769, 1280, 18);

        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        assertEquals(2L, board.getRequired(key).tileVersion());
    }

    @Test
    // tileVersion은 타일 단위 정합성 기준이므로 다른 타일 변경이 기존 타일 버전을 움직이면 안 된다.
    void applyPixelOnDifferentTileKeepsPreviousTileVersion() {
        InMemoryTileBoard board = initializedBoard();
        TileKey firstKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        TileKey secondKey = new TileKey(BoardConstants.Z0_LEVEL, 4, 5);

        board.applyPixel(768, 1280, 17);
        board.applyPixel(1024, 1280, 18);

        assertEquals(1L, board.getRequired(firstKey).tileVersion());
        assertEquals(1L, board.getRequired(secondKey).tileVersion());
    }

    @Test
    // replay도 정상 write와 같은 메모리 mutation 경로를 써야 재시작 후 tileVersion 기준이 갈라지지 않는다.
    void applyReplayRecordChangesPixelAndTileVersionLikeApplyPixel() {
        InMemoryTileBoard board = initializedBoard();

        board.applyReplayRecord(768, 1280, 17);

        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        TileState tileState = board.getRequired(key);
        assertEquals((byte) 17, tileState.pixels()[0]);
        assertEquals(1L, tileState.tileVersion());
    }

    @Test
    // 팔레트의 마지막 유효 색상은 1 byte 저장 표현 안에서 정상 적용되어야 한다.
    void applyPixelAcceptsLastValidColor() {
        InMemoryTileBoard board = initializedBoard();
        int color = BoardConstants.PALETTE_SIZE - 1;

        board.applyPixel(768, 1280, color);

        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        byte[] pixels = board.getRequired(key).pixels();
        assertEquals((byte) (BoardConstants.PALETTE_SIZE - 1), pixels[0]);
    }

    @Test
    // 보드의 마지막 유효 좌표는 마지막 z=0 타일의 마지막 local pixel로 매핑되어야 한다.
    void applyPixelAcceptsLastValidCoordinate() {
        InMemoryTileBoard board = initializedBoard();

        board.applyPixel(BoardConstants.BOARD_SIZE - 1, BoardConstants.BOARD_SIZE - 1, 17);

        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, 31, 31);
        byte[] pixels = board.getRequired(key).pixels();
        int index = BoardConstants.TILE_PIXEL_COUNT - 1;
        assertEquals((byte) 17, pixels[index]);
    }

    @Test
    // 음수 x는 보드 밖 좌표라 메모리 authoritative state에 반영할 수 없다.
    void applyPixelRejectsNegativeX() {
        InMemoryTileBoard board = initializedBoard();

        assertThrows(IllegalArgumentException.class, () -> board.applyPixel(-1, 0, 0));
    }

    @Test
    // x 상한은 BOARD_SIZE 미만이어야 z=0 타일 좌표가 0~31 범위에 머문다.
    void applyPixelRejectsXGreaterThanOrEqualBoardSize() {
        InMemoryTileBoard board = initializedBoard();

        assertThrows(
                IllegalArgumentException.class,
                () -> board.applyPixel(BoardConstants.BOARD_SIZE, 0, 0)
        );
    }

    @Test
    // 음수 y는 보드 밖 좌표라 메모리 authoritative state에 반영할 수 없다.
    void applyPixelRejectsNegativeY() {
        InMemoryTileBoard board = initializedBoard();

        assertThrows(IllegalArgumentException.class, () -> board.applyPixel(0, -1, 0));
    }

    @Test
    // y 상한은 BOARD_SIZE 미만이어야 z=0 타일 좌표가 0~31 범위에 머문다.
    void applyPixelRejectsYGreaterThanOrEqualBoardSize() {
        InMemoryTileBoard board = initializedBoard();

        assertThrows(IllegalArgumentException.class, () -> board.applyPixel(0, BoardConstants.BOARD_SIZE, 0));
    }

    @Test
    // 음수 색상은 256색 고정 팔레트의 인덱스로 해석할 수 없다.
    void applyPixelRejectsNegativeColor() {
        InMemoryTileBoard board = initializedBoard();

        assertThrows(IllegalArgumentException.class, () -> board.applyPixel(0, 0, -1));
    }

    @Test
    // 팔레트 인덱스 상한은 PALETTE_SIZE 미만이어야 1 byte 색상 표현과 맞는다.
    void applyPixelRejectsColorGreaterThanOrEqualPaletteSize() {
        InMemoryTileBoard board = initializedBoard();

        assertThrows(IllegalArgumentException.class, () -> board.applyPixel(0, 0, BoardConstants.PALETTE_SIZE));
    }

    private InMemoryTileBoard initializedBoard() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        board.initializeAllWhite();
        return board;
    }
}
