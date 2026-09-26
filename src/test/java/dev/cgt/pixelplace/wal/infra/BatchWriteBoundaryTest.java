package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.application.*;
import dev.cgt.pixelplace.tile.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 batch WAL/core/coordinator와 capture·retention·transaction의 경쟁 검증 */
class BatchWriteBoundaryTest {
    @TempDir Path directory;
    static final TileKey KEY=new TileKey(0,0,0);
    static List<PixelWriteRequest> requests(int first) {return List.of(new PixelWriteRequest(first,0,0,first),new PixelWriteRequest(first+1,0,0,first+1),new PixelWriteRequest(first+2,0,0,first+2));}

    @ParameterizedTest
    @ValueSource(strings={"force","rotation","memory","force-fatal","rotation-fatal","memory-fatal"})
    void captureAndRetentionCannotObserveWalOnlyStateEvenImmediatelyAfterFatal(String stage) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var capturing=new CountDownLatch(1);
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();
        var boundary=new FlushBoundaryCoordinator(measure);var board=spy(new InMemoryTileBoard());var dirty=new SynchronizedDirtyTileTracker();
        var seq=new EventSeqManager();var checkpoint=new AtomicLong(1);var captured=new AtomicReference<FlushPlan>();
        var fatal=new IllegalStateException("injected");boolean fail=stage.endsWith("fatal");
        try(var storage=spy(new ControlledStorage(directory.resolve("wal"),stage.startsWith("rotation")?1:10000));var threads=Executors.newFixedThreadPool(3)) {
            var core=new PixelWriteService(seq,new FileWalAppender(storage,measure),board,ready,measure);
            boundary.coordinate(()->core.writePixel(1,0,0,1));
            if(stage.startsWith("force"))doAnswer(call->{pause(entered,release);if(fail)throw fatal;return call.callRealMethod();}).when(storage.writers.getFirst()).force(true);
            if(stage.startsWith("rotation"))storage.setup=(channel,index)->{if(index==1)doAnswer(call->{pause(entered,release);if(fail)throw fatal;return call.callRealMethod();}).when(channel).force(true);};
            if(stage.startsWith("memory"))doAnswer(call->{pause(entered,release);if(fail)throw fatal;return call.callRealMethod();}).when(board).applyPixel(0,0,2);
            var keys=new CanonicalZ0TileKeys();var pending=new PendingAmbiguousFlushStore();
            var capture=new FlushPlanCaptureService(ready,()->{capturing.countDown();return new CheckpointSnapshot(checkpoint.get());},keys::orderedKeys,new DbBootstrapClassifier(keys),boundary,storage::readAfter,dirty,board,measure);
            var retention=new FlushWalRetention(boundary,ready,pending,storage,measure);
            FlushTransactionExecutor transaction=plan->{captured.set(plan);checkpoint.set(plan.flushTargetEventSeq());return FlushTransactionResult.committed();};
            var worker=new FlushWorker(new FlushSingleFlightGuard(),ready,capture,transaction,pending,mock(FlushReconciliationService.class),dirty,retention,measure);
            try {
                var batch=new BatchWriteGuard(requests(2));
                var writing=threads.submit(()->boundary.coordinate(()->core.writeBatch(batch,r->dirty.markDirty(r.tileKey(),r.eventSeq(),r.tileVersion()))));
                assertTrue(entered.await(5,TimeUnit.SECONDS));var flush=threads.submit(worker::flushOnce);assertTrue(capturing.await(5,TimeUnit.SECONDS));
                var cleaning=threads.submit(()->retention.onCommitConfirmed(1));
                assertThrows(TimeoutException.class,()->flush.get(50,TimeUnit.MILLISECONDS));assertThrows(TimeoutException.class,()->cleaning.get(50,TimeUnit.MILLISECONDS));
                assertNull(captured.get());assertEquals(1,checkpoint.get());
                release.countDown();var result=writing.get(5,TimeUnit.SECONDS);cleaning.get(5,TimeUnit.SECONDS);
                if(fail) {
                    assertNotNull(result.fatalFailure());assertInstanceOf(ServiceNotReadyException.class,assertThrows(ExecutionException.class,()->flush.get(5,TimeUnit.SECONDS)).getCause());
                    assertNull(captured.get());assertEquals(1,checkpoint.get());ready.markReady();assertFalse(ready.isReady());
                    assertThrows(ServiceNotReadyException.class,capture::capturePlan);
                } else {
                    assertNull(result.fatalFailure());assertEquals(FlushRunResult.COMMITTED,flush.get(5,TimeUnit.SECONDS));
                    assertEquals(4,checkpoint.get());assertEquals(4,captured.get().flushTargetEventSeq());
                    assertEquals(4,captured.get().tileSnapshots().getFirst().tileVersion());assertEquals(4,captured.get().tileSnapshots().getFirst().pixels()[0]);
                    assertEquals(4,storage.readAfter(4).walLastEventSeq());
                }
            } finally {release.countDown();threads.shutdownNow();org.mockito.Mockito.framework().clearInlineMock(board);}
        }
    }

    @Test void transactionOutsideBoundaryAllowsNextBatchWhileCapturedPlanRemainsFixed() throws Exception {
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();var boundary=new FlushBoundaryCoordinator(measure);
        var board=new InMemoryTileBoard();var dirty=new SynchronizedDirtyTileTracker();var seq=new EventSeqManager();var keys=new CanonicalZ0TileKeys();var pending=new PendingAmbiguousFlushStore();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var checkpoint=new AtomicLong();var captured=new AtomicReference<FlushPlan>();
        try(var storage=storage(directory.resolve("wal"),10000);var threads=Executors.newFixedThreadPool(2)) {
            var core=new PixelWriteService(seq,new FileWalAppender(storage,measure),board,ready,measure);
            boundary.coordinate(()->core.writeBatch(new BatchWriteGuard(requests(1)),r->dirty.markDirty(r.tileKey(),r.eventSeq(),r.tileVersion())));
            var capture=new FlushPlanCaptureService(ready,()->new CheckpointSnapshot(checkpoint.get()),keys::orderedKeys,new DbBootstrapClassifier(keys),boundary,storage::readAfter,dirty,board,measure);
            var retention=new FlushWalRetention(boundary,ready,pending,storage,measure);
            FlushTransactionExecutor transaction=plan->{captured.set(plan);pause(entered,release);checkpoint.set(plan.flushTargetEventSeq());return FlushTransactionResult.committed();};
            var worker=new FlushWorker(new FlushSingleFlightGuard(),ready,capture,transaction,pending,mock(FlushReconciliationService.class),dirty,retention,measure);
            try {
                var flush=threads.submit(worker::flushOnce);assertTrue(entered.await(5,TimeUnit.SECONDS));
                var next=threads.submit(()->boundary.coordinate(()->core.writeBatch(new BatchWriteGuard(requests(4)),r->dirty.markDirty(r.tileKey(),r.eventSeq(),r.tileVersion()))));
                assertNull(next.get(5,TimeUnit.SECONDS).fatalFailure());assertEquals(6,seq.currentLastIssued());
                assertEquals(3,captured.get().flushTargetEventSeq());assertEquals(3,captured.get().tileSnapshots().getFirst().tileVersion());assertEquals(3,captured.get().tileSnapshots().getFirst().pixels()[0]);
                release.countDown();assertEquals(FlushRunResult.COMMITTED,flush.get(5,TimeUnit.SECONDS));assertEquals(3,checkpoint.get());
                assertEquals(6,capture.capturePlan().flushTargetEventSeq());
            }finally{release.countDown();threads.shutdownNow();}
        }
    }

    static void pause(CountDownLatch entered,CountDownLatch release) {entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
}
