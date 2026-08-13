package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingAmbiguousFlushTest {

    private static final TileKey KEY = new TileKey(0, 0, 0);

    @Test
    void fromPlanPreservesOnlyExpectedTargetModeAndActualDrainedDirty() {
        DirtyTile dirty = new DirtyTile(KEY, 1L, 1L);
        FlushPlan plan = plan(List.of(dirty));

        PendingAmbiguousFlush pending = PendingAmbiguousFlush.from(plan);

        assertEquals(0L, pending.expectedLastFlushedEventSeq());
        assertEquals(1L, pending.flushTargetEventSeq());
        assertEquals(DbBootstrapState.INITIALIZED, pending.bootstrapState());
        assertEquals(List.of(dirty), pending.drainedDirtyTiles());
        List<String> fieldTypes = Arrays.stream(PendingAmbiguousFlush.class.getDeclaredFields())
                .map(field -> field.getGenericType().getTypeName())
                .toList();
        assertFalse(fieldTypes.stream().anyMatch(type -> type.contains("FlushPlan")));
        assertFalse(fieldTypes.stream().anyMatch(type -> type.contains("WalRecord")));
        assertFalse(fieldTypes.stream().anyMatch(type -> type.contains("FlushTileSnapshot")));
    }

    @Test
    void dirtyInputIsDefensivelyCopiedAndReturnedListIsImmutable() {
        DirtyTile dirty = new DirtyTile(KEY, 1L, 1L);
        List<DirtyTile> source = new ArrayList<>(List.of(dirty));
        PendingAmbiguousFlush pending = new PendingAmbiguousFlush(
                0L,
                1L,
                DbBootstrapState.INITIALIZED,
                source
        );

        source.clear();

        assertEquals(List.of(dirty), pending.drainedDirtyTiles());
        assertThrows(UnsupportedOperationException.class, () -> pending.drainedDirtyTiles().clear());
    }

    @Test
    void invalidExpectedTargetStateAndDirtyInputsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingAmbiguousFlush(-1L, 1L, DbBootstrapState.INITIALIZED, List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingAmbiguousFlush(1L, 1L, DbBootstrapState.INITIALIZED, List.of())
        );
        assertThrows(
                NullPointerException.class,
                () -> new PendingAmbiguousFlush(0L, 1L, null, List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingAmbiguousFlush(0L, 1L, DbBootstrapState.INCONSISTENT, List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingAmbiguousFlush(1L, 2L, DbBootstrapState.BOOTSTRAP_PENDING, List.of())
        );
        assertThrows(
                NullPointerException.class,
                () -> new PendingAmbiguousFlush(0L, 1L, DbBootstrapState.INITIALIZED, null)
        );
        List<DirtyTile> nullElement = new ArrayList<>();
        nullElement.add(null);
        assertThrows(
                NullPointerException.class,
                () -> new PendingAmbiguousFlush(0L, 1L, DbBootstrapState.INITIALIZED, nullElement)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PendingAmbiguousFlush.from(FlushPlan.noOp(0L, DbBootstrapState.INITIALIZED))
        );
    }

    private FlushPlan plan(List<DirtyTile> dirtyTiles) {
        return FlushPlan.nonNoOp(
                0L,
                1L,
                List.of(new WalRecord(
                        1L,
                        7L,
                        0,
                        0,
                        0,
                        0,
                        0,
                        17,
                        LocalDateTime.of(2026, 4, 3, 6, 0)
                )),
                List.of(new FlushTileSnapshot(
                        KEY,
                        new byte[BoardConstants.TILE_PIXEL_COUNT],
                        1L
                )),
                dirtyTiles,
                DbBootstrapState.INITIALIZED
        );
    }
}
