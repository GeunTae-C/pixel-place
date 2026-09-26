package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.cgt.pixelplace.pixel.application.BatchWriteOutcome.State.*;
import static dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutorTest.*;

/** 직접 batch fixture로 준비·force·memory·dirty·abort 결과 경계를 고정. worker/future 게시 대체 아님 */
class PixelWriteBatchTest {
    private final List<InMemoryTileBoard> ownedBoards=new ArrayList<>();
    @org.junit.jupiter.api.AfterEach void releaseOwnedSpyReferences() {
        for(var board:ownedBoards)org.mockito.Mockito.framework().clearInlineMock(board);
    }
    static List<PixelWriteRequest> requests() { return List.of(new PixelWriteRequest(1,0,0,1),new PixelWriteRequest(2,0,0,2),new PixelWriteRequest(3,0,0,3),new PixelWriteRequest(4,0,0,4)); }
    class Fixture {
        final ServiceReadiness ready=new ServiceReadiness();
        final EventSeqManager seq;
        final InMemoryTileBoard board=spy(new InMemoryTileBoard());
        final WalAppender wal=mock(WalAppender.class);
        final FlushBoundaryCoordinator boundary=new FlushBoundaryCoordinator(Measurements.disabled());
        final PixelWriteService core;
        final List<PixelWriteResult> dirty=new ArrayList<>();
        Fixture(){this(new EventSeqManager());}
        Fixture(EventSeqManager seq){this.seq=seq;ownedBoards.add(board);ready.markReady();core=new PixelWriteService(seq,wal,board,ready,Measurements.disabled());}
        BatchWriteResult run(BatchWriteGuard guard,Consumer<PixelWriteResult> mark){return boundary.coordinate(()->core.writeBatch(guard,mark));}
        BatchWriteResult run(BatchWriteGuard guard){return run(guard,dirty::add);}
    }

    @Test void allForceMustReturnBeforeAnyMemoryDirtyOrSuccessAndVersionsFollowInput() throws Exception {
        var f=new Fixture();var guard=new BatchWriteGuard(requests());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{assertFalse(Thread.holdsLock(guard));pause(entered,release);return null;}).when(f.wal).appendBatchAndFsync(anyList());
        try(var threads=Executors.newSingleThreadExecutor()) {
            try {
                var future=threads.submit(()->f.run(guard,result->{assertTrue(Thread.holdsLock(guard));f.dirty.add(result);}));
                assertTrue(entered.await(5,TimeUnit.SECONDS));verify(f.board,never()).applyPixel(anyInt(),anyInt(),anyInt());assertTrue(f.dirty.isEmpty());
                assertTrue(guard.snapshot().stream().allMatch(o->o.state()==PENDING));assertFalse(future.isDone());
                release.countDown();var result=future.get(5,TimeUnit.SECONDS);
                assertEquals(List.of(1L,2L,3L,4L),result.outcomes().stream().map(o->o.result().tileVersion()).toList());
                assertEquals(List.of(1L,2L,3L,4L),f.dirty.stream().map(PixelWriteResult::eventSeq).toList());
                assertNull(result.fatalFailure());verify(f.wal,never()).appendAndFsync(any());
                org.mockito.ArgumentCaptor<List<WalRecord>> records=org.mockito.ArgumentCaptor.forClass(List.class);verify(f.wal).appendBatchAndFsync(records.capture());
                assertEquals(List.of(1L,2L,3L,4L),records.getValue().stream().map(WalRecord::eventSeq).toList());
                assertEquals(4,f.board.getRequired(new TileKey(0,0,0)).pixels()[0]);
            }finally{release.countDown();}
        }
    }

    @ParameterizedTest @ValueSource(strings={"memory-runtime","memory-error","dirty-runtime","dirty-error"})
    void thirdFailurePreservesPrefixAndOnlyDirtyRuntimeContinuesSuffix(String point) {
        var f=new Fixture();var guard=new BatchWriteGuard(requests());
        Throwable failure=point.endsWith("error")?new AssertionError("third"):new IllegalArgumentException("third");
        var previous=new IllegalStateException("existing");failure.addSuppressed(previous);
        if(point.startsWith("memory"))doThrow(failure).when(f.board).applyPixel(0,0,3);
        Consumer<PixelWriteResult> dirty=result->{if(point.startsWith("dirty")&&result.eventSeq()==3){if(failure instanceof Error e)throw e;throw (RuntimeException)failure;}f.dirty.add(result);};
        BatchWriteResult result=null;
        if(failure instanceof Error)assertSame(failure,assertThrows(AssertionError.class,()->f.run(guard,dirty)));
        else result=f.run(guard,dirty);
        var outcomes=guard.snapshot();assertEquals(SUCCEEDED,outcomes.get(0).state());assertEquals(SUCCEEDED,outcomes.get(1).state());assertEquals(FAILED,outcomes.get(2).state());
        if(point.equals("dirty-runtime")) {
            assertEquals(3,outcomes.get(2).result().eventSeq());assertEquals(3,outcomes.get(2).result().tileVersion());
            assertEquals(SUCCEEDED,outcomes.get(3).state());assertEquals(List.of(1L,2L,4L),f.dirty.stream().map(PixelWriteResult::eventSeq).toList());assertTrue(f.ready.isReady());assertNull(result.fatalFailure());
        } else {
            assertEquals(FAILED,outcomes.get(3).state());assertEquals(2,f.dirty.size());verify(f.board,never()).applyPixel(0,0,4);
            f.ready.markNotReady();f.ready.markReady();assertFalse(f.ready.isReady());assertDoesNotThrow(f.ready::requireNotFatal);
            assertThrows(ServiceNotReadyException.class,()->f.core.writePixel(5,0,0,5));
        }
        Throwable actual=outcomes.get(2).failure();assertSame(failure,actual instanceof Error?actual:actual.getCause());assertArrayEquals(new Throwable[]{previous},failure.getSuppressed());
        if(point.startsWith("dirty")) {
            assertEquals(3,outcomes.get(2).result().eventSeq());
            assertEquals(point.equals("dirty-runtime")?4:3,f.board.getRequired(new TileKey(0,0,0)).pixels()[0]);
        }
    }

    @ParameterizedTest @ValueSource(strings={"invalid","capacity","consumed","wal","error"})
    void preparationOrWalFailureNeverAppliesMemoryAndNeverRewindsSequence(String point) {
        var allocations=new AtomicInteger();
        EventSeqManager seq=point.equals("consumed")?new EventSeqManager(){@Override public long allocate(){if(allocations.incrementAndGet()==2)throw new IllegalStateException("prepare");return super.allocate();}}:new EventSeqManager();
        var f=new Fixture(seq);
        if(point.equals("capacity"))seq.initializeLastIssued(Long.MAX_VALUE-1);
        var list=point.equals("invalid")?List.of(new PixelWriteRequest(1,0,0,1),new PixelWriteRequest(2,-1,0,2)):requests();
        var guard=new BatchWriteGuard(list);var failure=new IllegalStateException("wal");var error=new AssertionError("wal");
        if(point.equals("wal"))doThrow(failure).when(f.wal).appendBatchAndFsync(anyList());
        if(point.equals("error"))doThrow(error).when(f.wal).appendBatchAndFsync(anyList());
        if(point.equals("error"))assertSame(error,assertThrows(AssertionError.class,()->f.run(guard)));
        else assertNotNull(f.run(guard).fatalFailure());
        assertTrue(guard.snapshot().stream().allMatch(o->o.state()==FAILED));verify(f.board,never()).applyPixel(anyInt(),anyInt(),anyInt());assertTrue(f.dirty.isEmpty());assertFalse(f.ready.isReady());
        if(List.of("invalid","capacity","consumed").contains(point))verifyNoInteractions(f.wal);
        assertEquals(switch(point){case "capacity"->Long.MAX_VALUE-1;case "consumed"->1;case "invalid"->0;default->4;},seq.currentLastIssued());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void abortBeforeAdmissionOrDuringForcePreventsAllMemory(boolean duringForce) throws Exception {
        var f=new Fixture();var guard=new BatchWriteGuard(requests());var cause=new IllegalStateException("abort");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        if(!duringForce)guard.abort(cause);
        else doAnswer(call->{pause(entered,release);return null;}).when(f.wal).appendBatchAndFsync(anyList());
        try(var threads=Executors.newSingleThreadExecutor()) {
            try {
                var future=threads.submit(()->f.run(guard));
                if(duringForce){assertTrue(entered.await(5,TimeUnit.SECONDS));guard.abort(cause);release.countDown();}
                future.get(5,TimeUnit.SECONDS);
                assertTrue(guard.snapshot().stream().allMatch(o->o.state()==UNKNOWN&&o.failure()==cause));verify(f.board,never()).applyPixel(anyInt(),anyInt(),anyInt());assertTrue(f.dirty.isEmpty());
                assertEquals(!duringForce,f.ready.isReady());if(!duringForce)verifyNoInteractions(f.wal);
            }finally{release.countDown();}
        }
    }

    @Test void memoryThatOwnsGuardWinsAbortAndCannotBeOverwritten() throws Exception {
        var guard=new BatchWriteGuard(List.of(requests().getFirst()));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var abortStarted=new CountDownLatch(1);
        var result=new PixelWriteResult(1,new TileKey(0,0,0),1,0,0,1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            try {
                var memory=threads.submit(()->guard.complete(0,()->{pause(entered,release);return new BatchWriteOutcome(SUCCEEDED,result,null);}));
                assertTrue(entered.await(5,TimeUnit.SECONDS));var abort=threads.submit(()->{abortStarted.countDown();guard.abort(new IllegalStateException("abort"));});
                assertTrue(abortStarted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->abort.get(50,TimeUnit.MILLISECONDS));
                release.countDown();assertSame(result,memory.get(5,TimeUnit.SECONDS).result());abort.get(5,TimeUnit.SECONDS);
                assertEquals(SUCCEEDED,guard.snapshot().getFirst().state());
            }finally{release.countDown();}
        }
    }

    @Test void batchAndSingleShareOneMonitorAndSequenceAndBatchCannotExecuteTwice() throws Exception {
        var f=new Fixture();var guard=new BatchWriteGuard(requests());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{pause(entered,release);return null;}).when(f.wal).appendBatchAndFsync(anyList());
        try(var threads=Executors.newFixedThreadPool(2)) {
            try {
                var batch=threads.submit(()->f.run(guard));assertTrue(entered.await(5,TimeUnit.SECONDS));
                var single=threads.submit(()->f.core.writePixel(5,0,0,5));assertThrows(TimeoutException.class,()->single.get(50,TimeUnit.MILLISECONDS));
                release.countDown();batch.get(5,TimeUnit.SECONDS);assertEquals(5,single.get(5,TimeUnit.SECONDS).eventSeq());
                assertThrows(IllegalStateException.class,()->f.run(guard));assertEquals(5,f.seq.currentLastIssued());
            }finally{release.countDown();}
        }
    }
}
