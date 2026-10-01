package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A-4 정상 전체 연결과 후속 유한 입력의 가드·예열 소진 계약. 실제 B/D 부하를 실행하지 않음 */
class Phase18ExecutionTest {
    @TempDir Path directory;
    private ObjectNode input(String stage) throws Exception {
        var j=(ObjectNode)Phase18Plan.JSON.valueToTree(Phase18PlanTest.valid());
        j.put("phase",stage.startsWith("A")?"A":stage);j.put("stage",stage);
        if(stage.equals("A-4"))((ObjectNode)j.path("bounds")).put("totalSeconds",300);
        var o=(ObjectNode)j.path("ownership");o.put("wal",Path.of(o.path("repo").asText(),"data",j.path("sessionId").asText(),"trial","pixel-place.wal").toString());
        o.put("catalog","pixel_place_bench_trial");o.put("dbInstance","unopened-unit-identity");o.put("redisIndex",2);
        var c=(ObjectNode)j.path("cases").get(0);c.put("role",stage.equals("A-4")?"pilot":"target");c.put("kind","mixed");c.put("caseId","mixed");
        var w=(ObjectNode)c.path("workload");w.put("writeRate",2);w.put("readRate",2);w.put("warmupSeconds",2);w.put("measurementSeconds",5);
        w.put("wsConnections",2);w.put("simultaneousSeconds",5);w.put("bootstrap",true);
        ((ObjectNode)c.path("runtime")).put("measurement",true);
        return j;
    }
    private Phase18Plan plan(ObjectNode j)throws Exception {
        var p=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);p.validate();return p;
    }
    @Test void officialPilotRequiresExplicitActionScopeAndObservation()throws Exception {
        var j=input("A-4");var p=plan(j);
        Phase18Execution.requireAction("Pilot",p);assertTrue(Phase18Execution.observed(p));
        assertEquals(33,Phase18Execution.requestCapacity(p)); // W15 + R14 + 준비 진단 상한4
        assertThrows(IllegalArgumentException.class,()->Phase18Execution.requireAction("Run",p));
        assertThrows(IllegalArgumentException.class,()->Phase18Execution.requireAction("VerifyObservation",p));
        ((ObjectNode)j.path("cases").get(0)).put("role","target");
        var wrongRole=plan(j);assertThrows(IllegalArgumentException.class,()->Phase18Execution.requireService(wrongRole));
        j=input("A-4");j.put("state","draft");var draft=plan(j);
        assertThrows(IllegalArgumentException.class,()->Phase18Execution.requireService(draft));
    }
    @Test void representativeAndSoakFitRealRequestCapacityAndWaitForPlannedPhase()throws Exception {
        for(String stage:List.of("B","D")){
            var j=input(stage);var c=(ObjectNode)j.path("cases").get(0);var w=(ObjectNode)c.path("workload");
            w.put("writeRate",105);w.put("readRate",100);w.put("wsConnections",100);w.put("warmupSeconds",30);
            w.put("measurementSeconds",stage.equals("B")?120:1800);w.put("simultaneousSeconds",stage.equals("B")?120:1800);
            w.put("userMode","reuse");w.put("userPool",20000);
            var p=plan(j);Phase18Execution.requireAction("Run",p);
            assertEquals(stage.equals("B")?30755:375155,Phase18Execution.requestCapacity(p));
            assertTrue(Phase18Execution.observed(p));
        }
        assertEquals(135000,Phase18Execution.phaseWaitMillis(120,15000));
        assertEquals(1815000,Phase18Execution.phaseWaitMillis(1800,15000));
        assertThrows(ArithmeticException.class,()->Phase18Execution.phaseWaitMillis(Long.MAX_VALUE,15000));
    }
    @Test void largeWarmupPreservesNotSentAndRejectsUnknownMissingAndFutureTerminal()throws Exception {
        var j=input("B");var c=(ObjectNode)j.path("cases").get(0);var w=(ObjectNode)c.path("workload");
        w.put("writeRate",100);w.put("readRate",0);w.put("bootstrap",false);w.put("warmupSeconds",2);c.put("kind","write");
        var p=plan(j);var loaded=new Phase18Plan.Loaded(p,"0".repeat(64));var trial=p.cases().getFirst();
        Path raw=directory.resolve("warmup.jsonl");var lines=new ArrayList<String>();
        for(int i=0;i<200;i++)lines.add(Phase18Plan.JSON.writeValueAsString(new Phase18Raw(1,loaded.planHash(),trial.runId(),trial.caseId(),Phase18Plan.requestId(trial,"write","warmup",i),
                "write","warmup",i,"test",i*10000000L,1,null,null,"client_not_sent","arrival_late",null,null,null,"not_sent","NOT_SENT","not_applicable",null,null,0,0,0,null,null,null)));
        Files.write(raw,lines);assertEquals(200,Phase18Consistency.readWarmup(raw,loaded).size());
        assertThrows(IllegalArgumentException.class,()->Phase18Consistency.read(raw,loaded));
        var row=(ObjectNode)Phase18Plan.JSON.readTree(lines.getFirst());row.put("status","timeout");row.put("outcome","UNKNOWN");row.put("sendState","sent");
        lines.set(0,row.toString());Files.write(raw,lines);assertThrows(IllegalArgumentException.class,()->Phase18Consistency.readWarmup(raw,loaded));
        row=(ObjectNode)Phase18Plan.JSON.readTree(lines.get(1));row.put("scheduledPhase","measurement");row.put("requestId",Phase18Plan.requestId(trial,"write","measurement",1));
        lines.set(0,row.toString());Files.write(raw,lines);assertThrows(IllegalArgumentException.class,()->Phase18Consistency.readWarmup(raw,loaded));
    }
    @Test void serverTrackerOwnsMoreThan160RequestsAndStillRejectsOverReservation()throws Exception {
        var tracker=new Phase18Activity.Tracker(201);var expected=new HashSet<String>();
        for(int i=0;i<201;i++){
            var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/tiles/0/0/0");
            request.addHeader("X-Phase18-Request","request-"+i);expected.add("request-"+i);
            tracker.doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),(req,res)->assertFalse(tracker.idle(expected)));
        }
        assertTrue(tracker.idle(expected));assertEquals(201,tracker.snapshot().size());
        var excess=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/tiles/0/0/0");excess.addHeader("X-Phase18-Request","excess");
        assertThrows(jakarta.servlet.ServletException.class,()->tracker.doFilter(excess,new org.springframework.mock.web.MockHttpServletResponse(),(req,res)->fail("unowned dispatch")));
        assertTrue(tracker.idle(expected));
    }
    @Test void pixelMappingPreservesSeed15AndContinuousBootstrapWarmupMeasurementOrdinals()throws Exception {
        var j=input("A-4");var w=(ObjectNode)j.path("cases").get(0).path("workload");w.put("seed",15);w.put("writePattern","spread-tiles");
        var c=plan(j).cases().getFirst();
        // 기존 benchmark의 고정 seed15 표본. 구현식을 다시 계산하는 기대값 대신 좌표·색을 고정
        assertArrayEquals(new int[]{5433,1872,0},Phase18LoadClient.pixel(c,"bootstrap",0));
        assertArrayEquals(new int[]{4101,8106,1},Phase18LoadClient.pixel(c,"warmup",0));
        assertArrayEquals(new int[]{7495,6050,4},Phase18LoadClient.pixel(c,"warmup",3));
        assertArrayEquals(new int[]{3442,1206,5},Phase18LoadClient.pixel(c,"measurement",0));
        assertArrayEquals(new int[]{3688,6791,14},Phase18LoadClient.pixel(c,"measurement",9));
        w.put("writePattern","same-pixel");c=plan(j).cases().getFirst();
        assertArrayEquals(new int[]{17,19,0},Phase18LoadClient.pixel(c,"bootstrap",0));
        assertArrayEquals(new int[]{17,19,5},Phase18LoadClient.pixel(c,"measurement",0));
    }
}
