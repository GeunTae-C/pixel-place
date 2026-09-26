package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.pixel.application.PixelBroadcastService;
import dev.cgt.pixelplace.pixel.application.PixelCommandService;
import dev.cgt.pixelplace.pixel.application.PixelCooldown;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.application.SynchronizedDirtyTileTracker;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.domain.TileState;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlushPlanCaptureServiceTest {

    private static final CanonicalZ0TileKeys CANONICAL_KEYS = new CanonicalZ0TileKeys();
    private static final TileKey KEY_A = new TileKey(0, 0, 0);
    private static final TileKey KEY_B = new TileKey(0, 1, 0);
    private static final TileKey KEY_C = new TileKey(0, 0, 1);
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 4, 3, 6, 0);

    @Test
    void firstReadinessFailureStopsBeforeEveryCollaborator() {
        Fixture fixture = new Fixture();
        fixture.readiness.markNotReady();

        assertThrows(ServiceNotReadyException.class, fixture.service::capturePlan);

        verifyNoInteractions(
                fixture.checkpointReader,
                fixture.tileMetadataReader,
                fixture.coordinator,
                fixture.walReplaySource,
                fixture.dirtyTileTracker,
                fixture.board
        );
    }

    @Test
    void missingCheckpointExceptionIsPropagatedWithoutDefaultingToZero() {
        Fixture fixture = new Fixture();
        IllegalStateException missing = new IllegalStateException("main checkpoint missing");
        when(fixture.checkpointReader.readMainCheckpoint()).thenThrow(missing);

        IllegalStateException actual = assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        assertSame(missing, actual);
        verifyNoInteractions(
                fixture.tileMetadataReader,
                fixture.coordinator,
                fixture.walReplaySource,
                fixture.dirtyTileTracker,
                fixture.board
        );
    }

    @Test
    void metadataFailureStopsBeforeCoordinatorAndWal() {
        Fixture fixture = new Fixture();
        IllegalStateException metadataFailure = new IllegalStateException("metadata failed");
        when(fixture.tileMetadataReader.readAllTileKeys()).thenThrow(metadataFailure);

        IllegalStateException actual = assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        assertSame(metadataFailure, actual);
        verify(fixture.checkpointReader).readMainCheckpoint();
        verifyNoInteractions(
                fixture.coordinator,
                fixture.walReplaySource,
                fixture.dirtyTileTracker,
                fixture.board
        );
    }

    @Test
    void inconsistentDatabaseStopsBeforeCoordinatorWalAndDirty() {
        Fixture fixture = new Fixture();
        when(fixture.tileMetadataReader.readAllTileKeys()).thenReturn(List.of(KEY_A));

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.coordinator, never()).capture(any());
        verifyNoInteractions(fixture.walReplaySource, fixture.dirtyTileTracker, fixture.board);
    }

    @Test
    void secondReadinessFailureStopsBeforeWalDirtyAndSnapshot() {
        Fixture fixture = new Fixture();
        when(fixture.tileMetadataReader.readAllTileKeys()).thenAnswer(invocation -> {
            fixture.readiness.markNotReady();
            return CANONICAL_KEYS.orderedKeys();
        });

        assertThrows(ServiceNotReadyException.class, fixture.service::capturePlan);

        verify(fixture.coordinator).capture(any());
        verifyNoInteractions(fixture.walReplaySource, fixture.dirtyTileTracker, fixture.board);
    }

    @Test
    void emptyRecordsWithTailEqualCheckpointCreatesNoOp() {
        Fixture fixture = new Fixture();

        FlushPlan plan = fixture.service.capturePlan();

        assertTrue(plan.noOp());
        assertEquals(0L, plan.expectedLastFlushedEventSeq());
        assertEquals(0L, plan.flushTargetEventSeq());
        verify(fixture.dirtyTileTracker, never()).drainDirtyTiles();
        verifyNoInteractions(fixture.board);
    }

    @Test
    void noOpDoesNotDrainExistingDirtyState() {
        ServiceReadiness readiness = readyReadiness();
        SynchronizedDirtyTileTracker tracker = new SynchronizedDirtyTileTracker();
        tracker.markDirty(KEY_A, 1L, 1L);
        FlushPlanCaptureService service = new FlushPlanCaptureService(
                readiness,
                () -> new CheckpointSnapshot(0L),
                CANONICAL_KEYS::orderedKeys,
                classifier(),
                new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()),
                expected -> new WalReplayBatch(List.of(), expected),
                tracker,
                mock(InMemoryTileBoard.class)
        , dev.cgt.pixelplace.measurement.Measurements.disabled());

        FlushPlan plan = service.capturePlan();

        assertTrue(plan.noOp());
        assertEquals(List.of(new DirtyTile(KEY_A, 1L, 1L)), tracker.drainDirtyTiles());
    }

    @Test
    void bootstrapPendingNoOpDoesNotCaptureCanonicalSnapshots() {
        Fixture fixture = new Fixture();
        when(fixture.tileMetadataReader.readAllTileKeys()).thenReturn(List.of());

        FlushPlan plan = fixture.service.capturePlan();

        assertTrue(plan.noOp());
        assertEquals(DbBootstrapState.BOOTSTRAP_PENDING, plan.bootstrapState());
        assertTrue(plan.tileSnapshots().isEmpty());
        verifyNoInteractions(fixture.board);
    }

    @Test
    void walTailBehindCheckpointIsRejected() {
        Fixture fixture = new Fixture();
        fixture.checkpoint(10L);
        when(fixture.walReplaySource.readAfter(10L)).thenReturn(new WalReplayBatch(List.of(), 9L));

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.dirtyTileTracker, never()).drainDirtyTiles();
    }

    @Test
    void emptyRecordsWithTailAheadOfCheckpointIsRejected() {
        Fixture fixture = new Fixture();
        when(fixture.walReplaySource.readAfter(0L)).thenReturn(new WalReplayBatch(List.of(), 1L));

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.dirtyTileTracker, never()).drainDirtyTiles();
    }

    @Test
    void negativeWalTailIsRejected() {
        Fixture fixture = new Fixture();
        when(fixture.walReplaySource.readAfter(0L)).thenReturn(new WalReplayBatch(List.of(), -1L));

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);
        verify(fixture.dirtyTileTracker, never()).drainDirtyTiles();
    }

    @Test
    void eventSeqGapsAreAcceptedAndTailBecomesTarget() {
        Fixture fixture = new Fixture();
        fixture.batch(
                List.of(record(3L, KEY_A), record(7L, KEY_A), record(10L, KEY_A)),
                10L
        );

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(10L, plan.flushTargetEventSeq());
        assertEquals(List.of(3L, 7L, 10L), plan.walRecords().stream().map(WalRecord::eventSeq).toList());
    }

    @Test
    void duplicateAndReverseWalBatchesAreRejectedBeforeDirtyDrain() {
        Fixture duplicate = new Fixture();
        duplicate.batch(List.of(record(3L, KEY_A), record(3L, KEY_A)), 3L);
        assertThrows(IllegalStateException.class, duplicate.service::capturePlan);
        verify(duplicate.dirtyTileTracker, never()).drainDirtyTiles();

        Fixture reverse = new Fixture();
        reverse.batch(List.of(record(7L, KEY_A), record(3L, KEY_A)), 3L);
        assertThrows(IllegalStateException.class, reverse.service::capturePlan);
        verify(reverse.dirtyTileTracker, never()).drainDirtyTiles();
    }

    @Test
    void lastRecordAndWalTailMismatchIsRejectedBeforeDirtyDrain() {
        Fixture fixture = new Fixture();
        fixture.batch(List.of(record(3L, KEY_A)), 10L);

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.dirtyTileTracker, never()).drainDirtyTiles();
    }

    @Test
    void recordAtOrBeforeExpectedCheckpointIsRejectedBeforeDirtyDrain() {
        Fixture fixture = new Fixture();
        fixture.checkpoint(10L);
        when(fixture.walReplaySource.readAfter(10L))
                .thenReturn(new WalReplayBatch(List.of(record(10L, KEY_A)), 10L));

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.dirtyTileTracker, never()).drainDirtyTiles();
    }

    @Test
    void nonCanonicalWalAffectedKeyIsRejectedBeforeDirtyDrain() {
        Fixture fixture = new Fixture();
        TileKey nonCanonical = new TileKey(1, 0, 0);
        fixture.batch(List.of(record(1L, nonCanonical)), 1L);

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.dirtyTileTracker, never()).drainDirtyTiles();
        verifyNoInteractions(fixture.board);
    }

    @Test
    void initializedPlanIncludesWalAffectedKeyWithoutDirtyHint() {
        Fixture fixture = new Fixture();
        fixture.batch(List.of(record(1L, KEY_A)), 1L);

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(List.of(KEY_A), snapshotKeys(plan));
    }

    @Test
    void initializedPlanIncludesExactWalAndDirtyUnion() {
        Fixture fixture = new Fixture();
        DirtyTile dirtyB = new DirtyTile(KEY_B, 1L, 2L);
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of(dirtyB));

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(List.of(KEY_A, KEY_B), snapshotKeys(plan));
        assertEquals(List.of(dirtyB), plan.drainedDirtyTiles());
    }

    @Test
    void overlappingWalAndDirtyKeyProducesOneSnapshot() {
        Fixture fixture = new Fixture();
        DirtyTile dirtyA = new DirtyTile(KEY_A, 1L, 2L);
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of(dirtyA));

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(List.of(KEY_A), snapshotKeys(plan));
    }

    @Test
    void bootstrapPendingNonNoOpCapturesExactlyAllCanonicalSnapshots() {
        Fixture fixture = new Fixture();
        when(fixture.tileMetadataReader.readAllTileKeys()).thenReturn(List.of());
        fixture.batch(List.of(record(1L, KEY_A)), 1L);

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(DbBootstrapState.BOOTSTRAP_PENDING, plan.bootstrapState());
        assertEquals(BoardConstants.Z0_TILE_COUNT, plan.tileSnapshots().size());
        assertTrue(CANONICAL_KEYS.exactlyMatches(snapshotKeys(plan)));
    }

    @Test
    void snapshotsUseDeterministicZTyTxOrder() {
        Fixture fixture = new Fixture();
        fixture.batch(List.of(record(1L, KEY_C), record(2L, KEY_B)), 2L);
        when(fixture.dirtyTileTracker.drainDirtyTiles())
                .thenReturn(List.of(new DirtyTile(KEY_A, 1L, 1L)));

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(List.of(KEY_A, KEY_B, KEY_C), snapshotKeys(plan));
    }

    @Test
    void missingMemoryTileFailsAndRestoresActualDrainResult() {
        Fixture fixture = new Fixture();
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        IllegalStateException missing = new IllegalStateException("tile missing");
        when(fixture.board.getRequired(KEY_A)).thenThrow(missing);

        IllegalStateException actual = assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        assertSame(missing, actual);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(List.of());
    }

    @Test
    void capturedPlanDoesNotChangeAfterLiveMemoryMutation() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        board.applyPixel(0, 0, 7);
        FlushPlanCaptureService service = standaloneService(
                readyReadiness(),
                new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()),
                expected -> new WalReplayBatch(List.of(record(1L, KEY_A)), 1L),
                mockTrackerReturning(List.of()),
                board
        );

        FlushPlan plan = service.capturePlan();
        board.applyPixel(0, 0, 8);

        assertEquals((byte) 7, plan.tileSnapshots().get(0).pixels()[0]);
        assertEquals(1L, plan.tileSnapshots().get(0).tileVersion());
        assertEquals((byte) 8, board.getRequired(KEY_A).pixels()[0]);
        assertEquals(2L, board.getRequired(KEY_A).tileVersion());
    }

    @Test
    void captureServiceHasNoPersistenceOrCheckpointWriterDependency() {
        List<String> fieldNames = List.of(FlushPlanCaptureService.class.getDeclaredFields()).stream()
                .map(field -> field.getName().toLowerCase())
                .toList();

        assertTrue(fieldNames.stream().noneMatch(name -> name.contains("persistence")));
        assertTrue(fieldNames.stream().noneMatch(name -> name.contains("checkpointwriter")));
    }

    @Test
    void dirtyEventSeqAtTargetIsAccepted() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 1L);
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of(dirty));

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(List.of(dirty), plan.drainedDirtyTiles());
        verify(fixture.dirtyTileTracker, never()).restoreDirtyTiles(any());
    }

    @Test
    void dirtyEventSeqBeyondTargetFailsAndRestoresDrainResult() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 2L, 1L);
        List<DirtyTile> drained = List.of(dirty);
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(drained);

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.dirtyTileTracker).restoreDirtyTiles(drained);
    }

    @Test
    void dirtyTileVersionAtSnapshotVersionIsAccepted() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 100L);
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of(dirty));

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(100L, plan.tileSnapshots().get(0).tileVersion());
    }

    @Test
    void dirtyTileVersionBeyondSnapshotFailsAndRestoresDrainResult() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 101L);
        List<DirtyTile> drained = List.of(dirty);
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(drained);

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        verify(fixture.dirtyTileTracker).restoreDirtyTiles(drained);
    }

    @Test
    void snapshotCopyFailureRestoresActualDrainResult() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 1L);
        List<DirtyTile> drained = List.of(dirty);
        TileState failingState = mock(TileState.class);
        IllegalStateException copyFailure = new IllegalStateException("copy failed");
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(drained);
        when(fixture.board.getRequired(KEY_A)).thenReturn(failingState);
        when(failingState.pixels()).thenThrow(copyFailure);

        IllegalStateException actual = assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        assertSame(copyFailure, actual);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(drained);
    }

    @Test
    void planCreationFailureRestoresActualDrainResult() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 1L);
        List<DirtyTile> drained = List.of(dirty);
        IllegalStateException planFailure = new IllegalStateException("plan failed");
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(drained);
        fixture.service = new FlushPlanCaptureService(
                fixture.readiness,
                fixture.checkpointReader,
                fixture.tileMetadataReader,
                fixture.classifier,
                fixture.coordinator,
                fixture.walReplaySource,
                fixture.dirtyTileTracker,
                fixture.board
        , dev.cgt.pixelplace.measurement.Measurements.disabled()) {
            @Override
            FlushPlan createNonNoOpPlan(
                    long expectedLastFlushedEventSeq,
                    long flushTargetEventSeq,
                    List<WalRecord> walRecords,
                    List<FlushTileSnapshot> tileSnapshots,
                    List<DirtyTile> drainedDirtyTiles,
                    DbBootstrapState bootstrapState
            ) {
                throw planFailure;
            }
        };

        IllegalStateException actual = assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        assertSame(planFailure, actual);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(drained);
    }

    @Test
    void failureRestoresOnlyDrainedDirtyWithoutSyntheticWalAffectedKey() {
        Fixture fixture = new Fixture();
        DirtyTile dirtyB = new DirtyTile(KEY_B, 2L, 1L);
        AtomicReference<Collection<DirtyTile>> restored = recordRestoredTiles(fixture.dirtyTileTracker);
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of(dirtyB));

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        assertEquals(List.of(dirtyB), restored.get());
        assertFalse(restored.get().stream().anyMatch(dirty -> dirty.tileKey().equals(KEY_A)));
    }

    @Test
    void bootstrapFailureDoesNotRestoreSyntheticCanonicalTargets() {
        Fixture fixture = new Fixture();
        TileKey invalidKey = new TileKey(1, 0, 0);
        DirtyTile actualDrained = new DirtyTile(invalidKey, 1L, 1L);
        AtomicReference<Collection<DirtyTile>> restored = recordRestoredTiles(fixture.dirtyTileTracker);
        when(fixture.tileMetadataReader.readAllTileKeys()).thenReturn(List.of());
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of(actualDrained));

        assertThrows(IllegalStateException.class, fixture.service::capturePlan);

        assertEquals(List.of(actualDrained), restored.get());
        assertEquals(1, restored.get().size());
    }

    @Test
    void restoreFailureIsSuppressedOnOriginalCaptureFailure() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 2L, 1L);
        IllegalStateException restoreFailure = new IllegalStateException("restore failed");
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of(dirty));
        doThrow(restoreFailure).when(fixture.dirtyTileTracker).restoreDirtyTiles(List.of(dirty));

        IllegalStateException captureFailure = assertThrows(
                IllegalStateException.class,
                fixture.service::capturePlan
        );

        assertEquals(1, captureFailure.getSuppressed().length);
        assertSame(restoreFailure, captureFailure.getSuppressed()[0]);
    }

    @Test
    void captureRuntimeFailureAndRestoreErrorRethrowsSameRestoreErrorWithCaptureSuppressed() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 1L);
        List<DirtyTile> drained = List.of(dirty);
        RuntimeException captureFailure = new RuntimeException("capture failed");
        AssertionError restoreError = new AssertionError("restore fatal");
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(drained);
        when(fixture.board.getRequired(KEY_A)).thenThrow(captureFailure);
        doThrow(restoreError).when(fixture.dirtyTileTracker).restoreDirtyTiles(drained);

        AssertionError actual = assertThrows(AssertionError.class, fixture.service::capturePlan);

        assertSame(restoreError, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(captureFailure, actual.getSuppressed()[0]);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(drained);
    }

    @Test
    void captureErrorAndRestoreRuntimeFailureKeepsSameCaptureErrorPrimary() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 1L);
        List<DirtyTile> drained = List.of(dirty);
        AssertionError captureError = new AssertionError("capture fatal");
        RuntimeException restoreFailure = new RuntimeException("restore failed");
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(drained);
        when(fixture.board.getRequired(KEY_A)).thenThrow(captureError);
        doThrow(restoreFailure).when(fixture.dirtyTileTracker).restoreDirtyTiles(drained);

        AssertionError actual = assertThrows(AssertionError.class, fixture.service::capturePlan);

        assertSame(captureError, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(restoreFailure, actual.getSuppressed()[0]);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(drained);
    }

    @Test
    void sameCaptureAndRestoreThrowableIsNotSelfSuppressed() {
        Fixture fixture = new Fixture();
        DirtyTile dirty = new DirtyTile(KEY_A, 1L, 1L);
        List<DirtyTile> drained = List.of(dirty);
        AssertionError sameError = new AssertionError("same fatal");
        fixture.batch(List.of(record(1L, KEY_A)), 1L);
        when(fixture.dirtyTileTracker.drainDirtyTiles()).thenReturn(drained);
        when(fixture.board.getRequired(KEY_A)).thenThrow(sameError);
        doThrow(sameError).when(fixture.dirtyTileTracker).restoreDirtyTiles(drained);

        AssertionError actual = assertThrows(AssertionError.class, fixture.service::capturePlan);

        assertSame(sameError, actual);
        assertEquals(0, actual.getSuppressed().length);
        verify(fixture.dirtyTileTracker).restoreDirtyTiles(drained);
    }

    @Test
    // WAL scan부터 snapshot 완료까지 같은 coordinator를 공유하여 후속 write가 plan에 섞이지 않음
    void sharedBoundaryBlocksCommandCoreUntilPlanCaptureCompletes() throws Exception {
        ServiceReadiness readiness = readyReadiness();
        FlushBoundaryCoordinator coordinator = new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled());
        InMemoryTileBoard board = new InMemoryTileBoard();
        board.applyPixel(0, 0, 17);
        CountDownLatch walEntered = new CountDownLatch(1);
        CountDownLatch releaseWal = new CountDownLatch(1);
        CountDownLatch commandReachedBoundary = new CountDownLatch(1);
        CountDownLatch commandWalAppend = new CountDownLatch(1);
        WalReplaySource blockingWalSource = expected -> {
            walEntered.countDown();
            await(releaseWal);
            return new WalReplayBatch(List.of(record(1L, KEY_A)), 1L);
        };
        DirtyTileTracker tracker = mockTrackerReturning(List.of());
        FlushPlanCaptureService captureService = standaloneService(
                readiness,
                coordinator,
                blockingWalSource,
                tracker,
                board
        );
        PixelCooldown cooldown = mock(PixelCooldown.class);
        doAnswer(invocation -> {
            commandReachedBoundary.countDown();
            return null;
        }).when(cooldown).checkWritable(7L);
        EventSeqManager eventSeqManager = new EventSeqManager();
        eventSeqManager.initializeLastIssued(1L);
        WalAppender commandAppender = walRecord -> commandWalAppend.countDown();
        PixelWriteService writeService = new PixelWriteService(
                eventSeqManager,
                commandAppender,
                board,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        PixelCommandService commandService = new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(coordinator, writeService, tracker, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                mock(PixelBroadcastService.class),
                readiness,
                new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),
                dev.cgt.pixelplace.measurement.Measurements.disabled());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<FlushPlan> captured = executor.submit(captureService::capturePlan);
            assertTrue(walEntered.await(5, TimeUnit.SECONDS));

            Future<PixelWriteResult> command = executor.submit(
                    () -> commandService.writePixel(7L, 0, 0, 18)
            );
            assertTrue(commandReachedBoundary.await(5, TimeUnit.SECONDS));
            assertFalse(commandWalAppend.await(100, TimeUnit.MILLISECONDS));

            releaseWal.countDown();
            FlushPlan plan = captured.get(5, TimeUnit.SECONDS);
            PixelWriteResult commandResult = command.get(5, TimeUnit.SECONDS);

            assertEquals(1L, plan.flushTargetEventSeq());
            assertEquals(List.of(1L), plan.walRecords().stream().map(WalRecord::eventSeq).toList());
            assertEquals((byte) 17, plan.tileSnapshots().get(0).pixels()[0]);
            assertEquals(1L, plan.tileSnapshots().get(0).tileVersion());
            assertEquals(2L, commandResult.eventSeq());
            assertEquals((byte) 18, board.getRequired(KEY_A).pixels()[0]);
            assertEquals(2L, board.getRequired(KEY_A).tileVersion());
            verify(tracker).markDirty(KEY_A, 2L, 2L);
        } finally {
            releaseWal.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void checkpointReadDoesNotHoldCoordinatorAgainstCommandWrite() throws Exception {
        assertDatabaseReadDoesNotHoldCoordinator(true);
    }

    @Test
    void metadataReadDoesNotHoldCoordinatorAgainstCommandWrite() throws Exception {
        assertDatabaseReadDoesNotHoldCoordinator(false);
    }

    @Test
    void codecReplayAndPlanCapturePreserveCreatedAtNanoseconds() {
        LocalDateTime precise = LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_456_789);
        WalRecord original = record(1L, KEY_A, precise);
        ObjectMapper objectMapper = new ObjectMapper();
        WalRecordJsonCodec codec = new WalRecordJsonCodec(objectMapper);
        WalRecordParser parser = new WalRecordParser(objectMapper);
        String encoded = new String(codec.serializeLine(original), StandardCharsets.UTF_8).stripTrailing();
        WalRecord decoded = parser.parseLine(encoded, 1L);
        Fixture fixture = new Fixture();
        fixture.batch(List.of(decoded), 1L);

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(precise, decoded.createdAt());
        assertEquals(precise, plan.walRecords().get(0).createdAt());
    }

    @Test
    void recordOrderAndTargetUseEventSeqInsteadOfCreatedAt() {
        Fixture fixture = new Fixture();
        fixture.batch(
                List.of(
                        record(3L, KEY_A, TIME.plusHours(1)),
                        record(7L, KEY_A, TIME.plusHours(1)),
                        record(10L, KEY_A, TIME.minusHours(1))
                ),
                10L
        );

        FlushPlan plan = fixture.service.capturePlan();

        assertEquals(10L, plan.flushTargetEventSeq());
        assertEquals(List.of(3L, 7L, 10L), plan.walRecords().stream().map(WalRecord::eventSeq).toList());
    }

    private void assertDatabaseReadDoesNotHoldCoordinator(boolean blockCheckpoint) throws Exception {
        ServiceReadiness readiness = readyReadiness();
        FlushBoundaryCoordinator coordinator = new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled());
        CountDownLatch dbReadEntered = new CountDownLatch(1);
        CountDownLatch releaseDbRead = new CountDownLatch(1);
        CheckpointReader checkpointReader = () -> {
            if (blockCheckpoint) {
                dbReadEntered.countDown();
                await(releaseDbRead);
            }
            return new CheckpointSnapshot(0L);
        };
        TileMetadataReader metadataReader = () -> {
            if (!blockCheckpoint) {
                dbReadEntered.countDown();
                await(releaseDbRead);
            }
            return CANONICAL_KEYS.orderedKeys();
        };
        DirtyTileTracker tracker = mockTrackerReturning(List.of());
        FlushPlanCaptureService captureService = new FlushPlanCaptureService(
                readiness,
                checkpointReader,
                metadataReader,
                classifier(),
                coordinator,
                expected -> new WalReplayBatch(List.of(), expected),
                tracker,
                mock(InMemoryTileBoard.class)
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        PixelCooldown cooldown = mock(PixelCooldown.class);
        PixelWriteService writeService = mock(PixelWriteService.class);
        PixelWriteResult result = new PixelWriteResult(1L, KEY_A, 1L, 0, 0, 17);
        when(writeService.writePixel(7L, 0, 0, 17)).thenReturn(result);
        PixelCommandService commandService = new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(coordinator, writeService, tracker, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                mock(PixelBroadcastService.class),
                readiness,
                new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),
                dev.cgt.pixelplace.measurement.Measurements.disabled());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<FlushPlan> capture = executor.submit(captureService::capturePlan);
            assertTrue(dbReadEntered.await(5, TimeUnit.SECONDS));

            Future<PixelWriteResult> command = executor.submit(
                    () -> commandService.writePixel(7L, 0, 0, 17)
            );

            assertSame(result, command.get(5, TimeUnit.SECONDS));
            releaseDbRead.countDown();
            assertTrue(capture.get(5, TimeUnit.SECONDS).noOp());
        } finally {
            releaseDbRead.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static FlushPlanCaptureService standaloneService(
            ServiceReadiness readiness,
            FlushBoundaryCoordinator coordinator,
            WalReplaySource walReplaySource,
            DirtyTileTracker dirtyTileTracker,
            InMemoryTileBoard board
    ) {
        return new FlushPlanCaptureService(
                readiness,
                () -> new CheckpointSnapshot(0L),
                CANONICAL_KEYS::orderedKeys,
                classifier(),
                coordinator,
                walReplaySource,
                dirtyTileTracker,
                board
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
    }

    private static DirtyTileTracker mockTrackerReturning(List<DirtyTile> drained) {
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        when(tracker.drainDirtyTiles()).thenReturn(drained);
        return tracker;
    }

    private static AtomicReference<Collection<DirtyTile>> recordRestoredTiles(DirtyTileTracker tracker) {
        AtomicReference<Collection<DirtyTile>> restored = new AtomicReference<>();
        doAnswer(invocation -> {
            Collection<DirtyTile> argument = invocation.getArgument(0);
            restored.set(List.copyOf(argument));
            return null;
        }).when(tracker).restoreDirtyTiles(any());
        return restored;
    }

    private static List<TileKey> snapshotKeys(FlushPlan plan) {
        return plan.tileSnapshots().stream().map(FlushTileSnapshot::tileKey).toList();
    }

    private static DbBootstrapClassifier classifier() {
        return new DbBootstrapClassifier(CANONICAL_KEYS);
    }

    private static ServiceReadiness readyReadiness() {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        return readiness;
    }

    private static TileState tileState(long version) {
        return new TileState(new byte[BoardConstants.TILE_PIXEL_COUNT], version);
    }

    private static WalRecord record(long eventSeq, TileKey key) {
        return record(eventSeq, key, TIME);
    }

    private static WalRecord record(long eventSeq, TileKey key, LocalDateTime createdAt) {
        return new WalRecord(
                eventSeq,
                7L,
                key.z(),
                key.tx(),
                key.ty(),
                key.tx() * BoardConstants.TILE_SIZE,
                key.ty() * BoardConstants.TILE_SIZE,
                17,
                createdAt
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch wait timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Latch wait was interrupted.", exception);
        }
    }

    private static final class Fixture {

        private final ServiceReadiness readiness = readyReadiness();
        private final CheckpointReader checkpointReader = mock(CheckpointReader.class);
        private final TileMetadataReader tileMetadataReader = mock(TileMetadataReader.class);
        private final DbBootstrapClassifier classifier = classifier();
        private final FlushBoundaryCoordinator coordinator = mock(FlushBoundaryCoordinator.class);
        private final WalReplaySource walReplaySource = mock(WalReplaySource.class);
        private final DirtyTileTracker dirtyTileTracker = mock(DirtyTileTracker.class);
        private final InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        private FlushPlanCaptureService service;

        private Fixture() {
            when(checkpointReader.readMainCheckpoint()).thenReturn(new CheckpointSnapshot(0L));
            when(tileMetadataReader.readAllTileKeys()).thenReturn(CANONICAL_KEYS.orderedKeys());
            doAnswer(invocation -> {
                Supplier<?> action = invocation.getArgument(0);
                return action.get();
            }).when(coordinator).capture(any());
            when(walReplaySource.readAfter(0L)).thenReturn(new WalReplayBatch(List.of(), 0L));
            when(dirtyTileTracker.drainDirtyTiles()).thenReturn(List.of());
            when(board.getRequired(any())).thenReturn(tileState(100L));
            service = new FlushPlanCaptureService(
                    readiness,
                    checkpointReader,
                    tileMetadataReader,
                    classifier,
                    coordinator,
                    walReplaySource,
                    dirtyTileTracker,
                    board
            , dev.cgt.pixelplace.measurement.Measurements.disabled());
        }

        private void checkpoint(long expected) {
            when(checkpointReader.readMainCheckpoint()).thenReturn(new CheckpointSnapshot(expected));
        }

        private void batch(List<WalRecord> records, long tail) {
            when(walReplaySource.readAfter(0L)).thenReturn(new WalReplayBatch(records, tail));
        }
    }
}
