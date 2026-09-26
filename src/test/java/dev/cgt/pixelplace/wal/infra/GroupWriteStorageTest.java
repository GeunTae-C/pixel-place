package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.application.*;
import dev.cgt.pixelplace.tile.domain.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 worker→core→공유 storage 실패와 disk prefix 보존. 직접 batch fixture를 worker 연결로 보완 */
class GroupWriteStorageTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.Test
    void actualGroupBatchesCoverTwentyOneRecordsWithThreeForcesAndPublishOutsideEveryOwnLock() throws Exception {
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();var boundary=new FlushBoundaryCoordinator(measure);
        var board=mock(InMemoryTileBoard.class);var version=new AtomicLong();
        when(board.applyPixel(anyInt(),anyInt(),anyInt())).thenAnswer(call->new TileMutationResult(new TileKey(0,0,0),version.incrementAndGet()));
        var dirty=mock(DirtyTileTracker.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var props=new WriteExecutionProperties();props.getGroup().setQueueTimeout(java.time.Duration.ofSeconds(30));
        try(var storage=new ControlledStorage(directory.resolve("wal"),100000);var callers=Executors.newFixedThreadPool(22)) {
            var core=new PixelWriteService(new EventSeqManager(),new FileWalAppender(storage,measure),board,ready,measure);
            var forces=new AtomicInteger();
            storage.setup=(channel,index)->doAnswer(call->{assertTrue(Thread.holdsLock(core));assertTrue(Thread.holdsLock(storage));
                assertTrue(((java.util.concurrent.locks.ReentrantLock)field(boundary,"lock")).isHeldByCurrentThread());
                if(forces.incrementAndGet()==1)BatchWriteBoundaryTest.pause(entered,release);return call.callRealMethod();}).when(channel).force(true);
            try(var group=new GroupPixelWriteExecutor(boundary,core,dirty,ready,props,measure)) {try {
                var first=callers.submit(()->group.execute(1,0,0,1));assertTrue(entered.await(5,TimeUnit.SECONDS));
                var requests=new ArrayList<Future<PixelWriteResult>>();
                for(int id=2;id<=21;id++){int user=id;requests.add(callers.submit(()->group.execute(user,0,0,user)));await(()->group.snapshot().queued()==user-1);}
                var state=field(group,"state");CompletableFuture<?> future;
                synchronized(state){var queue=(Deque<?>)field(group,"queue");future=(CompletableFuture<?>)field(queue.getFirst(),"result");}
                var publication=future.thenRun(()->{assertFalse(Thread.holdsLock(core));assertFalse(Thread.holdsLock(storage));assertFalse(Thread.holdsLock(state));
                    try{assertFalse(((java.util.concurrent.locks.ReentrantLock)field(boundary,"lock")).isHeldByCurrentThread());}catch(Exception ex){throw new AssertionError(ex);}});
                verify(board,never()).applyPixel(anyInt(),anyInt(),anyInt());release.countDown();first.get(5,TimeUnit.SECONDS);
                for(int i=0;i<requests.size();i++){var result=requests.get(i).get(5,TimeUnit.SECONDS);assertEquals(i+2,result.eventSeq());assertEquals(i+2,result.tileVersion());}
                publication.get(5,TimeUnit.SECONDS);group.close();assertEquals(3,forces.get());assertEquals(21,storage.readAfter(0).records().size());
                verify(dirty,times(21)).markDirty(any(),anyLong(),anyLong());
            }finally{release.countDown();}}
        }
    }

    static Object field(Object object,String name) throws Exception {var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}

    @org.junit.jupiter.api.Test
    void springUnknownPublicationPrecedesDelayedFileReturnButStorageCloseFollowsWorkerTermination() throws Exception {
        var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("group",
                Map.of("pixel-place.write.mode","group","pixel-place.write.group.shutdown-grace","30ms")));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var storage=spy(new ControlledStorage(directory.resolve("wal"),10000));
        storage.setup=(channel,index)->doAnswer(call->{BatchWriteBoundaryTest.pause(entered,release);return call.callRealMethod();}).when(channel).force(true);
        context.registerBean(SegmentedWalStorage.class,()->storage);
        context.registerBean(dev.cgt.pixelplace.measurement.PixelMeasurement.class,Measurements::disabled);
        var board=mock(InMemoryTileBoard.class);var dirty=mock(DirtyTileTracker.class);
        context.registerBean(InMemoryTileBoard.class,()->board);context.registerBean(DirtyTileTracker.class,()->dirty);
        context.register(WriteExecutionConfiguration.class,FileWalAppender.class,PixelWriteService.class,EventSeqManager.class,ServiceReadiness.class,FlushBoundaryCoordinator.class);
        context.refresh();context.getBean(ServiceReadiness.class).markReady();var executor=context.getBean(PixelWriteExecutor.class);
        doAnswer(call->{assertTrue(executor.snapshot().closed());assertFalse(executor.snapshot().workerAlive());return call.callRealMethod();}).when(storage).close();
        try(var callers=Executors.newFixedThreadPool(2)) {try {
            var request=callers.submit(()->executor.execute(1,0,0,1));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var closing=callers.submit(context::close);
            assertInstanceOf(PixelWriteUnknownException.class,assertThrows(ExecutionException.class,()->request.get(5,TimeUnit.SECONDS)).getCause());
            assertTrue(executor.snapshot().workerAlive());assertEquals(1,executor.snapshot().workerInFlight());assertEquals(0,executor.snapshot().outstanding());
            verify(storage,never()).close();assertFalse(closing.isDone());release.countDown();closing.get(5,TimeUnit.SECONDS);
            verify(storage,times(1)).close();verifyNoInteractions(board,dirty);assertTrue(executor.snapshot().closed());
        }finally{release.countDown();}}finally{context.close();}
    }

    @org.junit.jupiter.api.Test
    void actualGroupFatalStillAllowsNormalPendingExactReconciliationAndBlocksNewCapture() throws Exception {
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();var boundary=new FlushBoundaryCoordinator(measure);
        var board=new InMemoryTileBoard();var dirty=new SynchronizedDirtyTileTracker();var keys=new CanonicalZ0TileKeys();var pending=new PendingAmbiguousFlushStore();
        try(var storage=new ControlledStorage(directory.resolve("wal"),10000);
            var group=new GroupPixelWriteExecutor(boundary,new PixelWriteService(new EventSeqManager(),new FileWalAppender(storage,measure),board,ready,measure),dirty,ready,new WriteExecutionProperties(),measure)) {
            group.execute(1,0,0,1);
            var capture=spy(new FlushPlanCaptureService(ready,()->new dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot(0),keys::orderedKeys,
                    new DbBootstrapClassifier(keys),boundary,storage::readAfter,dirty,board,measure));
            var plan=capture.capturePlan();var candidate=PendingAmbiguousFlush.from(plan);pending.installIfAbsent(candidate);
            doThrow(new IOException("force")).when(storage.writers.getFirst()).force(true);
            assertThrows(IllegalStateException.class,()->group.execute(2,0,0,2));assertSame(candidate,pending.current().orElseThrow());
            clearInvocations(capture);var transaction=mock(FlushTransactionExecutor.class);
            var reconcile=new FlushReconciliationService(()->new FlushDbState(plan.flushTargetEventSeq(),DbBootstrapState.INITIALIZED));
            var flush=new FlushWorker(new FlushSingleFlightGuard(),ready,capture,transaction,pending,reconcile,dirty,
                    new FlushWalRetention(boundary,ready,pending,storage,measure),measure);
            assertEquals(FlushRunResult.RECONCILED_COMMIT,flush.flushOnce());assertTrue(pending.current().isEmpty());verifyNoInteractions(capture,transaction);
            assertThrows(ServiceNotReadyException.class,capture::capturePlan);ready.markReady();assertFalse(ready.isReady());
        }
    }

    @ParameterizedTest @ValueSource(strings={"partial","force","rotation","later-force","prepare","exhausted"})
    void realWorkerFailurePreservesDiskAndClosesAdmissionWithoutPoisoningPurePreparation(String point) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();
        var boundary=new FlushBoundaryCoordinator(measure);var dirty=mock(DirtyTileTracker.class);
        var board=mock(InMemoryTileBoard.class);when(board.applyPixel(anyInt(),anyInt(),anyInt())).thenReturn(new TileMutationResult(new TileKey(0,0,0),1));
        var seq=spy(new EventSeqManager());var fault=new IllegalArgumentException("prepare");var io=new IOException("file failure");
        var props=new WriteExecutionProperties();props.getGroup().setQueueTimeout(java.time.Duration.ofSeconds(30));props.getGroup().setMaxBatchSize(2);
        Path base=directory.resolve("wal");
        try(var storage=new ControlledStorage(base,List.of("rotation","later-force").contains(point)?1:10000);
            var executor=new GroupPixelWriteExecutor(boundary,new PixelWriteService(seq,new FileWalAppender(storage,measure),board,ready,measure),dirty,ready,props,measure);
            var callers=Executors.newFixedThreadPool(5)) {
            // 첫 정상 요청을 memory 직전에서 정지하여 다음 batch의 FIFO 3건을 확정
            storage.setup=(channel,index)->{
                if(index==0)doAnswer(call->{BatchWriteBoundaryTest.pause(entered,release);return call.callRealMethod();}).when(channel).force(true);
            };
            try {
                var first=callers.submit(()->executor.execute(1,0,0,1));assertTrue(entered.await(5,TimeUnit.SECONDS));
                var pending=new ArrayList<Future<PixelWriteResult>>();
                for(int id=2;id<=4;id++){int user=id;pending.add(callers.submit(()->executor.execute(user,0,0,user)));await(()->executor.snapshot().queued()==user-1);}
                // 다음 batch 진입 전에 준비/파일 실패를 설치. 첫 force의 이미 진입한 Answer는 그대로 반환
                if(point.equals("partial"))doAnswer(call->{var b=(ByteBuffer)call.getArgument(0);b.limit(b.position()+3);call.callRealMethod();throw io;}).when(storage.writers.getFirst()).write(any(ByteBuffer.class));
                if(point.equals("force"))doThrow(io).when(storage.writers.getFirst()).force(true);
                if(point.equals("rotation"))storage.setup=(channel,index)->{if(index==1)doThrow(io).when(channel).force(true);};
                if(point.equals("later-force"))storage.setup=(channel,index)->{if(index==2)doCallRealMethod().doThrow(io).when(channel).force(true);};
                if(point.equals("prepare"))doCallRealMethod().doThrow(fault).when(seq).allocate();
                if(point.equals("exhausted"))seq.initializeLastIssued(Long.MAX_VALUE-1);
                release.countDown();first.get(5,TimeUnit.SECONDS);
                for(var request:pending.subList(0,2)) {
                    var failure=assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->request.get(5,TimeUnit.SECONDS)).getCause());
                    if(point.equals("prepare"))assertSame(fault,failure.getCause());
                    if(List.of("partial","force","rotation","later-force").contains(point))assertSame(io,failure.getCause().getCause());
                }
                assertInstanceOf(PixelWriteBusyException.class,assertThrows(ExecutionException.class,()->pending.get(2).get(5,TimeUnit.SECONDS)).getCause());
                executor.close();assertEquals(ExecutionSnapshot.State.FAILED,executor.snapshot().state());assertEquals(0,executor.snapshot().outstanding());
                assertEquals(0,executor.snapshot().unpublishedTerminal());assertThrows(PixelWriteBusyException.class,()->executor.execute(5,0,0,5));
                verify(board,times(1)).applyPixel(anyInt(),anyInt(),anyInt());verify(dirty,times(1)).markDirty(any(),anyLong(),anyLong());
                assertDoesNotThrow(ready::requireNotFatal);ready.markReady();assertFalse(ready.isReady());
                boolean preparation=point.equals("prepare")||point.equals("exhausted");
                if(preparation){assertEquals(1,storage.readAfter(0).records().size());assertEquals(point.equals("prepare")?2:Long.MAX_VALUE-1,seq.currentLastIssued());}
                else assertThrows(IllegalStateException.class,()->storage.readAfter(0));
                assertTrue(Files.size(base)>0);
            }finally{release.countDown();}
        }
        // partial line은 fail-fast, force 실패의 완전한 line은 새 JVM과 같은 fresh reader로 복구 가능
        try(var reopened=storage(base,10000)) {
            if(point.equals("partial"))assertThrows(IllegalStateException.class,()->reopened.readAfter(0));
            else assertEquals(List.of("force","later-force").contains(point)?3:1,reopened.readAfter(0).records().size());
        }
        // 예외 종류가 아니라 위에서 확정한 실제 파일 상태별 성공/fail-fast를 별도 JVM에서도 고정
        int expected=point.equals("partial")?-1:List.of("force","later-force").contains(point)?3:1;
        var classpath=new LinkedHashSet<String>();
        for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())
            if(loader instanceof java.net.URLClassLoader urls)for(var url:urls.getURLs())classpath.add(Path.of(url.toURI()).toString());
        if(classpath.isEmpty())classpath.addAll(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        Path args=directory.resolve("recovery-java.args");
        Files.writeString(args,"-Xmx256m\n-Djava.io.tmpdir=\""+System.getProperty("java.io.tmpdir").replace('\\','/')+"\"\n-cp\n\""
                +String.join(java.io.File.pathSeparator,classpath).replace('\\','/')+"\"\n"+WalRecoveryJvmProbe.class.getName()+"\n\""+base.toString().replace('\\','/')+"\"\n"+expected+"\n");
        String evidenceRoot=System.getenv("PIXEL_PLACE_REGRESSION_EVIDENCE");
        Path output=evidenceRoot==null?directory.resolve("recovery.log"):Path.of(evidenceRoot,"recovery-probes",point+".log");
        Files.createDirectories(output.getParent());
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertTrue(child.waitFor(30,TimeUnit.SECONDS),"Recovery child must exit");
        assertEquals(0,child.exitValue(),Files.readString(output));
        assertTrue(Files.readString(output).contains("RECOVERY_FILE_STATE_VERIFIED expected="+expected));
        System.out.println(Files.readString(output).lines().filter(line->line.startsWith("RECOVERY_FILE_STATE_VERIFIED")).findFirst().orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings={"force","rotation","memory","force-fatal","rotation-fatal","memory-fatal"})
    void captureWaitingBehindActualGroupNeverBuildsWalOnlyPlan(String stage) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var capturing=new CountDownLatch(1);
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();var boundary=new FlushBoundaryCoordinator(measure);
        var board=new InMemoryTileBoard();var dirty=new SynchronizedDirtyTileTracker();var props=new WriteExecutionProperties();
        var keys=new CanonicalZ0TileKeys();var pending=new PendingAmbiguousFlushStore();var checkpoint=new AtomicLong(1);var plan=new AtomicReference<FlushPlan>();
        boolean fatal=stage.endsWith("fatal");
        try(var storage=new ControlledStorage(directory.resolve("wal"),stage.startsWith("rotation")?1:10000);var callers=Executors.newFixedThreadPool(3)) {
            var core=new PixelWriteService(new EventSeqManager(),new FileWalAppender(storage,measure),board,ready,measure);
            boundary.coordinate(()->core.writePixel(1,0,0,1));
            DirtyTileTracker marked=stage.startsWith("memory")?new DirtyTileTracker(){
                public void markDirty(TileKey key,long seq,long version){BatchWriteBoundaryTest.pause(entered,release);if(fatal)throw new AssertionError("memory boundary");dirty.markDirty(key,seq,version);}
                public List<DirtyTile> drainDirtyTiles(){return dirty.drainDirtyTiles();}
                public void restoreDirtyTiles(Collection<DirtyTile> tiles){dirty.restoreDirtyTiles(tiles);}
            }:dirty;
            if(stage.startsWith("force"))doAnswer(call->{BatchWriteBoundaryTest.pause(entered,release);if(fatal)throw new IOException("force");return call.callRealMethod();}).when(storage.writers.getFirst()).force(true);
            if(stage.startsWith("rotation"))storage.setup=(channel,index)->{if(index==1)doAnswer(call->{BatchWriteBoundaryTest.pause(entered,release);if(fatal)throw new IOException("rotation");return call.callRealMethod();}).when(channel).force(true);};
            var capture=new FlushPlanCaptureService(ready,()->{capturing.countDown();return new dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot(checkpoint.get());},keys::orderedKeys,new DbBootstrapClassifier(keys),boundary,storage::readAfter,dirty,board,measure);
            var retention=new FlushWalRetention(boundary,ready,pending,storage,measure);
            var flush=new FlushWorker(new FlushSingleFlightGuard(),ready,capture,p->{plan.set(p);checkpoint.set(p.flushTargetEventSeq());return FlushTransactionResult.committed();},pending,mock(FlushReconciliationService.class),dirty,retention,measure);
            try(var executor=new GroupPixelWriteExecutor(boundary,core,marked,ready,props,measure)) {
                try {
                    var write=callers.submit(()->executor.execute(2,0,0,2));assertTrue(entered.await(5,TimeUnit.SECONDS));
                    var writing=callers.submit(flush::flushOnce);assertTrue(capturing.await(5,TimeUnit.SECONDS));
                    var cleaning=callers.submit(()->retention.onCommitConfirmed(1));
                    assertThrows(TimeoutException.class,()->writing.get(40,TimeUnit.MILLISECONDS));
                    assertThrows(TimeoutException.class,()->cleaning.get(40,TimeUnit.MILLISECONDS));assertNull(plan.get());release.countDown();
                    cleaning.get(5,TimeUnit.SECONDS);
                    if(fatal){assertThrows(ExecutionException.class,()->write.get(5,TimeUnit.SECONDS));assertInstanceOf(ServiceNotReadyException.class,assertThrows(ExecutionException.class,()->writing.get(5,TimeUnit.SECONDS)).getCause());assertNull(plan.get());assertEquals(1,checkpoint.get());}
                    else{write.get(5,TimeUnit.SECONDS);assertEquals(FlushRunResult.COMMITTED,writing.get(5,TimeUnit.SECONDS));assertEquals(2,plan.get().flushTargetEventSeq());assertEquals(2,plan.get().tileSnapshots().getFirst().tileVersion());}
                }finally{release.countDown();}
            }
        }
    }

    @org.junit.jupiter.api.Test
    void transactionWaitingOutsideBoundaryAllowsNextActualGroupWriteWithoutChangingCapturedPlan() throws Exception {
        var measure=Measurements.disabled();var ready=new ServiceReadiness();ready.markReady();var boundary=new FlushBoundaryCoordinator(measure);
        var board=new InMemoryTileBoard();var dirty=new SynchronizedDirtyTileTracker();var keys=new CanonicalZ0TileKeys();var pending=new PendingAmbiguousFlushStore();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var checkpoint=new AtomicLong();var captured=new AtomicReference<FlushPlan>();
        try(var storage=storage(directory.resolve("wal"),10000);var callers=Executors.newFixedThreadPool(2);
            var executor=new GroupPixelWriteExecutor(boundary,new PixelWriteService(new EventSeqManager(),new FileWalAppender(storage,measure),board,ready,measure),dirty,ready,new WriteExecutionProperties(),measure)) {
            executor.execute(1,0,0,1);
            var capture=new FlushPlanCaptureService(ready,()->new dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot(checkpoint.get()),keys::orderedKeys,
                    new DbBootstrapClassifier(keys),boundary,storage::readAfter,dirty,board,measure);
            var retention=new FlushWalRetention(boundary,ready,pending,storage,measure);
            var flush=new FlushWorker(new FlushSingleFlightGuard(),ready,capture,plan->{captured.set(plan);BatchWriteBoundaryTest.pause(entered,release);
                checkpoint.set(plan.flushTargetEventSeq());return FlushTransactionResult.committed();},pending,mock(FlushReconciliationService.class),dirty,retention,measure);
            try {
                var flushing=callers.submit(flush::flushOnce);assertTrue(entered.await(5,TimeUnit.SECONDS));
                // transaction 지연을 group 접수/worker로 전파하면 이 write가 완료되지 않음
                assertEquals(2,callers.submit(()->executor.execute(2,0,0,2)).get(5,TimeUnit.SECONDS).eventSeq());
                assertEquals(1,captured.get().flushTargetEventSeq());assertEquals(1,captured.get().tileSnapshots().getFirst().tileVersion());
                assertEquals(1,captured.get().tileSnapshots().getFirst().pixels()[0]);assertFalse(flushing.isDone());
                release.countDown();assertEquals(FlushRunResult.COMMITTED,flushing.get(5,TimeUnit.SECONDS));assertEquals(1,checkpoint.get());
                assertEquals(2,capture.capturePlan().flushTargetEventSeq());
            }finally{release.countDown();}
        }
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long started=System.nanoTime();while(!condition.getAsBoolean()&&System.nanoTime()-started<TimeUnit.SECONDS.toNanos(5))Thread.sleep(1);assertTrue(condition.getAsBoolean());
    }
}
