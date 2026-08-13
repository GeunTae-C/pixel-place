package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class JpaPixelEventWriterTest {

    private final PixelEventJpaRepository repository = mock(PixelEventJpaRepository.class);
    private final JpaPixelEventWriter writer = new JpaPixelEventWriter(repository);

    @Test
    void appendAllUsesOneSaveAllAndExplicitFlushInWalIterationOrder() {
        List<WalRecord> records = List.of(
                record(3L, LocalDateTime.of(2026, 4, 3, 6, 0, 0, 900_000_000)),
                record(7L, LocalDateTime.of(2026, 4, 3, 5, 0, 0, 100_000_000)),
                record(10L, LocalDateTime.of(2026, 4, 3, 5, 0, 0, 100_000_000))
        );

        writer.appendAll(records);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PixelEventEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        InOrder order = inOrder(repository);
        order.verify(repository).saveAll(captor.capture());
        order.verify(repository).flush();
        List<PixelEventEntity> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        assertEquals(List.of(3L, 7L, 10L), saved.stream().map(PixelEventEntity::getEventSeq).toList());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(PixelEventEntity.class));
    }

    @Test
    void mappingLeavesOriginalNanosecondsAndUsesDistinctEventSeqForSameMillis() {
        LocalDateTime firstTime = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_456_789);
        LocalDateTime secondTime = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_999_999);
        List<WalRecord> records = List.of(record(1L, firstTime), record(2L, secondTime));

        writer.appendAll(records);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PixelEventEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        List<PixelEventEntity> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        assertEquals(List.of(1L, 2L), saved.stream().map(PixelEventEntity::getEventSeq).toList());
        assertEquals(List.of(123_000_000, 123_000_000), saved.stream()
                .map(entity -> entity.getCreatedAt().getNano())
                .toList());
        assertEquals(firstTime, records.get(0).createdAt());
        assertEquals(secondTime, records.get(1).createdAt());
    }

    @Test
    void nullEmptyAndNullElementInputsAreRejectedBeforeRepositoryAccess() {
        assertThrows(NullPointerException.class, () -> writer.appendAll(null));
        assertThrows(IllegalArgumentException.class, () -> writer.appendAll(List.of()));
        List<WalRecord> invalid = new ArrayList<>();
        invalid.add(record(1L, LocalDateTime.now()));
        invalid.add(null);
        assertThrows(NullPointerException.class, () -> writer.appendAll(invalid));

        verifyNoInteractions(repository);
    }

    private WalRecord record(long eventSeq, LocalDateTime createdAt) {
        return new WalRecord(eventSeq, 7L, 0, 3, 5, 768, 1280, 17, createdAt);
    }
}
