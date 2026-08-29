package dev.cgt.pixelplace.tile.domain;

import dev.cgt.pixelplace.common.constant.BoardConstants;

import java.util.Arrays;

/*
 * memory authoritative board를 구성하는 단일 tile bytes와 per-tile version 값
 * 정상 write와 recovery replay가 같은 표현을 사용하며 배열 방어 복사로 외부 변경 차단
 */
public record TileState(byte[] pixels, long tileVersion) {

    public TileState {
        // 256x256 palette index 배열이 아니면 authoritative tile shape 유지 불가
        if (pixels == null || pixels.length != BoardConstants.TILE_PIXEL_COUNT) {
            throw new IllegalArgumentException("Tile pixels must match tile size.");
        }
        pixels = Arrays.copyOf(pixels, pixels.length);
    }

    @Override
    public byte[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    /* bootstrap-pending memory pre-init에 사용할 version 0 기본색 tile 생성 */
    public static TileState allWhite() {
        byte[] pixels = new byte[BoardConstants.TILE_PIXEL_COUNT];
        Arrays.fill(pixels, BoardConstants.DEFAULT_COLOR_INDEX);
        return new TileState(pixels, 0L);
    }
}
