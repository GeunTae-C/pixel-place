package dev.cgt.pixelplace.checkpoint.infra;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaCheckpointFenceTest {

    private final WalCheckpointJpaRepository repository = mock(WalCheckpointJpaRepository.class);
    private final JpaCheckpointFence fence = new JpaCheckpointFence(repository);

    @Test
    void lockMainCheckpointUsesPessimisticWriteQueryAndReturnsCurrentValue() throws Exception {
        WalCheckpointEntity entity = mock(WalCheckpointEntity.class);
        when(entity.getLastFlushedEventSeq()).thenReturn(17L);
        when(repository.lockMainCheckpoint()).thenReturn(Optional.of(entity));
        Method method = WalCheckpointJpaRepository.class.getMethod("lockMainCheckpoint");

        assertEquals(17L, fence.lockMainCheckpoint());
        assertEquals(LockModeType.PESSIMISTIC_WRITE, method.getAnnotation(Lock.class).value());
        verify(repository).lockMainCheckpoint();
    }

    @Test
    void missingMainRowFailsWithoutSeedOrUpsert() {
        when(repository.lockMainCheckpoint()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, fence::lockMainCheckpoint);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void advancePassesExactExpectedAndTargetAndAcceptsOnlyOneAffectedRow() throws Exception {
        when(repository.advanceMainCheckpoint(10L, 17L)).thenReturn(1);

        fence.advanceMainCheckpoint(10L, 17L);

        verify(repository).advanceMainCheckpoint(10L, 17L);
        Method method = WalCheckpointJpaRepository.class.getMethod(
                "advanceMainCheckpoint",
                long.class,
                long.class
        );
        Modifying modifying = method.getAnnotation(Modifying.class);
        assertEquals(true, modifying.flushAutomatically());
        assertEquals(true, modifying.clearAutomatically());
    }

    @Test
    void nonAdvancingTargetsAreRejectedBeforeRepositoryCall() {
        assertThrows(IllegalArgumentException.class, () -> fence.advanceMainCheckpoint(-1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> fence.advanceMainCheckpoint(10L, 10L));
        assertThrows(IllegalArgumentException.class, () -> fence.advanceMainCheckpoint(10L, 9L));

        verify(repository, never()).advanceMainCheckpoint(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void zeroAndMultipleAffectedRowsFailTransactionBoundary() {
        when(repository.advanceMainCheckpoint(10L, 17L)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> fence.advanceMainCheckpoint(10L, 17L));

        when(repository.advanceMainCheckpoint(10L, 17L)).thenReturn(2);
        assertThrows(IllegalStateException.class, () -> fence.advanceMainCheckpoint(10L, 17L));
    }
}
