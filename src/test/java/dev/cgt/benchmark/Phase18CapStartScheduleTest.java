package dev.cgt.benchmark;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 READY 진입과 send의 미송신 경계. HTTP/WS/DB 실행 없이 raw·control 직접 검증 */
class Phase18CapStartScheduleTest {
    @BeforeAll static void baseline()throws Exception{Phase18CapFlowTest.baseline();}
    private static Phase18Raw initial(Phase18CapFlowTest.Fixture f,long ordinal,String status) {
        var c=f.p().cases().getFirst();int[] pixel=Phase18Cap.readPixel(c.workload(),"initial-read",ordinal);
        return new Phase18Raw(1,"0".repeat(64),c.runId(),c.caseId(),Phase18Plan.requestId(c,"read","initial-read",ordinal),"read","initial-read",ordinal,"fixture",ordinal*10000000,
            1,1L,2L,status,"",status.equals("accepted")?200:null,null,0L,status.equals("accepted")?"sent":"not_sent",status.equals("accepted")?"ACCEPTED":"NOT_SENT","not_applicable",null,null,pixel[0],pixel[1],0,null,100,1L);
    }
    private static void save(Phase18CapFlowTest.Fixture f,Phase18Raw row)throws Exception{Phase18CapFlowTest.call(f.client(),"save",new Class<?>[]{Phase18Raw.class},row);}
    @Test void readyRequiresAllExactInitialGetIdsAndValidatedSuccess()throws Exception {
        for(String scenario:List.of("success","missing","not-sent","rejected","invalid-validation","wrong-id")) {
            var messages=new ArrayList<String>();var f=Phase18CapFlowTest.fixture(true,messages::add);
            try {
                save(f,initial(f,0,"accepted"));
                if(!scenario.equals("missing")) {
                    var row=initial(f,1,scenario.equals("not-sent")?"client_not_sent":scenario.equals("rejected")?"rejected_429":"accepted");
                    var json=(tools.jackson.databind.node.ObjectNode)Phase18Plan.JSON.valueToTree(row);
                    if(scenario.equals("wrong-id"))json.put("requestId","wrong-id-same-count");
                    if(scenario.equals("invalid-validation"))json.putNull("validationNanos");
                    save(f,Phase18Plan.JSON.treeToValue(json,Phase18Raw.class));
                }
                if(scenario.equals("success")){
                    Phase18CapFlowTest.call(f.client(),"readyAfterInitialReads",new Class<?>[0]);assertEquals(List.of("READY"),messages);
                    assertEquals(Phase18Control.State.OPEN,((Phase18Control)Phase18CapFlowTest.field(f.client(),"control")).snapshot().state());
                } else {
                    assertThrows(IllegalArgumentException.class,()->Phase18CapFlowTest.call(f.client(),"readyAfterInitialReads",new Class<?>[0]),scenario);
                    assertFalse(messages.contains("READY"));assertFalse(messages.stream().anyMatch(m->m.startsWith("WARMUP")));
                    assertEquals(Phase18Control.State.STOPPED,((Phase18Control)Phase18CapFlowTest.field(f.client(),"control")).snapshot().state());
                }
            } finally {if(scenario.equals("success"))f.close();else assertThrows(IllegalArgumentException.class,f::close);}
        }
    }
    @SuppressWarnings("unchecked")
    private String missed(long dueDelta,boolean late,boolean stop,boolean busy)throws Exception {
        try(var f=Phase18CapFlowTest.fixture(true,v->{})) {
            long due=System.nanoTime()-1000000000L;
            var pool=(BenchmarkUsers)Phase18CapFlowTest.field(f.client(),"pool");var first=pool.acquire(0,due-200000000000L);
            if(!busy)pool.complete(first,due-181000000000L+dueDelta,false);
            var anchors=(Map<String,Object>)Phase18CapFlowTest.field(f.client(),"anchors");
            anchors.put("measurement",Map.of("nanos",due,"endNanos",late?due:due+200000000000L));
            var control=(Phase18Control)Phase18CapFlowTest.field(f.client(),"control");if(stop)control.stop();
            Phase18CapFlowTest.call(f.client(),"send",new Class<?>[]{String.class,String.class,long.class,long.class,long.class,Semaphore.class,boolean.class},
                "write","measurement",0L,0L,due,new Semaphore(0),false);
            var row=Phase18Plan.JSON.readValue(Files.readString(f.root().resolve("raw.jsonl")).trim(),Phase18Raw.class);
            assertEquals("not_sent",row.sendState());assertEquals(0,row.ordinal());assertNull(row.sentNanos());assertEquals(0,control.snapshot().dispatched());
            if(busy)pool.complete(first,System.nanoTime(),false);
            return row.reason();
        }
    }
    @Test void originalDueWinsEvenWhenDelayedDispatchFindsUserReadyAndSlotsFull()throws Exception {
        assertEquals("user_not_ready",missed(1,false,false,false));
        assertEquals("processing_capacity",missed(0,false,false,false));
        assertEquals("processing_capacity",missed(-1,false,false,false));
        assertEquals("user_not_ready",missed(0,false,false,true));
    }
    @Test void stopThenPhaseLateThenUserThenCapacityDefinesPrimaryReason()throws Exception {
        assertEquals("stopped",missed(1,true,true,false));
        assertEquals("arrival_late",missed(1,true,false,false));
        assertEquals("user_not_ready",missed(1,false,false,false));
        assertEquals("processing_capacity",missed(0,false,false,false));
    }
    @Test void completionRaceCannotMakeEarlierDueEligible()throws Exception {
        var pool=new BenchmarkUsers(1,false,1000,false);var first=pool.acquire(0,1);long due=200000000000L;
        try(var executor=Executors.newSingleThreadExecutor()) {
            var release=new CountDownLatch(1);var done=executor.submit(()->{release.await();pool.complete(first,due-181000000000L+1,false);return true;});
            assertNull(pool.acquire(0,due));release.countDown();assertTrue(done.get(2,TimeUnit.SECONDS));
            assertNull(pool.acquire(0,due));assertNotNull(pool.acquire(0,due+1));
        }
    }
}
