package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlushPlanTest {

    private static final TileKey KEY_A = new TileKey(0, 0, 0);
    private static final TileKey KEY_B = new TileKey(0, 1, 0);
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 4, 3, 6, 0);

    @Test
    void noOpFactoryFixesTargetAndAllPayloadsToEmpty() {
        FlushPlan plan = FlushPlan.noOp(10L, DbBootstrapState.BOOTSTRAP_PENDING);

        assertTrue(plan.noOp());
        assertEquals(10L, plan.expectedLastFlushedEventSeq());
        assertEquals(10L, plan.flushTargetEventSeq());
        assertTrue(plan.walRecords().isEmpty());
        assertTrue(plan.tileSnapshots().isEmpty());
        assertTrue(plan.drainedDirtyTiles().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> plan.walRecords().add(record(11L, KEY_A)));
    }

    @Test
    void negativeExpectedCheckpointIsRejectedForEveryPlanType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.noOp(-1L, DbBootstrapState.INITIALIZED)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        -1L,
                        1L,
                        List.of(record(1L, KEY_A)),
                        List.of(snapshot(KEY_A, 1L)),
                        List.of(),
                        DbBootstrapState.INITIALIZED
                )
        );
    }

    @Test
    void nonNoOpTargetMustBeGreaterThanExpectedCheckpoint() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        10L,
                        10L,
                        List.of(record(10L, KEY_A)),
                        List.of(snapshot(KEY_A, 1L)),
                        List.of(),
                        DbBootstrapState.INITIALIZED
                )
        );
    }

    @Test
    void strictlyIncreasingEventSeqGapsAreAccepted() {
        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                10L,
                List.of(record(3L, KEY_A), record(7L, KEY_A), record(10L, KEY_A)),
                List.of(snapshot(KEY_A, 3L)),
                List.of(),
                DbBootstrapState.INITIALIZED
        );

        assertEquals(List.of(3L, 7L, 10L), plan.walRecords().stream().map(WalRecord::eventSeq).toList());
    }

    @Test
    void duplicateAndReverseEventSeqAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        3L,
                        List.of(record(3L, KEY_A), record(3L, KEY_A)),
                        List.of(snapshot(KEY_A, 2L)),
                        List.of(),
                        DbBootstrapState.INITIALIZED
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        3L,
                        List.of(record(7L, KEY_A), record(3L, KEY_A)),
                        List.of(snapshot(KEY_A, 2L)),
                        List.of(),
                        DbBootstrapState.INITIALIZED
                )
        );
    }

    @Test
    void lastRecordMustMatchTarget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        10L,
                        List.of(record(3L, KEY_A)),
                        List.of(snapshot(KEY_A, 1L)),
                        List.of(),
                        DbBootstrapState.INITIALIZED
                )
        );
    }

    @Test
    void duplicateSnapshotKeysAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        10L,
                        List.of(record(10L, KEY_A)),
                        List.of(snapshot(KEY_A, 1L), snapshot(KEY_A, 2L)),
                        List.of(),
                        DbBootstrapState.INITIALIZED
                )
        );
    }

    @Test
    // 최초 non-no-op persistence가 partial tiles 상태를 만들지 않도록 canonical 1,024 snapshot 강제
    void bootstrapPendingPlanRequiresAllCanonicalSnapshots() {
        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                1L,
                List.of(record(1L, KEY_A)),
                canonicalSnapshots(),
                List.of(),
                DbBootstrapState.BOOTSTRAP_PENDING
        );

        assertEquals(BoardConstants.Z0_TILE_COUNT, plan.tileSnapshots().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        1L,
                        List.of(record(1L, KEY_A)),
                        List.of(snapshot(KEY_A, 1L)),
                        List.of(),
                        DbBootstrapState.BOOTSTRAP_PENDING
                )
        );
    }

    @Test
    void initializedSnapshotsExactlyMatchWalAffectedAndDrainedDirtyUnion() {
        DirtyTile dirtyB = new DirtyTile(KEY_B, 9L, 4L);
        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                10L,
                List.of(record(10L, KEY_A)),
                List.of(snapshot(KEY_B, 4L), snapshot(KEY_A, 1L)),
                List.of(dirtyB),
                DbBootstrapState.INITIALIZED
        );

        assertEquals(List.of(KEY_A, KEY_B), plan.tileSnapshots().stream().map(FlushTileSnapshot::tileKey).toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        10L,
                        List.of(record(10L, KEY_A)),
                        List.of(snapshot(KEY_A, 1L)),
                        List.of(dirtyB),
                        DbBootstrapState.INITIALIZED
                )
        );
    }

    @Test
    void inconsistentBootstrapStateIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FlushPlan.noOp(0L, DbBootstrapState.INCONSISTENT));
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        1L,
                        List.of(record(1L, KEY_A)),
                        List.of(snapshot(KEY_A, 1L)),
                        List.of(),
                        DbBootstrapState.INCONSISTENT
                )
        );
    }

    @Test
    void inputListsAreDefensivelyCopiedAndPlanListsAreImmutable() {
        List<WalRecord> records = new ArrayList<>(List.of(record(10L, KEY_A)));
        List<FlushTileSnapshot> snapshots = new ArrayList<>(
                List.of(snapshot(KEY_A, 1L), snapshot(KEY_B, 2L))
        );
        List<DirtyTile> dirtyTiles = new ArrayList<>(List.of(new DirtyTile(KEY_B, 9L, 2L)));

        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                10L,
                records,
                snapshots,
                dirtyTiles,
                DbBootstrapState.INITIALIZED
        );
        records.clear();
        snapshots.clear();
        dirtyTiles.clear();

        assertEquals(1, plan.walRecords().size());
        assertEquals(2, plan.tileSnapshots().size());
        assertEquals(1, plan.drainedDirtyTiles().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.tileSnapshots().clear());
    }

    @Test
    void snapshotBytesRemainImmutableThroughPlanAccessor() {
        byte[] source = new byte[BoardConstants.TILE_PIXEL_COUNT];
        source[0] = 7;
        FlushTileSnapshot snapshot = new FlushTileSnapshot(KEY_A, source, 1L);
        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                1L,
                List.of(record(1L, KEY_A)),
                List.of(snapshot),
                List.of(),
                DbBootstrapState.INITIALIZED
        );

        byte[] exposed = plan.tileSnapshots().get(0).pixels();
        exposed[0] = 99;
        source[0] = 88;

        assertEquals((byte) 7, plan.tileSnapshots().get(0).pixels()[0]);
    }

    @Test
    void expectedCheckpointAndTargetRemainSeparateValues() {
        FlushPlan plan = FlushPlan.nonNoOp(
                3L,
                10L,
                List.of(record(7L, KEY_A), record(10L, KEY_A)),
                List.of(snapshot(KEY_A, 2L)),
                List.of(),
                DbBootstrapState.INITIALIZED
        );

        assertEquals(3L, plan.expectedLastFlushedEventSeq());
        assertEquals(10L, plan.flushTargetEventSeq());
        assertFalse(plan.noOp());
    }

    @Test
    void dirtyEventSeqAndSnapshotTileVersionRemainDistinct() {
        DirtyTile dirty = new DirtyTile(KEY_A, 10L, 2L);
        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                10L,
                List.of(record(10L, KEY_A)),
                List.of(snapshot(KEY_A, 2L)),
                List.of(dirty),
                DbBootstrapState.INITIALIZED
        );

        assertEquals(10L, plan.drainedDirtyTiles().get(0).latestEventSeq());
        assertEquals(2L, plan.drainedDirtyTiles().get(0).latestTileVersion());
        assertEquals(2L, plan.tileSnapshots().get(0).tileVersion());
    }

    @Test
    void createdAtNanosecondsArePreserved() {
        LocalDateTime precise = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_456_789);
        WalRecord record = record(1L, KEY_A, precise);

        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                1L,
                List.of(record),
                List.of(snapshot(KEY_A, 1L)),
                List.of(),
                DbBootstrapState.INITIALIZED
        );

        assertEquals(precise, plan.walRecords().get(0).createdAt());
    }

    @Test
    void eventOrderUsesOnlyEventSeqRegardlessOfCreatedAtOrder() {
        LocalDateTime later = TIME.plusHours(1);
        LocalDateTime earlier = TIME.minusHours(1);
        FlushPlan plan = FlushPlan.nonNoOp(
                0L,
                3L,
                List.of(
                        record(1L, KEY_A, later),
                        record(2L, KEY_A, later),
                        record(3L, KEY_A, earlier)
                ),
                List.of(snapshot(KEY_A, 3L)),
                List.of(),
                DbBootstrapState.INITIALIZED
        );

        assertEquals(List.of(1L, 2L, 3L), plan.walRecords().stream().map(WalRecord::eventSeq).toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> FlushPlan.nonNoOp(
                        0L,
                        2L,
                        List.of(record(2L, KEY_A, earlier), record(2L, KEY_A, later)),
                        List.of(snapshot(KEY_A, 2L)),
                        List.of(),
                        DbBootstrapState.INITIALIZED
                )
        );
    }

    private FlushTileSnapshot snapshot(TileKey key, long tileVersion) {
        return new FlushTileSnapshot(key, new byte[BoardConstants.TILE_PIXEL_COUNT], tileVersion);
    }

    private WalRecord record(long eventSeq, TileKey key) {
        return record(eventSeq, key, TIME);
    }

    private WalRecord record(long eventSeq, TileKey key, LocalDateTime createdAt) {
        return new WalRecord(
                eventSeq,
                7L,
                key.z(),
                key.tx(),
                key.ty(),
                key.tx() * BoardConstants.TILE_SIZE,
                key.ty() * BoardConstants.TILE_SIZE,
                17,
                createdAt
        );
    }

    private List<FlushTileSnapshot> canonicalSnapshots() {
        byte[] pixels = new byte[BoardConstants.TILE_PIXEL_COUNT];
        return new CanonicalZ0TileKeys().orderedKeys().stream()
                .map(key -> new FlushTileSnapshot(key, pixels, 0L))
                .toList();
    }
}
