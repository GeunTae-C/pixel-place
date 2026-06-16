package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;

import java.util.Arrays;

/*
 * tile read 결과를 HTTP layer로 넘기는 값 객체
 * rawBytes는 256x256 palette index byte 배열이고, tileVersion은 해당 타일의 cache/version 기준
 * rawBytes는 외부 수정으로부터 보호해 응답 생성 중 원본 tile payload가 흔들리지 않게 함
 */
public record TileReadResult(byte[] rawBytes, long tileVersion) {

    public TileReadResult {
        if (rawBytes == null || rawBytes.length != BoardConstants.TILE_PIXEL_COUNT) {
            throw new IllegalArgumentException("Tile raw bytes must match tile size.");
        }
        rawBytes = Arrays.copyOf(rawBytes, rawBytes.length);
    }

    @Override
    public byte[] rawBytes() {
        return Arrays.copyOf(rawBytes, rawBytes.length);
    }
}
