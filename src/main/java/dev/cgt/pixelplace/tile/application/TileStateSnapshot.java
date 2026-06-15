package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.Arrays;

// DB snapshot에서 메모리 보드로 옮길 때 사용하는 중간 전달 타입
public record TileStateSnapshot(TileKey key, byte[] pixels, long tileVersion) {

    public TileStateSnapshot {
        pixels = Arrays.copyOf(pixels, pixels.length);
    }

    @Override
    public byte[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }
}
