package dev.cgt.benchmark;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** CAP 정상 최소 조합·엄격한 거절·희소 일정·실제 dispatch/소유 경계 검증 */
class Phase18CapTest {
    @TempDir Path directory;
    private static Phase18Plan base;
    @BeforeAll static void baseline()throws Exception{base=Phase18PlanTest.valid();}
    static Phase18Plan plan(Phase18Plan base,String stage,String role,long rate,long ws,long warm,long seconds,String schedule,long pool){
        var b=base.bounds();long issued=System.currentTimeMillis();
        var w=new Phase18Plan.Workload(rate,0,"spread-tiles","spread",15,ws,warm,seconds,128,128,10000,90000,30000,180000,
                "reuse",pool,1000,1000,10,0,true,new Phase18Cap.Schedule(schedule,schedule.equals("fixed-rate")?0:pool,200,50,issued,issued+3600000));
        var old=base.cases().getFirst();var r=old.runtime();
        var c=new Phase18Plan.Case("cap-test","cap-run",1,role,"write",old.plannedStartUtc(),w,
                new Phase18Plan.Runtime(r.mode(),r.batchMax(),r.outstandingMax(),r.queueMillis(),r.shutdownMillis(),r.segmentBytes(),r.flushMillis(),true,r.jvmOptions(),1,1),old.observation());
        return new Phase18Plan(3,"CAP",stage,"draft",base.sessionId(),base.sourceBaseline(),base.predecessor(),base.inputs(),base.ownership(),
                new Phase18Plan.Bounds(200000,200000,200000,200000,10000,536870912,2L*1024*1024*1024,64*1024*1024,1024*1024*1024,1024*1024*1024,2147483648L,10737418240L,3600,1048576),List.of(c));
    }
    @Test void structuralMinimumAndUnsupportedHighCombinationsHaveDifferentOutcomes()throws Exception{
        var longRun=plan(base,"CAP-1","cap-target",105,100,30,1200,"fixed-rate",20393);
        Phase18Cap.validate(longRun,longRun.cases().getFirst());longRun.validate();
        assertEquals(129151,longRun.counts(longRun.cases().getFirst()).writes());
        assertTrue(longRun.counts(longRun.cases().getFirst()).memoryBytes()<536870912L);
        var ws200=plan(base,"CAP-2","cap-target",105,200,30,120,"fixed-rate",20393);
        Phase18Cap.validate(ws200,ws200.cases().getFirst());ws200.validate();
        var high=plan(base,"CAP-1","cap-target",10000,100,30,60,"fixed-rate",2000000);
        assertThrows(IllegalArgumentException.class,()->Phase18Cap.validate(high,high.cases().getFirst()));
        var expansion=plan(base,"CAP-2","cap-target",105,3200,30,120,"fixed-rate",20393);
        assertThrows(IllegalArgumentException.class,()->Phase18Cap.validate(expansion,expansion.cases().getFirst()));
    }
    @Test void compactFormalScheduleIncludesInitialGetsAndRejectsResetExpiry()throws Exception{
        var p=plan(base,"CAP-3","cap-target",0,2,30,401,"users",2);
        var j=(tools.jackson.databind.node.ObjectNode)Phase18Plan.JSON.valueToTree(p);
        var c=(tools.jackson.databind.node.ObjectNode)j.path("cases").get(0);c.put("kind","mixed");
        var w=(tools.jackson.databind.node.ObjectNode)c.path("workload");w.put("bootstrap",false);
        ((tools.jackson.databind.node.ObjectNode)w.path("schedule")).put("readPeriodSeconds",60);
        p=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);p.validate();
        var work=p.cases().getFirst().workload();assertEquals(2,Phase18Cap.count(work,"read","initial-read"));
        assertEquals(10_000_000,Phase18Cap.offset(work,"read",1,"initial-read"));assertEquals(10_000_000,Phase18Cap.late(work,"read","initial-read"));
        assertEquals(5,Phase18Cap.count(work,"write","measurement"));assertEquals(200_000_000_000L,Phase18Cap.offset(work,"write",2));
        assertEquals(0,Phase18Cap.count(work,"write","warmup"));assertEquals(1,Phase18Cap.count(work,"read","warmup"));
        assertEquals(14,Phase18Cap.count(work,"read","measurement"));assertEquals(17,p.counts(p.cases().getFirst()).reads());
        assertEquals(3,Phase18Cap.readOrdinal(work,"measurement",0));
        assertArrayEquals(new int[]{15*256,0,0},Phase18Cap.readPixel(work,"initial-read",0));
        assertArrayEquals(Phase18Cap.readPixel(work,"initial-read",1),Phase18Cap.readPixel(work,"measurement",13));
        assertArrayEquals(Phase18Cap.readPixel(work,"initial-read",0),Phase18Cap.readPixel(work,"warmup",0));
        w.put("warmupSeconds",0);
        var noWarmup=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);assertThrows(IllegalArgumentException.class,noWarmup::validate);
        w.put("warmupSeconds",30);((tools.jackson.databind.node.ObjectNode)w.path("schedule")).put("readPeriodSeconds",50);
        var wrongPeriod=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);assertThrows(IllegalArgumentException.class,wrongPeriod::validate);
        var gate=new Phase18Control();assertFalse(gate.dispatchChecked(()->false));assertEquals(0,gate.snapshot().active());assertEquals(0,gate.snapshot().dispatched());
    }
    @Test void sparseS2UsesExplicitOffsetsAndOneSecondLateness(){
        var p=plan(base,"CAP-0","cap-smoke-s2",0,2,0,220,"smoke-s2",2);var w=p.cases().getFirst().workload();
        assertEquals(4,Phase18Cap.count(w,"write","measurement"));assertEquals(4,Phase18Cap.count(w,"read","measurement"));
        assertEquals(201_000_000_000L,Phase18Cap.offset(w,"write",3));assertEquals(160_000_000_000L,Phase18Cap.offset(w,"read",3));
        assertEquals(1_000_000_000L,Phase18Cap.late(w,"write"));assertEquals(0,Phase18Cap.count(w,"write","warmup"));
    }
    @Test void exactRejectionShapesDoNotAdmitReadsUnknown503OrMalformedCooldown(){
        var busy=Phase18Plan.JSON.readTree("{\"message\":\"Pixel write is busy. Please retry later.\"}");
        var cooldown=Phase18Plan.JSON.readTree("{\"message\":\"Pixel write cooldown is active.\",\"remainingMillis\":180000}");
        assertTrue(Phase18Cap.rejection("write",503,busy));assertTrue(Phase18Cap.rejection("write",429,cooldown));
        assertFalse(Phase18Cap.rejection("read",503,busy));assertFalse(Phase18Cap.rejection("write",500,busy));
        assertFalse(Phase18Cap.rejection("write",429,Phase18Plan.JSON.readTree("{\"message\":\"Pixel write cooldown is active.\",\"remainingMillis\":-1}")));
        assertFalse(Phase18Cap.rejection("write",503,Phase18Plan.JSON.readTree("{\"message\":\"Pixel write outcome is unknown.\"}")));
    }
    @Test void dispatchGateAndCancelledUserDoNotConsumeAttemptOrInventDispatch()throws Exception{
        var gate=new Phase18Control();var invoked=new AtomicInteger();
        assertFalse(gate.dispatchIf(()->false,()->{invoked.incrementAndGet();return null;}));assertEquals(0,gate.snapshot().dispatched());
        var users=new BenchmarkUsers(2,false,1000,false);var first=users.acquire(0,1);users.cancel(first);assertEquals(first,users.acquire(0,2));
        users.complete(first,10_000_000_000L,false);assertNull(users.acquire(0,190_999_999_999L));assertNotNull(users.acquire(0,191_000_000_000L));
        var second=users.acquire(1,1);users.complete(second,2,true);assertNull(users.acquire(1,Long.MAX_VALUE));
        gate.stop();assertFalse(gate.dispatchIf(()->true,()->{invoked.incrementAndGet();return null;}));assertEquals(0,invoked.get());
    }
    @Test void earlyWsGapReorderDuplicateAndExtraRemainDistinguishable()throws Exception{
        Path path=directory.resolve("times.bin");var events=new Phase18Events(130,200,path);
        var later=new Phase18Events.Event(999,2,1,0,2);var earlier=new Phase18Events.Event(7,1,0,0,1);
        events.received(199,later,100);events.accepted(later);events.accepted(earlier);events.received(199,earlier,110);events.received(199,earlier,120);
        assertEquals(1,((long[])events.report().get("arrivalReversals"))[199]);assertEquals(1,((long[])events.report().get("duplicates"))[199]);
        assertEquals(0L,((List<?>)events.report().get("missing")).get(199));
        events.received(0,new Phase18Events.Event(1234,3,2,0,3),130);
        assertEquals(1L,((List<?>)events.report().get("extra")).get(0));events.closeTiming();assertEquals(130L*200*8,Files.size(path));
        try(var file=new java.io.RandomAccessFile(path.toFile(),"r")){file.seek(199L*130*8);assertEquals(100,file.readLong());assertEquals(110,file.readLong());}
    }
}
