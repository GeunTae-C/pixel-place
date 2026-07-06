package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
 * DirtyTile 값 객체 검증
 * flush 대상 tile은 checkpoint 기준 eventSeq와 tile snapshot version을 함께 보존해야 함
 */
class DirtyTileTest {

    private final TileKey tileKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);

    @Test
    void createDirtyTileWithValidValues() {
        DirtyTile dirtyTile = new DirtyTile(tileKey, 10L, 3L);

        assertAll(
                () -> assertSame(tileKey, dirtyTile.tileKey()),
                () -> assertEquals(10L, dirtyTile.latestEventSeq()),
                () -> assertEquals(3L, dirtyTile.latestTileVersion())
        );
    }

    @Test
    void createDirtyTileRejectsNullTileKey() {
        assertThrows(IllegalArgumentException.class, () -> new DirtyTile(null, 10L, 3L));
    }

    @Test
    void createDirtyTileRejectsNonPositiveLatestEventSeq() {
        assertThrows(IllegalArgumentException.class, () -> new DirtyTile(tileKey, 0L, 3L));
    }

    @Test
    void createDirtyTileRejectsNonPositiveLatestTileVersion() {
        assertThrows(IllegalArgumentException.class, () -> new DirtyTile(tileKey, 10L, 0L));
    }
}
