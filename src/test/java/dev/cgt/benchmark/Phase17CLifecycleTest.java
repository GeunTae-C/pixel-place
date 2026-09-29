package dev.cgt.benchmark;

import dev.cgt.pixelplace.flush.application.FlushRunResult;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Main이 실제 사용하는 execute→close→save의 latch·실패 경계. DB/native/C-2 성공과 구분 */
class Phase17CLifecycleTest {
    @TempDir Path temporary;
    private Phase17CTrace trace(){return new Phase17CTrace(1000,1000000);}
    private static void call(Phase17CTrace t,String op,String target,String result,Throwable failure){
        long id=t.begin(op,target);t.end(id,op,target,result,failure);
    }
    private void startup(Phase17CTrace t){
        long prep=t.begin("prepareForRecovery","prep");
        String parent=temporary.toString();long s=t.begin("native.S",parent);
        call(t,"native.open",parent,"1",null);call(t,"native.flush","1","completed",null);call(t,"native.close","1","completed",null);
        t.end(s,"native.S",parent,"completed",null);t.end(prep,"prepareForRecovery","prep","completed",null);
        call(t,"initializeAllWhite","board","completed",null);call(t,"initializeLastIssued","seq","0",null);call(t,"markReady","ready","completed",null);
    }
    private void validate(Phase17CTrace t,String action){
        Phase17CVerificationMain.validateTrace(t,action,null,Map.of(),temporary.resolve("pixel-place.wal"));
    }
    private void save(Phase17CTrace t,Throwable failure,boolean closed,AtomicInteger validations,String action)throws Exception {
        Phase17CVerificationMain.saveAfterClose(failure,closed,p->{
            BenchmarkJson.write(temporary.resolve("trace.json"),t.report());
            if(p!=null)BenchmarkJson.write(temporary.resolve("failure.json"),Map.of("kind",p.getClass().getName()));
        },()->{validations.incrementAndGet();validate(t,action);},()->Files.writeString(temporary.resolve("manifest.json"),"success"));
    }
    private static void await(CountDownLatch latch)throws InterruptedException{assertTrue(latch.await(5,TimeUnit.SECONDS));}
    private static void finish(ExecutorService executor)throws InterruptedException{executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}

    @Test void v1105MainLifecycleWaitsForSameInFlightFlushReturnAndProducerTerminationBeforeFinalValidation()throws Exception {
        var t=trace();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closing=new CountDownLatch(1);
        var worker=mock(FlushWorker.class);var observation=new Phase17CObservation(t,temporary.resolve("pixel-place.wal"));
        when(worker.flushOnce()).thenAnswer(invocation->{entered.countDown();await(release);return FlushRunResult.NO_OP;});
        var observed=(FlushWorker)observation.postProcessAfterInitialization(worker,"flushWorker");
        var producer=Executors.newSingleThreadExecutor();var orchestration=Executors.newSingleThreadExecutor();
        var validations=new AtomicInteger();var closed=new AtomicBoolean();var flush=new AtomicReference<Future<FlushRunResult>>();
        try{
            Future<Throwable> result=orchestration.submit(()->Phase17CVerificationMain.lifecycle(()->{
                startup(t);flush.set(producer.submit(observed::flushOnce));await(entered);
            },()->{
                producer.shutdown();closing.countDown();Phase17CVerificationMain.awaitTermination(producer,5,TimeUnit.SECONDS);
                assertEquals(FlushRunResult.NO_OP,flush.get().get(5,TimeUnit.SECONDS));closed.set(true);
            },p->save(t,p,closed.get(),validations,"smoke")));
            await(closing);
            assertEquals(0,validations.get());assertFalse(Files.exists(temporary.resolve("manifest.json")));
            // 진행 중 trace 자체의 엄격성은 그대로. 고칠 대상은 analyzer 호출 시점
            assertThrows(IllegalStateException.class,()->validate(t,"smoke"));
            release.countDown();assertNull(result.get(5,TimeUnit.SECONDS));
            assertEquals(1,validations.get());assertTrue(closed.get());assertTrue(Files.exists(temporary.resolve("manifest.json")));
            assertEquals(1,t.count("flushOnce","return"));verify(worker,times(1)).flushOnce();
        }finally{release.countDown();finish(producer);finish(orchestration);}
    }
    @ParameterizedTest @ValueSource(strings={"missing-return","missing-native-close","flush-failure","overflow","incomplete"})
    void v1106FinalSaveRejectsIncompleteFailedOrOverflowedTraceAndPreservesDiagnostics(String defect)throws Exception {
        var t=defect.equals("overflow")?new Phase17CTrace(1,64):trace();startup(t);
        switch(defect){
            case "missing-return"->t.begin("flushOnce","flush");
            case "missing-native-close"->call(t,"native.open","owned","7",null);
            case "flush-failure"->call(t,"flushOnce","flush","",new IllegalStateException("fixture"));
            case "incomplete"->t.incomplete();
        }
        var validations=new AtomicInteger();var failure=Phase17CVerificationMain.lifecycle(()->{},()->{},p->save(t,p,true,validations,"smoke"));
        assertInstanceOf(IllegalStateException.class,failure);assertEquals(1,validations.get());
        assertTrue(Files.exists(temporary.resolve("trace.json")));assertTrue(Files.exists(temporary.resolve("failure.json")));
        assertFalse(Files.exists(temporary.resolve("manifest.json")));
    }
    @ParameterizedTest @ValueSource(strings={"execute","close"})
    void v1107FailureSkipsFinalValidationAndSuccessButStillClosesAndSaves(String stage)throws Exception {
        var t=trace();startup(t);var failure=new IllegalArgumentException("fixture");var closed=new AtomicBoolean();var validations=new AtomicInteger();
        var result=Phase17CVerificationMain.lifecycle(()->{if(stage.equals("execute"))throw failure;},()->{
            closed.set(true);if(stage.equals("close"))throw failure;
        },p->save(t,p,!stage.equals("close"),validations,"smoke"));
        assertSame(failure,result);assertTrue(closed.get());assertEquals(0,validations.get());
        assertTrue(Files.exists(temporary.resolve("failure.json")));assertTrue(Files.exists(temporary.resolve("trace.json")));
        assertFalse(Files.exists(temporary.resolve("manifest.json")));
    }
    @ParameterizedTest @ValueSource(strings={"execute","close"})
    void v1107TimeoutRemainsFailureAfterLateNormalReturnOnActualBoundedPath(String stage)throws Exception {
        var t=trace();startup(t);var release=new CountDownLatch(1);var lateReturned=new AtomicBoolean();var timedOut=new AtomicBoolean();var validations=new AtomicInteger();
        Phase17CVerificationMain.Step late=()->Phase17CVerificationMain.bounded(()->{
            await(release);lateReturned.set(true);return true;
        },1,TimeUnit.MILLISECONDS,"fixture-timeout",()->{timedOut.set(true);release.countDown();});
        try{
            var result=Phase17CVerificationMain.lifecycle(()->{if(stage.equals("execute"))late.run();},()->{if(stage.equals("close"))late.run();},p->save(t,p,true,validations,"smoke"));
            assertInstanceOf(TimeoutException.class,result);assertTrue(timedOut.get());assertTrue(lateReturned.get());assertEquals(0,validations.get());
            assertTrue(Files.exists(temporary.resolve("failure.json")));assertFalse(Files.exists(temporary.resolve("manifest.json")));
        }finally{release.countDown();}
    }
    @Test void v1107ErrorPrecedenceAndSuppressedIdentitySurviveExecuteCloseAndSaveFailures(){
        var first=new IllegalStateException("execute");var error=new AssertionError("close");var save=new IllegalStateException("save");var existing=new Exception("prior");error.addSuppressed(existing);
        var result=Phase17CVerificationMain.lifecycle(()->{throw first;},()->{throw error;},p->{assertSame(error,p);throw save;});
        assertSame(error,result);assertArrayEquals(new Throwable[]{existing,first,save},error.getSuppressed());
    }
    @Test void v1107TimeoutEvidenceFailureStillWaitsForOwnedTaskAndRetainsLateError(){
        var release=new CountDownLatch(1);var returned=new AtomicBoolean();var evidence=new IllegalStateException("evidence");var error=new AssertionError("late");
        try{
            var result=assertThrows(AssertionError.class,()->Phase17CVerificationMain.bounded(()->{await(release);returned.set(true);throw error;},1,TimeUnit.MILLISECONDS,"late-error",()->{release.countDown();throw evidence;}));
            assertSame(error,result);assertTrue(returned.get());assertInstanceOf(TimeoutException.class,error.getSuppressed()[0]);
            assertSame(evidence,error.getSuppressed()[0].getSuppressed()[0]);
        }finally{release.countDown();}
    }
    @Test void v1107CloseReturnWithoutActualProducerTerminationCannotPublishSuccess()throws Exception {
        var producer=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var t=trace();startup(t);var validations=new AtomicInteger();
        try{
            producer.submit(()->{entered.countDown();await(release);return true;});await(entered);producer.shutdown();
            var result=Phase17CVerificationMain.lifecycle(()->{},()->Phase17CVerificationMain.awaitTermination(producer,1,TimeUnit.MILLISECONDS),p->save(t,p,true,validations,"smoke"));
            assertInstanceOf(IllegalStateException.class,result);assertFalse(producer.isTerminated());assertEquals(0,validations.get());
            release.countDown();assertTrue(producer.awaitTermination(5,TimeUnit.SECONDS));
            assertFalse(Files.exists(temporary.resolve("manifest.json")));
        }finally{release.countDown();finish(producer);}
    }
    @Test void v1107UnconfirmedShutdownAndDiagnosticWriteFailurePreventSuccess()throws Exception {
        var t=trace();startup(t);var validations=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->save(t,null,false,validations,"smoke"));assertEquals(0,validations.get());
        var failure=new java.io.IOException("diagnostic");
        assertSame(failure,assertThrows(java.io.IOException.class,()->Phase17CVerificationMain.saveAfterClose(null,true,p->{throw failure;},validations::incrementAndGet,()->fail("publish"))));
        assertEquals(1,validations.get());
        assertFalse(Files.exists(temporary.resolve("manifest.json")));
    }
    @Test void v1108WriterSkipsStartupButStillRequiresPhysicalAndCloseEvidence(){
        var t=trace();call(t,"native.open","owned","7",null);call(t,"native.close","7","completed",null);
        assertNull(Phase17CVerificationMain.startupExpectation("prepare-unflushed",null));
        assertDoesNotThrow(()->validate(t,"prepare-unflushed"));
        call(t,"native.open","unclosed","8",null);assertThrows(IllegalStateException.class,()->validate(t,"prepare-unflushed"));
        var missingPhysical=trace();call(missingPhysical,"S",temporary.toString(),"completed",null);
        assertThrows(IllegalStateException.class,()->validate(missingPhysical,"prepare-unflushed"));
    }
    @Test void v1108OnlyPreReadyServiceNotReadyFailureIsAllowed(){
        var allowed=trace();call(allowed,"flushOnce","flush","",new ServiceNotReadyException());startup(allowed);
        assertDoesNotThrow(()->validate(allowed,"smoke"));
        var before=trace();call(before,"flushOnce","flush","",new IllegalStateException("fixture"));startup(before);
        assertThrows(IllegalStateException.class,()->validate(before,"smoke"));
        var after=trace();startup(after);call(after,"flushOnce","flush","",new ServiceNotReadyException());
        assertThrows(IllegalStateException.class,()->validate(after,"smoke"));
    }
}
