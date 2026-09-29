package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** T17-15: 새로운 내구성 I/O owner가 storage monitor를 소유하는 동안 모든 파일 작업/close 진입 차단 */
class SegmentedWalDurabilityConcurrencyTest {
    @TempDir Path root;
    @ParameterizedTest
    @ValueSource(strings={"prepare","R","last-S","create-S","delete-Q","delete-S"})
    void appendScanRetentionAndCloseStaySerializedAcrossDurabilityIo(String point)throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var armed=new AtomicBoolean();
        var durability=spy(new TestWalFileDurability());
        Runnable hold=()-> {
            if(!armed.compareAndSet(true,false))return;
            entered.countDown();
            try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture release timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        };
        if(point.equals("prepare"))doAnswer(c->{hold.run();return c.callRealMethod();}).when(durability).prepareDirectory(any());
        else if(point.equals("R"))doAnswer(c->{hold.run();return c.callRealMethod();}).when(durability).syncRecoveredFile(any());
        else if(point.equals("delete-Q"))doAnswer(c->{hold.run();return c.callRealMethod();}).when(durability).queryName(any(),any());
        else doAnswer(c->{hold.run();return c.callRealMethod();}).when(durability).syncDirectory(any());
        var threads=new CopyOnWriteArrayList<Thread>();
        var executor=Executors.newFixedThreadPool(5,r->{Thread t=new Thread(r);threads.add(t);return t;});
        try(var storage=new ControlledStorage(root.resolve("wal"),1,durability)) {
            if(!point.equals("create-S")) {records(storage.segmentPath(0),2);records(storage.segmentPath(1),5);records(storage.segmentPath(2),9);}
            var batch=storage.readAfter(0);
            if(point.startsWith("delete"))storage.prepareForRecovery(batch);
            armed.set(true);
            Future<?> owner=executor.submit(()->{
                if(point.startsWith("delete"))storage.deleteCommittedPrefix(5);
                else if(point.equals("create-S")||point.equals("prepare"))storage.appendAndFsync(record(point.equals("create-S")?2:12));
                else storage.prepareForRecovery(batch);
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var attempts=new CountDownLatch(4);var futures=new ArrayList<Future<?>>();
            for(Runnable action:List.<Runnable>of(()->storage.appendAndFsync(record(14)),()->storage.readAfter(0),
                    ()->storage.deleteCommittedPrefix(2),storage::close)) {
                futures.add(executor.submit(()->{attempts.countDown();try{action.run();}catch(IllegalStateException closed){assertTrue(closed.getMessage().contains("closed"));}}));
            }
            assertTrue(attempts.await(5,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(threads.stream().filter(t->t.getState()==Thread.State.BLOCKED).count()!=4 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(4,threads.stream().filter(t->t.getState()==Thread.State.BLOCKED).count());
            assertTrue(futures.stream().noneMatch(Future::isDone));
            release.countDown();owner.get(5,TimeUnit.SECONDS);
            for(var future:futures)future.get(5,TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class,()->storage.readAfter(0));
        } finally {
            release.countDown();executor.shutdown();
            if(!executor.awaitTermination(10,TimeUnit.SECONDS)){executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
            for(Thread thread:threads)thread.join(5000);
            assertTrue(threads.stream().noneMatch(Thread::isAlive));
        }
    }
}
