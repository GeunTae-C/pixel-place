package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.wal.infra.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 한 A-2 initial 또는 recovery JVM만 소유. 정상 shutdown과 실제 동일 DB/WAL 대응 없이는 성공 manifest 미게시 */
public final class Phase18BenchmarkMain {
    private final Phase18Plan.Loaded loaded;
    private final Phase18Environment environment;
    private final Phase18Plan.Case c;
    private final Path planFile,envFile,output;
    private final boolean recovery;
    private final Set<String> expected=new HashSet<>();
    private final List<Object> drains=new ArrayList<>();
    private BenchmarkFixtures fixture;
    private ConfigurableApplicationContext context;
    private List<Long> users=List.of();
    private List<Phase18Raw> rows=List.of();
    private Map<String,Object> consistency=Map.of();
    private boolean normalShutdown;
    private final Phase18ParentControl upstream;
    private tools.jackson.databind.JsonNode initial;
    private Phase18BenchmarkMain(Path planFile,Path envFile,boolean recovery) throws Exception {
        this.planFile=planFile;this.envFile=envFile;this.recovery=recovery;loaded=Phase18Plan.read(planFile);Phase18Runtime.verify(loaded.plan());
        Phase18Transport.requireFixture(loaded.plan());environment=Phase18Environment.read(envFile,loaded.plan());c=loaded.plan().cases().getFirst();
        upstream=new Phase18ParentControl(c.observation().stopAckMillis(),c.workload().drainMillis());
        output=Path.of(loaded.plan().ownership().evidenceRoot(),c.runId(),recovery?"recovery":"initial");
        Phase18Paths.checked(output.toString());Phase18Plan.require(!Files.exists(output),"new app output");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3||!Set.of("initial","recovery").contains(args[0]))throw new IllegalArgumentException("initial|recovery plan environment");
        var app=new Phase18BenchmarkMain(Phase18Paths.checked(args[1]),Phase18Paths.checked(args[2]),args[0].equals("recovery"));
        Files.createDirectory(app.output);app.upstream.listen(System.in);Throwable failure=null;
        try{app.execute();}catch(Exception|Error problem){failure=problem;}
        finally{try{app.finishStopped();}catch(Exception|Error problem){failure=Phase18Process.preserve(failure,problem);}
            try{app.close();}catch(Exception|Error problem){failure=Phase18Process.preserve(failure,problem);}}
        failure=Phase18Process.preserve(failure,app.upstream.failure());
        try{app.save(failure);}catch(Exception|Error problem){failure=Phase18Process.preserve(failure,problem);}
        Phase18Process.rethrow(failure);
    }
    private void execute()throws Exception {
        upstream.check();var p=loaded.plan();Path wal=Path.of(p.ownership().wal());
        if(recovery)initial=recoverInput();else Phase18Plan.require(!Files.exists(wal.getParent()),"fresh initial WAL root");
        var credentials=BenchmarkEnvironment.credentials();
        Phase18Fixtures.credentials(credentials,p);
        fixture=new BenchmarkFixtures(credentials,p.ownership().catalog(),!recovery,Path.of(environment.mysqlRoot()));
        Phase18Fixtures.live(fixture,environment,p);
        upstream.check();var storageBefore=fixture.storageSnapshot();
        BenchmarkJson.write(output.resolve("storage-before.json"),storageBefore);
        if(recovery){var list=new ArrayList<Long>();for(var id:initial.path("users"))list.add(id.longValue());users=List.copyOf(list);fixture.fixtureFiles();}
        else{users=Phase18Fixtures.prepare(fixture,environment,p,output);Files.createDirectories(wal.getParent());}
        upstream.check();var keys=BenchmarkEnvironment.keys();var properties=BenchmarkEnvironment.properties(credentials,p.ownership().catalog(),wal,keys,c.runtime().measurement());
        properties.put("pixel-place.write.mode",c.runtime().mode());properties.put("pixel-place.write.group.max-batch-size",16);properties.put("pixel-place.write.group.max-outstanding",128);
        properties.put("pixel-place.write.group.queue-timeout","1s");properties.put("pixel-place.write.group.shutdown-grace","10s");properties.put("pixel-place.wal.max-segment-bytes",c.runtime().segmentBytes());
        var app=BenchmarkEnvironment.application(properties);app.addPrimarySources(List.of(Phase18Activity.class));
        app.addInitializers(ctx->ctx.getBeanFactory().registerSingleton("phase18Tracker",new Phase18Activity.Tracker(160)));
        System.out.println("APP_PREPARING");System.out.flush();
        bounded(()->{context=app.run();return true;},c.workload().readyMillis(),"ready");
        BenchmarkJson.write(output.resolve("ready-returned.json"),Map.of("stopped",upstream.stopped(),"observedNanos",System.nanoTime()));
        upstream.check();verifyRuntime();
        if(recovery){rows=Phase18Consistency.read(output.getParent().resolve("initial/raw.jsonl"),loaded);drain(initial.path("checkpoint").longValue());}
        else {
            upstream.check();diagnostics(keys);upstream.check();startProducer(keys);upstream.check();
            rows=Phase18Consistency.read(output.resolve("raw.jsonl"),loaded);for(var row:rows)if(row.sendState().equals("sent"))expected.add(row.requestId());
            drain(rows.stream().filter(r->r.eventSeq()!=null).mapToLong(Phase18Raw::eventSeq).max().orElse(0));
        }
        upstream.check();consistency=Phase18Consistency.verify(fixture,context,users,rows);
        var storageAfter=fixture.storageSnapshot();long binlogGrowth=BenchmarkStorageBudget.growth(storageBefore.binaryLogs(),storageAfter.binaryLogs());
        long catalogBytes=fixture.bytesForCatalogs(Set.of(p.ownership().catalog()));
        Phase18Plan.require(binlogGrowth<=p.bounds().binlogBytes()&&catalogBytes<=p.bounds().databaseBytes(),"actual catalog/binlog budget");
        BenchmarkJson.write(output.resolve("storage-after.json"),Map.of("snapshot",storageAfter,"binlogGrowthBytes",binlogGrowth,"catalogBytes",catalogBytes));
        if(recovery)Phase18Plan.require(context.getBean(PixelMeasurement.class).walCounts().completedRecords()==0,"recovery no append");
        environment.budget(p);p.verifyInputs();Phase18Runtime.verify(p);
    }
    private String endpoint(){return "http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();}
    private void startProducer(BenchmarkEnvironment.Keys keys)throws Exception {
        BenchmarkJson.write(output.resolve("start.json"),Map.of("endpoint",endpoint(),"users",users,"output",output.toString()));
        var command=Phase18Transport.command(loaded.plan(),output.resolve("producer-process"),output.resolve("producer.args"),"512m",
                "dev.cgt.benchmark.Phase18LoadClient",List.of(planFile.toString(),output.resolve("start.json").toString()));
        Phase18Process.runWithKeys(command,keys,upstream,child->{
            send(child,"START");
            if(c.workload().bootstrap()){
                String boot=receive(child,c.workload().readyMillis());Phase18Plan.require(boot.equals("BOOTSTRAP 1"),"bootstrap HTTP");
                expected.add(Phase18Plan.requestId(c,"write","bootstrap",0));drain(1);cooldown(keys);send(child,"CONNECT");
            }
            Phase18Plan.require(receive(child,c.workload().readyMillis()).equals("READY"),"producer/WS ready");
            context.getBean(PixelMeasurement.class).phase(PixelMeasurement.Phase.warmup);send(child,"WARMUP");
            String warm=receive(child,c.workload().controlMillis());Phase18Plan.require(warm.startsWith("WARMUP_DONE "),"warmup done");
            addPhase("warmup");drain(c.workload().bootstrap()?1+c.workload().writeRate()*c.workload().warmupSeconds():c.workload().writeRate()*c.workload().warmupSeconds());
            long appliedCount=-1;
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            boolean stopActive=c.caseId().equals("stop-active"), timeout=c.caseId().equals("timeout"), upstreamTimeout=c.caseId().equals("upstream-timeout");
            try {
            if(stopActive||timeout||upstreamTimeout){
                context.getBean(Phase18Activity.Tracker.class).delay(Phase18Plan.requestId(c,"read","measurement",0),entered,release);
                send(child,"MEASURE");Phase18Plan.require(entered.await(5,TimeUnit.SECONDS),"measurement read handler entered");
            }
            if(c.caseId().startsWith("stop")){
                long requested=System.nanoTime();send(child,"STOP");String ack=receive(child,c.observation().stopAckMillis());long ackAt=System.nanoTime();
                String[] fields=ack.split(" ");Phase18Plan.require(fields.length==4&&fields[0].equals("APPLIED"),"actual producer STOP applied");
                appliedCount=Long.parseLong(fields[1]);
                Phase18Plan.require(stopActive?appliedCount>expected.size()-diagnosticCount():appliedCount==expected.size()-diagnosticCount(),"STOP actual dispatch count");
                BenchmarkJson.write(output.resolve("stop.json"),Map.of("runStatus","FAILED","reason","intentional_parent_stop","requestedParentNanos",requested,"ackParentNanos",ackAt,
                        "receivedChildNanos",Long.parseLong(fields[2]),"appliedChildNanos",Long.parseLong(fields[3]),"appliedDispatches",appliedCount));
            }
            if(!timeout&&!upstreamTimeout)release.countDown();
            context.getBean(PixelMeasurement.class).phase(PixelMeasurement.Phase.measurement);if(!stopActive&&!timeout&&!upstreamTimeout)send(child,"MEASURE");
            Phase18Plan.require(receive(child,c.workload().controlMillis()).startsWith("SEND_DONE "),"send done");
            String drained=receive(child,c.workload().drainMillis());Phase18Plan.require(drained.startsWith("DRAINED "),"producer drained");
            if(appliedCount>=0)Phase18Plan.require(drained.equals("DRAINED "+appliedCount),"no dispatch after STOP / callbacks drained");
            Phase18Plan.require(receive(child,c.workload().drainMillis()).startsWith("DONE "),"producer raw saved");
            } finally {release.countDown();}
        });
        if(c.caseId().startsWith("stop")){
            var raw=Phase18Consistency.read(output.resolve("raw.jsonl"),loaded);var producer=BenchmarkJson.read(output.resolve("producer.json"));
            long applied=BenchmarkJson.read(output.resolve("stop.json")).path("appliedChildNanos").longValue();
            long accepted=raw.stream().filter(r->r.status().equals("accepted")).count();
            Phase18Plan.require(raw.stream().filter(r->r.sentNanos()!=null).allMatch(r->r.sentNanos()<=applied)
                    &&raw.stream().anyMatch(r->r.status().equals("client_not_sent"))
                    &&producer.path("control").path("dispatched").longValue()==accepted&&producer.path("control").path("active").longValue()==0,"STOP raw effect/callback ownership");
            BenchmarkJson.write(output.resolve("stop-verification.json"),Map.of("verificationPassed",true,"runStatus","FAILED","newDispatchAfterApplied",0,"callbackActive",0,"originalPlanned",loaded.plan().counts(c)));
        }
    }
    private String receive(Phase18Process.Owned child,long millis)throws Exception {return upstream.receive(child,millis);}
    private void send(Phase18Process.Owned child,String message)throws Exception {upstream.handoff(()->{child.send(message);return null;});}
    /** 실패 실행의 소진 증거 수집. 실제 자식 종료 전 서버 close 금지, 정상 실행으로 복귀 금지 */
    private void finishStopped()throws Exception {
        bounded(()->{Phase18Process.awaitRemaining();return true;},c.workload().drainMillis(),"producer-exit");
        if(!upstream.stopped()||context==null||!Files.exists(output.resolve("raw.jsonl")))return;
        rows=Phase18Consistency.readStopped(output.resolve("raw.jsonl"),loaded);
        for(var row:rows)if(row.sendState().equals("sent"))expected.add(row.requestId());
        drain(rows.stream().filter(r->r.eventSeq()!=null).mapToLong(Phase18Raw::eventSeq).max().orElse(0));
    }
    private long diagnosticCount(){return expected.stream().filter(id->id.contains("/diagnostic/")).count();}
    private void addPhase(String phase){for(String kind:List.of("write","read")){long n=(kind.equals("write")?c.workload().writeRate():c.workload().readRate())*(phase.equals("warmup")?c.workload().warmupSeconds():c.workload().measurementSeconds());for(long i=0;i<n;i++)expected.add(Phase18Plan.requestId(c,kind,phase,i));}}
    private void drain(long tail)throws Exception {
        var measurement=context.getBean(PixelMeasurement.class);var tracker=context.getBean(Phase18Activity.Tracker.class);
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(c.workload().drainMillis()),idle=0;
        while(System.nanoTime()<until){
            var executor=context.getBean(PixelWriteExecutor.class).snapshot();boolean pending=context.getBean(PendingAmbiguousFlushStore.class).current().isPresent();
            if(tracker.idle(expected)&&measurement.activeCommands()==0&&!pending&&Phase15BenchmarkMain.executorIdle(executor)&&context.getBean(ServiceReadiness.class).isReady()){
                if(idle==0)idle=System.nanoTime();var capture=measurement.lastCapture();
                if(capture!=null&&capture.observedNanos()>=idle&&capture.records()==0&&capture.tail()==tail&&capture.checkpoint()==tail&&fixture.checkpoint()==tail){
                    drains.add(Map.of("serverRequests",tracker.snapshot(),"executor",executor,"capture",capture,"idleSince",idle,"ready",true,"pending",false));return;}
            }else idle=0;
            environment.budget(loaded.plan());Thread.sleep(25);
        }
        throw new IllegalStateException("Server read/write/fresh-capture drain incomplete");
    }
    private void diagnostics(BenchmarkEnvironment.Keys keys)throws Exception {
        var tracker=context.getBean(Phase18Activity.Tracker.class);String id=c.runId()+"/diagnostic/delayed-read";
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);tracker.delay(id,entered,release);expected.add(id);
        boolean timeout=false,rejected=false;
        try(var http=HttpClient.newBuilder().build()){
            var request=HttpRequest.newBuilder(URI.create(endpoint()+"/api/tiles/0/0/0")).header("X-Phase18-Request",id).timeout(Duration.ofMillis(300)).GET().build();
            var result=upstream.handoff(()->http.sendAsync(request,info->new Phase18TileBody.Subscriber(131072)));
            try{
                Phase18Plan.require(entered.await(5,TimeUnit.SECONDS),"delayed actual handler entered");
                try{result.get(5,TimeUnit.SECONDS);}catch(ExecutionException e){timeout=e.getCause() instanceof HttpTimeoutException;}
                Phase18Plan.require(timeout,"actual HTTP timeout");rejected=!tracker.idle(expected);Phase18Plan.require(rejected,"active server read must refuse drain");
            }finally{release.countDown();}
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!tracker.idle(expected)&&System.nanoTime()<until)Thread.sleep(5);
            Phase18Plan.require(tracker.idle(expected),"server handler actually finished");
            BenchmarkJson.write(output.resolve("read-timeout.json"),Map.of("httpStatus","timeout","serverDrainRefusedWhileActive",rejected,"serverFinishedAfterRelease",true,"originalTimeoutPreserved",timeout,"activity",tracker.snapshot()));
            for(String path:List.of("/api/tiles/1/0/0","/api/tiles/0/32/0")){
                String rid=c.runId()+"/diagnostic/invalid/"+expected.size();expected.add(rid);
                var response=upstream.handoff(()->http.sendAsync(HttpRequest.newBuilder(URI.create(endpoint()+path)).header("X-Phase18-Request",rid).timeout(Duration.ofSeconds(5)).GET().build(),info->new Phase18TileBody.Subscriber(8192))).get();
                Phase18Plan.require(response.statusCode()==400,"invalid tile HTTP contract");
            }
        }
    }
    private void cooldown(BenchmarkEnvironment.Keys keys)throws Exception {
        String id=c.runId()+"/diagnostic/cooldown";expected.add(id);
        String token=new ServiceJwtTokens(context.getBean(AuthProperties.class),Clock.systemUTC()).issueAccess(users.getFirst()).getTokenValue();
        try(var http=HttpClient.newBuilder().build()){
            var request=HttpRequest.newBuilder(URI.create(endpoint()+"/api/pixels")).header("X-Phase18-Request",id).header("Authorization","Bearer "+token)
                    .header("Origin","http://localhost:3000").header("Content-Type","application/json").timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"x\":0,\"y\":0,\"color\":17}")).build();
            var response=upstream.handoff(()->http.sendAsync(request,info->new Phase18TileBody.Subscriber(8192))).get();var json=Phase18Plan.JSON.readTree(response.body());
            Phase18Plan.require(response.statusCode()==429&&json.path("remainingMillis").isIntegralNumber()&&json.path("remainingMillis").longValue()>0&&json.path("remainingMillis").longValue()<=180000,"actual cooldown response");
            Phase18Plan.require(context.getBean(PixelMeasurement.class).walCounts().completedRecords()==1,"cooldown no new record");
            BenchmarkJson.write(output.resolve("cooldown.json"),Map.of("httpStatus",429,"remainingMillis",json.path("remainingMillis").longValue(),"walRecords",1));
        }
    }
    private void verifyRuntime()throws Exception {
        var p=loaded.plan();var props=context.getBean(WalProperties.class);var write=context.getBean(WriteExecutionProperties.class);var measure=context.getBean(PixelMeasurement.class);
        Phase18Plan.require(context.getBean(ServiceReadiness.class).isReady()&&context.getBeansOfType(PixelWriteExecutor.class).size()==1,"ready/executor count");
        Phase18Plan.require(props.getActiveFile().equals(Path.of(p.ownership().wal()))&&props.getMaxSegmentBytes()==c.runtime().segmentBytes(),"actual WAL settings");
        Phase18Plan.require(write.getMode().equals(c.runtime().mode())&&write.getGroup().getMaxBatchSize()==16&&write.getGroup().getMaxOutstanding()==128
                &&write.getGroup().getQueueTimeout().equals(Duration.ofSeconds(1))&&write.getGroup().getShutdownGrace().equals(Duration.ofSeconds(10)),"actual group settings");
        var executor=context.getBean(PixelWriteExecutor.class);Phase18Plan.require(executor.getClass()==(c.runtime().mode().equals("group")?GroupPixelWriteExecutor.class:SinglePixelWriteExecutor.class),"actual executor");
        Phase18Plan.require(measure.enabled()==c.runtime().measurement()&&context.getBean(dev.cgt.pixelplace.flush.scheduling.FlushScheduleProperties.class).fixedDelay().equals(Duration.ofSeconds(1)),"actual flush/measurement");
        Phase17CVerificationMain.requireSameField(executor,"measurement",measure);
        Phase17CVerificationMain.requireSameField(context.getBean(dev.cgt.pixelplace.tile.web.TileController.class),"measurement",measure);
        Phase18Plan.require(context.getBean(WalFileDurability.class) instanceof WindowsWalFileDurability,"production native adapter");
        Phase18Plan.require(Runtime.getRuntime().maxMemory()==2L*1024*1024*1024&&Path.of(System.getProperty("java.io.tmpdir")).equals(Path.of(p.ownership().temp()))
                &&Path.of(System.getProperty("jna.tmpdir")).equals(Path.of(p.ownership().jna())),"actual app heap/temp/JNA");
        var nativeVersion=com.sun.jna.Native.class.getDeclaredMethod("getNativeVersion");nativeVersion.setAccessible(true);
        // WAL은 첫 append 때 생성될 수 있음. 읽기 전용 case에서 파일을 만들지 않고 검증된 부모 volume을 기록
        BenchmarkJson.write(output.resolve("native.json"),Map.of("loadedVersion",nativeVersion.invoke(null),"jna",com.sun.jna.Native.VERSION,"filesystem",Files.getFileStore(Path.of(p.ownership().wal()).getParent()).type()));
        context.getBean(org.springframework.security.web.SecurityFilterChain.class);context.getBean(dev.cgt.pixelplace.pixel.websocket.PixelWebSocketHandler.class);
        BenchmarkJson.write(output.resolve("runtime.json"),Map.ofEntries(Map.entry("schemaVersion",1),Map.entry("planHash",loaded.planHash()),Map.entry("pid",ProcessHandle.current().pid()),
                Map.entry("startUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString()),Map.entry("java",System.getProperty("java.home")),Map.entry("javaVersion",System.getProperty("java.runtime.version")),Map.entry("user",System.getProperty("user.name")),Map.entry("os",System.getProperty("os.name")),
                Map.entry("maxHeap",Runtime.getRuntime().maxMemory()),Map.entry("temp",System.getProperty("java.io.tmpdir")),Map.entry("jnaTemp",System.getProperty("jna.tmpdir")),Map.entry("jnaVersion",com.sun.jna.Native.VERSION),Map.entry("nativeVersion",com.sun.jna.Native.VERSION_NATIVE),
                Map.entry("mode",write.getMode()),Map.entry("executor",executor.getClass().getName()),Map.entry("segmentBytes",props.getMaxSegmentBytes()),Map.entry("flushMillis",1000),Map.entry("measurement",measure.enabled()),Map.entry("catalog",p.ownership().catalog()),Map.entry("wal",p.ownership().wal()),Map.entry("redis",fixture.redisFacts),Map.entry("actualNative",true),Map.entry("authTtl",context.getBean(AuthProperties.class).accessTokenTtl().toString())));
    }
    private tools.jackson.databind.JsonNode recoverInput()throws Exception {
        Path file=output.getParent().resolve("initial/manifest.json");var m=BenchmarkJson.read(file);var runner=BenchmarkJson.read(output.getParent().resolve("initial/runner-result.json"));
        requireInitial(m,runner,loaded,BenchmarkJson.hash(file));
        Phase18Plan.require(m.path("walFiles").equals(Phase18Plan.JSON.valueToTree(Phase17CObservation.snapshot(Path.of(loaded.plan().ownership().wal())))),"initial WAL file hashes");
        Phase18Plan.require(m.path("rawHash").asText().equals(BenchmarkJson.hash(file.getParent().resolve("raw.jsonl")))
                &&m.path("runtimeReceiptHash").asText().equals(BenchmarkJson.hash(Path.of(System.getProperty("phase18.runtimeReceipt")))),"initial raw/runtime hash");
        return m;
    }
    static void requireInitial(tools.jackson.databind.JsonNode m,tools.jackson.databind.JsonNode r,Phase18Plan.Loaded loaded,String manifestHash) throws Exception {
        var p=loaded.plan();var c=p.cases().getFirst();
        Phase18Plan.require(r.path("exit").asInt(-1)==0&&r.path("success").asBoolean()&&r.path("manifestHash").asText().equals(manifestHash)
                &&m.path("complete").asBoolean()&&m.path("normalShutdown").asBoolean()&&m.path("initialAction").asText().equals("initial")
                &&m.path("planHash").asText().equals(loaded.planHash())&&m.path("catalog").asText().equals(p.ownership().catalog())
                &&m.path("inputHash").asText().equals(Phase18TileBody.hash(Phase18Plan.JSON.writeValueAsBytes(p.inputs())))
                &&m.path("wal").asText().equals(p.ownership().wal())&&m.path("mode").asText().equals(c.runtime().mode())
                &&m.path("segmentBytes").asLong()==c.runtime().segmentBytes()&&m.path("environmentIdentity").asText().equals(p.ownership().dbInstance()),"same initial recovery identity");
        var prior=ProcessHandle.of(m.path("pid").asLong());
        Phase18Plan.require(prior.isEmpty()||!prior.get().isAlive()||!prior.get().info().startInstant().orElseThrow().equals(Instant.parse(m.path("startUtc").asText())),"previous writer stopped");
    }
    private void close()throws Exception {
        Throwable failure=null;try{if(context!=null){
            var scheduler=context.getBean("flushTaskScheduler",org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor();
            var tracker=context.getBean(Phase18Activity.Tracker.class);
            bounded(()->{context.close();while(!scheduler.awaitTermination(1,TimeUnit.SECONDS)){/* 실제 producer 종료까지 소유 */}return true;},c.workload().drainMillis(),"context-close");
            var activity=tracker.snapshot();Phase18Plan.require(activity.values().stream().allMatch(s->s.completed()!=0),"closed server handlers");
            BenchmarkJson.write(output.resolve("server-closed.json"),Map.of("schedulerTerminated",scheduler.isTerminated(),"requests",activity));
        }}catch(Exception|Error e){failure=e;}
        try{if(fixture!=null)bounded(()->{fixture.close();return true;},c.workload().drainMillis(),"fixture-close");}catch(Exception|Error e){failure=Phase18Process.preserve(failure,e);}
        normalShutdown=failure==null&&Phase18Process.unresolved().isEmpty();Phase18Process.rethrow(failure);
    }
    private <T>T bounded(Callable<T> task,long millis,String name)throws Exception {
        return Phase17CVerificationMain.bounded(task,millis,TimeUnit.MILLISECONDS,name,()->BenchmarkJson.write(output.resolve(name+"-timeout.json"),Map.of("nextTaskAllowed",false,"deadlineMillis",millis,"pid",ProcessHandle.current().pid())));
    }
    private void save(Throwable failure)throws Exception {
        BenchmarkJson.write(output.resolve("drain.json"),drains);
        if(upstream.stopped())BenchmarkJson.write(output.resolve("upstream-stop.json"),upstream.report());
        failure=Phase18Process.preserve(failure,upstream.failure());
        if(failure!=null||!normalShutdown){BenchmarkJson.write(output.resolve("failure.json"),Map.of("kind",failure==null?"shutdown":failure.getClass().getName(),"normalShutdown",normalShutdown,"nextTaskAllowed",false,"failures",failure==null?List.of():Phase18ParentControl.failures(failure)));return;}
        BenchmarkJson.write(output.resolve("consistency.json"),consistency);var p=loaded.plan();
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion",1);m.put("complete",true);m.put("normalShutdown",true);m.put("initialAction",recovery?"recovery":"initial");m.put("planHash",loaded.planHash());m.put("inputHash",Phase18TileBody.hash(Phase18Plan.JSON.writeValueAsBytes(p.inputs())));
        m.put("runtimeReceiptHash",BenchmarkJson.hash(Path.of(System.getProperty("phase18.runtimeReceipt"))));m.put("environmentIdentity",p.ownership().dbInstance());m.put("catalog",p.ownership().catalog());m.put("wal",p.ownership().wal());m.put("mode",c.runtime().mode());m.put("segmentBytes",c.runtime().segmentBytes());m.put("walFiles",Phase17CObservation.snapshot(Path.of(p.ownership().wal())));
        m.put("users",users);m.put("pid",ProcessHandle.current().pid());m.put("startUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString());m.put("checkpoint",consistency.get("checkpoint"));m.put("rawHash",BenchmarkJson.hash(recovery?output.getParent().resolve("initial/raw.jsonl"):output.resolve("raw.jsonl")));m.put("drainHash",BenchmarkJson.hash(output.resolve("drain.json")));m.put("consistencyHash",BenchmarkJson.hash(output.resolve("consistency.json")));BenchmarkJson.write(output.resolve("manifest.json"),m);
    }
}
