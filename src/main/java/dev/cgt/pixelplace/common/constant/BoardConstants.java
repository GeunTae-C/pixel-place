package dev.cgt.pixelplace.common.constant;

// 보드/타일 관련 고정 불변식을 한 곳에 모음
// recovery와 z=0 전체 pre-init 검증이 같은 숫자를 공유해야 조용한 불일치를 막을 수 있음
public final class BoardConstants {

    public static final int BOARD_SIZE = 8192;
    public static final int TILE_SIZE = 256;
    public static final int Z0_LEVEL = 0;
    public static final int Z0_TILE_COUNT_PER_AXIS = BOARD_SIZE / TILE_SIZE;
    public static final int Z0_TILE_COUNT = Z0_TILE_COUNT_PER_AXIS * Z0_TILE_COUNT_PER_AXIS;
    public static final int TILE_PIXEL_COUNT = TILE_SIZE * TILE_SIZE;
    public static final int PALETTE_SIZE = 256;
    public static final int OVERVIEW_SCALE = 4;
    public static final int OVERVIEW_SIZE = BOARD_SIZE / OVERVIEW_SCALE;
    public static final int OVERVIEW_TILE_SIZE = TILE_SIZE / OVERVIEW_SCALE;
    public static final int OVERVIEW_REFRESH_SECONDS = 10;
    public static final long OVERVIEW_REFRESH_MILLIS = OVERVIEW_REFRESH_SECONDS * 1_000L;
    // 15 = #ffffff
    public static final byte DEFAULT_COLOR_INDEX = 15;

    private BoardConstants() {
    }
}
