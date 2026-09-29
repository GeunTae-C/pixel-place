package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.flush.infra.ProgrammaticFlushTransactionExecutor;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.*;
import dev.cgt.pixelplace.tile.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static dev.cgt.pixelplace.wal.infra.SegmentedWalConcurrencyTest.await;
import static dev.cgt.pixelplace.wal.infra.SegmentedWalConcurrencyTest.blocked;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 reader·command·plan·worker 연결의 lock 순서 검증. 테스트 DB 없이 파일/경합 경계만 대상 */
class SegmentedWalRetentionConcurrencyTest {
    @TempDir Path directory;
    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void releaseOnlyThisCasesInlineMockReferences() {
        // 실제 board를 잡는 spy·Answer closure의 수명을 해당 사례로 제한. 다른 context mock은 변경하지 않음
        for (Fixture fixture : fixtures) fixture.releaseMocks();
        fixtures.clear();
    }

    private Fixture fixture(SegmentedWalStorage storage) {
        var fixture = new Fixture(storage);
        fixtures.add(fixture);
        return fixture;
    }

    @Test
    void retentionCannotDeleteUntilActualScannerHasClosedReader() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1), deleting = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true), readerClosed = new AtomicBoolean();
        AtomicInteger deletes = new AtomicInteger();
        Path base = directory.resolve("wal");
        try (var storage = new SegmentedWalStorage(properties(base, 10000), PARSER, CODEC, dev.cgt.pixelplace.measurement.Measurements.disabled(), new dev.cgt.pixelplace.wal.infra.TestWalFileDurability()) {
            @Override BufferedReader openReader(Path path) throws IOException {
                var actual = super.openReader(path);
                if (!first.getAndSet(false)) return actual;
                return new BufferedReader(actual) {
                    @Override public String readLine() throws IOException { reading.countDown(); await(release); return super.readLine(); }
                    @Override public void close() throws IOException { super.close(); readerClosed.set(true); }
                };
            }
            @Override void deleteFile(Path path) throws IOException {
                assertTrue(readerClosed.get()); deletes.incrementAndGet(); super.deleteFile(path);
            }
        }; var threads = Executors.newFixedThreadPool(2)) {
            records(base, 2); records(storage.segmentPath(1), 5); records(storage.segmentPath(2), 9);
            try {
                var scan = threads.submit(() -> storage.readAfter(0)); await(reading);
                AtomicReference<Thread> deleteThread = new AtomicReference<>();
                var cleanup = threads.submit(() -> { deleteThread.set(Thread.currentThread()); deleting.countDown(); return storage.deleteCommittedPrefix(5); });
                await(deleting); blocked(deleteThread.get());
                assertEquals(0, deletes.get()); assertFalse(readerClosed.get());
                release.countDown();
                assertEquals(9, scan.get(5, TimeUnit.SECONDS).walLastEventSeq());
                assertEquals(2, cleanup.get(5, TimeUnit.SECONDS).deletedFiles());
                assertEquals(2, deletes.get());
            } finally { release.countDown(); threads.shutdownNow(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "delay", "error"})
    void retentionHoldsCoordinatorAndSingleFlightUntilDeleteCompletes(String outcome) throws Exception {
        CountDownLatch deleting = new CountDownLatch(1), release = new CountDownLatch(1), writing = new CountDownLatch(1);
        Error failure = new AssertionError("delete error");
        try (var storage = spy(storage(directory.resolve("wal"), 1)); var threads = Executors.newFixedThreadPool(2)) {
            var f = fixture(storage);
            f.write(); f.write(); f.write();
            Path first = storage.segmentPath(0);
            doAnswer(inv -> {
                deleting.countDown(); await(release);
                if (outcome.equals("delay")) throw new IOException("delete delayed");
                if (outcome.equals("error")) throw failure;
                return inv.callRealMethod();
            }).when(storage).deleteFile(first);
            try {
                var flush = threads.submit(f.worker::flushOnce); await(deleting);
                assertEquals(3, f.checkpoint.get());
                clearInvocations(f.store, f.manager, f.capture, f.retention, f.board);
                assertEquals(FlushRunResult.SKIPPED_ALREADY_RUNNING, f.worker.flushOnce());
                verifyNoInteractions(f.store, f.manager, f.capture, f.retention);
                AtomicReference<Thread> writer = new AtomicReference<>();
                var write = threads.submit(() -> { writer.set(Thread.currentThread()); writing.countDown(); return f.write(); });
                await(writing); parked(writer.get());
                verify(f.board, never()).applyPixel(anyInt(), anyInt(), anyInt());
                assertFalse(write.isDone()); assertFalse(flush.isDone());
                release.countDown();
                if (outcome.equals("error")) {
                    assertSame(failure, assertThrows(ExecutionException.class, () -> flush.get(5, TimeUnit.SECONDS)).getCause());
                    assertThrows(ExecutionException.class, () -> write.get(5, TimeUnit.SECONDS));
                    assertThrows(RuntimeException.class, f.ready::requireNotFatal);
                    assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
                } else {
                    assertEquals(FlushRunResult.COMMITTED, flush.get(5, TimeUnit.SECONDS));
                    assertEquals(4, write.get(5, TimeUnit.SECONDS).eventSeq());
                    assertEquals(4, storage.readAfter(3).walLastEventSeq());
                    assertTrue(f.ready.isReady());
                }
                verify(f.dirty, never()).restoreDirtyTiles(any());
                assertEquals(3, f.checkpoint.get());
                assertTrue(f.guard.tryRun(() -> { }));
            } finally { release.countDown(); threads.shutdownNow(); }
        }
    }

    @Test
    void writeWinningCoordinatorAfterCommitIsPreservedByFreshRetentionScan() throws Exception {
        CountDownLatch committed = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var storage = storage(directory.resolve("wal"), 1); var threads = Executors.newSingleThreadExecutor()) {
            var f = fixture(storage);
            f.write(); f.write();
            doAnswer(inv -> { committed.countDown(); await(release); return inv.callRealMethod(); }).when(f.retention).onCommitConfirmed(2);
            try {
                var flush = threads.submit(f.worker::flushOnce); await(committed);
                assertEquals(2, f.checkpoint.get());
                assertEquals(3, f.write().eventSeq());
                assertTrue(Files.exists(storage.segmentPath(2)));
                release.countDown();
                assertEquals(FlushRunResult.COMMITTED, flush.get(5, TimeUnit.SECONDS));
                assertFalse(Files.exists(storage.segmentPath(0)));
                assertFalse(Files.exists(storage.segmentPath(1)));
                assertEquals(List.of(3L), storage.readAfter(0).records().stream().map(r -> r.eventSeq()).toList());
                assertEquals(1, f.dirty.drainDirtyTiles().size());
                verify(f.dirty, never()).restoreDirtyTiles(any());
            } finally { release.countDown(); threads.shutdownNow(); }
        }
    }

    @Test
    void realNoOpRetriesDelayedDeletionButUnresolvedPendingBlocksPastAuthority() throws Exception {
        try (var storage = spy(storage(directory.resolve("wal"), 1))) {
            var f = fixture(storage);
            f.write(); f.write();
            Path first = storage.segmentPath(0);
            doThrow(new IOException("delayed")).when(storage).deleteFile(first);
            assertEquals(FlushRunResult.COMMITTED, f.worker.flushOnce());
            assertTrue(Files.exists(first));
            doCallRealMethod().when(storage).deleteFile(first);
            assertEquals(FlushRunResult.NO_OP, f.worker.flushOnce());
            assertFalse(Files.exists(first));
            f.write();
            doThrow(new IllegalStateException("unknown commit")).when(f.manager).commit(f.status);
            assertThrows(AmbiguousFlushCommitException.class, f.worker::flushOnce);
            when(f.probe.probe()).thenReturn(new FlushDbState(1, DbBootstrapState.INITIALIZED));
            clearInvocations(storage);
            assertThrows(UnresolvedFlushCommitException.class, f.worker::flushOnce);
            f.retention.retryIfEligible();
            verify(storage, never()).deleteCommittedPrefix(anyLong());
            verify(storage, never()).openDirectory(any());
            verify(f.dirty, never()).restoreDirtyTiles(any());
            assertTrue(f.store.current().isPresent());
        }
    }

    @Test
    void actualCapturedPlanCanCommitAfterFatalButCannotStartRetention() throws Exception {
        try (var storage = spy(storage(directory.resolve("wal"), 1))) {
            var f = fixture(storage);
            f.write(); f.write();
            doAnswer(inv -> { f.checkpoint.set(f.target.get()); f.ready.markFatalNotReady(); return null; }).when(f.manager).commit(f.status);
            assertEquals(FlushRunResult.COMMITTED, f.worker.flushOnce());
            assertEquals(2, f.checkpoint.get());
            assertFalse(f.ready.isReady());
            verify(f.capture).capturePlan();
            verify(storage, never()).deleteCommittedPrefix(anyLong());
            verify(f.dirty, never()).restoreDirtyTiles(any());
            assertTrue(f.dirty.drainDirtyTiles().isEmpty());
            assertTrue(Files.exists(storage.segmentPath(0)));
        }
    }

    private static void parked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(Thread.State.WAITING, thread.getState(), "Command must wait on retention coordinator");
    }

    /** DB port의 관측값만 대체하고 command→파일→memory→plan→실제 executor→retention 연결 유지 */
    private static class Fixture {
        final InMemoryTileBoard board = spy(new InMemoryTileBoard());
        final ServiceReadiness ready = new ServiceReadiness();
        final SynchronizedDirtyTileTracker dirty = spy(new SynchronizedDirtyTileTracker());
        final FlushBoundaryCoordinator coordinator = new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled());
        final FlushSingleFlightGuard guard = new FlushSingleFlightGuard();
        final PendingAmbiguousFlushStore store = spy(new PendingAmbiguousFlushStore());
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = mock(TransactionStatus.class);
        final FlushDbStateProbe probe = mock(FlushDbStateProbe.class);
        final AtomicLong checkpoint = new AtomicLong(), target = new AtomicLong();
        final FlushPlanCaptureService capture;
        final FlushWalRetention retention;
        final FlushWorker worker;
        final PixelCommandService command;
        final List<Object> ownedMocks = new ArrayList<>();

        Fixture(SegmentedWalStorage storage) {
            var keys = new CanonicalZ0TileKeys();
            var checkpointReader = mock(CheckpointReader.class);
            var metadata = mock(TileMetadataReader.class);
            when(checkpointReader.readMainCheckpoint()).thenAnswer(inv -> new CheckpointSnapshot(checkpoint.get()));
            // B 경합은 canonical 초기 DB가 이미 있는 상태. 불필요한 full-bootstrap spy payload 보유 방지
            // 최초 1,024 snapshot 원자성은 기존 capture/MySQL 회귀의 실제 assertion 유지
            when(metadata.readAllTileKeys()).thenReturn(keys.orderedKeys());
            capture = spy(new FlushPlanCaptureService(ready, checkpointReader, metadata, new DbBootstrapClassifier(keys),
                    coordinator, new FileWalReplaySource(storage), dirty, board, dev.cgt.pixelplace.measurement.Measurements.disabled()));
            var persistence = mock(FlushPersistenceService.class);
            doAnswer(inv -> { target.set(((FlushPlan) inv.getArgument(0)).flushTargetEventSeq()); return null; }).when(persistence).persist(any());
            when(manager.getTransaction(any())).thenReturn(status);
            doAnswer(inv -> { checkpoint.set(target.get()); return null; }).when(manager).commit(status);
            retention = spy(new FlushWalRetention(coordinator, ready, store, storage, dev.cgt.pixelplace.measurement.Measurements.disabled()));
            worker = new FlushWorker(guard, ready, capture, new ProgrammaticFlushTransactionExecutor(manager, persistence, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                    store, new FlushReconciliationService(probe), dirty, retention, dev.cgt.pixelplace.measurement.Measurements.disabled());
            command = new PixelCommandService(mock(PixelCooldown.class),
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(coordinator, new PixelWriteService(new EventSeqManager(), new FileWalAppender(storage, dev.cgt.pixelplace.measurement.Measurements.disabled()), board, ready, dev.cgt.pixelplace.measurement.Measurements.disabled()), dirty, ready, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                mock(PixelBroadcastService.class),
                ready,
                new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),
                dev.cgt.pixelplace.measurement.Measurements.disabled());
            ready.markReady();
            ownedMocks.addAll(List.of(board, dirty, store, manager, status, probe, checkpointReader,
                    metadata, capture, persistence, retention));
        }

        PixelWriteResult write() { return command.writePixel(7, 0, 0, 17); }

        void releaseMocks() {
            for (Object owned : ownedMocks) org.mockito.Mockito.framework().clearInlineMock(owned);
            ownedMocks.clear();
        }
    }
}
