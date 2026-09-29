package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import dev.cgt.pixelplace.tile.application.TileStateSnapshot;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static dev.cgt.pixelplace.common.constant.BoardConstants.TILE_PIXEL_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/* classifier·WAL 검증 뒤에만 memory/seed/readiness를 여는 startup recovery 순서 계약 검증 */
class StartupRecoveryServiceTest {

    private static final CanonicalZ0TileKeys CANONICAL_KEYS = new CanonicalZ0TileKeys();
    private static final List<TileKey> ALL_KEYS = CANONICAL_KEYS.orderedKeys();
    private static final List<TileStateSnapshot> ALL_SNAPSHOTS = createCanonicalSnapshots();

    @Test
    // empty DB bootstrap은 WAL replay 뒤에도 DirtyTileTracker 없이 authoritative memory만 복구
    void bootstrapPendingInitializesWhiteReplaysWalSeedsTailAndMarksReady() {
        WalRecord first = record(2L, 0, 0, 17);
        WalRecord last = record(5L, 1, 0, 19);
        Fixture fixture = fixture(
                view(0L, List.of(), List.of()),
                new WalReplayBatch(List.of(first, last), 5L)
        );

        fixture.service.recover();

        verify(fixture.board).initializeAllWhite();
        verify(fixture.board).applyReplayRecord(first.x(), first.y(), first.color());
        verify(fixture.board).applyReplayRecord(last.x(), last.y(), last.color());
        verify(fixture.eventSeqManager).initializeLastIssued(5L);
        assertTrue(fixture.readiness.isReady());
    }

    @Test
    void initializedCheckpointZeroLoadsCanonicalSnapshots() {
        assertInitializedRecovery(0L, new WalReplayBatch(List.of(), 0L));
    }

    @Test
    void initializedPositiveCheckpointLoadsSnapshotsAndReplaysOnlyNewerWal() {
        assertInitializedRecovery(
                7L,
                new WalReplayBatch(List.of(record(10L, 0, 0, 23)), 10L)
        );
    }

    @TestFactory
    Stream<DynamicTest> inconsistentDatabaseShapesFailIndependentlyBeforeWalMemorySeedAndReady() {
        List<InvalidDbCase> invalidCases = List.of(
                new InvalidDbCase("positive checkpoint with no rows", view(1L, List.of(), List.of())),
                new InvalidDbCase("one database key", view(0L, List.of(ALL_KEYS.get(0)), List.of())),
                new InvalidDbCase(
                        "one thousand twenty three database keys",
                        view(0L, ALL_KEYS.subList(0, ALL_KEYS.size() - 1), List.of())
                ),
                new InvalidDbCase(
                        "canonical key missing behind duplicate",
                        view(0L, replaceLastKey(ALL_KEYS.get(0)), List.of())
                ),
                new InvalidDbCase(
                        "database key out of range",
                        view(0L, replaceLastKey(new TileKey(0, 32, 0)), List.of())
                ),
                new InvalidDbCase(
                        "non z zero database key",
                        view(0L, replaceLastKey(new TileKey(1, 0, 0)), List.of())
                ),
                new InvalidDbCase(
                        "canonical keys plus extra row",
                        view(0L, appendKey(new TileKey(1, 0, 0)), List.of())
                ),
                new InvalidDbCase(
                        "bootstrap keys empty but snapshots present",
                        view(0L, List.of(), List.of(snapshot(new TileKey(0, 0, 0), 0L)))
                ),
                new InvalidDbCase(
                        "canonical database keys but partial snapshots",
                        view(0L, ALL_KEYS, List.of(snapshot(new TileKey(0, 0, 0), 0L)))
                ),
                new InvalidDbCase(
                        "snapshot duplicate hides canonical key",
                        view(0L, ALL_KEYS, replaceLastSnapshot(snapshot(new TileKey(0, 0, 0), 0L)))
                ),
                new InvalidDbCase(
                        "snapshot key outside z zero",
                        view(0L, ALL_KEYS, replaceLastSnapshot(snapshot(new TileKey(1, 0, 0), 0L)))
                )
        );

        return invalidCases.stream().map(invalidCase -> DynamicTest.dynamicTest(
                invalidCase.name(),
                () -> {
                    Fixture fixture = fixture(invalidCase.view(), new WalReplayBatch(List.of(), 0L));

                    IllegalStateException failure = assertThrows(
                            IllegalStateException.class,
                            fixture.service::recover
                    );

                    assertTrue(failure.getMessage().contains("checkpoint="));
                    assertTrue(failure.getMessage().contains("databaseKeyCount="));
                    assertTrue(failure.getMessage().contains("classifierState="));
                    verifyNoInteractions(fixture.walReplaySource, fixture.board, fixture.eventSeqManager, fixture.preparation);
                    assertFalse(fixture.readiness.isReady());
                }
        ));
    }

    @Test
    void checkpointCaptureFailureStopsClassifierWalMemorySeedAndKeepsNotReady() {
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        DbBootstrapClassifier classifier = mock(DbBootstrapClassifier.class);
        WalReplaySource wal = mock(WalReplaySource.class);
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        IllegalStateException missing = new IllegalStateException("checkpoint missing");
        when(capture.capture()).thenThrow(missing);
        StartupRecoveryService service = new StartupRecoveryService(
                capture,
                classifier,
                CANONICAL_KEYS,
                wal,
                board,
                eventSeqManager,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled(), org.mockito.Mockito.mock(dev.cgt.pixelplace.wal.application.WalStoragePreparation.class));

        IllegalStateException actual = assertThrows(IllegalStateException.class, service::recover);

        assertSame(missing, actual);
        verify(capture, times(1)).capture();
        verifyNoInteractions(classifier, wal, board, eventSeqManager);
        assertFalse(readiness.isReady());
    }

    @TestFactory
    Stream<DynamicTest> invalidWalBatchesFailIndependentlyBeforeAnyMemorySeedAndReady() {
        List<InvalidWalCase> invalidCases = List.of(
                new InvalidWalCase("tail before checkpoint", new WalReplayBatch(List.of(), -1L)),
                new InvalidWalCase("empty records with larger tail", new WalReplayBatch(List.of(), 1L)),
                new InvalidWalCase(
                        "last record and tail mismatch",
                        new WalReplayBatch(List.of(record(1L, 0, 0, 17)), 2L)
                ),
                new InvalidWalCase(
                        "record at checkpoint",
                        new WalReplayBatch(List.of(record(0L, 0, 0, 17)), 0L)
                ),
                new InvalidWalCase(
                        "duplicate record sequence",
                        new WalReplayBatch(
                                List.of(record(1L, 0, 0, 17), record(1L, 1, 0, 19)),
                                1L
                        )
                ),
                new InvalidWalCase(
                        "reversed record sequence",
                        new WalReplayBatch(
                                List.of(record(2L, 0, 0, 17), record(1L, 1, 0, 19)),
                                1L
                        )
                )
        );

        return invalidCases.stream().map(invalidCase -> DynamicTest.dynamicTest(
                invalidCase.name(),
                () -> {
                    Fixture fixture = fixture(view(0L, List.of(), List.of()), invalidCase.batch());
                    fixture.readiness.markReady();

                    IllegalStateException failure = assertThrows(
                            IllegalStateException.class,
                            fixture.service::recover
                    );

                    assertTrue(failure.getMessage().contains("walLastEventSeq="));
                    assertTrue(failure.getMessage().contains("recordCount="));
                    verifyNoInteractions(fixture.board, fixture.eventSeqManager, fixture.preparation);
                    assertFalse(fixture.readiness.isReady());
                }
        ));
    }

    @Test
    void memoryInitializationFailureKeepsNotReadyAndPreventsSeed() {
        Fixture fixture = fixture(
                view(0L, List.of(), List.of()),
                new WalReplayBatch(List.of(), 0L)
        );
        fixture.readiness.markReady();
        doThrow(new IllegalStateException("memory init failed"))
                .when(fixture.board).initializeAllWhite();

        assertThrows(IllegalStateException.class, fixture.service::recover);

        verifyNoInteractions(fixture.eventSeqManager);
        assertFalse(fixture.readiness.isReady());
    }

    @Test
    void replayFailureKeepsNotReadyAndPreventsSeed() {
        WalRecord record = record(1L, 0, 0, 17);
        Fixture fixture = fixture(
                view(0L, List.of(), List.of()),
                new WalReplayBatch(List.of(record), 1L)
        );
        fixture.readiness.markReady();
        doThrow(new IllegalStateException("replay failed"))
                .when(fixture.board).applyReplayRecord(record.x(), record.y(), record.color());

        assertThrows(IllegalStateException.class, fixture.service::recover);

        verifyNoInteractions(fixture.eventSeqManager);
        assertFalse(fixture.readiness.isReady());
    }

    @Test
    void eventSeqSeedFailureKeepsNotReady() {
        Fixture fixture = fixture(
                view(0L, List.of(), List.of()),
                new WalReplayBatch(List.of(), 0L)
        );
        fixture.readiness.markReady();
        doThrow(new IllegalStateException("seed failed"))
                .when(fixture.eventSeqManager).initializeLastIssued(0L);

        assertThrows(IllegalStateException.class, fixture.service::recover);

        verify(fixture.board).initializeAllWhite();
        assertFalse(fixture.readiness.isReady());
    }

    @Test
    void readyTransitionOccursOnlyAfterCaptureValidationMemoryReplayAndSeed() {
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        DbBootstrapClassifier classifier = spy(new DbBootstrapClassifier(CANONICAL_KEYS));
        WalReplaySource wal = mock(WalReplaySource.class);
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        ServiceReadiness readiness = mock(ServiceReadiness.class);
        WalRecord record = record(1L, 0, 0, 17);
        when(capture.capture()).thenReturn(view(0L, List.of(), List.of()));
        when(wal.readAfter(0L)).thenReturn(new WalReplayBatch(List.of(record), 1L));
        StartupRecoveryService service = new StartupRecoveryService(
                capture,
                classifier,
                CANONICAL_KEYS,
                wal,
                board,
                eventSeqManager,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled(), org.mockito.Mockito.mock(dev.cgt.pixelplace.wal.application.WalStoragePreparation.class));

        service.recover();

        InOrder order = inOrder(readiness, capture, classifier, wal, board, eventSeqManager);
        order.verify(readiness).markNotReady();
        order.verify(capture).capture();
        order.verify(classifier).classify(0L, List.of());
        order.verify(wal).readAfter(0L);
        order.verify(board).initializeAllWhite();
        order.verify(board).applyReplayRecord(record.x(), record.y(), record.color());
        order.verify(eventSeqManager).initializeLastIssued(1L);
        order.verify(readiness).markReady();
    }

    @Test
    void captureReturnsBeforeClassifierAndWalRead() {
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        DbBootstrapClassifier classifier = spy(new DbBootstrapClassifier(CANONICAL_KEYS));
        WalReplaySource wal = mock(WalReplaySource.class);
        StartupRecoveryDbView view = view(0L, List.of(), List.of());
        when(capture.capture()).thenReturn(view);
        when(wal.readAfter(0L)).thenReturn(new WalReplayBatch(List.of(), 0L));
        StartupRecoveryService service = new StartupRecoveryService(
                capture,
                classifier,
                CANONICAL_KEYS,
                wal,
                mock(InMemoryTileBoard.class),
                mock(EventSeqManager.class),
                new ServiceReadiness()
        , dev.cgt.pixelplace.measurement.Measurements.disabled(), org.mockito.Mockito.mock(dev.cgt.pixelplace.wal.application.WalStoragePreparation.class));

        service.recover();

        InOrder order = inOrder(capture, classifier, wal);
        order.verify(capture).capture();
        order.verify(classifier).classify(0L, List.of());
        order.verify(wal).readAfter(0L);
    }

    @Test
    void recoverHasNoTransactionAndDoesNotOwnCheckpointOrTilePorts() throws Exception {
        Method recover = StartupRecoveryService.class.getMethod("recover");
        List<Class<?>> fieldTypes = Arrays.stream(StartupRecoveryService.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertNull(recover.getAnnotation(Transactional.class));
        assertFalse(fieldTypes.contains(CheckpointReader.class));
        assertFalse(fieldTypes.contains(TileSnapshotLoader.class));
        assertTrue(fieldTypes.contains(StartupRecoveryDbViewCaptureService.class));
        assertTrue(fieldTypes.contains(DbBootstrapClassifier.class));
    }

    private void assertInitializedRecovery(long checkpoint, WalReplayBatch batch) {
        Fixture fixture = fixture(view(checkpoint, ALL_KEYS, ALL_SNAPSHOTS), batch);

        fixture.service.recover();

        verify(fixture.board).loadAll(ALL_SNAPSHOTS);
        verify(fixture.board, never()).initializeAllWhite();
        verify(fixture.eventSeqManager).initializeLastIssued(batch.walLastEventSeq());
        assertTrue(fixture.readiness.isReady());
    }

    private static Fixture fixture(StartupRecoveryDbView view, WalReplayBatch batch) {
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        DbBootstrapClassifier classifier = spy(new DbBootstrapClassifier(CANONICAL_KEYS));
        WalReplaySource wal = mock(WalReplaySource.class);
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        ServiceReadiness readiness = new ServiceReadiness();
        when(capture.capture()).thenReturn(view);
        when(wal.readAfter(view.checkpoint().lastFlushedEventSeq())).thenReturn(batch);
        var preparation=mock(dev.cgt.pixelplace.wal.application.WalStoragePreparation.class);
        StartupRecoveryService service = new StartupRecoveryService(
                capture,
                classifier,
                CANONICAL_KEYS,
                wal,
                board,
                eventSeqManager,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled(), preparation);
        return new Fixture(service, wal, board, eventSeqManager, readiness, preparation);
    }

    private static StartupRecoveryDbView view(
            long checkpoint,
            List<TileKey> databaseKeys,
            List<TileStateSnapshot> snapshots
    ) {
        return new StartupRecoveryDbView(
                new CheckpointSnapshot(checkpoint),
                new TileLoadResult(databaseKeys, snapshots)
        );
    }

    private static List<TileStateSnapshot> createCanonicalSnapshots() {
        return ALL_KEYS.stream().map(key -> snapshot(key, 3L)).toList();
    }

    private static TileStateSnapshot snapshot(TileKey key, long version) {
        return new TileStateSnapshot(key, new byte[TILE_PIXEL_COUNT], version);
    }

    private static List<TileKey> replaceLastKey(TileKey replacement) {
        List<TileKey> keys = new ArrayList<>(ALL_KEYS);
        keys.set(keys.size() - 1, replacement);
        return List.copyOf(keys);
    }

    private static List<TileKey> appendKey(TileKey extra) {
        List<TileKey> keys = new ArrayList<>(ALL_KEYS);
        keys.add(extra);
        return List.copyOf(keys);
    }

    private static List<TileStateSnapshot> replaceLastSnapshot(TileStateSnapshot replacement) {
        List<TileStateSnapshot> snapshots = new ArrayList<>(ALL_SNAPSHOTS);
        snapshots.set(snapshots.size() - 1, replacement);
        return List.copyOf(snapshots);
    }

    private static WalRecord record(long eventSeq, int tx, int ty, int color) {
        return new WalRecord(
                eventSeq,
                7L,
                0,
                tx,
                ty,
                tx * 256,
                ty * 256,
                color,
                LocalDateTime.of(2026, 4, 3, 6, 0)
        );
    }

    private record Fixture(
            StartupRecoveryService service,
            WalReplaySource walReplaySource,
            InMemoryTileBoard board,
            EventSeqManager eventSeqManager,
            ServiceReadiness readiness,
            dev.cgt.pixelplace.wal.application.WalStoragePreparation preparation
    ) {
    }

    private record InvalidDbCase(String name, StartupRecoveryDbView view) {
    }

    private record InvalidWalCase(String name, WalReplayBatch batch) {
    }
}
