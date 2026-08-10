package dev.cgt.pixelplace.tile.infra;

import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaTileMetadataReaderTest {

    private final TileJpaRepository repository = mock(TileJpaRepository.class);
    private final JpaTileMetadataReader reader = new JpaTileMetadataReader(repository);

    @Test
    // bootstrap metadata 조회에서 TileEntity/BLOB 전체 로드 API 진입 금지
    void readAllTileKeysUsesKeyProjectionOnly() {
        List<TileKey> expected = List.of(new TileKey(0, 0, 0), new TileKey(1, 3, 4));
        when(repository.findAllTileKeysOrderByZTyTx()).thenReturn(expected);

        List<TileKey> actual = reader.readAllTileKeys();

        assertEquals(expected, actual);
        verify(repository).findAllTileKeysOrderByZTyTx();
        verify(repository, never()).findAll();
        verify(repository, never()).findAllByZOrderByTyAscTxAsc(0);
    }

    @Test
    void returnedListIsDefensivelyCopiedAndImmutable() {
        List<TileKey> repositoryResult = new ArrayList<>(List.of(new TileKey(0, 0, 0)));
        when(repository.findAllTileKeysOrderByZTyTx()).thenReturn(repositoryResult);

        List<TileKey> actual = reader.readAllTileKeys();
        repositoryResult.clear();

        assertEquals(List.of(new TileKey(0, 0, 0)), actual);
        assertThrows(UnsupportedOperationException.class, () -> actual.add(new TileKey(0, 1, 0)));
    }

    @Test
    void emptyDatabaseReturnsImmutableEmptyList() {
        when(repository.findAllTileKeysOrderByZTyTx()).thenReturn(List.of());

        List<TileKey> actual = reader.readAllTileKeys();

        assertEquals(List.of(), actual);
        assertThrows(UnsupportedOperationException.class, () -> actual.add(new TileKey(0, 0, 0)));
    }
}
