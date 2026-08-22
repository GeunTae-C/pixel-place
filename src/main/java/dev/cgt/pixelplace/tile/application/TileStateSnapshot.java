package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.Arrays;
import java.util.Objects;

/* DB snapshot을 memory board로 옮기는 immutable 값, 잘못된 bytes/version의 복구 유입 차단 */
public record TileStateSnapshot(TileKey key, byte[] pixels, long tileVersion) {

    public TileStateSnapshot {
        Objects.requireNonNull(key, "key must not be null.");
        Objects.requireNonNull(pixels, "pixels must not be null.");
        if (pixels.length != BoardConstants.TILE_PIXEL_COUNT) {
            throw new IllegalArgumentException(
                    "pixels length must be " + BoardConstants.TILE_PIXEL_COUNT + "."
            );
        }
        if (tileVersion < 0) {
            throw new IllegalArgumentException("tileVersion must not be negative.");
        }
        pixels = Arrays.copyOf(pixels, pixels.length);
    }

    @Override
    public byte[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    /* defensive copy 없이 recovery invariant를 재검증하기 위한 byte 길이 관측값 */
    public int pixelCount() {
        return pixels.length;
    }
}
