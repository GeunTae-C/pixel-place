package dev.cgt.pixelplace.pixel.infra;

import dev.cgt.pixelplace.wal.domain.WalRecord;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelEventEntityTest {

    @Test
    void assignedEventSeqIsIdWithoutGeneratedValueAndRepositoryUsesLongId() throws Exception {
        var idField = PixelEventEntity.class.getDeclaredField("eventSeq");
        ParameterizedType repositoryType = (ParameterizedType) PixelEventJpaRepository.class
                .getGenericInterfaces()[0];

        assertTrue(idField.isAnnotationPresent(Id.class));
        assertNull(idField.getAnnotation(GeneratedValue.class));
        assertEquals(Long.class, repositoryType.getActualTypeArguments()[1]);
        assertTrue(Persistable.class.isAssignableFrom(PixelEventEntity.class));
    }

    @Test
    void factoryPreservesAllWalValuesAndTruncatesOnlyCreatedAtToMillis() {
        LocalDateTime precise = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_456_789);
        WalRecord record = record(3L, precise);

        PixelEventEntity entity = PixelEventEntity.fromWalRecord(record);

        assertEquals(3L, entity.getEventSeq());
        assertEquals(7L, entity.getUserId());
        assertEquals(0, entity.getZ());
        assertEquals(3, entity.getTx());
        assertEquals(5, entity.getTy());
        assertEquals(768, entity.getX());
        assertEquals(1280, entity.getY());
        assertEquals(17, entity.getColor());
        assertEquals(123_000_000, entity.getCreatedAt().getNano());
        assertEquals(precise, record.createdAt());
    }

    @Test
    void millisecondPrecisionCreatedAtRemainsUnchangedAndNeverRoundsUp() {
        LocalDateTime milliseconds = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 999_000_000);

        PixelEventEntity exact = PixelEventEntity.fromWalRecord(record(1L, milliseconds));
        PixelEventEntity truncated = PixelEventEntity.fromWalRecord(
                record(2L, LocalDateTime.of(2026, 4, 3, 6, 0, 0, 999_999_999))
        );

        assertEquals(milliseconds, exact.getCreatedAt());
        assertEquals(milliseconds, truncated.getCreatedAt());
    }

    @Test
    void newStateChangesOnlyAfterPersistOrLoadCallback() {
        PixelEventEntity entity = PixelEventEntity.fromWalRecord(record(1L, LocalDateTime.now()));

        assertTrue(entity.isNew());
        assertEquals(1L, entity.getId());
        entity.markNotNew();
        assertFalse(entity.isNew());
    }

    @Test
    void invalidWalDomainValuesAreRejected() {
        LocalDateTime time = LocalDateTime.now();

        assertThrows(NullPointerException.class, () -> PixelEventEntity.fromWalRecord(null));
        assertThrows(IllegalArgumentException.class, () -> PixelEventEntity.fromWalRecord(
                new WalRecord(0L, 7L, 0, 3, 5, 768, 1280, 17, time)
        ));
        assertThrows(IllegalArgumentException.class, () -> PixelEventEntity.fromWalRecord(
                new WalRecord(1L, 0L, 0, 3, 5, 768, 1280, 17, time)
        ));
        assertThrows(IllegalArgumentException.class, () -> PixelEventEntity.fromWalRecord(
                new WalRecord(1L, 7L, 1, 3, 5, 768, 1280, 17, time)
        ));
        assertThrows(IllegalArgumentException.class, () -> PixelEventEntity.fromWalRecord(
                new WalRecord(1L, 7L, 0, 4, 5, 768, 1280, 17, time)
        ));
        assertThrows(IllegalArgumentException.class, () -> PixelEventEntity.fromWalRecord(
                new WalRecord(1L, 7L, 0, 3, 5, 768, 1280, 256, time)
        ));
        assertThrows(NullPointerException.class, () -> PixelEventEntity.fromWalRecord(
                new WalRecord(1L, 7L, 0, 3, 5, 768, 1280, 17, null)
        ));
    }

    private WalRecord record(long eventSeq, LocalDateTime createdAt) {
        return new WalRecord(eventSeq, 7L, 0, 3, 5, 768, 1280, 17, createdAt);
    }
}
