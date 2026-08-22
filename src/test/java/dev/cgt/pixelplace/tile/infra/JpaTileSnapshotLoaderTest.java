package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/* 전체 key metadata와 z=0 bytes를 보정 없이 capture transaction에 전달하는 JPA loader 검증 */
class JpaTileSnapshotLoaderTest {

    @Test
    void loadsAllKeysBeforeZ0EntitiesAndPreservesNonZ0Metadata() {
        TileJpaRepository repository = mock(TileJpaRepository.class);
        TileKey z0Key = new TileKey(0, 0, 0);
        TileKey nonZ0Key = new TileKey(1, 9, 9);
        List<TileKey> keys = new ArrayList<>(List.of(z0Key, nonZ0Key));
        byte[] pixels = new byte[BoardConstants.TILE_PIXEL_COUNT];
        pixels[0] = 17;
        TileEntity entity = TileEntity.fromFlushSnapshot(new FlushTileSnapshot(z0Key, pixels, 4L));
        when(repository.findAllTileKeysOrderByZTyTx()).thenReturn(keys);
        when(repository.findAllByZOrderByTyAscTxAsc(BoardConstants.Z0_LEVEL))
                .thenReturn(List.of(entity));

        TileLoadResult result = new JpaTileSnapshotLoader(repository).loadZ0Tiles();

        InOrder order = inOrder(repository);
        order.verify(repository).findAllTileKeysOrderByZTyTx();
        order.verify(repository).findAllByZOrderByTyAscTxAsc(BoardConstants.Z0_LEVEL);
        assertEquals(List.of(z0Key, nonZ0Key), result.databaseTileKeys());
        assertEquals(z0Key, result.snapshots().get(0).key());
        assertArrayEquals(pixels, result.snapshots().get(0).pixels());
        assertEquals(4L, result.snapshots().get(0).tileVersion());

        keys.clear();
        assertEquals(List.of(z0Key, nonZ0Key), result.databaseTileKeys());
        assertThrows(UnsupportedOperationException.class, () -> result.snapshots().clear());
    }

    @Test
    void emptyRepositoryResultStaysEmptyWithoutWhiteTileSynthesis() {
        TileJpaRepository repository = mock(TileJpaRepository.class);
        when(repository.findAllTileKeysOrderByZTyTx()).thenReturn(List.of());
        when(repository.findAllByZOrderByTyAscTxAsc(BoardConstants.Z0_LEVEL)).thenReturn(List.of());

        TileLoadResult result = new JpaTileSnapshotLoader(repository).loadZ0Tiles();

        assertEquals(List.of(), result.databaseTileKeys());
        assertEquals(List.of(), result.snapshots());
    }

    @Test
    void loadDeclaresMatchingReadOnlyRepeatableReadRequiredTransaction() throws Exception {
        Method method = JpaTileSnapshotLoader.class.getMethod("loadZ0Tiles");
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertEquals(true, transactional.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transactional.isolation());
        assertEquals(Propagation.REQUIRED, transactional.propagation());
    }
}
