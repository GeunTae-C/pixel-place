package dev.cgt.benchmark;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 관측 공백과 실제 실패의 안전 경계. seq 차이를 건수로 치환하거나 정상 stale에 추가 scan 금지 */
class Phase18CollectorTest {
    @TempDir Path temporary;
    private static Phase18ObservationPolicy policy(long backlog,long heap){return new Phase18ObservationPolicy(1,"0".repeat(64),"tools",heap,backlog,128,10,100,65536,65536,"none");}
    private static Phase18Collector.Input input(long now,PixelMeasurement.Capture capture,boolean pending,Map<String,Object> wal){
        return new Phase18Collector.Input(now,"measurement",100,1,true,true,capture,pending,wal,Long.MAX_VALUE,0);
    }
    private static Map<String,Object> present(){return Map.of("status","observed","bytes",0,"segments",0);}
    @Test void staleCaptureIsPointInTimeAndPendingNeverPublishesLatestBacklog()throws Exception {
        var safety=new Phase18Collector.Safety(Phase18PlanTest.valid(),policy(10,1000),1);
        var capture=new PixelMeasurement.Capture(10,2,100000,3);
        var fresh=safety.evaluate(input(20,capture,false,present()));
        assertEquals("fresh",fresh.captureStatus());assertEquals(3L,fresh.backlogAtCapture());assertEquals("",fresh.stop());
        var stale=safety.evaluate(input(30,capture,false,present()));assertEquals("stale",stale.captureStatus());assertEquals("",stale.stop());
        var pending=safety.evaluate(input(40,capture,true,present()));assertEquals("pending-gap",pending.captureStatus());assertNull(pending.backlogAtCapture());assertEquals("",pending.stop());
        assertEquals("capture-safety-evidence-lost",safety.evaluate(input(3_000_000_041L,capture,true,present())).stop());
    }
    @Test void initialMissingAndDeletionRaceHaveFiniteAllowanceButCollectorFailureStops()throws Exception {
        var safety=new Phase18Collector.Safety(Phase18PlanTest.valid(),policy(10,1000),1);
        var race=Map.<String,Object>of("status","missing","reason","NoSuchFileException");
        var first=safety.evaluate(input(10,null,false,race));assertEquals("not-observed",first.captureStatus());assertNull(first.ageNanos());assertEquals("",first.stop());
        var capture=new PixelMeasurement.Capture(2_000_000_000L,0,0,0);
        assertEquals("",safety.evaluate(input(2_000_000_001L,capture,false,present())).stop());
        var denied=Map.<String,Object>of("status","missing","reason","AccessDeniedException");
        assertEquals("wal-collector-failure",safety.evaluate(input(2_000_000_002L,capture,false,denied)).stop());
    }
    @Test void actualHeapBacklogDeadlineAndLostFallbackAreNotNormalStale()throws Exception {
        var p=Phase18PlanTest.valid();var capture=new PixelMeasurement.Capture(10,0,1_000_000,11);
        assertEquals("backlog-limit",new Phase18Collector.Safety(p,policy(10,1000),1).evaluate(input(20,capture,false,present())).stop());
        assertEquals("heap-limit",new Phase18Collector.Safety(p,policy(10,99),1).evaluate(input(20,null,false,present())).stop());
        assertEquals("deadline",new Phase18Collector.Safety(p,policy(10,1000),1).evaluate(input(86_401_000_000_000L,null,false,present())).stop());
        var tree=Phase18Plan.JSON.valueToTree(p);((tools.jackson.databind.node.ObjectNode)tree.path("cases").get(0).path("workload")).put("writeRate",11);
        var more=Phase18Plan.JSON.treeToValue(tree,Phase18Plan.class);
        assertEquals("capture-safety-evidence-lost",new Phase18Collector.Safety(more,policy(10,1000),1).evaluate(input(20,null,false,present())).stop());
    }
    @Test void metadataBoundIsCollectorFailureAndLargeHashStreamsWithoutFixtureLimit()throws Exception {
        Path wal=temporary.resolve("pixel-place.wal");
        try(var channel=java.nio.channels.FileChannel.open(wal,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            channel.position(33_554_432L);channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        var snapshot=Phase18Observation.snapshot(wal,33_554_433L,1);
        assertEquals("33554433:"+BenchmarkJson.hash(wal),snapshot.get("pixel-place.wal"));
        assertThrows(IllegalArgumentException.class,()->Phase18Observation.snapshot(wal,33_554_432L,1));
        Files.createFile(temporary.resolve("pixel-place.wal.seg-0000000000000000001"));
        assertThrows(java.io.IOException.class,()->Phase18Collector.walMetadata(wal,1));
    }
    @Test void recordForceAggregatesWithoutPerRequestJournalAndOverflowIsExplicit(){
        var trace=new Phase18Trace(4,4096);
        for(int i=0;i<1000;i++){long id=trace.begin("record_force","one");trace.end(id,"record_force","one","completed",null);}
        assertTrue(trace.complete());assertEquals(List.of(),trace.report().get("journal"));
        assertEquals(1000,((long[])((Map<?,?>)trace.report().get("totals")).get("startup/record_force/one"))[0]);
        for(int i=0;i<10;i++){long id=trace.begin("R","one");trace.end(id,"R","one","completed",null);}
        assertFalse(trace.complete());assertTrue(((Number)trace.report().get("loss")).longValue()>0);
    }
    @Test void observerRuntimeFailureMarksLossAndRawErrorKeepsIdentity(){
        var trace=new Phase18Trace(10,4096);long id=trace.begin("R","one");
        trace.end(id,"R","one",new Object(){@Override public String toString(){throw new IllegalStateException("observer");}},null);
        assertFalse(trace.complete());
        var primary=new AssertionError("native");var secondary=new AssertionError("observer");
        Phase18Trace.preserveError(primary,secondary);Phase18Trace.preserveError(primary,secondary);
        assertArrayEquals(new Throwable[]{secondary},primary.getSuppressed());
        assertSame(secondary,assertThrows(AssertionError.class,()->Phase18Trace.preserveError(null,secondary)));
    }
    /** 작업 실패 뒤 실제 end 경로의 관측 Error를 주입. 새 Error 전파와 최초 원인 보존을 함께 검증 */
    @Test void traceEndPropagatesObserverErrorWithOriginalIOExceptionAndRuntimeFailure() throws Exception {
        for (Exception original : List.of(new java.io.IOException("storage"), new IllegalStateException("transaction"))) {
            var observer = new AssertionError("trace append");
            var trace = failingTraceEnd(observer);
            long id = trace.begin("R", "wal");
            Error propagated = assertThrows(AssertionError.class, () -> {
                try { throw original; }
                finally { trace.end(id, "R", "wal", null, original); }
            });
            assertSame(observer, propagated);
            assertArrayEquals(new Throwable[]{original}, propagated.getSuppressed());
            assertSame(observer, assertThrows(AssertionError.class, () -> Phase18Trace.preserveError(original, observer)));
            assertArrayEquals(new Throwable[]{original}, observer.getSuppressed());
            var processObserver = new AssertionError("process evidence");
            assertSame(processObserver, Phase18Process.preserve(original, processObserver));
            assertArrayEquals(processObserver.getSuppressed(), observer.getSuppressed());
        }
    }
    @Test void traceEndKeepsOriginalErrorIdentityAndAvoidsSelfOrDuplicateSuppression() throws Exception {
        var original = new AssertionError("native");
        var observer = new AssertionError("trace append");
        for (Error injected : List.of(observer, observer, original)) {
            var trace = failingTraceEnd(injected);
            long id = trace.begin("R", "wal");
            assertSame(original, assertThrows(AssertionError.class, () -> {
                try { throw original; }
                finally { trace.end(id, "R", "wal", null, original); }
            }));
        }
        assertArrayEquals(new Throwable[]{observer}, original.getSuppressed());
        var trace = failingTraceEnd(observer);
        long id = trace.begin("R", "wal");
        assertSame(observer, assertThrows(AssertionError.class, () -> trace.end(id, "R", "wal", null, null)));
        assertEquals(0, observer.getSuppressed().length);
    }
    /** 실제 메서드 catch 경계 검증용 thread-local 저장소 실패. production 주입 지점 추가 불필요 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Phase18Trace failingTraceEnd(Error error) throws Exception {
        var trace = new Phase18Trace(10, 4096);
        var field = Phase18Trace.class.getDeclaredField("active");
        field.setAccessible(true);
        ((ThreadLocal) field.get(trace)).set(new ArrayDeque<>() {
            @Override public Object poll() { throw error; }
        });
        return trace;
    }
    @Test void nativeIdentityCallsAggregateButRecoverySyncCloseKeepsParentOrder(){
        var trace=new Phase18Trace(20,16384);
        for(int i=0;i<5000;i++){long id=trace.begin("native.open","wal");trace.end(id,"native.open","wal",Integer.toString(i),null);}
        assertTrue(trace.complete());assertEquals(List.of(),trace.report().get("journal"));
        long parent=trace.begin("R","wal");long child=trace.begin("native.close","handle");trace.end(child,"native.close","handle","completed",null);trace.end(parent,"R","wal","completed",null);
        var journal=(List<?>)trace.report().get("journal");assertEquals(2,journal.size());
        assertEquals(parent,((Map<?,?>)journal.getFirst()).get("parent"));
    }
    /** transaction proxy를 한 번만 호출하고 committed/rollback/ambiguous 및 원래 throwable 보존 */
    @Test void transactionAdvicePreservesEachOutcomeAndOriginalThrownIdentity(){
        var trace=new Phase18Trace(100,65536);var actual=new TransactionFixture();
        var observed=(dev.cgt.pixelplace.flush.application.FlushTransactionExecutor)new Phase18Observation(trace).postProcessAfterInitialization(actual,"transaction");
        var plan=dev.cgt.pixelplace.flush.application.FlushPlan.noOp(0,dev.cgt.pixelplace.flush.application.DbBootstrapState.BOOTSTRAP_PENDING);
        var failure=new IllegalStateException("db");
        for(var result:List.of(dev.cgt.pixelplace.flush.application.FlushTransactionResult.committed(),
                dev.cgt.pixelplace.flush.application.FlushTransactionResult.definiteRollback(failure),
                dev.cgt.pixelplace.flush.application.FlushTransactionResult.ambiguousCommit(failure))){
            actual.result=result;assertSame(result,observed.execute(plan));
        }
        actual.problem=failure;assertSame(failure,assertThrows(IllegalStateException.class,()->observed.execute(plan)));
        var error=new AssertionError("native");actual.problem=error;assertSame(error,assertThrows(AssertionError.class,()->observed.execute(plan)));
        assertEquals(5,actual.calls);assertTrue(trace.complete());
        var events=(List<?>)trace.report().get("journal");assertEquals(5,events.size());
        assertTrue(((Map<?,?>)events.get(2)).get("result").toString().contains("AMBIGUOUS_COMMIT"));
    }
    public static class TransactionFixture implements dev.cgt.pixelplace.flush.application.FlushTransactionExecutor {
        int calls;Throwable problem;dev.cgt.pixelplace.flush.application.FlushTransactionResult result;
        @Override public dev.cgt.pixelplace.flush.application.FlushTransactionResult execute(dev.cgt.pixelplace.flush.application.FlushPlan plan){
            calls++;if(problem instanceof Error error)throw error;if(problem instanceof RuntimeException runtime)throw runtime;return result;
        }
    }
}
