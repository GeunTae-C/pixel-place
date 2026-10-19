package dev.cgt.benchmark;

import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.http.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 서비스 없이 실제 callback·raw·예열/최종/복구 공용 reader의 같은 경로를 통과 */
class Phase18CapFlowTest {
    static Phase18Plan base;
    @BeforeAll static void baseline()throws Exception{base=Phase18PlanTest.valid();}
    static Object call(Object c,String n,Class<?>[] types,Object... args)throws Exception{return Phase18ControlDeadlineTest.invoke(c,n,types,args);}
    static Object field(Object c,String n)throws Exception{return Phase18ControlDeadlineTest.field(c,n);}
    record Fixture(Phase18Plan p,Path root,Phase18LoadClient client,PipedInputStream input,PipedOutputStream pipe) implements AutoCloseable{
        public void close()throws Exception{try{Phase18ControlDeadlineTest.closeClient(client);}finally{pipe.close();input.close();}}
    }
    static Fixture fixture()throws Exception{return fixture(false,v->{});}
    static Fixture fixture(boolean formal,java.util.function.Consumer<String> messages)throws Exception{
        var j=(ObjectNode)Phase18Plan.JSON.valueToTree(base);String session="phase18-cap-test-"+UUID.randomUUID();
        j.put("schemaVersion",3);j.put("phase","CAP");j.put("stage","CAP-0");j.put("sessionId",session);
        var o=(ObjectNode)j.path("ownership");String rootF="F:/pixel-place-work/sessions/"+session;
        String evidenceSession=System.getenv().getOrDefault("PIXEL_PLACE_WORK_SESSION","phase18-cap-callback");
        Path root=Path.of("F:/pixel-place-work/evidence",evidenceSession,"cap-callback",session);
        o.put("sessionRoot",rootF);o.put("evidenceRoot",root.toString());
        for(String area:List.of("build","cache","temp","jna"))o.put(area,rootF+"/"+(area.equals("cache")?"project-cache":area));
        o.put("wal","F:/pixel-place-wal/phase18/"+session+"/cap-run/pixel-place.wal");o.put("catalog","pixel_place_bench_cap_test");o.put("dbInstance","unopened");o.put("redisIndex",2);
        var b=(ObjectNode)j.path("bounds");b.put("totalSeconds",2400);b.put("outputBytes",33554432);b.put("memoryBytes",536870912);b.put("samples",10000);
        var c=(ObjectNode)j.path("cases").get(0);c.put("role","cap-smoke-s1");c.put("caseId","cap-s1");c.put("runId","cap-run");c.put("kind","mixed");
        var w=(ObjectNode)c.path("workload");w.put("writeRate",2);w.put("readRate",2);w.put("wsConnections",2);w.put("warmupSeconds",2);w.put("measurementSeconds",5);
        w.put("userMode","once");w.put("userPool",0);w.put("bootstrap",true);w.put("reuseMarginMillis",1000);w.put("requestMillis",10000);w.put("drainMillis",180000);
        w.put("inputWindowSeconds",5);w.put("inputTolerancePpm",1000);w.put("writeInFlight",16);w.put("readInFlight",16);w.put("simultaneousSeconds",0);
        long now=System.currentTimeMillis();w.set("schedule",Phase18Plan.JSON.valueToTree(new Phase18Cap.Schedule("fixed-rate",0,200,50,now,now+2400000)));
        var runtime=(ObjectNode)c.path("runtime");runtime.put("mode","group");runtime.put("measurement",true);runtime.put("segmentBytes",33554432);
        if(formal){
            j.put("stage","CAP-3");c.put("role","cap-target");
            w.put("inputWindowSeconds",10);
            b.put("totalSeconds",3600);
            w.put("writeRate",0);w.put("readRate",0);w.put("warmupSeconds",30);w.put("measurementSeconds",401);w.put("userMode","reuse");w.put("userPool",2);w.put("bootstrap",false);
            w.set("schedule",Phase18Plan.JSON.valueToTree(new Phase18Cap.Schedule("users",2,200,60,now,now+3600000)));
        }
        var p=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);p.validate();Files.createDirectories(root.resolve("client"));
        Path start=root.resolve("start.json");BenchmarkJson.write(root.resolve("plan.json"),p);
        BenchmarkJson.write(start,Map.of("endpoint","http://127.0.0.1:1","output",root.resolve("client").toString(),"users",java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList()));
        var in=new PipedInputStream();var pipe=new PipedOutputStream(in);String prev=System.getProperty(Phase18Deadline.PROPERTY);
        System.setProperty(Phase18Deadline.PROPERTY,Long.toString(p.cases().getFirst().workload().schedule().expiresEpochMillis()));
        try{return new Fixture(p,root.resolve("client"),new Phase18LoadClient(new Phase18Plan.Loaded(p,"0".repeat(64)),start,in,BenchmarkEnvironment.keys(),messages),in,pipe);}
        finally{if(prev==null)System.clearProperty(Phase18Deadline.PROPERTY);else System.setProperty(Phase18Deadline.PROPERTY,prev);}
    }
    @SuppressWarnings("unchecked") static HttpResponse<byte[]> response(int code,String body){
        return (HttpResponse<byte[]>)Proxy.newProxyInstance(Phase18CapFlowTest.class.getClassLoader(),new Class[]{HttpResponse.class},(p,m,a)->switch(m.getName()){case "statusCode"->code;case "body"->body.getBytes(java.nio.charset.StandardCharsets.UTF_8);default->throw new UnsupportedOperationException(m.getName());});
    }
    static void complete(Fixture f,String phase,int code,String body)throws Exception{
        var client=f.client;boolean bootstrap=phase.equals("bootstrap");long now=System.nanoTime();
        var pool=(BenchmarkUsers)field(client,"pool");var user=bootstrap?new BenchmarkUsers.Assignment(0,1):pool.acquire(now);
        var gate=(Phase18Control)field(client,"control");var pixel=Phase18LoadClient.pixel(f.p.cases().getFirst(),phase,0);
        assertTrue(gate.dispatch(()->{call(client,"complete",new Class<?>[]{String.class,String.class,long.class,long.class,long.class,long.class,BenchmarkUsers.Assignment.class,int[].class,HttpResponse.class,Throwable.class,Semaphore.class,boolean.class,String.class},
            "write",phase,0L,0L,now,now+1000,user,pixel,response(code,body),null,new Semaphore(0),bootstrap,"sent");return null;}));
    }
    static void remainder(Fixture f,String phase)throws Exception{
        var w=f.p.cases().getFirst().workload();
        for(String kind:List.of("write","read"))for(long i=kind.equals("write")?1:0;i<Phase18Cap.count(w,kind,phase);i++)
            call(f.client,"notSent",new Class<?>[]{String.class,String.class,long.class,long.class,String.class,BenchmarkUsers.Assignment.class},kind,phase,i,Phase18Cap.offset(w,kind,i,phase),"arrival_late",null);
    }
    @Test void allowlistedResponsesRemainRejectedThroughWarmupAndRecoveryReader()throws Exception{
        try(var f=fixture()){
            int[] px=Phase18LoadClient.pixel(f.p.cases().getFirst(),"bootstrap",0);
            complete(f,"bootstrap",200,"{\"accepted\":true,\"eventSeq\":1,\"tileVersion\":1,\"x\":"+px[0]+",\"y\":"+px[1]+",\"color\":"+px[2]+"}");
            complete(f,"warmup",503,"{\"message\":\"Pixel write is busy. Please retry later.\"}");remainder(f,"warmup");
            var loaded=new Phase18Plan.Loaded(f.p,"0".repeat(64));assertEquals(9,Phase18Consistency.readWarmup(f.root.resolve("raw.jsonl"),loaded).size());
            complete(f,"measurement",429,"{\"message\":\"Pixel write cooldown is active.\",\"remainingMillis\":180000}");remainder(f,"measurement");
            var rows=Phase18Consistency.read(f.root.resolve("raw.jsonl"),loaded);assertEquals(29,rows.size());assertEquals(2,rows.stream().filter(Phase18Cap::rejection).count());
            assertEquals(1,rows.stream().filter(r->r.status().equals("accepted")).count());assertNull(((AtomicReference<?>)field(f.client,"failure")).get());
            assertEquals(0,((Phase18Control)field(f.client,"control")).snapshot().active());
            // 복구도 같은 reader를 사용. 거절에 붙은 위조 accepted seq와 terminal 누락/중복은 통과 불가
            String raw=Files.readString(f.root.resolve("raw.jsonl"));Path corrupt=f.root.resolve("corrupt.jsonl");
            Files.writeString(corrupt,raw.replace("\"eventSeq\":null","\"eventSeq\":999"));assertThrows(IllegalArgumentException.class,()->Phase18Consistency.read(corrupt,loaded));
            Files.writeString(corrupt,raw.substring(raw.indexOf('\n')+1));assertThrows(IllegalArgumentException.class,()->Phase18Consistency.read(corrupt,loaded));
            Files.writeString(corrupt,raw+raw.substring(0,raw.indexOf('\n')+1));assertThrows(IllegalArgumentException.class,()->Phase18Consistency.read(corrupt,loaded));
        }
    }
    @Test void unknown503PreservesUnknownAndStop()throws Exception{
        var f=fixture();try{
            complete(f,"measurement",503,"{\"message\":\"Pixel write outcome is unknown.\"}");
            assertNotNull(((AtomicReference<?>)field(f.client,"failure")).get());
            var gate=(Phase18Control)field(f.client,"control");assertEquals(Phase18Control.State.STOPPED,gate.snapshot().state());
            assertFalse(gate.dispatch(()->{fail("dispatch after UNKNOWN");return null;}));
            assertTrue(Files.readString(f.root.resolve("raw.jsonl")).contains("\"outcome\":\"UNKNOWN\""));
            assertThrows(IllegalArgumentException.class,()->Phase18Consistency.read(f.root.resolve("raw.jsonl"),new Phase18Plan.Loaded(f.p,"0".repeat(64))));
        }finally{assertThrows(IllegalStateException.class,f::close);}
    }
    @Test void capStartedBoundDoesNotStopEmptyLargePlanAndCountsGapsAsRecords(){
        var p=Phase18CapTest.plan(base,"CAP-1","cap-target",105,100,30,1200,"fixed-rate",20393);
        var policy=new Phase18ObservationPolicy(1,"0".repeat(64),"cap-test",1610612736,1000,128,256,100000,16777216,8388608,"none");
        var safe=new Phase18Collector.Safety(p,policy,0);
        var wal=Map.<String,Object>of("status","observed","bytes",0L);
        var empty=new Phase18Collector.Input(1,"startup",100,0,true,true,null,false,wal,20L*1024*1024*1024,0,0L);
        assertEquals("",safe.evaluate(empty).stop());
        var capture=new dev.cgt.pixelplace.measurement.PixelMeasurement.Capture(2,7,999,2);
        var now=safe.evaluate(new Phase18Collector.Input(3,"measurement",100,3,true,true,capture,false,wal,20L*1024*1024*1024,0,4L));
        assertEquals(9,now.backlogUpperBound());
        assertEquals("capture-safety-evidence-lost",safe.evaluate(new Phase18Collector.Input(300_000_000_000L,"measurement",100,0,true,true,capture,false,wal,20L*1024*1024*1024,0,4L)).stop());
        var absent=new Phase18Collector.Safety(p,policy,0);
        assertEquals("",absent.evaluate(empty).stop());
        long missing=p.cases().getFirst().observation().maxMissingMillis()*1_000_000L+2;
        assertEquals("capture-safety-evidence-lost",absent.evaluate(new Phase18Collector.Input(missing,"measurement",100,0,true,true,null,false,wal,20L*1024*1024*1024,0,0L)).stop());
        // A real NO_OP refresh permits a sparse workload without disabling evidence-age safety.
        var fresh=new Phase18Collector.Safety(p,policy,0);
        for(long t:new long[]{1,200_000_000_000L})assertEquals("",fresh.evaluate(new Phase18Collector.Input(t,"measurement",100,0,true,true,
                new dev.cgt.pixelplace.measurement.PixelMeasurement.Capture(t,999,999,0),false,wal,20L*1024*1024*1024,0,0L)).stop());
    }
}
