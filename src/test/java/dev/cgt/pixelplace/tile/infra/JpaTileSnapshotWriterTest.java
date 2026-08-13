package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JpaTileSnapshotWriterTest {

    private static final TileKey KEY = new TileKey(0, 3, 5);

    private final TileJpaRepository repository = mock(TileJpaRepository.class);
    private final JpaTileSnapshotWriter writer = new JpaTileSnapshotWriter(repository);

    @Test
    void factoryMapsKeyRawBytesAndCapturedTileVersion() {
        byte[] pixels = pixels((byte) 7);
        FlushTileSnapshot snapshot = new FlushTileSnapshot(KEY, pixels, 42L);

        TileEntity entity = TileEntity.fromFlushSnapshot(snapshot);

        assertEquals(0, entity.getZ());
        assertEquals(3, entity.getTx());
        assertEquals(5, entity.getTy());
        assertArrayEquals(pixels, entity.getData());
        assertEquals(42L, entity.getTileVersion());
    }

    @Test
    void entityStorageRemainsImmutableAfterSourceAndAccessorArrayChanges() {
        byte[] source = pixels((byte) 7);
        FlushTileSnapshot snapshot = new FlushTileSnapshot(KEY, source, 4L);
        TileEntity entity = TileEntity.fromFlushSnapshot(snapshot);

        source[0] = 99;
        byte[] snapshotAccessor = snapshot.pixels();
        snapshotAccessor[0] = 98;
        byte[] entityAccessor = entity.getData();
        entityAccessor[0] = 97;

        assertEquals((byte) 7, entity.getData()[0]);
    }

    @Test
    void factoryRevalidatesRawLengthAndTileVersion() {
        FlushTileSnapshot invalidLength = mock(FlushTileSnapshot.class);
        when(invalidLength.tileKey()).thenReturn(KEY);
        when(invalidLength.pixels()).thenReturn(new byte[BoardConstants.TILE_PIXEL_COUNT - 1]);
        when(invalidLength.tileVersion()).thenReturn(1L);
        FlushTileSnapshot invalidVersion = mock(FlushTileSnapshot.class);
        when(invalidVersion.tileKey()).thenReturn(KEY);
        when(invalidVersion.pixels()).thenReturn(new byte[BoardConstants.TILE_PIXEL_COUNT]);
        when(invalidVersion.tileVersion()).thenReturn(-1L);

        assertThrows(NullPointerException.class, () -> TileEntity.fromFlushSnapshot(null));
        assertThrows(IllegalArgumentException.class, () -> TileEntity.fromFlushSnapshot(invalidLength));
        assertThrows(IllegalArgumentException.class, () -> TileEntity.fromFlushSnapshot(invalidVersion));
    }

    @Test
    void writeAllUsesOneSaveAllThenExplicitFlush() {
        List<FlushTileSnapshot> snapshots = List.of(
                new FlushTileSnapshot(KEY, pixels((byte) 1), 3L),
                new FlushTileSnapshot(new TileKey(0, 4, 5), pixels((byte) 2), 9L)
        );

        writer.writeAll(snapshots);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<TileEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        InOrder order = inOrder(repository);
        order.verify(repository).saveAll(captor.capture());
        order.verify(repository).flush();
        List<TileEntity> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        assertEquals(List.of(3L, 9L), saved.stream().map(TileEntity::getTileVersion).toList());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(TileEntity.class));
    }

    @Test
    void writerHasNoLiveBoardOrEventSeqDependency() {
        List<String> fieldTypes = Arrays.stream(JpaTileSnapshotWriter.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();

        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("InMemoryTileBoard")));
        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("EventSeq")));
    }

    @Test
    void nullEmptyAndNullElementInputsAreRejectedBeforeRepositoryAccess() {
        assertThrows(NullPointerException.class, () -> writer.writeAll(null));
        assertThrows(IllegalArgumentException.class, () -> writer.writeAll(List.of()));
        List<FlushTileSnapshot> invalid = new ArrayList<>();
        invalid.add(new FlushTileSnapshot(KEY, pixels((byte) 0), 0L));
        invalid.add(null);
        assertThrows(NullPointerException.class, () -> writer.writeAll(invalid));

        verifyNoInteractions(repository);
    }

    private byte[] pixels(byte value) {
        byte[] pixels = new byte[BoardConstants.TILE_PIXEL_COUNT];
        Arrays.fill(pixels, value);
        return pixels;
    }
}
