package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* classifier 입력 shape를 정상화하지 않고 immutable하게 보존하는 recovery loader result 계약 검증 */
class TileLoadResultTest {

    @Test
    void emptyKeysAndSnapshotsAreAllowedAsBootstrapInput() {
        TileLoadResult result = TileLoadResult.allMissingResult();

        assertTrue(result.databaseTileKeys().isEmpty());
        assertTrue(result.snapshots().isEmpty());
        assertTrue(result.allMissing());
    }

    @Test
    void keyAndSnapshotListsAreDefensivelyCopiedAndUnmodifiable() {
        TileKey key = new TileKey(0, 0, 0);
        TileStateSnapshot snapshot = snapshot(key);
        List<TileKey> keys = new ArrayList<>(List.of(key));
        List<TileStateSnapshot> snapshots = new ArrayList<>(List.of(snapshot));

        TileLoadResult result = new TileLoadResult(keys, snapshots);
        keys.clear();
        snapshots.clear();

        assertEquals(List.of(key), result.databaseTileKeys());
        assertEquals(List.of(snapshot), result.snapshots());
        assertThrows(UnsupportedOperationException.class, () -> result.databaseTileKeys().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.snapshots().clear());
    }

    @Test
    void nullCollectionsAndElementsAreRejected() {
        assertThrows(NullPointerException.class, () -> new TileLoadResult(null, List.of()));
        assertThrows(NullPointerException.class, () -> new TileLoadResult(List.of(), null));
        assertThrows(
                NullPointerException.class,
                () -> new TileLoadResult(Arrays.asList((TileKey) null), List.of())
        );
        assertThrows(
                NullPointerException.class,
                () -> new TileLoadResult(List.of(), Arrays.asList((TileStateSnapshot) null))
        );
    }

    @Test
    void partialInvalidAndDuplicateShapesRemainVisibleToClassifier() {
        TileKey key = new TileKey(0, 0, 0);
        TileKey extra = new TileKey(1, 40, 40);
        TileLoadResult result = new TileLoadResult(
                List.of(key, key, extra),
                List.of(snapshot(key))
        );

        assertEquals(List.of(key, key, extra), result.databaseTileKeys());
        assertEquals(1, result.snapshots().size());
        assertFalse(result.allMissing());
    }

    @Test
    void allMissingIsDerivedOnlyFromDatabaseKeys() {
        TileLoadResult inconsistent = new TileLoadResult(
                List.of(),
                List.of(snapshot(new TileKey(0, 0, 0)))
        );

        assertTrue(inconsistent.allMissing());
    }

    private TileStateSnapshot snapshot(TileKey key) {
        return new TileStateSnapshot(key, new byte[256 * 256], 0L);
    }
}
