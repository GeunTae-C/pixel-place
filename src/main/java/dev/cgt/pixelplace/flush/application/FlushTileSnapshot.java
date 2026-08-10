package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.Arrays;
import java.util.Objects;

/*
 * coordinator boundary에서 capture한 단일 z=0 tile bytes와 version의 immutable 값 객체
 * 생성 시와 accessor 호출 시 모두 방어 복사하여 이후 live memory 변경과 외부 배열 변경 차단
 */
public final class FlushTileSnapshot {

    private final TileKey tileKey;
    private final byte[] pixels;
    private final long tileVersion;

    public FlushTileSnapshot(TileKey tileKey, byte[] pixels, long tileVersion) {
        this.tileKey = Objects.requireNonNull(tileKey, "tileKey must not be null");
        if (!isCanonicalZ0(tileKey)) {
            throw new IllegalArgumentException("tileKey must be a canonical z=0 key. tileKey=" + tileKey);
        }
        Objects.requireNonNull(pixels, "pixels must not be null");
        if (pixels.length != BoardConstants.TILE_PIXEL_COUNT) {
            throw new IllegalArgumentException(
                    "pixels length must be " + BoardConstants.TILE_PIXEL_COUNT + ". length=" + pixels.length
            );
        }
        if (tileVersion < 0) {
            throw new IllegalArgumentException("tileVersion must not be negative. tileVersion=" + tileVersion);
        }

        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.tileVersion = tileVersion;
    }

    public TileKey tileKey() {
        return tileKey;
    }

    /* plan 외부에 mutable byte[] 참조가 노출되지 않도록 매 호출 방어 복사 */
    public byte[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    public long tileVersion() {
        return tileVersion;
    }

    private boolean isCanonicalZ0(TileKey key) {
        return key.z() == BoardConstants.Z0_LEVEL
                && key.tx() >= 0
                && key.tx() < BoardConstants.Z0_TILE_COUNT_PER_AXIS
                && key.ty() >= 0
                && key.ty() < BoardConstants.Z0_TILE_COUNT_PER_AXIS;
    }
}
