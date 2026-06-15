package dev.cgt.pixelplace.tile.domain;

import dev.cgt.pixelplace.common.constant.BoardConstants;

import java.util.Arrays;

// 단일 타일의 메모리 상태를 담는 최소 단위
// 현재는 write path 구현체가 아니라 recovery skeleton이 메모리 authoritative state를 구성하기 위한 최소 표현만 제공함
public record TileState(byte[] pixels, long tileVersion) {
    /**
     * pixels = 256x256 = 65536개의 픽셀 색 인덱스
     * tileVersion = 이 타일이 몇 번 바뀌었는지 나타내는 버전 값
    **/

    public TileState {
        // 타일 크기와 다른 배열이 들어오면 메모리 보드 불변식이 깨지므로 즉시 실패시킴
        if (pixels == null || pixels.length != BoardConstants.TILE_PIXEL_COUNT) {
            throw new IllegalArgumentException("Tile pixels must match tile size.");
        }
        pixels = Arrays.copyOf(pixels, pixels.length);
    }

    @Override
    public byte[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    // 새 하얀 타일 한 장을 생성함
    // Java byte 배열의 기본값은 0이므로, xterm 팔레트 기준 흰색 index인 DEFAULT_COLOR_INDEX로 명시적으로 채움
    public static TileState allWhite() {
        byte[] pixels = new byte[BoardConstants.TILE_PIXEL_COUNT];
        Arrays.fill(pixels, BoardConstants.DEFAULT_COLOR_INDEX);
        return new TileState(pixels, 0L);
    }
}
