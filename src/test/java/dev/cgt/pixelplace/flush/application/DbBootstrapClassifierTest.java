package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbBootstrapClassifierTest {

    private final CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();
    private final DbBootstrapClassifier classifier = new DbBootstrapClassifier(canonicalKeys);

    @Test
    void checkpointZeroAndNoTilesIsBootstrapPending() {
        assertEquals(DbBootstrapState.BOOTSTRAP_PENDING, classifier.classify(0L, List.of()));
    }

    @Test
    void checkpointZeroAndCanonicalTilesIsInitialized() {
        assertEquals(DbBootstrapState.INITIALIZED, classifier.classify(0L, canonicalKeys.orderedKeys()));
    }

    @Test
    void positiveCheckpointAndCanonicalTilesIsInitialized() {
        assertEquals(DbBootstrapState.INITIALIZED, classifier.classify(10L, canonicalKeys.orderedKeys()));
    }

    @Test
    void positiveCheckpointAndNoTilesIsInconsistent() {
        assertEquals(DbBootstrapState.INCONSISTENT, classifier.classify(1L, List.of()));
    }

    @Test
    void partialTileCollectionsAreInconsistent() {
        assertEquals(
                DbBootstrapState.INCONSISTENT,
                classifier.classify(0L, List.of(canonicalKeys.orderedKeys().get(0)))
        );
        assertEquals(
                DbBootstrapState.INCONSISTENT,
                classifier.classify(0L, canonicalKeys.orderedKeys().subList(0, BoardConstants.Z0_TILE_COUNT - 1))
        );
    }

    @Test
    void missingCanonicalKeyIsInconsistent() {
        List<TileKey> keys = new ArrayList<>(canonicalKeys.orderedKeys());
        keys.remove(keys.size() - 1);

        assertEquals(DbBootstrapState.INCONSISTENT, classifier.classify(0L, keys));
    }

    @Test
    void outOfRangeReplacementIsInconsistent() {
        List<TileKey> keys = new ArrayList<>(canonicalKeys.orderedKeys());
        keys.set(keys.size() - 1, new TileKey(0, BoardConstants.Z0_TILE_COUNT_PER_AXIS, 31));

        assertEquals(DbBootstrapState.INCONSISTENT, classifier.classify(0L, keys));
    }

    @Test
    void nonZ0ReplacementIsInconsistent() {
        List<TileKey> keys = new ArrayList<>(canonicalKeys.orderedKeys());
        keys.set(keys.size() - 1, new TileKey(1, 31, 31));

        assertEquals(DbBootstrapState.INCONSISTENT, classifier.classify(0L, keys));
    }

    @Test
    void duplicateKeyIsInconsistent() {
        List<TileKey> keys = new ArrayList<>(canonicalKeys.orderedKeys());
        keys.set(keys.size() - 1, keys.get(0));

        assertEquals(DbBootstrapState.INCONSISTENT, classifier.classify(0L, keys));
    }

    @Test
    void extraKeyIsInconsistent() {
        List<TileKey> keys = new ArrayList<>(canonicalKeys.orderedKeys());
        keys.add(new TileKey(0, 32, 32));

        assertEquals(DbBootstrapState.INCONSISTENT, classifier.classify(0L, keys));
    }

    @Test
    void negativeCheckpointIsInconsistent() {
        assertEquals(
                DbBootstrapState.INCONSISTENT,
                classifier.classify(-1L, canonicalKeys.orderedKeys())
        );
    }

    @Test
    void canonicalKeyListHasExactShapeOrderAndIsImmutable() {
        List<TileKey> keys = canonicalKeys.orderedKeys();

        assertEquals(BoardConstants.Z0_TILE_COUNT, keys.size());
        assertEquals(new TileKey(0, 0, 0), keys.get(0));
        assertEquals(new TileKey(0, 31, 0), keys.get(31));
        assertEquals(new TileKey(0, 0, 1), keys.get(32));
        assertEquals(new TileKey(0, 31, 31), keys.get(keys.size() - 1));
        assertTrue(canonicalKeys.contains(new TileKey(0, 31, 31)));
        assertFalse(canonicalKeys.contains(new TileKey(1, 0, 0)));
        assertSame(keys, new CanonicalZ0TileKeys().orderedKeys());
        assertThrows(UnsupportedOperationException.class, () -> keys.add(new TileKey(0, 0, 0)));
    }
}
