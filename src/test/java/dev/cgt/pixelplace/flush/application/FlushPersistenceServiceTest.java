package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.pixel.application.PixelEventWriter;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.application.TileSnapshotWriter;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlushPersistenceServiceTest {

    private static final TileKey KEY = new TileKey(0, 0, 0);

    private final CheckpointFence checkpointFence = mock(CheckpointFence.class);
    private final TileMetadataReader tileMetadataReader = mock(TileMetadataReader.class);
    private final DbBootstrapClassifier classifier = mock(DbBootstrapClassifier.class);
    private final PixelEventWriter eventWriter = mock(PixelEventWriter.class);
    private final TileSnapshotWriter tileWriter = mock(TileSnapshotWriter.class);
    private final FlushPersistenceService service = new FlushPersistenceService(
            checkpointFence,
            tileMetadataReader,
            classifier,
            eventWriter,
            tileWriter
    );

    @BeforeEach
    void markTransactionActive() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransactionFixture() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.clear();
    }

    @Test
    void inactiveTransactionFailsBeforeEveryPersistenceDependency() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThrows(IllegalStateException.class, () -> service.persist(initializedPlan()));

        verifyNoInteractions(checkpointFence, tileMetadataReader, classifier, eventWriter, tileWriter);
    }

    @Test
    void nullAndNoOpPlansAreRejectedBeforeEveryDependency() {
        assertThrows(NullPointerException.class, () -> service.persist(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.persist(FlushPlan.noOp(0L, DbBootstrapState.INITIALIZED))
        );

        verifyNoInteractions(checkpointFence, tileMetadataReader, classifier, eventWriter, tileWriter);
    }

    @Test
    void checkpointLockIsFirstDbWorkAndSuccessfulOrderEndsWithAdvance() {
        FlushPlan plan = initializedPlan();
        stubInitialized(plan);

        service.persist(plan);

        InOrder order = inOrder(checkpointFence, tileMetadataReader, classifier, eventWriter, tileWriter);
        order.verify(checkpointFence).lockMainCheckpoint();
        order.verify(tileMetadataReader).readAllTileKeys();
        order.verify(classifier).classify(0L, canonicalKeys());
        order.verify(eventWriter).appendAll(plan.walRecords());
        order.verify(tileWriter).writeAll(plan.tileSnapshots());
        order.verify(checkpointFence).advanceMainCheckpoint(0L, 1L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void checkpointMismatchStopsBeforeMetadataWritersAndAdvance() {
        when(checkpointFence.lockMainCheckpoint()).thenReturn(2L);

        assertThrows(IllegalStateException.class, () -> service.persist(initializedPlan()));

        verifyNoInteractions(tileMetadataReader, classifier, eventWriter, tileWriter);
        verify(checkpointFence, never()).advanceMainCheckpoint(0L, 1L);
    }

    @Test
    void inconsistentDbStateStopsBeforeWriters() {
        FlushPlan plan = initializedPlan();
        when(checkpointFence.lockMainCheckpoint()).thenReturn(0L);
        when(tileMetadataReader.readAllTileKeys()).thenReturn(List.of(KEY));
        when(classifier.classify(0L, List.of(KEY))).thenReturn(DbBootstrapState.INCONSISTENT);

        assertThrows(IllegalStateException.class, () -> service.persist(plan));

        verifyNoInteractions(eventWriter, tileWriter);
        verify(checkpointFence, never()).advanceMainCheckpoint(0L, 1L);
    }

    @Test
    void changedBootstrapModeStopsBeforeWriters() {
        FlushPlan plan = initializedPlan();
        when(checkpointFence.lockMainCheckpoint()).thenReturn(0L);
        when(tileMetadataReader.readAllTileKeys()).thenReturn(List.of());
        when(classifier.classify(0L, List.of())).thenReturn(DbBootstrapState.BOOTSTRAP_PENDING);

        assertThrows(IllegalStateException.class, () -> service.persist(plan));

        verifyNoInteractions(eventWriter, tileWriter);
        verify(checkpointFence, never()).advanceMainCheckpoint(0L, 1L);
    }

    @Test
    void eventWriterFailureStopsTileAndCheckpointAndPreservesCause() {
        FlushPlan plan = initializedPlan();
        IllegalStateException failure = new IllegalStateException("duplicate event");
        stubInitialized(plan);
        org.mockito.Mockito.doThrow(failure).when(eventWriter).appendAll(plan.walRecords());

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> service.persist(plan));

        assertSame(failure, actual);
        verifyNoInteractions(tileWriter);
        verify(checkpointFence, never()).advanceMainCheckpoint(0L, 1L);
    }

    @Test
    void tileWriterFailureStopsCheckpointAndPreservesCause() {
        FlushPlan plan = initializedPlan();
        IllegalStateException failure = new IllegalStateException("tile failed");
        stubInitialized(plan);
        org.mockito.Mockito.doThrow(failure).when(tileWriter).writeAll(plan.tileSnapshots());

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> service.persist(plan));

        assertSame(failure, actual);
        verify(checkpointFence, never()).advanceMainCheckpoint(0L, 1L);
    }

    @Test
    void checkpointFailureIsPropagatedAfterExactPlanPayloads() {
        FlushPlan plan = initializedPlan();
        IllegalStateException failure = new IllegalStateException("cas failed");
        stubInitialized(plan);
        org.mockito.Mockito.doThrow(failure).when(checkpointFence).advanceMainCheckpoint(0L, 1L);

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> service.persist(plan));

        assertSame(failure, actual);
        verify(eventWriter).appendAll(plan.walRecords());
        verify(tileWriter).writeAll(plan.tileSnapshots());
        verify(checkpointFence).advanceMainCheckpoint(0L, 1L);
    }

    @Test
    void serviceHasNoWalMemoryDirtyOrReadinessDependency() {
        List<String> fieldTypes = Arrays.stream(FlushPersistenceService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();

        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("WalReplay")));
        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("InMemoryTileBoard")));
        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("DirtyTileTracker")));
        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("ServiceReadiness")));
    }

    @Test
    void capturedPlanPersistsEvenWhenNoReadinessDependencyExists() {
        FlushPlan plan = initializedPlan();
        stubInitialized(plan);

        service.persist(plan);

        verify(eventWriter).appendAll(plan.walRecords());
        verify(tileWriter).writeAll(plan.tileSnapshots());
    }

    private void stubInitialized(FlushPlan plan) {
        List<TileKey> keys = canonicalKeys();
        when(checkpointFence.lockMainCheckpoint()).thenReturn(plan.expectedLastFlushedEventSeq());
        when(tileMetadataReader.readAllTileKeys()).thenReturn(keys);
        when(classifier.classify(plan.expectedLastFlushedEventSeq(), keys))
                .thenReturn(DbBootstrapState.INITIALIZED);
    }

    private FlushPlan initializedPlan() {
        WalRecord record = new WalRecord(
                1L,
                7L,
                0,
                0,
                0,
                0,
                0,
                17,
                LocalDateTime.of(2026, 4, 3, 6, 0)
        );
        FlushTileSnapshot snapshot = new FlushTileSnapshot(
                KEY,
                new byte[BoardConstants.TILE_PIXEL_COUNT],
                1L
        );
        return FlushPlan.nonNoOp(
                0L,
                1L,
                List.of(record),
                List.of(snapshot),
                List.of(new DirtyTile(KEY, 1L, 1L)),
                DbBootstrapState.INITIALIZED
        );
    }

    private List<TileKey> canonicalKeys() {
        return new CanonicalZ0TileKeys().orderedKeys();
    }
}
