package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.*;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutorTest.*;

/** 접수/claim/terminal/게시 수명을 제어하여 단순 sleep 타이밍에 의존하지 않는 worker 계약 검증 */
class GroupPixelWriteExecutorTest {
    static final class Fixture implements AutoCloseable {
        final ServiceReadiness ready = new ServiceReadiness();
        final FlushBoundaryCoordinator boundary = spy(new FlushBoundaryCoordinator(Measurements.disabled()));
        final WalAppender wal = mock(WalAppender.class);
        final InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        final AtomicLong version = new AtomicLong();
        final DirtyTileTracker dirty = mock(DirtyTileTracker.class);
        final EventSeqManager seq = new EventSeqManager();
        final WriteExecutionProperties properties = new WriteExecutionProperties();
        final PixelWriteService core = new PixelWriteService(seq,wal,board,ready,Measurements.disabled());
        final ExecutorService callers = Executors.newFixedThreadPool(24);
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<List<WalRecord>> batches = new CopyOnWriteArrayList<>();
        GroupPixelWriteExecutor executor;
        Fixture() {
            ready.markReady();properties.getGroup().setQueueTimeout(Duration.ofSeconds(30));
            when(board.applyPixel(anyInt(),anyInt(),anyInt())).thenAnswer(call ->
                    new TileMutationResult(new TileKey(0,0,0),version.incrementAndGet()));
            doAnswer(call -> { List<WalRecord> records=call.getArgument(0);batches.add(records);
                if(batches.size()==1)pause(entered,release);return null; }).when(wal).appendBatchAndFsync(anyList());
        }
        void start() { start(System::nanoTime,Thread::start); }
        void start(LongSupplier clock,Consumer<Thread> starter) {
            executor=new GroupPixelWriteExecutor(boundary,core,dirty,ready,properties,Measurements.disabled(),clock,starter);
        }
        Future<PixelWriteResult> send(int id) {return callers.submit(()->executor.execute(id,0,0,id%256));}
        void first() throws Exception {send(1);assertTrue(entered.await(5,TimeUnit.SECONDS));}
        @Override public void close() {release.countDown();if(executor!=null)executor.close();callers.shutdownNow();org.mockito.Mockito.framework().clearInlineMock(board);}
    }

    @Test void fifoClaimsOnlyAlreadyQueuedUpToSixteenAndUsesOnePlatformWorker() throws Exception {
        try(var f=new Fixture()) {
            f.start();f.first();var pending=new ArrayList<Future<PixelWriteResult>>();
            for(int id=2;id<=21;id++){pending.add(f.send(id));int queued=id-1;await(()->f.executor.snapshot().queued()==queued);}
            assertEquals(21,f.executor.snapshot().outstanding());assertEquals(1,f.executor.snapshot().claimedUnsettled());
            verify(f.board,never()).applyPixel(anyInt(),anyInt(),anyInt());verifyNoInteractions(f.dirty);
            f.release.countDown();for(var future:pending)future.get(5,TimeUnit.SECONDS);
            f.executor.close();assertEquals(List.of(1,16,4),f.batches.stream().map(List::size).toList());
            assertEquals(java.util.stream.LongStream.rangeClosed(1,21).boxed().toList(),f.batches.stream().flatMap(List::stream).map(WalRecord::userId).toList());
            assertEquals(21,f.version.get());
            assertTrue(f.executor.snapshot().closed());assertEquals(0,f.executor.snapshot().outstanding());
        }
    }

    @Test void capacityIncludesClaimedAndCancelledPermitReturnsExactlyOnce() throws Exception {
        try(var f=new Fixture()) {
            f.properties.getGroup().setMaxBatchSize(2);f.properties.getGroup().setMaxOutstanding(2);f.start();f.first();
            var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
            var queued=Thread.ofPlatform().start(()->{try{f.executor.execute(2,0,0,2);}catch(Throwable ex){failure.set(ex);}interrupted.set(Thread.currentThread().isInterrupted());});
            await(()->f.executor.snapshot().queued()==1);assertThrows(PixelWriteBusyException.class,()->f.executor.execute(3,0,0,3));
            queued.interrupt();queued.join(5000);assertFalse(queued.isAlive());assertInstanceOf(PixelWriteBusyException.class,failure.get());assertTrue(interrupted.get());
            assertEquals(1,f.executor.snapshot().outstanding());assertEquals(0,f.executor.snapshot().queued());
            var next=f.send(3);await(()->f.executor.snapshot().queued()==1);f.release.countDown();next.get(5,TimeUnit.SECONDS);
            f.executor.close();assertEquals(List.of(1L,3L),f.batches.stream().flatMap(List::stream).map(WalRecord::userId).toList());
            assertEquals(0,f.executor.snapshot().outstanding());
        }
    }

    @Test void claimedInterruptWaitsForTerminalAndRestoresFlagWithoutInterruptingWorker() throws Exception {
        try(var f=new Fixture()) {
            f.start();var result=new AtomicReference<PixelWriteResult>();var interrupted=new AtomicBoolean();
            var caller=Thread.ofPlatform().start(()->{result.set(f.executor.execute(1,0,0,1));interrupted.set(Thread.currentThread().isInterrupted());});
            assertTrue(f.entered.await(5,TimeUnit.SECONDS));caller.interrupt();
            assertNull(result.get());assertEquals(1,f.executor.snapshot().claimedUnsettled());
            f.release.countDown();caller.join(5000);assertFalse(caller.isAlive());assertNotNull(result.get());assertTrue(interrupted.get());
        }
    }

    @ParameterizedTest @ValueSource(longs={-10000000000L,9223372036804775807L})
    void workerUsesElapsedSharedTimeAcrossNegativeAndOverflowBoundaries(long start) throws Exception {
        for(long elapsed:new long[]{29_999_999_999L,30_000_000_000L,30_000_000_001L}) {
            try(var f=new Fixture()) {
                f.properties.getGroup().setQueueTimeout(Duration.ofSeconds(30));var now=new AtomicLong(start);
                var held=new AtomicReference<Thread>();var caller=new AtomicReference<Thread>();var workerReads=new AtomicInteger();
                f.start(()->{if(Thread.currentThread()==held.get())workerReads.incrementAndGet();else caller.set(Thread.currentThread());return now.get();},held::set);
                var request=f.send(1);await(()->f.executor.snapshot().queued()==1);
                // caller의 30초 future 대기를 먼저 확인. 주입 시각만 이동하여 5초 검증창 안에서는 worker만 만료 선택 가능
                await(()->caller.get()!=null&&caller.get().getState()==Thread.State.TIMED_WAITING);
                now.set(start+elapsed);f.release.countDown();held.get().start();
                if(elapsed<30_000_000_000L) assertEquals(1,request.get(5,TimeUnit.SECONDS).eventSeq());
                else assertInstanceOf(PixelWriteBusyException.class,assertThrows(ExecutionException.class,()->request.get(5,TimeUnit.SECONDS)).getCause());
                f.executor.close();assertEquals(1,workerReads.get());assertEquals(elapsed<30_000_000_000L?1:0,f.batches.size());assertEquals(0,f.executor.snapshot().outstanding());
            }
        }
    }

    @Test void callerTimeoutRemovesQueuedRequestBeforeDelayedWorkerStarts() throws Exception {
        try(var f=new Fixture()) {
            f.properties.getGroup().setQueueTimeout(Duration.ofMillis(20));var held=new AtomicReference<Thread>();f.start(System::nanoTime,held::set);
            assertInstanceOf(PixelWriteBusyException.class,assertThrows(ExecutionException.class,()->f.send(1).get(5,TimeUnit.SECONDS)).getCause());
            assertEquals(0,f.executor.snapshot().outstanding());held.get().start();f.executor.close();assertTrue(f.batches.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"memory-runtime","dirty-runtime","memory-error","dirty-error"})
    void thirdFailureKeepsPrefixAndOnlyDirtyRuntimeContinuesSuffix(String stage) throws Exception {
        try(var f=new Fixture()) {
            var failure=stage.endsWith("error")?new AssertionError("injected"):new IllegalStateException("injected");
            failure.addSuppressed(new IllegalArgumentException("prior"));var uncaught=new AtomicReference<Throwable>();
            f.start(System::nanoTime,t->{t.setUncaughtExceptionHandler((thread,error)->uncaught.set(error));t.start();});f.first();
            var pending=new ArrayList<Future<PixelWriteResult>>();
            for(int id=2;id<=5;id++){pending.add(f.send(id));int n=id-1;await(()->f.executor.snapshot().queued()==n);}
            if(stage.startsWith("memory"))doThrow(failure).when(f.board).applyPixel(0,0,4);
            else doThrow(failure).when(f.dirty).markDirty(any(),eq(4L),eq(4L));
            f.release.countDown();assertEquals(2,pending.get(0).get(5,TimeUnit.SECONDS).eventSeq());assertEquals(3,pending.get(1).get(5,TimeUnit.SECONDS).eventSeq());
            Throwable third=assertThrows(ExecutionException.class,()->pending.get(2).get(5,TimeUnit.SECONDS)).getCause();
            if(failure instanceof Error)assertSame(failure,third);else assertSame(failure,third.getCause());
            if(stage.equals("dirty-runtime")){assertEquals(5,pending.get(3).get(5,TimeUnit.SECONDS).eventSeq());assertTrue(f.ready.isReady());}
            else {assertSame(third,assertThrows(ExecutionException.class,()->pending.get(3).get(5,TimeUnit.SECONDS)).getCause());
                verify(f.board,never()).applyPixel(0,0,5);assertThrows(PixelWriteBusyException.class,()->f.executor.execute(6,0,0,6));}
            f.executor.close();assertEquals(0,f.executor.snapshot().outstanding());
            assertEquals(1,failure.getSuppressed().length);if(failure instanceof Error)assertSame(failure,uncaught.get());
            if(!stage.equals("dirty-runtime")){f.ready.markReady();assertFalse(f.ready.isReady());assertDoesNotThrow(f.ready::requireNotFatal);}
        }
    }

    @Test void graceUnknownPublishesWithoutWaitingForOtherThreadsForceAndCloseWaitsForWorker() throws Exception {
        try(var f=new Fixture()) {
            f.properties.getGroup().setShutdownGrace(Duration.ofMillis(30));f.start();var claimed=f.send(1);assertTrue(f.entered.await(5,TimeUnit.SECONDS));
            var queued=f.send(2);await(()->f.executor.snapshot().queued()==1);var closing=f.callers.submit(f.executor::close);
            assertInstanceOf(PixelWriteUnknownException.class,assertThrows(ExecutionException.class,()->claimed.get(5,TimeUnit.SECONDS)).getCause());
            assertInstanceOf(PixelWriteBusyException.class,assertThrows(ExecutionException.class,()->queued.get(5,TimeUnit.SECONDS)).getCause());
            var snapshot=f.executor.snapshot();assertEquals(ExecutionSnapshot.State.FAILED,snapshot.state());assertEquals(0,snapshot.outstanding());
            assertEquals(1,snapshot.workerInFlight());assertTrue(snapshot.workerAlive());assertFalse(snapshot.closed());assertFalse(closing.isDone());
            f.ready.markReady();assertFalse(f.ready.isReady());assertDoesNotThrow(f.ready::requireNotFatal);
            f.release.countDown();closing.get(5,TimeUnit.SECONDS);f.executor.close();assertTrue(f.executor.snapshot().closed());
            verify(f.board,never()).applyPixel(anyInt(),anyInt(),anyInt());verifyNoInteractions(f.dirty);assertEquals(1,f.batches.size());
        }
    }

    @Test void startFailureCleansPermitNeverRestartsAndPreservesErrorIdentity() {
        try(var f=new Fixture()) {
            var failure=new AssertionError("start");var starts=new AtomicInteger();
            f.start(System::nanoTime,t->{starts.incrementAndGet();throw failure;});
            assertSame(failure,assertThrows(AssertionError.class,()->f.executor.execute(1,0,0,1)));
            assertThrows(PixelWriteBusyException.class,()->f.executor.execute(2,0,0,2));f.executor.close();f.executor.close();
            assertEquals(1,starts.get());assertEquals(0,f.executor.snapshot().outstanding());assertTrue(f.executor.snapshot().closed());
            verifyNoInteractions(f.wal);assertFalse(f.ready.isReady());
        }
    }

    @Test void emptyCloseIsIdempotentDoesNotStartWorkerAndRejectsAdmission() {
        try(var f=new Fixture()) {f.start();f.executor.close();f.executor.close();assertTrue(f.executor.snapshot().closed());
            assertFalse(f.executor.snapshot().workerAlive());assertThrows(PixelWriteBusyException.class,()->f.executor.execute(1,0,0,1));verifyNoInteractions(f.wal);}
    }

    @Test void concurrentCloseDrainsExistingQueuedAndClaimedWithinGraceAndNeverReopensAdmission() throws Exception {
        try(var f=new Fixture()) {
            f.start();var first=f.send(1);assertTrue(f.entered.await(5,TimeUnit.SECONDS));
            var second=f.send(2);await(()->f.executor.snapshot().queued()==1);
            var closing=f.callers.submit(f.executor::close);await(()->!f.executor.snapshot().accepting());
            var repeated=f.callers.submit(f.executor::close);
            assertThrows(PixelWriteBusyException.class,()->f.executor.execute(3,0,0,3));assertEquals(2,f.executor.snapshot().outstanding());
            assertFalse(closing.isDone());f.release.countDown();assertEquals(1,first.get(5,TimeUnit.SECONDS).eventSeq());assertEquals(2,second.get(5,TimeUnit.SECONDS).eventSeq());
            closing.get(5,TimeUnit.SECONDS);repeated.get(5,TimeUnit.SECONDS);assertEquals(ExecutionSnapshot.State.STOPPED,f.executor.snapshot().state());
            assertTrue(f.executor.snapshot().closed());assertTrue(f.ready.isReady());assertEquals(List.of(1,1),f.batches.stream().map(List::size).toList());
        }
    }

    @Test void workerInternalErrorDeliversOriginalFailureAndRejectsQueuedWithoutRestart() throws Exception {
        try(var f=new Fixture()) {
            var fatal=new AssertionError("worker boundary");var uncaught=new AtomicReference<Throwable>();
            doAnswer(call->{pause(f.entered,f.release);throw fatal;}).when(f.boundary).coordinate(any());
            f.start(System::nanoTime,t->{t.setUncaughtExceptionHandler((thread,error)->uncaught.set(error));t.start();});
            var first=f.send(1);assertTrue(f.entered.await(5,TimeUnit.SECONDS));var queued=f.send(2);await(()->f.executor.snapshot().queued()==1);
            f.release.countDown();assertSame(fatal,assertThrows(ExecutionException.class,()->first.get(5,TimeUnit.SECONDS)).getCause());
            assertInstanceOf(PixelWriteBusyException.class,assertThrows(ExecutionException.class,()->queued.get(5,TimeUnit.SECONDS)).getCause());
            f.executor.close();assertSame(fatal,uncaught.get());assertFalse(f.ready.isReady());assertDoesNotThrow(f.ready::requireNotFatal);
            assertEquals(0,f.executor.snapshot().outstanding());verifyNoInteractions(f.wal,f.dirty);
        }
    }

    @Test void selectedSuccessSurvivesShutdownAndPublicationOwnsNoLocksAndCountsUntilCompletion() throws Exception {
        try(var f=new Fixture()) {
            f.properties.getGroup().setShutdownGrace(Duration.ofMillis(30));f.release.countDown();
            var selected=new CountDownLatch(1);var allowWorker=new CountDownLatch(1);
            var publishing=new CountDownLatch(1);var allowPublication=new CountDownLatch(1);
            doAnswer(call->{Object result=call.callRealMethod();pause(selected,allowWorker);return result;}).when(f.boundary).coordinate(any());
            f.start();var request=f.send(1);
            try {
                assertTrue(selected.await(5,TimeUnit.SECONDS));var state=field(f.executor,"state");
                CompletableFuture<?> future;
                synchronized(state) {
                    var terminal=(Set<?>)field(f.executor,"unpublished");assertEquals(1,terminal.size());
                    future=(CompletableFuture<?>)field(terminal.iterator().next(),"result");
                }
                var observed=future.thenRun(()->{
                    assertFalse(Thread.holdsLock(state));assertFalse(Thread.holdsLock(f.core));assertFalse(Thread.holdsLock(f.wal));
                    try {assertFalse(((java.util.concurrent.locks.ReentrantLock)field(f.boundary,"lock")).isHeldByCurrentThread());}
                    catch(Exception failure){throw new AssertionError(failure);}
                    pause(publishing,allowPublication);
                });
                assertEquals(0,f.executor.snapshot().outstanding());assertEquals(1,f.executor.snapshot().unpublishedTerminal());
                var closing=f.callers.submit(f.executor::close);assertTrue(publishing.await(5,TimeUnit.SECONDS));
                assertTrue(future.isDone());assertEquals(1,f.executor.snapshot().unpublishedTerminal());
                assertFalse(closing.isDone());allowPublication.countDown();observed.get(5,TimeUnit.SECONDS);
                assertEquals(1,request.get(5,TimeUnit.SECONDS).eventSeq());
                await(()->f.executor.snapshot().unpublishedTerminal()==0);assertEquals(1,f.executor.snapshot().workerInFlight());
                allowWorker.countDown();closing.get(5,TimeUnit.SECONDS);verify(f.board,times(1)).applyPixel(0,0,1);
            } finally {allowPublication.countDown();allowWorker.countDown();}
        }
    }

    static Object field(Object object,String name) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }

    @Test void claimedCallerKeepsUserGateThroughCooldownWhileAnotherUserCanComplete() throws Exception {
        try(var f=new Fixture()) {
            f.start();var cooldown=mock(PixelCooldown.class);var broadcast=mock(PixelBroadcastService.class);var gate=new PixelUserWriteGate();
            var command=new PixelCommandService(cooldown,f.executor,broadcast,f.ready,gate,Measurements.disabled());
            var cooldownEntered=new CountDownLatch(1);var cooldownRelease=new CountDownLatch(1);var flag=new AtomicBoolean();
            doAnswer(call->{flag.set(Thread.interrupted());try{pause(cooldownEntered,cooldownRelease);}finally{if(flag.get())Thread.currentThread().interrupt();}return null;}).when(cooldown).startCooldown(1);
            var firstResult=new CompletableFuture<PixelWriteResult>();
            var caller=Thread.ofPlatform().start(()->{try{firstResult.complete(command.writePixel(1,0,0,1));}catch(Throwable ex){firstResult.completeExceptionally(ex);}});
            try {
                assertTrue(f.entered.await(5,TimeUnit.SECONDS));caller.interrupt();
                var same=f.callers.submit(()->command.writePixel(1,0,0,3));PixelUserWriteGateTest.awaitQueued(PixelUserWriteGateTest.lock(gate,1),1);
                var other=f.callers.submit(()->command.writePixel(2,0,0,2));await(()->f.executor.snapshot().queued()==1);
                assertFalse(firstResult.isDone());verify(cooldown,times(1)).checkWritable(1);verify(cooldown,never()).startCooldown(anyLong());
                f.release.countDown();assertTrue(cooldownEntered.await(5,TimeUnit.SECONDS));assertTrue(flag.get());
                assertEquals(2,other.get(5,TimeUnit.SECONDS).eventSeq());assertFalse(same.isDone());verify(cooldown,times(1)).checkWritable(1);
                doThrow(new PixelCooldownActiveException(180000)).when(cooldown).checkWritable(1);
                cooldownRelease.countDown();firstResult.get(5,TimeUnit.SECONDS);caller.join(5000);
                assertInstanceOf(PixelCooldownActiveException.class,assertThrows(ExecutionException.class,()->same.get(5,TimeUnit.SECONDS)).getCause());
                assertEquals(0,gate.entryCount());verify(broadcast,times(2)).broadcast(any());assertEquals(2,f.seq.currentLastIssued());
            }finally{f.release.countDown();cooldownRelease.countDown();caller.join(5000);}
        }
    }
}
