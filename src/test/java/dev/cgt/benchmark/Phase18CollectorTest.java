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
    @Test void expandedS2SeparatesUsageFromLimitsAndRetainsFullTrace()throws Exception{
        var node=(tools.jackson.databind.node.ObjectNode)Phase18Plan.JSON.valueToTree(s2());
        ((tools.jackson.databind.node.ObjectNode)node.path("bounds")).put("samples",50000).put("outputBytes",1342177280).put("totalSeconds",2400);
        var expandedCase=(tools.jackson.databind.node.ObjectNode)node.path("cases").get(0);
        expandedCase.put("kind","mixed");
        ((tools.jackson.databind.node.ObjectNode)expandedCase.path("workload")).put("writeInFlight",4).put("readInFlight",4);
        var schedule=(tools.jackson.databind.node.ObjectNode)expandedCase.path("workload").path("schedule");
        schedule.put("expiresEpochMillis",schedule.path("issuedEpochMillis").asLong()+2400000);
        var ownership=(tools.jackson.databind.node.ObjectNode)node.path("ownership");
        ownership.put("wal","F:/pixel-place-wal/phase18/"+node.path("sessionId").asText()+"/"+expandedCase.path("runId").asText()+"/pixel-place.wal");
        var p=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);var u=Phase18K6Session.s2Output(p);
        assertEquals(10322,u.samples());assertEquals(63418368,u.sampleBytes());
        assertEquals(12368,u.traceEntries());assertEquals(10002432,u.traceBytes());
        assertEquals(100,Phase18K6Session.expandLimit(100,49));
        assertEquals(500,Phase18K6Session.expandLimit(100,50));
        assertEquals(2500,Phase18K6Session.expandLimit(100,400));
        assertThrows(ArithmeticException.class,()->Phase18K6Session.expandLimit(Long.MAX_VALUE,Long.MAX_VALUE));
        var policy=new Phase18ObservationPolicy(1,"0".repeat(64),p.cases().getFirst().caseId(),1610612736,64,128,256,30000,52428800,335544320,"none");
        var old=System.getProperty("phase18.k6");System.setProperty("phase18.k6","isolated-test");
        try{
            assertDoesNotThrow(()->policy.validate(new Phase18Plan.Loaded(p,"0".repeat(64))));
            assertDoesNotThrow(p::validate);
            assertDoesNotThrow(()->Phase18Environment.requireEvidenceReservation(p,1677721600));
            assertThrows(IllegalArgumentException.class,()->Phase18Environment.requireEvidenceReservation(p,335544320));
            assertDoesNotThrow(()->Phase18Collector.requireSampleCapacity(10321,10321L*6144,6144,p,policy));
            // 실제50% 초과는 허용. 준비 계산의 여유와 런타임 동결 한도는 별개
            assertDoesNotThrow(()->Phase18Collector.requireSampleCapacity(30000,180000000,6144,p,policy));
            assertThrows(IllegalArgumentException.class,()->Phase18Collector.requireSampleCapacity(50000,0,1,p,policy));
            assertTrue(u.runBytes()*2<p.bounds().outputBytes());
            assertTrue(u.runBytes()<2*(policy.maxSampleBytes()+policy.maxTraceBytes())+Phase18K6Session.metricsBytes(p));
        }finally{if(old==null)System.clearProperty("phase18.k6");else System.setProperty("phase18.k6",old);}
        // 실제 Trace 구현의 유한 대역. DB/WAL 호출이나 실제 서비스 시간을 기다리지 않음
        var trace=new Phase18Trace(policy.maxTraceEntries(),policy.maxTraceBytes());trace.phase("measurement");
        for(int cycle=0;cycle<2580;cycle++){
            long flush=trace.begin("flushOnce","flushWorker");
            long capture=trace.begin("capturePlan","flushPlanCaptureService");
            trace.end(capture,"capturePlan","flushPlanCaptureService","records=0;tiles=0;dirty=0;checkpoint=4;target=4",null);
            long retention=trace.begin("retryIfEligible","flushWalRetention");
            long deletion=trace.begin("deleteCommittedPrefix","segmentedWalStorage");
            trace.end(deletion,"deleteCommittedPrefix","segmentedWalStorage","deleted=0;delayed=false",null);
            trace.end(retention,"retryIfEligible","flushWalRetention","completed",null);
            trace.end(flush,"flushOnce","flushWorker","NO_OP",null);
        }
        for(int i=0;i<2048;i++){
            trace.phase(List.of("startup","measurement","drain","recovery").get(i%4));
            String op=List.of("R","S","capturePlan","writeBatch","close","replayFrom","flushOnce","deleteCommittedPrefix").get(i%8);
            long id=trace.begin(op,p.ownership().wal());trace.end(id,op,p.ownership().wal(),"x".repeat(512),null);
        }
        var report=trace.report();assertTrue(trace.complete());assertEquals(0L,report.get("loss"));
        assertEquals(12368,((List<?>)report.get("journal")).size());
        assertTrue(((Map<?,?>)report.get("totals")).size()<=512);
        assertTrue(((Number)report.get("chargedBytes")).longValue()<=u.traceBytes());
        Path out=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"));
        BenchmarkJson.write(out.resolve("expanded-trace.json"),report);
        assertTrue(Files.size(out.resolve("expanded-trace.json"))<=u.observationBytes());
        BenchmarkJson.write(out.resolve("expanded-profile.json"),Map.of("usage",u,"policy",policy,"plan",p,
                "actualCharged",report.get("chargedBytes"),"actualSerialized",Files.size(out.resolve("expanded-trace.json")),
                "journalEntries",12368,"aggregateEntries",((Map<?,?>)report.get("totals")).size(),"loss",0));
    }
    @Test void keptTraceCeilingDoesNotCoverConservativeS2Lifetime()throws Exception{
        var trace=new Phase18Trace(6000,2097152);trace.phase("measurement");int firstLoss=0;
        // 현재 FlushWorker의 무작업 cycle 4개 반환 경로. 실제 DB/flush 실행이나 wall-clock 대기 없음
        for(int cycle=1;cycle<=2337;cycle++){
            long flush=trace.begin("flushOnce","flushWorker");
            long capture=trace.begin("capturePlan","flushPlanCaptureService");
            trace.end(capture,"capturePlan","flushPlanCaptureService","records=0;tiles=0;dirty=0;checkpoint=4;target=4",null);
            long retention=trace.begin("retryIfEligible","flushWalRetention");
            long deletion=trace.begin("deleteCommittedPrefix","segmentedWalStorage");
            trace.end(deletion,"deleteCommittedPrefix","segmentedWalStorage","deleted=0;delayed=false",null);
            trace.end(retention,"retryIfEligible","flushWalRetention","completed",null);
            trace.end(flush,"flushOnce","flushWorker","NO_OP",null);
            if(cycle==220)assertTrue(trace.complete(),"measurement-only duration fits but does not prove total lifetime");
            if(!trace.complete()&&firstLoss==0)firstLoss=cycle;
        }
        assertFalse(trace.complete());assertTrue(firstLoss>220&&firstLoss<2337);
        BenchmarkJson.write(Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"trace-lifetime-gap.json"),
                Map.of("fixtureOnly",true,"cycles",2337,"operationsPerCycle",4,"firstLossCycle",firstLoss,
                        "chargedBytes",trace.report().get("chargedBytes"),"retainedEntries",((List<?>)trace.report().get("journal")).size(),
                        "loss",trace.report().get("loss"),"targetTraceBytes",2097152,"targetTraceEntries",6000,"sufficiencyProven",false));
    }
    @Test void actualCollectorOverflowStopsAndKeepsFirstFailureWithoutServices()throws Exception{
        var original=s2();var tree=(tools.jackson.databind.node.ObjectNode)Phase18Plan.JSON.valueToTree(original);
        Path wal=temporary.resolve("pixel-place.wal");Files.createFile(wal);
        ((tools.jackson.databind.node.ObjectNode)tree.path("ownership")).put("wal",wal.toString()).put("sessionRoot",temporary.toString());
        var p=Phase18Plan.JSON.treeToValue(tree,Phase18Plan.class);
        var context=org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class);
        var measurement=org.mockito.Mockito.mock(PixelMeasurement.class);
        org.mockito.Mockito.when(measurement.phase()).thenReturn(PixelMeasurement.Phase.measurement);
        org.mockito.Mockito.when(measurement.walCounts()).thenReturn(new PixelMeasurement.WalCounts(0,0,0,0,0,0,0,0,0,Map.of()));
        var executor=org.mockito.Mockito.mock(dev.cgt.pixelplace.pixel.application.PixelWriteExecutor.class);
        org.mockito.Mockito.when(executor.snapshot()).thenReturn(new dev.cgt.pixelplace.pixel.application.ExecutionSnapshot(0,0,0,0,0,dev.cgt.pixelplace.pixel.application.ExecutionSnapshot.State.RUNNING,0,true));
        var pending=org.mockito.Mockito.mock(dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore.class);
        org.mockito.Mockito.when(pending.current()).thenReturn(Optional.empty());
        var ready=org.mockito.Mockito.mock(dev.cgt.pixelplace.recovery.application.ServiceReadiness.class);
        org.mockito.Mockito.when(ready.isReady()).thenReturn(true);
        org.mockito.Mockito.when(context.getBean(PixelMeasurement.class)).thenReturn(measurement);
        org.mockito.Mockito.when(context.getBean(dev.cgt.pixelplace.pixel.application.PixelWriteExecutor.class)).thenReturn(executor);
        org.mockito.Mockito.when(context.getBean(dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore.class)).thenReturn(pending);
        org.mockito.Mockito.when(context.getBean(dev.cgt.pixelplace.recovery.application.ServiceReadiness.class)).thenReturn(ready);
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        org.mockito.Mockito.when(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)).thenReturn(registry);
        var parent=new Phase18ParentControl(2000,2000);
        var tiny=new Phase18ObservationPolicy(1,"0".repeat(64),p.cases().getFirst().caseId(),1610612736,64,128,256,6000,2097152,1,"none");
        var collector=new Phase18Collector(new Phase18Plan.Loaded(p,"0".repeat(64)),tiny,org.mockito.Mockito.mock(Phase18Environment.class),context,new Phase18Trace(6000,2097152),parent,temporary);
        try{
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(!parent.stopped()&&System.nanoTime()<until)Thread.sleep(10);
            assertTrue(parent.stopped());
            var failure=assertThrows(IllegalArgumentException.class,parent::check);assertTrue(failure.getMessage().contains("collector output bound"));
            parent.stop("LATE",new IllegalStateException("later"));assertSame(failure,assertThrows(IllegalArgumentException.class,parent::check));
        }finally{collector.close();registry.close();}
        collector.write();assertEquals(0,Files.size(temporary.resolve("resource-samples.jsonl")));
        assertFalse(BenchmarkJson.read(temporary.resolve("collector.json")).path("complete").asBoolean());
    }
    static Phase18Plan s2()throws Exception{
        var p=Phase18CapTest.plan(Phase18PlanTest.valid(),"CAP-0","cap-smoke-s2",0,2,0,220,"smoke-s2",2);
        var j=(tools.jackson.databind.node.ObjectNode)Phase18Plan.JSON.valueToTree(p);
        var c=(tools.jackson.databind.node.ObjectNode)j.path("cases").get(0);
        ((tools.jackson.databind.node.ObjectNode)c.path("workload")).put("bootstrap",false);
        c.set("observation",Phase18Plan.JSON.valueToTree(new Phase18Plan.Observation(250,15000,5000,2000,
                List.of("heap","outstanding","backlog","disk","deadline","metrics","wal-metadata"),List.of("after-gc","process-io","host-disk"),"planned-writes-upper-bound","stop-new-dispatch")));
        ((tools.jackson.databind.node.ObjectNode)j.path("ownership")).put("wal","F:/pixel-place-wal/phase18/phase18-s2-output-fixture/run01/pixel-place.wal");
        ((tools.jackson.databind.node.ObjectNode)c.path("runtime")).put("batchMax",16);
        ((tools.jackson.databind.node.ObjectNode)j.path("bounds")).put("outputBytes",268435456);
        return Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);
    }
    @Test void s2LifetimeSerializationAndSharedReservationRejectOldLimits()throws Exception{
        var p=s2();var n=p.counts(p.cases().getFirst());assertEquals(9350,n.samples());assertEquals(2337,n.seconds());
        assertEquals(11695696,n.diskBytes());assertEquals(101188608,Phase18K6Session.metricsBytes(p));
        assertEquals(268106320,2*(67108864L+2097152)+Phase18K6Session.outputBytes(p));
        var good=new Phase18ObservationPolicy(1,"0".repeat(64),p.cases().getFirst().caseId(),1610612736,64,128,256,6000,2097152,67108864,"none");
        var old=System.getProperty("phase18.k6");System.setProperty("phase18.k6","isolated-test");
        try{
            assertTrue(assertThrows(IllegalArgumentException.class,()->good.validate(new Phase18Plan.Loaded(p,"0".repeat(64)))).getMessage().contains("conservative trace lifetime"));
            var small=new Phase18ObservationPolicy(1,"0".repeat(64),p.cases().getFirst().caseId(),1610612736,64,128,256,6000,2097152,1048576,"none");
            assertTrue(assertThrows(IllegalArgumentException.class,()->small.validate(new Phase18Plan.Loaded(p,"0".repeat(64)))).getMessage().contains("lifetime bytes"));
            assertEquals(6144,Phase18Collector.sampleByteUpperBound(p));
            assertEquals(57446400,n.samples()*Phase18Collector.sampleByteUpperBound(p));
            assertDoesNotThrow(()->Phase18Environment.requireEvidenceReservation(p,335544320));
            assertTrue(assertThrows(IllegalArgumentException.class,()->Phase18Environment.requireEvidenceReservation(p,201326592)).getMessage().contains("evidence reservation"));
            assertThrows(IllegalArgumentException.class,()->Phase18Collector.requireSampleCapacity(10000,0,1,p,good));
            assertThrows(IllegalArgumentException.class,()->Phase18Collector.requireSampleCapacity(0,67108864,1,p,good));
            assertThrows(IllegalArgumentException.class,()->Phase18Collector.requireSampleCapacity(0,0,6145,p,good));
            assertDoesNotThrow(()->Phase18Collector.requireSampleCapacity(9349,9349L*6144,6144,p,good));
            var node=(tools.jackson.databind.node.ObjectNode)Phase18Plan.JSON.valueToTree(p);
            ((tools.jackson.databind.node.ObjectNode)node.path("bounds")).put("samples",9349);
            var few=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
            assertTrue(assertThrows(IllegalArgumentException.class,()->good.validate(new Phase18Plan.Loaded(few,"0".repeat(64)))).getMessage().contains("sample count"));
            ((tools.jackson.databind.node.ObjectNode)node.path("bounds")).put("samples",10001);
            assertThrows(IllegalArgumentException.class,()->Phase18Plan.JSON.treeToValue(node,Phase18Plan.class).validate());
            ((tools.jackson.databind.node.ObjectNode)node.path("bounds")).put("samples",10000).put("outputBytes",268106319);
            var shortOutput=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
            assertTrue(assertThrows(IllegalArgumentException.class,()->good.validate(new Phase18Plan.Loaded(shortOutput,"0".repeat(64)))).getMessage().contains("combined observation"));
            Path evidence=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"maximum-sample.json");
            BenchmarkJson.write(evidence,Phase18Collector.maximumSample(p));
        }finally{if(old==null)System.clearProperty("phase18.k6");else System.setProperty("phase18.k6",old);}
    }
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
