package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/* memory load 전에 key/bytes/version과 byte 배열 소유권을 고정하는 snapshot 값 계약 검증 */
class TileStateSnapshotTest {

    private static final TileKey KEY = new TileKey(0, 0, 0);

    @Test
    void nullKeyAndPixelsAreRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new TileStateSnapshot(null, new byte[BoardConstants.TILE_PIXEL_COUNT], 0L)
        );
        assertThrows(NullPointerException.class, () -> new TileStateSnapshot(KEY, null, 0L));
    }

    @Test
    void onlyExactPixelLengthIsAccepted() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TileStateSnapshot(KEY, new byte[BoardConstants.TILE_PIXEL_COUNT - 1], 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TileStateSnapshot(KEY, new byte[BoardConstants.TILE_PIXEL_COUNT + 1], 0L)
        );
        assertEquals(
                BoardConstants.TILE_PIXEL_COUNT,
                new TileStateSnapshot(KEY, new byte[BoardConstants.TILE_PIXEL_COUNT], 0L).pixels().length
        );
    }

    @Test
    void zeroVersionIsAllowedAndNegativeVersionIsRejected() {
        assertEquals(
                0L,
                new TileStateSnapshot(KEY, new byte[BoardConstants.TILE_PIXEL_COUNT], 0L).tileVersion()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TileStateSnapshot(KEY, new byte[BoardConstants.TILE_PIXEL_COUNT], -1L)
        );
    }

    @Test
    void constructorAndAccessorDefensivelyCopyPixels() {
        byte[] input = new byte[BoardConstants.TILE_PIXEL_COUNT];
        Arrays.fill(input, (byte) 7);
        TileStateSnapshot snapshot = new TileStateSnapshot(KEY, input, 3L);

        input[0] = 9;
        byte[] returned = snapshot.pixels();
        returned[1] = 9;

        assertEquals((byte) 7, snapshot.pixels()[0]);
        assertEquals((byte) 7, snapshot.pixels()[1]);
    }
}
