package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** latch와 실제 monitor 대기를 사용해 append·scan·회전·종료 중간 상태 노출 방지 검증 */
class SegmentedWalConcurrencyTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings={"success","io","error"})
    void readerWaitsForRecordForceAndSeesOnlySuccessOrPoison(String outcome) throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), reading=new CountDownLatch(1);
        Error error=new AssertionError("force error");
        try(var storage=new ControlledStorage(directory.resolve("wal"),1000); var executor=Executors.newFixedThreadPool(2)) {
            storage.setup=(channel,index)->doAnswer(inv->{
                entered.countDown(); await(release);
                if(outcome.equals("io")) throw new IOException("force failure");
                if(outcome.equals("error")) throw error;
                return inv.callRealMethod();
            }).when(channel).force(true);
            try {
                Future<?> append=executor.submit(()->storage.appendAndFsync(record(2)));
                await(entered);
                AtomicReference<Thread> readerThread=new AtomicReference<>();
                var scan=executor.submit(()->{readerThread.set(Thread.currentThread());reading.countDown();return storage.readAfter(0);});
                await(reading); blocked(readerThread.get());
                assertFalse(append.isDone()); assertFalse(scan.isDone());
                release.countDown();
                if(outcome.equals("success")) {
                    append.get(5,TimeUnit.SECONDS);
                    assertEquals(2,scan.get(5,TimeUnit.SECONDS).walLastEventSeq());
                } else {
                    ExecutionException failed=assertThrows(ExecutionException.class,()->append.get(5,TimeUnit.SECONDS));
                    if(outcome.equals("error")) assertSame(error,failed.getCause());
                    assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->scan.get(5,TimeUnit.SECONDS)).getCause());
                }
            } finally {release.countDown();executor.shutdownNow();}
        }
    }

    @Test
    void scanAndNextAppendCannotObserveRotationBetweenOldCloseAndCreate() throws Exception {
        CountDownLatch closing=new CountDownLatch(1), release=new CountDownLatch(1), scanStarted=new CountDownLatch(1), appendStarted=new CountDownLatch(1);
        try(var storage=new ControlledStorage(directory.resolve("wal"),1); var executor=Executors.newFixedThreadPool(3)) {
            storage.setup=(channel,index)->{if(index==0) doAnswer(inv->{closing.countDown();await(release);return inv.callRealMethod();}).when(channel).close();};
            storage.appendAndFsync(record(2));
            try {
                var rotate=executor.submit(()->storage.appendAndFsync(record(5))); await(closing);
                AtomicReference<Thread> scanner=new AtomicReference<>(), writer=new AtomicReference<>();
                var scan=executor.submit(()->{scanner.set(Thread.currentThread());scanStarted.countDown();return storage.readAfter(0);});
                var next=executor.submit(()->{writer.set(Thread.currentThread());appendStarted.countDown();storage.appendAndFsync(record(9));});
                await(scanStarted);await(appendStarted);blocked(scanner.get());blocked(writer.get());
                release.countDown(); rotate.get(5,TimeUnit.SECONDS); next.get(5,TimeUnit.SECONDS);
                var batch=scan.get(5,TimeUnit.SECONDS);
                assertTrue(batch.walLastEventSeq()>=5);
                assertEquals(batch.walLastEventSeq(),batch.records().getLast().eventSeq());
                assertEquals(9,storage.readAfter(0).walLastEventSeq());
            } finally {release.countDown();executor.shutdownNow();}
        }
    }

    @Test
    void closeWaitsForActualReaderResourcesAndPermanentlyPreventsReopen() throws Exception {
        Path base=directory.resolve("wal");
        CountDownLatch reading=new CountDownLatch(1), release=new CountDownLatch(1), closeStarted=new CountDownLatch(1);
        try(var storage=spy(storage(base,1000)); var executor=Executors.newFixedThreadPool(2)) {
            records(base,2);
            BufferedReader actual=spy(java.nio.file.Files.newBufferedReader(base));
            doAnswer(inv->{reading.countDown();await(release);return inv.callRealMethod();}).when(actual).readLine();
            doReturn(actual).when(storage).openReader(base);
            try {
                var scan=executor.submit(()->storage.readAfter(0));await(reading);
                AtomicReference<Thread> closer=new AtomicReference<>();
                var close=executor.submit(()->{closer.set(Thread.currentThread());closeStarted.countDown();storage.close();});
                await(closeStarted);blocked(closer.get());verify(actual,never()).close();
                release.countDown();assertEquals(2,scan.get(5,TimeUnit.SECONDS).walLastEventSeq());close.get(5,TimeUnit.SECONDS);
                verify(actual,times(1)).close();
                clearInvocations(storage);
                assertThrows(IllegalStateException.class,()->storage.readAfter(0));
                assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(5)));
                verify(storage,never()).openDirectory(any());
            } finally {release.countDown();executor.shutdownNow();actual.close();}
        }
    }

    static void await(CountDownLatch latch) {
        try {assertTrue(latch.await(5,TimeUnit.SECONDS),"Latch deadline");}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    static void blocked(Thread thread) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(thread.getState()!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.onSpinWait();
        assertEquals(Thread.State.BLOCKED,thread.getState(),"Caller must wait for the storage monitor");
    }
}
