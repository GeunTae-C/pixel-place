package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 bridge의 준비·결과·due·출력 실패 경계. 시간은 고정 Clock으로 전진하며 서비스 사용 없음 */
class Phase18K6BoundaryTest {
    static class Time extends Clock {
        long now=2000000000000L;
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    record Fixture(Phase18K6Session session,Time time,Path root) implements AutoCloseable {
        Object call(String path,Object body)throws Exception{return Phase18ControlDeadlineTest.invoke(session,"dispatch",new Class<?>[]{String.class,JsonNode.class},path,Phase18Plan.JSON.valueToTree(body));}
        void open()throws Exception{for(int i=0;i<2;i++)call("/ws",Map.of("batchId",i+1,"events",List.of(Map.of("event","open","connection",i,"epochMillis",time.now,"receiveOrdinal",1))));}
        @SuppressWarnings("unchecked") Map<String,Object> claim(String phase,String kind,long ordinal)throws Exception{return (Map<String,Object>)call("/claim",Map.of("phase",phase,"kind",kind,"ordinal",ordinal,"scenarioStart",time.now));}
        void read(Map<String,Object> assignment,boolean valid)throws Exception{call("/result",Map.ofEntries(Map.entry("id",assignment.get("id")),Map.entry("startedEpochMillis",time.now),Map.entry("bodyEpochMillis",time.now),Map.entry("status",200),Map.entry("durationMillis",1),Map.entry("decodedLength",valid?65536:0),Map.entry("contentType","application/octet-stream"),Map.entry("encoding","gzip"),Map.entry("cacheControl","no-store"),Map.entry("version","0"),Map.entry("sha256","0".repeat(64))));}
        public void close()throws Exception{session.close();}
    }
    private Fixture fixture(boolean scheduled)throws Exception{
        var p=Phase18K6Test.small(scheduled);var time=new Time();var keys=BenchmarkEnvironment.keys();
        Path root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"k6-boundary-"+UUID.randomUUID());Files.createDirectory(root);
        var users=java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList();
        var tokens=ServiceJwtTokens.benchmarkAccess(new AuthProperties(keys.jwt(),keys.cookie(),Duration.ofMinutes(15)),time);
        return new Fixture(new Phase18K6Session(new Phase18Plan.Loaded(p,"0".repeat(64)),users,"http://127.0.0.1:1",keys.jwt(),tokens,time.now+3600000,root,
            new Phase18K6Session.App(){public void phase(String phase){}public void drain(Set<String> ids,long tail){}public void check(){}},time),time,root);
    }
    @Test void noInitialGetBeforeEveryWsBufferAndNoReadyForMissingOrInvalidInitialIds()throws Exception{
        for(String scenario:List.of("missing-ws","missing-read","invalid-read","not-sent","complete"))try(var f=fixture(true)){
            if(scenario.equals("missing-ws")){
                assertThrows(IllegalArgumentException.class,()->f.session.begin("initial-read"));
                assertEquals(0,Files.size(f.root.resolve("k6-records.jsonl")));continue;
            }
            f.open();f.session.begin("initial-read");var one=f.claim("initial-read","read",0);f.read(one,true);f.time.now+=10;
            if(!scenario.equals("missing-read")){
                var two=f.claim("initial-read","read",1);
                if(scenario.equals("not-sent"))f.call("/cancel",Map.of("id",two.get("id"),"reason","arrival_late"));
                else f.read(two,!scenario.equals("invalid-read"));
            }
            f.time.now+=1000;
            if(scenario.equals("complete")){f.call("/finish",Map.of("phase","initial-read"));assertDoesNotThrow(()->f.session.begin("warmup"));}
            else {assertThrows(Exception.class,()->f.call("/finish",Map.of("phase","initial-read")));assertThrows(Exception.class,()->f.session.begin("warmup"));}
        }
    }
    private static void set(Object target,String name,Object value)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object get(Object target,String name)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    @Test void appDrainDoesNotBlockWsStateOrStopAndCannotAdvanceAfterStop()throws Exception{
        try(var f=fixture(false)){
            var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            set(f.session,"app",new Phase18K6Session.App(){public void phase(String phase){}public void check(){}public void drain(Set<String> ids,long tail)throws Exception{entered.countDown();assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS));}});
            f.open();f.session.begin("warmup");f.read(f.claim("warmup","read",0),true);f.time.now+=1000;
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var drain=Thread.ofPlatform().start(()->{try{f.call("/finish",Map.of("phase","warmup"));}catch(Throwable e){failure.set(e);}});
            try{
                assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(500),()->f.call("/state",Map.of()));
                f.call("/stop",Map.of());assertThrows(Exception.class,()->f.session.begin("measurement"));
            }finally{release.countDown();drain.join(2000);}
            assertFalse(drain.isAlive());assertNotNull(failure.get());assertEquals("controller_stop",f.session.failure());
        }
    }
    @Test void originalDueAndStopPhaseUserCapacityPriorityRemainDistinct()throws Exception{
        for(String scenario:List.of("stopped","arrival_late","user_not_ready","boundary-ready"))try(var f=fixture(true)){
            set(f.session,"phase","measurement");set(f.session,"phaseFinished",false);set(f.session,"anchor",f.time.now);set(f.session,"end",f.time.now+1000);
            var pool=(BenchmarkUsers)get(f.session,"pool");var user=pool.acquire(0,(f.time.now-200000)*1000000);
            pool.complete(user,(f.time.now-181000)*1000000+(scenario.equals("boundary-ready")?0:1),false);
            if(scenario.equals("stopped"))f.session.stop("test_stop");
            if(scenario.equals("arrival_late"))set(f.session,"end",f.time.now);
            var result=f.claim("measurement","write",0);
            if(scenario.equals("boundary-ready"))assertEquals(true,result.get("send"));
            else assertEquals(scenario,result.get("reason"));
        }
    }
    @Test void revisedClaimRechecksStopAndPhaseEndBeforeAuthorizingSend()throws Exception{
        for(String scenario:List.of("allow","stop","expiry","phase-end"))try(var f=fixture(false)){
            f.open();f.session.begin("warmup");
            set(f.session,"causal",new Phase18K6Causal(8,2,f.root.resolve("claim-causal.bin")));
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            set(f.session,"app",new Phase18K6Session.App(){
                public void phase(String phase){}public void drain(Set<String> ids,long tail){}
                public void check(){if(checks.incrementAndGet()==2){
                    if(scenario.equals("stop"))throw new IllegalStateException("injected stop");
                    if(scenario.equals("expiry"))f.time.now+=3600000;
                    if(scenario.equals("phase-end"))f.time.now+=1000;
                }}
            });
            var value=f.claim("warmup","write",0);
            assertEquals(2,checks.get());
            if(scenario.equals("allow")){assertEquals(true,value.get("send"));assertEquals(true,value.get("authorized"));}
            else {
                assertEquals(false,value.get("send"));assertFalse(value.containsKey("authorized"));
                assertEquals(scenario.equals("phase-end")?"arrival_late":"stopped",value.get("reason"));
                assertEquals(0,((Number)get(f.session,"pendingWrites")).intValue());
                var row=Phase18Plan.JSON.readTree(Files.readAllLines(f.root.resolve("k6-records.jsonl")).getLast());
                assertEquals("NOT_SENT",row.path("outcome").asText());
            }
        }
    }
    @Test void acceptedBusyCooldownUnknownAndRequiredOutputFailureStaySeparate()throws Exception{
        for(int status:new int[]{200,429,503,500})try(var f=fixture(false)){
            f.open();f.session.begin("warmup");var assignment=f.claim("warmup","write",0);
            String body=switch(status){
                case 200 -> Phase18Plan.JSON.writeValueAsString(Map.of("accepted",true,"eventSeq",Long.MAX_VALUE,"tileVersion",1,"x",assignment.get("x"),"y",assignment.get("y"),"color",assignment.get("color")));
                case 429 -> "{\"message\":\"Pixel write cooldown is active.\",\"remainingMillis\":180000}";
                case 503 -> "{\"message\":\"Pixel write is busy. Please retry later.\"}";
                default -> "{}";
            };
            f.call("/result",Map.of("id",assignment.get("id"),"startedEpochMillis",f.time.now,"bodyEpochMillis",f.time.now+1,"status",status,"durationMillis",1,"body",body));
            var lines=Files.readAllLines(f.root.resolve("k6-records.jsonl"));var record=Phase18Plan.JSON.readTree(lines.getLast());
            assertEquals(status==200?"ACCEPTED":status==500?"UNKNOWN":"REJECTED",record.path("outcome").asText());
            if(status==200)assertEquals(Long.MAX_VALUE,record.path("event").path("eventSeq").asLong());
            assertEquals(status==500,f.session.failure()!=null);
        }
        var f=fixture(false);
        try{set(f.session,"bytes",Phase18K6Session.MAX_BYTES);assertThrows(IllegalArgumentException.class,f::open);}
        finally{f.close();}
    }
    @Test void occupiedCapacityAndExpiredOriginalClockNeverBecomeLateCatchUpRequests()throws Exception{
        try(var f=fixture(false)){
            var original=(Phase18Plan.Workload)get(f.session,"w");var json=(ObjectNode)Phase18Plan.JSON.valueToTree(original);json.put("writeInFlight",1);
            set(f.session,"w",Phase18Plan.JSON.treeToValue(json,Phase18Plan.Workload.class));
            f.open();f.session.begin("warmup");assertEquals(true,f.claim("warmup","write",0).get("send"));
            f.time.now+=500;assertEquals("processing_capacity",f.claim("warmup","write",1).get("reason"));
        }
        try(var f=fixture(true)){
            set(f.session,"phase","measurement");set(f.session,"phaseFinished",false);set(f.session,"anchor",f.time.now);set(f.session,"end",f.time.now);
            f.time.now+=3600000;assertEquals("stopped",f.claim("measurement","write",0).get("reason"));
        }
    }
}
