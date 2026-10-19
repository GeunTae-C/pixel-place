package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** 최소 조합의 전체 예약. 실서비스 부하/PASS를 대신하지 않음 */
class Phase18K6SupportTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @Test void twentyMinuteExactReceiptsCrossWordsChunksAndLastConnection()throws Exception {
        int events=129150,connections=100;
        var path=temporary.resolve("times.bin");var receipts=temporary.resolve("receipts.bin");
        var observed=new Phase18Events(events,connections,path,true);
        for(int i=events-1;i>=0;i--){
            var event=new Phase18Events.Event(7L*(i+1),i+1,i%8192,0,i%16);
            // HTTP보다 WS가 먼저 도착하고 seq 역순이어도 같은 canonical ordinal로 결합
            for(int connection=0;connection<connections;connection++)observed.received(connection,event,2000000000000L+i);
            observed.accepted(event);
        }
        assertTrue(observed.complete());assertTrue(observed.retainedArrayBytes()<140L*1024*1024);
        observed.saveReceipts(receipts);observed.closeTiming();
        assertEquals(103320000,java.nio.file.Files.size(path));
        try(var file=new java.io.RandomAccessFile(path.toFile(),"r")){
            file.seek(((long)(connections-1)*events+events-1)*8);assertEquals(2000000000000L,file.readLong());
            file.seek(((long)(connections-1)*events+8192)*8);assertEquals(2000000000000L+events-1-8192,file.readLong());
        }
        observed.received(99,new Phase18Events.Event(7,1,0,0,0),2000000000001L);
        assertFalse(observed.complete(),"last connection duplicate must remain a failure");
    }
    @Test void longRunAndWs200RequireNativeOutputAndKeepFixedMemoryCeilings()throws Exception {
        for(long[] input:new long[][]{{105,100,1200},{105,200,120}}){
            var p=Phase18CapTest.plan(Phase18PlanTest.valid(),"CAP-1","cap-target",input[0],input[1],30,input[2],"fixed-rate",20393);
            if(Phase18K6Session.outputBytes(p)>p.bounds().outputBytes())assertThrows(IllegalArgumentException.class,()->Phase18K6Session.requireSupport(p),"old output ceiling insufficient");
            var node=(ObjectNode)Phase18Plan.JSON.valueToTree(p);
            ((ObjectNode)node.path("bounds")).put("outputBytes",Phase18K6Session.outputBytes(p));
            var supported=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
            supported.validate();assertDoesNotThrow(()->Phase18K6Session.requireSupport(supported));
            var n=supported.counts(supported.cases().getFirst());
            assertTrue(128L*1024*1024+(n.writes()+n.reads())*2300<=536870912);
            assertTrue(Phase18K6Session.outputBytes(supported)<12L*1024*1024*1024);
        }
        var tooHigh=Phase18CapTest.plan(Phase18PlanTest.valid(),"CAP-1","cap-target",250,100,30,1200,"fixed-rate",50000);
        assertThrows(IllegalArgumentException.class,()->Phase18K6Session.requireSupport(tooHigh));
    }

    static Phase18Plan revisedPlan(long rate,long seconds)throws Exception {
        var node=(ObjectNode)Phase18Plan.JSON.valueToTree(Phase18Cap1Test.plan(rate,seconds));node.put("schemaVersion",4);
        var p=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        var bounds=(ObjectNode)node.path("bounds");
        bounds.put("outputBytes",Phase18Cap1.limit(256L*1024*1024,Phase18Cap1.output(p).runBytes()));
        bounds.put("buildBytes",Phase18Cap1.limit(2048L*1024*1024,Phase18Cap1.workspaceUsage(p)));
        return Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
    }
    @Test void revisedMaximumRetainsExistingHeapAndRejectsWrongSchemaScope()throws Exception {
        var p=revisedPlan(250,1200);p.validate();Phase18K6Session.requireSupport(p);
        var n=p.counts(p.cases().getFirst());
        assertEquals(1583520444L,n.memoryBytes()+n.writes()*2300+n.wsDeliveries()*15+32L*1024*1024);
        assertTrue(n.memoryBytes()+n.writes()*2300+n.wsDeliveries()*15+32L*1024*1024<=1536L*1024*1024);
        assertEquals(1342177280L,Phase18K6Session.processMemoryBytes(p));
        assertEquals(1879048192L,Phase18K6Session.combinedMemoryBytes(p));
        var node=(ObjectNode)Phase18Plan.JSON.valueToTree(p);node.put("stage","CAP-0");
        assertThrows(IllegalArgumentException.class,()->Phase18Plan.JSON.treeToValue(node,Phase18Plan.class).validate());
    }
    @Test void cap1TwentyMinutePlanAndExactMaximumStayScoped()throws Exception {
        var p=Phase18Cap1Test.plan(250,1200);var n=p.counts(p.cases().getFirst());
        assertEquals(307501,n.writes());assertEquals(307500,n.wsEvents());assertEquals(30750000,n.wsDeliveries());
        p.validate();assertDoesNotThrow(()->Phase18K6Session.requireSupport(p));
        assertEquals(1024L*1024*1024,Phase18K6Session.analysisMemory(p));
        assertTrue(n.memoryBytes()+n.writes()*2300+n.wsDeliveries()*8+32L*1024*1024<=1536L*1024*1024);
        assertThrows(IllegalArgumentException.class,()->new Phase18Events(320001,100,temporary.resolve("overflow.bin"),true,true));
        assertThrows(IllegalArgumentException.class,()->new Phase18Events(307500,100,temporary.resolve("legacy.bin"),true));
        assertThrows(ArithmeticException.class,()->Math.multiplyExact(Long.MAX_VALUE,100));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="PHASE18_CAP1_SCALE_OUTPUT",matches=".+")
    void full250TwentyMinuteSyntheticRetentionAndResultConsumption()throws Exception {
        var p="1".equals(System.getenv("PHASE18_CAUSAL_SCALE"))?revisedPlan(250,1200):Phase18Cap1Test.plan(250,1200);p.validate();Phase18K6Session.requireSupport(p);
        var root=java.nio.file.Path.of(System.getenv("PHASE18_CAP1_SCALE_OUTPUT"));java.nio.file.Files.createDirectory(root);
        var node=(ObjectNode)Phase18Plan.JSON.valueToTree(p);
        ((ObjectNode)node.path("ownership")).put("temp",System.getProperty("java.io.tmpdir"));
        p=Phase18Plan.JSON.treeToValue(node,Phase18Plan.class);
        BenchmarkJson.write(root.resolve("plan.json"),p);String hash=BenchmarkJson.hash(root.resolve("plan.json"));
        var c=p.cases().getFirst();var w=c.workload();int count=307500,connections=100;
        long base=2000000000000L,warm=base+1000,measurement=warm+30000,end=measurement+1200000;
        var times=root.resolve("k6-ws-times.bin");var events=new Phase18Events(count,connections,times,true,true);
        var causal=Phase18Cap1.revised(p)?new Phase18K6Causal(count,connections,root.resolve("k6-ws-causal.bin"),()->0L):null;
        // 전체 입력은 별도 합성 자료. WS-first/전역 역전과 마지막 word/index까지 정확히 보유
        for(int i=count-1;i>=0;i--){
            String phase=i<7500?"warmup":"measurement";long ordinal=i<7500?i:i-7500;
            int[] pixel=Phase18LoadClient.pixel(c,phase,ordinal);var e=new Phase18Events.Event(i+2L,i+2L,pixel[0],pixel[1],pixel[2]);
            long received=warm+i*4L+5;
            for(int connection=0;connection<connections;connection++){
                int index=events.received(connection,e,received+connection%3);
                if(causal!=null)causal.received(connection,index,1000+i*4+4+connection%3,1000+i*4+6+connection%3);
            }
            events.accepted(e);
        }
        assertTrue(events.complete());events.saveReceipts(root.resolve("k6-ws-receipts.bin"));events.closeTiming();
        try(var writer=java.nio.file.Files.newBufferedWriter(root.resolve("k6-ws-canonical.jsonl"))){
            for(int i=0;i<count;i++){writer.write(Phase18Plan.JSON.writeValueAsString(events.event(i)));writer.newLine();}
        }
        if(causal!=null){
            causal.close();assertEquals(246000000,java.nio.file.Files.size(root.resolve("k6-ws-causal.bin")));
            try(var input=new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(root.resolve("k6-ws-causal.bin"))))){
                for(int connection=0;connection<connections;connection++)for(int i=count-1;i>=0;i--){assertEquals(1000+i*4+4+connection%3,input.readInt());assertEquals(1000+i*4+6+connection%3,input.readInt());}
                assertEquals(-1,input.read());
            }
        }
        assertEquals(246000000,java.nio.file.Files.size(times));
        try(var input=new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(times)))){
            for(int connection=0;connection<connections;connection++)for(int i=count-1;i>=0;i--)assertEquals(warm+i*4L+5+connection%3,input.readLong());
            assertEquals(-1,input.read());
        }
        var users=java.util.stream.LongStream.rangeClosed(1,p.counts(c).users()).boxed().toList();
        try(var writer=java.nio.file.Files.newBufferedWriter(root.resolve("k6-records.jsonl"))){
            for(int connection=0;connection<connections;connection++){writer.write(Phase18Plan.JSON.writeValueAsString(java.util.Map.of("type","ws","event","open","connection",connection,"epochMillis",warm-10)));writer.newLine();}
            for(int i=-1;i<count;i++){
                String phase=i<0?"bootstrap":i<7500?"warmup":"measurement";long ordinal=i<0?0:i<7500?i:i-7500;
                long due=i<0?base:warm+i*4L;int user=i<0?0:1+(i%(users.size()-1));int attempt=i<0?1:1+i/(users.size()-1);
                int[] pixel=Phase18LoadClient.pixel(c,phase,ordinal);
                var row=new java.util.LinkedHashMap<String,Object>();
                row.put("schema","phase18-k6-v2");row.put("type","http");row.put("id",Phase18Plan.requestId(c,"write",phase,ordinal));
                row.put("kind","write");row.put("phase",phase);row.put("ordinal",ordinal);row.put("dueEpochMillis",due);
                row.put("startedEpochMillis",due);row.put("bodyEpochMillis",due+10);row.put("validationEpochMillis",due+10);
                row.put("bridgeBeforeEpochMillis",due);row.put("bridgeAfterEpochMillis",due);row.put("bridgeEpochMillis",due);
                if(causal!=null){row.put("claimFloorMillis",Math.max(0,due-base-1));row.put("resultCeilingMillis",due-base+11);}
                row.put("k6DurationMillis",10);row.put("outcome","ACCEPTED");row.put("httpStatus",200);row.put("reason","");
                row.put("x",pixel[0]);row.put("y",pixel[1]);row.put("color",pixel[2]);row.put("userOrdinal",user);row.put("userId",users.get(user).toString());row.put("attempt",attempt);
                row.put("event",new Phase18Events.Event(i+2L,i+2L,pixel[0],pixel[1],pixel[2]));
                writer.write(Phase18Plan.JSON.writeValueAsString(row));writer.newLine();
            }
            for(int connection=0;connection<connections;connection++){writer.write(Phase18Plan.JSON.writeValueAsString(java.util.Map.of("type","ws","event","close","connection",connection,"epochMillis",end+10,"code",1000)));writer.newLine();}
        }
        var boundaries=new java.util.ArrayList<Object>();
        for(var entry:java.util.Map.of("bootstrap",base,"warmup",warm,"measurement",measurement).entrySet()){
            boundaries.add(java.util.Map.of("event","k6-scenario-start","phase",entry.getKey(),"epochMillis",entry.getValue()));
            boundaries.add(java.util.Map.of("event","drained","phase",entry.getKey(),"epochMillis",entry.getKey().equals("warmup")?measurement:entry.getKey().equals("measurement")?end:base+10));
        }
        boundaries.add(java.util.Map.of("event","ws-clock","before",warm,"after",warm,"bridge",warm));
        var producer=(ObjectNode)Phase18Plan.JSON.valueToTree(java.util.Map.of("schema","phase18-k6-v2","planHash",hash,"producerComplete",true,"unresolved",java.util.List.of(),"terminal",307501,"started",307501,"phaseBoundaries",boundaries,"ws",events.report()));
        producer.putNull("firstFailure");BenchmarkJson.write(root.resolve("k6-producer.json"),producer);
        BenchmarkJson.write(root.resolve("k6-input.json"),java.util.Map.of("schema","phase18-k6-v2","planHash",hash,"plan",p,"syntheticFixture",true,"k6BinarySha256","0".repeat(64),"scriptSha256","1".repeat(64),"limits",java.util.Map.of("requests",Phase18K6Session.requestLimit(p),"analysisMemoryBytes",Phase18K6Session.analysisMemory(p))));
        BenchmarkJson.write(root.resolve("k6-runtime.json"),java.util.Map.of("BinarySha256","0".repeat(64),"ScriptSha256","1".repeat(64),"Version","k6 v2.3.0 fixture","syntheticFixture",true));
        var runner=(ObjectNode)Phase18Plan.JSON.valueToTree(java.util.Map.of("Complete",true,"Processes",java.util.List.of(java.util.Map.of("Released",true,"ActualExit",0))));
        runner.putNull("FirstFailure");((ObjectNode)runner.path("Processes").get(0)).putNull("Failure");BenchmarkJson.write(root.resolve("k6-runner.json"),runner);
        var consumed=Phase18K6Results.read(root,new Phase18Plan.Loaded(p,hash),users);
        assertEquals(307501,consumed.accepted().size());assertEquals(307501,consumed.sent());assertEquals(30750000L,consumed.ws().get("deliveries"));
        BenchmarkJson.write(root.resolve("java-scale-result.json"),java.util.Map.of("syntheticFixture",true,"httpTerminals",consumed.sent(),"events",count,"deliveries",30750000,"timingBytes",java.nio.file.Files.size(times),"allTimingValuesChecked",true,"resultConsumerComplete",true,"heapMaxBytes",Runtime.getRuntime().maxMemory(),"heapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),"pid",ProcessHandle.current().pid()));
    }
}
