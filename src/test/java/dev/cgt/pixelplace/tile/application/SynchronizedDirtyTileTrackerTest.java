package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * SynchronizedDirtyTileTracker dirty map 규칙 검증
 * 순서 보장 없이 tile별 최신 eventSeq 상태만 flush 대상으로 회수
 */
class SynchronizedDirtyTileTrackerTest {

    private final SynchronizedDirtyTileTracker tracker = new SynchronizedDirtyTileTracker();

    @Test
    void drainDirtyTilesReturnsMarkedTile() {
        TileKey tileKey = tileKey(3, 5);

        tracker.markDirty(tileKey, 10L, 2L);

        Map<TileKey, DirtyTile> drained = drainAsMap();
        assertDirtyTile(drained.get(tileKey), tileKey, 10L, 2L);
    }

    @Test
    void markDirtyUpdatesSameTileWhenEventSeqIsGreater() {
        TileKey tileKey = tileKey(3, 5);
        tracker.markDirty(tileKey, 10L, 2L);
        tracker.markDirty(tileKey, 11L, 3L);

        DirtyTile dirtyTile = drainAsMap().get(tileKey);

        assertDirtyTile(dirtyTile, tileKey, 11L, 3L);
    }

    @Test
    void markDirtyKeepsExistingTileWhenEventSeqIsLower() {
        TileKey tileKey = tileKey(3, 5);
        tracker.markDirty(tileKey, 10L, 2L);
        tracker.markDirty(tileKey, 9L, 99L);

        DirtyTile dirtyTile = drainAsMap().get(tileKey);

        assertDirtyTile(dirtyTile, tileKey, 10L, 2L);
    }

    @Test
    void markDirtyKeepsExistingTileWhenEventSeqIsSame() {
        TileKey tileKey = tileKey(3, 5);
        tracker.markDirty(tileKey, 10L, 2L);
        tracker.markDirty(tileKey, 10L, 99L);

        DirtyTile dirtyTile = drainAsMap().get(tileKey);

        assertDirtyTile(dirtyTile, tileKey, 10L, 2L);
    }

    @Test
    void markDirtyKeepsDifferentTilesIndependently() {
        TileKey first = tileKey(3, 5);
        TileKey second = tileKey(4, 6);
        tracker.markDirty(first, 10L, 2L);
        tracker.markDirty(second, 11L, 1L);

        Map<TileKey, DirtyTile> drained = drainAsMap();

        assertAll(
                () -> assertEquals(2, drained.size()),
                () -> assertDirtyTile(drained.get(first), first, 10L, 2L),
                () -> assertDirtyTile(drained.get(second), second, 11L, 1L)
        );
    }

    @Test
    void drainDirtyTilesClearsExistingDirtyTiles() {
        tracker.markDirty(tileKey(3, 5), 10L, 2L);

        tracker.drainDirtyTiles();

        assertTrue(tracker.drainDirtyTiles().isEmpty());
    }

    @Test
    void drainDirtyTilesAllowsNewMarksAfterDrain() {
        TileKey first = tileKey(3, 5);
        TileKey second = tileKey(4, 6);

        tracker.markDirty(first, 10L, 2L);
        tracker.drainDirtyTiles();

        tracker.markDirty(second, 11L, 1L);

        Map<TileKey, DirtyTile> drained = drainAsMap();
        assertAll(
                () -> assertEquals(1, drained.size()),
                () -> assertDirtyTile(drained.get(second), second, 11L, 1L)
        );
    }

    @Test
    void drainDirtyTilesReturnsEmptyListWhenNoDirtyTileExists() {
        List<DirtyTile> drained = tracker.drainDirtyTiles();

        assertTrue(drained.isEmpty());
    }

    @Test
    void markDirtyRejectsNullTileKey() {
        assertThrows(IllegalArgumentException.class, () -> tracker.markDirty(null, 10L, 1L));
    }

    @Test
    void markDirtyRejectsNonPositiveEventSeq() {
        assertThrows(IllegalArgumentException.class, () -> tracker.markDirty(tileKey(3, 5), 0L, 1L));
    }

    @Test
    void markDirtyRejectsNonPositiveTileVersion() {
        assertThrows(IllegalArgumentException.class, () -> tracker.markDirty(tileKey(3, 5), 10L, 0L));
    }

    private Map<TileKey, DirtyTile> drainAsMap() {
        return tracker.drainDirtyTiles()
                .stream()
                .collect(Collectors.toMap(DirtyTile::tileKey, Function.identity()));
    }

    private void assertDirtyTile(DirtyTile dirtyTile, TileKey tileKey, long eventSeq, long tileVersion) {
        assertAll(
                () -> assertEquals(tileKey, dirtyTile.tileKey()),
                () -> assertEquals(eventSeq, dirtyTile.latestEventSeq()),
                () -> assertEquals(tileVersion, dirtyTile.latestTileVersion())
        );
    }

    private TileKey tileKey(int tx, int ty) {
        return new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
    }
}
