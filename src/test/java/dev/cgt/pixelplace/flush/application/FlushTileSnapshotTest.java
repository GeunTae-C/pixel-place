package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlushTileSnapshotTest {

    private static final TileKey KEY = new TileKey(0, 3, 5);

    @Test
    void constructorDefensivelyCopiesPixels() {
        byte[] source = pixels((byte) 7);
        FlushTileSnapshot snapshot = new FlushTileSnapshot(KEY, source, 1L);

        source[0] = 99;

        assertEquals((byte) 7, snapshot.pixels()[0]);
    }

    @Test
    void pixelsAccessorReturnsDefensiveCopy() {
        FlushTileSnapshot snapshot = new FlushTileSnapshot(KEY, pixels((byte) 7), 1L);

        byte[] returned = snapshot.pixels();
        returned[0] = 99;

        assertEquals((byte) 7, snapshot.pixels()[0]);
    }

    @Test
    void exactTilePixelCountIsAccepted() {
        FlushTileSnapshot snapshot = new FlushTileSnapshot(
                KEY,
                new byte[BoardConstants.TILE_PIXEL_COUNT],
                1L
        );

        assertEquals(BoardConstants.TILE_PIXEL_COUNT, snapshot.pixels().length);
    }

    @Test
    void nullAndWrongLengthPixelsAreRejected() {
        assertThrows(NullPointerException.class, () -> new FlushTileSnapshot(KEY, null, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlushTileSnapshot(KEY, new byte[BoardConstants.TILE_PIXEL_COUNT - 1], 1L)
        );
    }

    @Test
    void zeroVersionIsAcceptedAndNegativeVersionIsRejected() {
        FlushTileSnapshot snapshot = new FlushTileSnapshot(KEY, pixels((byte) 0), 0L);

        assertEquals(0L, snapshot.tileVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlushTileSnapshot(KEY, pixels((byte) 0), -1L)
        );
    }

    @Test
    void nullAndNonCanonicalKeysAreRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new FlushTileSnapshot(null, pixels((byte) 0), 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlushTileSnapshot(new TileKey(1, 0, 0), pixels((byte) 0), 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlushTileSnapshot(new TileKey(0, 32, 0), pixels((byte) 0), 0L)
        );
    }

    private byte[] pixels(byte value) {
        byte[] pixels = new byte[BoardConstants.TILE_PIXEL_COUNT];
        Arrays.fill(pixels, value);
        return pixels;
    }
}
