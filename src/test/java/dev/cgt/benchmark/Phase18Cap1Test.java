package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** CAP-1 산식과 기존 단계 경계 검증. 실제 부하 PASS 근거 아님 */
class Phase18Cap1Test {
    static Phase18Plan plan(long rate,long seconds)throws Exception {
        var legacy=Phase18CapTest.plan(Phase18PlanTest.valid(),"CAP-1","cap-target",rate,100,30,seconds,"fixed-rate",rate*(30+seconds));
        var node=(ObjectNode)Phase18Plan.JSON.valueToTree(legacy);
        var w=(ObjectNode)node.path("cases").get(0).path("workload");
        w.put("readInFlight",4);w.put("userPool",Math.min(rate*(30+seconds),rate*193+128));
        ((ObjectNode)node.path("cases").get(0).path("observation")).put("sampleMillis",250).put("stopAckMillis",2000);
        var b=(ObjectNode)node.path("bounds");b.put("samples",50000);
        var p=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        b.put("walBytes",Phase18Cap1.limit(4L*1024*1024,1024*1024+p.counts(p.cases().getFirst()).writes()*512));
        b.put("databaseBytes",Phase18Cap1.limit(1024L*1024*1024,Phase18Cap1.databaseUsage(p)));
        b.put("binlogBytes",Phase18Cap1.limit(1024L*1024*1024,Phase18Cap1.binlogUsage(p)));
        b.put("writeTerminals",p.counts(p.cases().getFirst()).writes()).put("wsEvents",p.counts(p.cases().getFirst()).wsEvents());
        var out=Phase18Cap1.output(p);long total=Phase18Cap1.seconds(p.cases().getFirst().workload());
        b.put("samples",Phase18Cap1.limit(10000,out.samples()));b.put("totalSeconds",total);
        b.put("outputBytes",Phase18Cap1.limit(256L*1024*1024,out.runBytes()));
        b.put("buildBytes",Phase18Cap1.limit(2048L*1024*1024,Phase18Cap1.workspaceUsage(p)));
        var schedule=(ObjectNode)w.path("schedule");schedule.put("expiresEpochMillis",schedule.path("issuedEpochMillis").asLong()+total*1000);
        return Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
    }
    @Test void extendedBaselineReservationMatchesPythonAndAcceptsCompletePlan()throws Exception {
        var p=plan(105,120);var out=Phase18Cap1.output(p);
        assertEquals(10L*1024*1024*1024,Phase18Cap1.mysqlLimit(p));
        assertEquals(5L*1024*1024*1024,p.bounds().binlogBytes());
        assertEquals(4383,p.bounds().totalSeconds());assertEquals(18254,out.samples());
        assertEquals(1882741920L,out.runBytes());assertEquals(1949850784L,out.evidenceBytes());
        assertEquals(15751,p.counts(p.cases().getFirst()).users());
        p.validate();Phase18K6Session.requireSupport(p);
        assertDoesNotThrow(()->Phase18Environment.requireEvidenceReservation(p,8388608000L));
        assertThrows(IllegalArgumentException.class,()->Phase18Environment.requireEvidenceReservation(p,335544320));
        // 원본 policy의 hash/identity는 기존 loader, 여기서는 조합 산식 검증
        assertEquals(150000,Phase18Cap1.limit(6000,out.traceEntries()));
        assertEquals(12582912000L,Phase18K6Session.metricsBytes(p));
    }
    @Test void next250UsesPhysicalUsageRatherThanSumOfIndependentHardLimits()throws Exception {
        var p=plan(250,60);
        assertTrue(p.bounds().outputBytes()+p.bounds().databaseBytes()+p.bounds().binlogBytes()+p.bounds().buildBytes()>32L*1024*1024*1024);
        assertEquals(Phase18Cap1.workspaceUsage(p)+Phase18Cap1.mysqlUsage(p)+Phase18Cap1.walUsage(p)
            +Phase18Cap1.output(p).evidenceBytes()+64L*1024*1024+5L*1024*1024*1024,Phase18Cap1.physicalF(p));
        p.validate();Phase18K6Session.requireSupport(p);
    }
    @Test void physicalReservationSubtractsOnlyConsumedUsageAndRetainsNextReserve()throws Exception {
        var p=plan(250,1200);long next=64L*1024*1024+5L*1024*1024*1024;
        assertEquals(Phase18Cap1.physicalF(p),Phase18Cap1.remainingPhysicalF(p,0,0,0,0));
        assertEquals(Phase18Cap1.physicalF(p)-1024,Phase18Cap1.remainingPhysicalF(p,1024,0,0,0));
        assertEquals(next,Phase18Cap1.remainingPhysicalF(p,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->Phase18Cap1.remainingPhysicalF(p,-1,0,0,0));
    }
    @Test void overlappingCaptureUsesOlderProvenCounterAndStillStopsWithoutEvidence()throws Exception {
        var p=plan(105,120);var policy=new Phase18ObservationPolicy(1,"0".repeat(64),"cap1-w-spread",1610612736,1178,128,256,150000,52428800,335544320,"none");
        var safety=new Phase18Collector.Safety(p,policy,0);
        for(int i=0;i<150;i++){
            long now=(i+1)*1000L;
            var capture=new dev.cgt.pixelplace.measurement.PixelMeasurement.Capture(now-500,0,0,147);
            var decision=safety.evaluate(new Phase18Collector.Input(now,"measurement",100,0,true,true,capture,false,java.util.Map.of("status","observed","bytes",0),Long.MAX_VALUE,0,i*100L));
            assertEquals("",decision.stop());
        }
        var overlap=new dev.cgt.pixelplace.measurement.PixelMeasurement.Capture(149999,0,0,147);
        var input=new Phase18Collector.Input(151000,"measurement",100,0,true,true,overlap,false,java.util.Map.of("status","observed","bytes",0),Long.MAX_VALUE,0,14933L);
        var decision=safety.evaluate(input);assertEquals(280L,decision.backlogUpperBound());assertEquals("",decision.stop());
        assertEquals("capture-safety-evidence-lost",new Phase18Collector.Safety(p,policy,0).evaluate(input).stop());
        var huge=new dev.cgt.pixelplace.measurement.PixelMeasurement.Capture(151001,0,0,1179);
        assertEquals("backlog-limit",safety.evaluate(new Phase18Collector.Input(152000,"measurement",100,0,true,true,huge,false,java.util.Map.of("status","observed","bytes",0),Long.MAX_VALUE,0,14934L)).stop());
    }
    @Test void replayActualCollectorStopWithBoundedCounterHistory()throws Exception {
        String raw=System.getenv("CAP1_COLLECTOR_REPLAY");org.junit.jupiter.api.Assumptions.assumeTrue(raw!=null);
        var run=java.nio.file.Path.of(raw).getParent();var root=run.getParent().getParent();
        var p=Phase18Plan.JSON.readValue(java.nio.file.Files.readString(root.resolve("plan-cap.json")),Phase18Plan.class);
        var policy=Phase18Plan.JSON.readValue(java.nio.file.Files.readString(root.resolve("policy-cap.json")),Phase18ObservationPolicy.class);
        Phase18Collector.Safety safety=null;int rows=0,originalStops=0,newStops=0;long oldUpper=0,newUpper=0;
        for(String line:java.nio.file.Files.readAllLines(java.nio.file.Path.of(raw))){
            var row=Phase18Plan.JSON.readTree(line);var capture=Phase18Plan.JSON.treeToValue(row.path("capture"),dev.cgt.pixelplace.measurement.PixelMeasurement.Capture.class);
            long now=capture==null?row.path("nanoTime").asLong()+row.path("collectionNanos").asLong():capture.observedNanos()+row.path("safety").path("ageNanos").asLong();if(safety==null)safety=new Phase18Collector.Safety(p,policy,now);
            long started=row.path("walCounts").path("singleCalls").asLong()+row.path("walCounts").path("batchRecords").asLong();
            assertEquals("observed",row.path("walMetadata").path("status").asText());
            var metadata=java.util.Map.<String,Object>of("status","observed","bytes",row.path("walMetadata").path("bytes").asLong());
            var decision=safety.evaluate(new Phase18Collector.Input(now,row.path("phase").asText(),row.path("heapUsed").asLong(),row.path("executor").path("outstanding").asInt(),true,true,capture,row.path("pending").asBoolean(),metadata,row.path("minimumDiskFreeBytes").asLong(),0,started));
            rows++;if(!decision.stop().isEmpty())newStops++;
            if(!row.path("safety").path("stop").asText().isEmpty()){assertEquals("capture-safety-evidence-lost",row.path("safety").path("stop").asText());originalStops++;oldUpper=row.path("safety").path("backlogUpperBound").asLong();newUpper=decision.backlogUpperBound();}
        }
        assertEquals(1,originalStops);assertEquals(0,newStops);assertTrue(newUpper<=policy.maxBacklogRecords());
        var out=java.nio.file.Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"collector-counter-replay.json");
        BenchmarkJson.write(out,java.util.Map.of("rawPath",raw,"rawHash",BenchmarkJson.hash(java.nio.file.Path.of(raw)),"rows",rows,"originalStops",originalStops,"newStops",newStops,"oldUpper",oldUpper,"newUpper",newUpper,"servicesStarted",0,"originalResultPreserved",true));
    }
    @Test void expiryMismatchAndMissingExpandedReservationAreRejected()throws Exception {
        var p=plan(105,120);var node=(ObjectNode)Phase18Plan.JSON.valueToTree(p);
        ((ObjectNode)node.path("bounds")).put("totalSeconds",4384);
        var changed=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        assertThrows(IllegalArgumentException.class,()->Phase18Cap.validate(changed,changed.cases().getFirst()));
        ((ObjectNode)node.path("bounds")).put("samples",10000);
        var missing=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        assertThrows(IllegalArgumentException.class,()->Phase18Cap.validate(missing,missing.cases().getFirst()));
    }
    @Test void smokeAndWrongRoleCannotUseCap1Budget()throws Exception {
        var p=plan(105,120);var node=(ObjectNode)Phase18Plan.JSON.valueToTree(p);
        ((ObjectNode)node.path("cases").get(0)).put("role","cap-smoke-s2");
        var wrongRole=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        assertThrows(IllegalArgumentException.class,()->Phase18Cap.validate(wrongRole,wrongRole.cases().getFirst()));
        node.put("stage","CAP-0");var smoke=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        assertThrows(IllegalArgumentException.class,()->Phase18Cap.validate(smoke,smoke.cases().getFirst()));
    }
    @Test void expansionKeepsEqualityRepeatedStepsAndOverflowStrict(){
        assertEquals(50,Phase18Cap1.limit(10,5));assertEquals(250,Phase18Cap1.limit(10,25));
        assertThrows(IllegalArgumentException.class,()->Phase18Cap1.limit(0,1));
        assertThrows(IllegalArgumentException.class,()->Phase18Cap1.limit(1,-1));
        assertThrows(ArithmeticException.class,()->Phase18Cap1.limit(Long.MAX_VALUE,Long.MAX_VALUE/2+1));
    }
}
