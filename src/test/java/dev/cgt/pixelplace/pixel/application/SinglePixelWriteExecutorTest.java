package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.domain.TileMutationResult;
import dev.cgt.pixelplace.wal.application.WalAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 종료와 실제 작업의 선후 관계 검증. 대기 요청도 storage 사용자로 계수 */
class SinglePixelWriteExecutorTest {
    @ParameterizedTest
    @ValueSource(strings={"boundary", "force", "memory", "dirty"})
    void closeDrainsRegisteredRequestAtEveryBoundaryAndRejectsNewRequests(String stage) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var measurement = Measurements.disabled();
        var ready = new ServiceReadiness(); ready.markReady();
        var coordinator = new FlushBoundaryCoordinator(measurement);
        var wal = mock(WalAppender.class); var board = mock(InMemoryTileBoard.class); var dirty = mock(DirtyTileTracker.class);
        doAnswer(call -> { if(stage.equals("force")) pause(entered,release); return null; }).when(wal).appendAndFsync(any());
        when(board.applyPixel(1,2,3)).thenAnswer(call -> {
            if(stage.equals("memory")) pause(entered,release);
            return new TileMutationResult(new TileKey(0,0,0),1);
        });
        doAnswer(call -> { if(stage.equals("dirty")) pause(entered,release); return null; }).when(dirty).markDirty(any(),anyLong(),anyLong());
        var executor = new SinglePixelWriteExecutor(coordinator, new PixelWriteService(new EventSeqManager(),wal,board,ready,measurement),dirty,ready,measurement);
        try(var threads=Executors.newFixedThreadPool(3)) {
            Future<?> holder = stage.equals("boundary") ? threads.submit(() -> coordinator.coordinate(() -> { pause(entered,release); return null; })) : null;
            if(holder!=null) assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<PixelWriteResult> work=threads.submit(() -> executor.execute(7,1,2,3));
            await(() -> executor.snapshot().activeCount()==1);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> closing=threads.submit(executor::close);
            await(() -> !executor.snapshot().accepting());
            assertEquals(1,executor.snapshot().activeCount()); assertFalse(executor.snapshot().closed());
            assertThrows(ServiceNotReadyException.class,()->executor.execute(8,1,2,3));
            assertThrows(TimeoutException.class,()->closing.get(50,TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1,work.get(5,TimeUnit.SECONDS).eventSeq()); closing.get(5,TimeUnit.SECONDS);
            if(holder!=null) holder.get(5,TimeUnit.SECONDS);
            assertEquals(new ExecutionSnapshot(0,0,0,0,0,ExecutionSnapshot.State.STOPPED,0,false),executor.snapshot());
            executor.close(); verify(dirty).markDirty(new TileKey(0,0,0),1,1);
        } finally { release.countDown(); }
    }

    @Test void executeAndCloseRaceAlwaysChoosesRegisteredCompletionOrRejection() throws Exception {
        var measure=Measurements.disabled(); var ready=new ServiceReadiness(); ready.markReady();
        var core=mock(PixelWriteService.class); var dirty=mock(DirtyTileTracker.class);
        when(core.writePixel(1,1,2,3)).thenReturn(new PixelWriteResult(1,new TileKey(0,0,0),1,1,2,3));
        try(var threads=Executors.newFixedThreadPool(2)) {
            for(int n=0;n<40;n++) {
                var executor=new SinglePixelWriteExecutor(new FlushBoundaryCoordinator(measure),core,dirty,ready,measure);
                var start=new CyclicBarrier(2);
                var execute=threads.submit(()->{start.await();try{return executor.execute(1,1,2,3);}catch(ServiceNotReadyException rejected){return null;}});
                var close=threads.submit(()->{start.await();executor.close();return null;});
                execute.get(5,TimeUnit.SECONDS); close.get(5,TimeUnit.SECONDS);
                assertEquals(new ExecutionSnapshot(0,0,0,0,0,ExecutionSnapshot.State.STOPPED,0,false),executor.snapshot());
            }
        }
    }

    @Test void dirtyErrorPreservesIdentityInstallsWriteFailureAndReturnsActivity() {
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();
        var core=mock(PixelWriteService.class);var dirty=mock(DirtyTileTracker.class);
        when(core.writePixel(1,1,2,3)).thenReturn(new PixelWriteResult(1,new TileKey(0,0,0),1,1,2,3));
        var fatal=new AssertionError("dirty");var prior=new RuntimeException("prior");fatal.addSuppressed(prior);
        doThrow(fatal).when(dirty).markDirty(any(),anyLong(),anyLong());
        var executor=new SinglePixelWriteExecutor(new FlushBoundaryCoordinator(measure),core,dirty,ready,measure);
        assertSame(fatal,assertThrows(AssertionError.class,()->executor.execute(1,1,2,3)));
        assertArrayEquals(new Throwable[]{prior},fatal.getSuppressed());assertEquals(0,executor.snapshot().activeCount());
        ready.markReady();assertFalse(ready.isReady());assertDoesNotThrow(ready::requireNotFatal);
    }

    static void pause(CountDownLatch entered,CountDownLatch release) {
        entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
}
