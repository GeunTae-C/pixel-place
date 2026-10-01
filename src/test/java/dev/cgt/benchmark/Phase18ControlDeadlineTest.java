package dev.cgt.benchmark;

import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 LoadClient 수신·callback 소진과 실제 자식 종료 경계. 서비스 부하와 긴 SOAK는 없음 */
class Phase18ControlDeadlineTest {
    static Phase18Plan base;
    @BeforeAll static void baseline() throws Exception {base=Phase18PlanTest.valid();}
    static Object invoke(Object target,String name,Class<?>[] types,Object... args) throws Exception {
        var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException e){Phase18Process.rethrow(e.getCause());throw e;}
    }
    static Object field(Object target,String name)throws Exception {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void await(Phase18LoadClient c,String expected)throws Exception {invoke(c,"await",new Class<?>[]{String.class},expected);}
    static void drain(Phase18LoadClient c)throws Exception {invoke(c,"drain",new Class<?>[0]);}
    static void closeClient(Phase18LoadClient c)throws Exception {invoke(c,"close",new Class<?>[0]);}
    static final class Input implements AutoCloseable {
        final Phase18Plan p;final Path root,plan,start;final Phase18Deadline budget;
        Input()throws Exception {this(false);}
        Input(boolean fullFlow)throws Exception {
            var j=(ObjectNode)Phase18Plan.JSON.valueToTree(base);String session="phase18-boundary-"+UUID.randomUUID();
            j.put("sessionId",session);j.put("phase","A");j.put("stage","A-2");
            var o=(ObjectNode)j.path("ownership");String large="E:/pixel-place-phase15/"+session;
            root=Path.of(o.path("repo").asText(),"build/agent-runs/phase18-a4-rev-20261001-01/control-raw",session);
            o.put("sessionRoot",large);o.put("evidenceRoot",root.toString());
            for(String area:List.of("build","cache","temp","jna"))o.put(area,large+"/"+area);
            o.put("wal",Path.of(o.path("repo").asText(),"data",session,"trial/pixel-place.wal").toString());
            o.put("catalog","pixel_place_bench_boundary");o.put("dbInstance","unopened-test");o.put("redisIndex",2);
            ((ObjectNode)j.path("bounds")).put("totalSeconds",300);
            var trial=(ObjectNode)j.path("cases").get(0);trial.put("kind","read");
            var w=(ObjectNode)trial.path("workload");w.put("readRate",1);w.put("controlMillis",150);w.put("drainMillis",2000);
            if(fullFlow){w.put("warmupSeconds",1);w.put("requestMillis",3000);}
            p=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);p.validate();budget=Phase18Deadline.start(p,300000);
            Files.createDirectories(root.resolve("client"));plan=root.resolve("plan.json");start=root.resolve("start.json");
            BenchmarkJson.write(plan,p);BenchmarkJson.write(start,Map.of("endpoint","http://127.0.0.1:1","users",List.of(),"output",root.resolve("client").toString()));
            System.out.println("CONTROL_BOUNDARY_EVIDENCE "+root);
        }
        Phase18LoadClient client(InputStream in,java.util.function.Consumer<String> out)throws Exception {
            return client(in,out,budget);
        }
        Phase18LoadClient client(InputStream in,java.util.function.Consumer<String> out,Phase18Deadline deadline)throws Exception {
            var previous=System.getProperty(Phase18Deadline.PROPERTY);System.setProperty(Phase18Deadline.PROPERTY,Long.toString(deadline.expiresEpochMillis()));
            try{return new Phase18LoadClient(new Phase18Plan.Loaded(p,"0".repeat(64)),start,in,BenchmarkEnvironment.keys(),out);}
            finally{if(previous==null)System.clearProperty(Phase18Deadline.PROPERTY);else System.setProperty(Phase18Deadline.PROPERTY,previous);}
        }
        public void close(){} // 원시 실패/성공 증거 보존
    }
    void normalDelayed(String message)throws Exception {
        try(var input=new Input();var pipe=new PipedInputStream();var writer=new PipedOutputStream(pipe);var workers=Executors.newSingleThreadExecutor()) {
            var client=input.client(pipe,value->{});var entered=new CountDownLatch(1);
            var result=workers.submit(()->{entered.countDown();await(client,message);return true;});
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                // B control15초보다 길고 drain60초보다 짧은 정상 선행 작업을 350ms로 축소. latch 이후 대기 상태 확인
                assertThrows(TimeoutException.class,()->result.get(350,TimeUnit.MILLISECONDS));
                writer.write((message+"\n").getBytes());writer.flush();assertTrue(result.get(2,TimeUnit.SECONDS));
            }finally{writer.write("STOP\n".getBytes());writer.flush();closeClient(client);}
        }
    }
    @Test void connectWaitIncludesServerDrainAndCooldown()throws Exception {normalDelayed("CONNECT");}
    @Test void measureWaitIncludesWarmupServerDrain()throws Exception {normalDelayed("MEASURE");}
    @Test void warmupDoneWaitIncludesActualClientCallbackDrain()throws Exception {
        try(var input=new Input()) {
            var command=command(input,"warmup");
            Phase18Process.run(command,child->{
                assertEquals("CALLBACK_OWNED",child.receive(5000));
                try(var workers=Executors.newSingleThreadExecutor()) {
                    var waiting=workers.submit(()->child.receive(Phase18Execution.responseWaitMillis("WARMUP_DONE",input.p.cases().getFirst().workload())));
                    try {assertThrows(TimeoutException.class,()->waiting.get(350,TimeUnit.MILLISECONDS));}
                    finally {Files.writeString(input.root.resolve("release"),"release",StandardOpenOption.CREATE_NEW);}
                    assertEquals("WARMUP_DONE 1",waiting.get(3,TimeUnit.SECONDS));
                }
            });
            assertEquals(0,BenchmarkJson.read(command.evidence().resolve("exit.json")).path("exit").asInt(-1));
        }
    }
    @Test void missingAndLateResponseRetainFirstTimeoutAndBlockNewDispatch()throws Exception {
        String message="MEASURE";
        try(var input=new Input();var pipe=new PipedInputStream();var writer=new PipedOutputStream(pipe)) {
            var client=input.client(pipe,value->{});
            // 실제 MEASURE의 선행 작업 포함 기한 초과 뒤 늦은 응답도 최초 실패를 지우지 못함
            var first=assertThrows(TimeoutException.class,()->await(client,message));
            assertTrue(first.getMessage().contains("Control deadline"));
            writer.write((message+"\n").getBytes());writer.flush();
            assertSame(first,assertThrows(TimeoutException.class,()->await(client,message)));
            assertFalse(((Phase18Control)field(client,"control")).dispatch(()->{fail("after first failure");return null;}));
            assertSame(first,field(client,"failure") instanceof java.util.concurrent.atomic.AtomicReference<?> ref?ref.get():null);
            assertSame(first,assertThrows(TimeoutException.class,()->closeClient(client)));
        }
    }
    @Test void globalDeadlineCapsExpandedConnectAndMissingWarmupResponse()throws Exception {
        try(var input=new Input();var pipe=new PipedInputStream();var writer=new PipedOutputStream(pipe)) {
            var client=input.client(pipe,value->{},Phase18Deadline.start(input.p,450));
            var first=assertThrows(TimeoutException.class,()->await(client,"CONNECT"));
            writer.write("CONNECT\n".getBytes());writer.flush();
            assertSame(first,assertThrows(TimeoutException.class,()->await(client,"CONNECT")));
            assertSame(first,assertThrows(TimeoutException.class,()->closeClient(client)));
        }
        try(var input=new Input()) {
            var cmd=command(input,"warmup");
            assertThrows(TimeoutException.class,()->Phase18Process.run(cmd,child->{
                assertEquals("CALLBACK_OWNED",child.receive(5000));
                try{child.receive(100);}finally{Files.writeString(input.root.resolve("release"),"release");}
            }));
            assertFalse(Files.exists(cmd.evidence().resolve("manifest.json")));
            assertEquals(0,BenchmarkJson.read(cmd.evidence().resolve("exit.json")).path("exit").asInt(-1));
        }
    }
    @Test void stopDuringExpandedWaitsAppliesBeforeCallbackDrainAndRealChildExit()throws Exception {
        for(String message:List.of("CONNECT","MEASURE","warmup"))try(var input=new Input();var workers=Executors.newSingleThreadExecutor()) {
            var cmd=command(input,message);var parent=new Phase18ParentControl(1000,4000);
            var first=new Phase18ParentControl.Stopped("boundary "+message);var ackObserved=new CountDownLatch(1);
            Future<?>[] releaser={null};
            assertSame(first,assertThrows(IOException.class,()->Phase18Process.run(cmd,child->{
                assertEquals("CALLBACK_OWNED",parent.receive(child,5000));
                releaser[0]=workers.submit(()->{
                    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                    while(!Boolean.TRUE.equals(parent.report().get("timelyApplied"))&&System.nanoTime()<until)Thread.sleep(5);
                    try {
                        assertEquals(true,parent.report().get("timelyApplied"));
                        assertTrue(child.isAlive());assertFalse(Boolean.TRUE.equals(parent.report().get("callbacksDrained")));
                        ackObserved.countDown();
                    }finally{Files.writeString(input.root.resolve("release"),"release",StandardOpenOption.CREATE_NEW);}
                    return null;
                });
                parent.stop("STOP",first);parent.check();
            },BenchmarkJson::write,Map.of(),parent)));
            releaser[0].get(5,TimeUnit.SECONDS);assertEquals(0,ackObserved.getCount());
            assertEquals(true,parent.report().get("callbacksDrained"));assertEquals(true,parent.report().get("rawSaved"));
            assertEquals(false,parent.report().get("producerAlive"));assertEquals(1L,parent.report().get("appliedDispatches"));
            assertEquals(0,BenchmarkJson.read(cmd.evidence().resolve("exit.json")).path("exit").asInt(-1));
            assertFalse(Files.exists(cmd.evidence().resolve("manifest.json")));assertTrue(Phase18Process.unresolved().isEmpty());
            assertThrows(IOException.class,()->parent.handoff(()->{fail("handoff after stop");return null;}));
        }
    }
    @Test void literalBPlanWaitsAccountForEveryActualPredecessor()throws Exception {
        Phase18DeadlineTest.base=base;var w=Phase18DeadlineTest.plan("B",600).cases().getFirst().workload();
        assertEquals(80000,Phase18Execution.responseWaitMillis("CONNECT",w));
        assertEquals(75000,Phase18Execution.responseWaitMillis("MEASURE",w));
        assertEquals(105000,Phase18Execution.responseWaitMillis("WARMUP_DONE",w));
        assertEquals(135000,Phase18Execution.responseWaitMillis("SEND_DONE",w));
        assertEquals(255000,Phase18Execution.responseWaitMillis("DRAINED",w));
        assertEquals(195000,Phase18Execution.responseWaitMillis("BOOTSTRAP",w));
        assertEquals(135000,Phase18Execution.responseWaitMillis("READY",w));
        assertEquals(15000,Phase18Execution.responseWaitMillis("START",w));
        assertEquals(15000,Phase18Execution.responseWaitMillis("WARMUP",w));
    }
    @Test void completeActualClientRunWaitsForWarmupHttpCallbackAndDelayedMeasure()throws Exception {
        try(var input=new Input(true)) {
            var cmd=command(input,"full-flow");
            Phase18Process.run(cmd,child->{
                child.send("START");assertEquals("READY",child.receive(5000));child.send("WARMUP");
                assertEquals("HTTP_PENDING",child.receive(2000));
                try(var workers=Executors.newSingleThreadExecutor()) {
                    var response=workers.submit(()->child.receive(Phase18Execution.responseWaitMillis("WARMUP_DONE",input.p.cases().getFirst().workload())));
                    try{assertThrows(TimeoutException.class,()->response.get(1350,TimeUnit.MILLISECONDS));}
                    finally{Files.writeString(input.root.resolve("release"),"release");}
                    assertEquals("WARMUP_DONE 1",response.get(3,TimeUnit.SECONDS));
                    // 실제 run()이 MEASURE를 기다리는 동안 control 한도를 넘어도 정상 유지
                    var absent=workers.submit(()->child.receive(350));
                    assertInstanceOf(TimeoutException.class,assertThrows(ExecutionException.class,()->absent.get(2,TimeUnit.SECONDS)).getCause());
                    assertTrue(child.isAlive());child.send("MEASURE");
                    assertEquals("SEND_DONE 2",child.receive(3000));assertEquals("DRAINED 2",child.receive(3000));assertEquals("DONE 2",child.receive(3000));
                }
            });
            var summary=BenchmarkJson.read(input.root.resolve("client/producer.json"));
            assertEquals(2,summary.path("terminalRows").asInt());assertEquals(0,summary.path("control").path("active").asInt(-1));
            assertTrue(Files.readAllLines(input.root.resolve("client/raw.jsonl")).stream().allMatch(line->Phase18Plan.JSON.readTree(line).path("status").asText().equals("accepted")));
        }
    }
    static Phase18Process.Command command(Input input,String mode)throws Exception {
        String previous=System.getProperty("java.class.path");System.setProperty("java.class.path",Phase18DeadlineTest.classpath());
        try{return Phase18Transport.command(input.p,input.root.resolve("process"),input.root.resolve("child.args"),"128m",ControlChild.class.getName(),List.of(input.plan.toString(),input.start.toString(),mode),input.budget);}
        finally{System.setProperty("java.class.path",previous);}
    }
    /** 실제 발생기의 control reader·await·drain·close를 짧은 자식 JVM에서 실행 */
    public static final class ControlChild {
        public static void main(String[] args)throws Exception {
            var p=Phase18Plan.JSON.readValue(Files.readString(Path.of(args[0])),Phase18Plan.class);
            if(args[2].equals("full-flow")){fullFlow(p,Path.of(args[1]));return;}
            var client=new Phase18LoadClient(new Phase18Plan.Loaded(p,"0".repeat(64)),Path.of(args[1]),System.in,BenchmarkEnvironment.keys(),v->{System.out.println(v);System.out.flush();});
            var gate=(Phase18Control)field(client,"control");gate.dispatch(()->null);
            var release=new CountDownLatch(1);Path marker=Path.of(args[0]).getParent().resolve("release");
            try(var workers=Executors.newFixedThreadPool(2)) {
                var callback=workers.submit(()->{release.await();gate.complete();return true;});
                workers.submit(()->{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!Files.exists(marker)&&System.nanoTime()<end)Thread.sleep(5);release.countDown();return true;});
                System.out.println("CALLBACK_OWNED");System.out.flush();
                try {
                    if(!args[2].equals("warmup"))await(client,args[2]);
                    drain(client);callback.get(3,TimeUnit.SECONDS);
                    if(args[2].equals("warmup")&&gate.snapshot().state()==Phase18Control.State.OPEN){System.out.println("WARMUP_DONE 1");System.out.flush();}
                    else {
                        if(gate.dispatch(()->null))throw new AssertionError("dispatch after STOP");
                        System.out.println("DRAINED 1");System.out.println("DONE 0");System.out.flush();
                    }
                } finally {release.countDown();closeClient(client);}
            }
        }
        static void fullFlow(Phase18Plan p,Path start)throws Exception {
            var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            var release=new CountDownLatch(1);Path marker=start.getParent().resolve("release");
            try(var workers=Executors.newFixedThreadPool(2)) {
                http.setExecutor(workers);
                workers.submit(()->{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!Files.exists(marker)&&System.nanoTime()<end)Thread.sleep(5);release.countDown();return null;});
                http.createContext("/api/tiles",exchange->{
                    try {
                        if(exchange.getRequestHeaders().getFirst("X-Phase18-Request").contains("/warmup/")) {
                            System.out.println("HTTP_PENDING");System.out.flush();
                            if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Callback release missing");
                        }
                        byte[] gzip=Phase18TileBody.gzip(new byte[65536]);
                        var h=exchange.getResponseHeaders();h.set("Content-Type","application/octet-stream");h.set("Content-Encoding","gzip");h.set("Cache-Control","no-store");h.set("X-Tile-Version","0");
                        exchange.sendResponseHeaders(200,gzip.length);exchange.getResponseBody().write(gzip);
                    } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}finally{exchange.close();}
                });
                http.start();
                var input=(ObjectNode)BenchmarkJson.read(start);input.put("endpoint","http://127.0.0.1:"+http.getAddress().getPort());
                Path connected=start.resolveSibling("connected.json");BenchmarkJson.write(connected,input);
                var client=new Phase18LoadClient(new Phase18Plan.Loaded(p,"0".repeat(64)),connected,System.in,BenchmarkEnvironment.keys(),v->{System.out.println(v);System.out.flush();});
                try{invoke(client,"run",new Class<?>[0]);}
                catch(Exception|Error e){invoke(client,"fail",new Class<?>[]{Throwable.class},e);throw e;}
                finally{release.countDown();closeClient(client);http.stop(0);}
            } finally {http.stop(0);}
        }
    }
}
