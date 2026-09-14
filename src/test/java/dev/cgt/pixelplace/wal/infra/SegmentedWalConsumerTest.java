package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.application.*;
import dev.cgt.pixelplace.tile.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static dev.cgt.pixelplace.wal.infra.SegmentedWalConcurrencyTest.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 storage·port·command·recovery·plan 경계 검증. DB transaction 신규 통합 시나리오는 C 소유 */
class SegmentedWalConsumerTest {
    @TempDir Path directory;
    private static final TileKey KEY=new TileKey(0,0,0);

    @Test
    void actualRecoveryAndPlanUseAllSegmentsAndWalBuildsPlanWithoutDirty() throws Exception {
        try(var storage=storage(directory.resolve("wal"),1)) {
            records(storage.segmentPath(0),2,5);records(storage.segmentPath(1),9);records(storage.segmentPath(2),14,21);
            Fixture fixture=new Fixture(storage);
            fixture.recovery.recover();
            assertTrue(fixture.ready.isReady());assertEquals(21,fixture.sequence.currentLastIssued());
            assertEquals(17,fixture.board.getRequired(KEY).pixels()[0]);
            var plan=fixture.capture.capturePlan();
            assertEquals(21,plan.flushTargetEventSeq());
            assertEquals(List.of(record(2),record(5),record(9),record(14),record(21)),plan.walRecords());
            assertEquals(1024,plan.tileSnapshots().size());assertTrue(plan.drainedDirtyTiles().isEmpty());
            verify(fixture.dirty).drainDirtyTiles();
        }
    }

    @Test
    void noOpWithEmptyActiveKeepsDirtyAndCheckpointTailEquality() throws Exception {
        try(var storage=storage(directory.resolve("wal"),1)) {
            records(storage.segmentPath(4),9);records(storage.segmentPath(5));
            Fixture fixture=new Fixture(storage);
            fixture.ready.markReady();fixture.checkpoint(9);
            fixture.dirty.markDirty(KEY,9,1);clearInvocations(fixture.dirty);
            assertTrue(fixture.capture.capturePlan().noOp());
            verify(fixture.dirty,never()).drainDirtyTiles();
            assertEquals(1,fixture.dirty.drainDirtyTiles().size());
        }
    }

    @Test
    void corruptScanStopsRecoveryMemoryAndPlanDirtyBeforeAnyPartialResult() throws Exception {
        Path base=directory.resolve("wal");
        try(var storage=storage(base,1)) {
            Files.writeString(base,"partial");
            Fixture fixture=new Fixture(storage);clearInvocations(fixture.board);
            assertThrows(IllegalStateException.class,fixture.recovery::recover);
            assertFalse(fixture.ready.isReady());assertEquals(0,fixture.sequence.currentLastIssued());
            verifyNoInteractions(fixture.board);
        }
        try(var storage=storage(base,1)) {
            Fixture fixture=new Fixture(storage);fixture.ready.markReady();
            assertThrows(IllegalStateException.class,fixture.capture::capturePlan);
            verify(fixture.dirty,never()).drainDirtyTiles();
            assertEquals("partial",Files.readString(base));
        }
    }

    @Test
    void actualWalFailureStopsMemoryDirtyAndPostprocessingThenRejectsNotReady() throws Exception {
        try(var storage=new ControlledStorage(directory.resolve("wal"),1)) {
            IOException io=new IOException("record force");
            storage.setup=(channel,index)->doThrow(io).when(channel).force(true);
            Fixture fixture=new Fixture(storage);fixture.recovery.recover();clearInvocations(fixture.board,fixture.dirty);
            RuntimeException first=assertThrows(IllegalStateException.class,()->fixture.command.writePixel(77,0,0,17));
            assertSame(io,first.getCause().getCause());assertFalse(fixture.ready.isReady());
            verify(fixture.board,never()).applyPixel(anyInt(),anyInt(),anyInt());
            verify(fixture.dirty,never()).markDirty(any(),anyLong(),anyLong());
            verify(fixture.cooldown,never()).startCooldown(anyLong());verifyNoInteractions(fixture.broadcast);
            assertThrows(ServiceNotReadyException.class,()->fixture.command.writePixel(77,0,0,17));
        }
    }

    @Test
    void planWaitsThroughForceMemoryAndDirtyBeforeCapturingActualCommandBoundary() throws Exception {
        CountDownLatch forced=new CountDownLatch(1), releaseForce=new CountDownLatch(1), marking=new CountDownLatch(1), releaseDirty=new CountDownLatch(1), readingDb=new CountDownLatch(1);
        try(var storage=spy(new ControlledStorage(directory.resolve("wal"),1));var executor=Executors.newFixedThreadPool(2)) {
            var scans=new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(inv->{scans.incrementAndGet();return inv.callRealMethod();}).when(storage).openDirectory(any());
            storage.setup=(channel,index)->doAnswer(inv->{forced.countDown();await(releaseForce);return inv.callRealMethod();}).when(channel).force(true);
            Fixture fixture=new Fixture(storage);fixture.recovery.recover();clearInvocations(fixture.board,fixture.dirty,storage);
            scans.set(0);
            doAnswer(inv->{marking.countDown();await(releaseDirty);return inv.callRealMethod();}).when(fixture.dirty).markDirty(any(),anyLong(),anyLong());
            when(fixture.checkpoint.readMainCheckpoint()).thenAnswer(inv->{readingDb.countDown();return new CheckpointSnapshot(0);});
            try {
                var command=executor.submit(()->fixture.command.writePixel(77,0,0,17));await(forced);
                verify(fixture.board,never()).applyPixel(anyInt(),anyInt(),anyInt());
                verify(fixture.dirty,never()).markDirty(any(),anyLong(),anyLong());assertFalse(command.isDone());
                AtomicReference<Thread> captureThread=new AtomicReference<>();
                var capture=executor.submit(()->{captureThread.set(Thread.currentThread());return fixture.capture.capturePlan();});
                await(readingDb); awaitParked(captureThread.get());
                assertEquals(1,scans.get());
                releaseForce.countDown();await(marking);
                assertFalse(command.isDone());assertFalse(capture.isDone());
                assertEquals(1,scans.get());
                releaseDirty.countDown();assertEquals(1,command.get(5,TimeUnit.SECONDS).eventSeq());
                var plan=capture.get(5,TimeUnit.SECONDS);
                assertEquals(1,plan.flushTargetEventSeq());assertEquals(77,plan.walRecords().getFirst().userId());
                assertEquals(1,plan.drainedDirtyTiles().size());
                assertEquals(17,plan.tileSnapshots().getFirst().pixels()[0]);
                assertEquals(1,plan.tileSnapshots().getFirst().tileVersion());
            } finally {releaseForce.countDown();releaseDirty.countDown();executor.shutdownNow();}
        }
    }

    private static void awaitParked(Thread thread) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(thread.getState()!=Thread.State.WAITING && System.nanoTime()<deadline) Thread.onSpinWait();
        assertEquals(Thread.State.WAITING,thread.getState(),"Plan must wait on command coordinator");
    }

    private static final class Fixture {
        final CanonicalZ0TileKeys keys=new CanonicalZ0TileKeys();
        final InMemoryTileBoard board=spy(new InMemoryTileBoard());
        final ServiceReadiness ready=new ServiceReadiness();
        final EventSeqManager sequence=new EventSeqManager();
        final SynchronizedDirtyTileTracker dirty=spy(new SynchronizedDirtyTileTracker());
        final CheckpointReader checkpoint=mock(CheckpointReader.class);
        final TileMetadataReader metadata=mock(TileMetadataReader.class);
        final PixelCooldown cooldown=mock(PixelCooldown.class);
        final PixelBroadcastService broadcast=mock(PixelBroadcastService.class);
        final StartupRecoveryService recovery;
        final FlushPlanCaptureService capture;
        final PixelCommandService command;

        Fixture(SegmentedWalStorage storage) {
            var coordinator=new FlushBoundaryCoordinator();
            var reader=new FileWalReplaySource(storage);
            var db=mock(StartupRecoveryDbViewCaptureService.class);
            when(db.capture()).thenReturn(new StartupRecoveryDbView(new CheckpointSnapshot(0),TileLoadResult.allMissingResult()));
            var classifier=new DbBootstrapClassifier(keys);
            when(checkpoint.readMainCheckpoint()).thenReturn(new CheckpointSnapshot(0));
            when(metadata.readAllTileKeys()).thenReturn(List.of());
            recovery=new StartupRecoveryService(db,classifier,keys,reader,board,sequence,ready);
            capture=new FlushPlanCaptureService(ready,checkpoint,metadata,classifier,coordinator,reader,dirty,board);
            command=new PixelCommandService(cooldown,coordinator,new PixelWriteService(sequence,new FileWalAppender(storage),board,ready),dirty,broadcast,ready);
        }
        void checkpoint(long seq) {
            when(checkpoint.readMainCheckpoint()).thenReturn(new CheckpointSnapshot(seq));
            when(metadata.readAllTileKeys()).thenReturn(keys.orderedKeys());
        }
    }
}
