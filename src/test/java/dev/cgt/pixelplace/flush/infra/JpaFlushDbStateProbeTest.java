package dev.cgt.pixelplace.flush.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.flush.application.DbBootstrapState;
import dev.cgt.pixelplace.flush.application.FlushDbState;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaFlushDbStateProbeTest {

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final CheckpointFence checkpointFence = mock(CheckpointFence.class);
    private final TileMetadataReader metadataReader = mock(TileMetadataReader.class);
    private final DbBootstrapClassifier classifier = mock(DbBootstrapClassifier.class);

    @BeforeEach
    void stubTransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(status);
    }

    @Test
    void probeUsesRequiresNewWritableTransactionAndLocksBeforeMetadata() {
        List<TileKey> keys = List.of(new TileKey(0, 0, 0));
        when(checkpointFence.lockMainCheckpoint()).thenReturn(7L);
        when(metadataReader.readAllTileKeys()).thenReturn(keys);
        when(classifier.classify(7L, keys)).thenReturn(DbBootstrapState.INITIALIZED);
        JpaFlushDbStateProbe probe = probe();

        FlushDbState state = probe.probe();

        assertEquals(new FlushDbState(7L, DbBootstrapState.INITIALIZED), state);
        InOrder order = inOrder(checkpointFence, metadataReader, classifier, transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(checkpointFence).lockMainCheckpoint();
        order.verify(metadataReader).readAllTileKeys();
        order.verify(classifier).classify(7L, keys);
        order.verify(transactionManager).commit(status);

        ArgumentCaptor<TransactionDefinition> captor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(captor.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, captor.getValue().getPropagationBehavior());
        assertEquals(false, captor.getValue().isReadOnly());
        assertEquals(JpaFlushDbStateProbe.TRANSACTION_NAME, captor.getValue().getName());
    }

    @Test
    void inconsistentStateRollsBackAndFails() {
        List<TileKey> keys = List.of(new TileKey(0, 0, 0));
        when(checkpointFence.lockMainCheckpoint()).thenReturn(0L);
        when(metadataReader.readAllTileKeys()).thenReturn(keys);
        when(classifier.classify(0L, keys)).thenReturn(DbBootstrapState.INCONSISTENT);

        assertThrows(IllegalStateException.class, () -> probe().probe());

        verify(transactionManager).rollback(status);
    }

    @Test
    void lockFailureIsPropagatedAndTransactionRollsBack() {
        IllegalStateException failure = new IllegalStateException("lock failed");
        when(checkpointFence.lockMainCheckpoint()).thenThrow(failure);

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> probe().probe());

        assertSame(failure, actual);
        verify(transactionManager).rollback(status);
    }

    private JpaFlushDbStateProbe probe() {
        return new JpaFlushDbStateProbe(
                transactionManager,
                checkpointFence,
                metadataReader,
                classifier
        );
    }
}
