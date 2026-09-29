package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.measurement.*;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.*;
import dev.cgt.pixelplace.wal.infra.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.cgt.benchmark.BenchmarkResults.*;

/** C plan의 한 action만 실행하는 JavaExec. 성공 manifest와 실제 process exit는 runner가 함께 판정 */
public final class Phase17CVerificationMain {
    private final Phase17CPlan plan; private final Phase17CTrace trace; private final Phase17CObservation observation;
    private BenchmarkFixtures fixture; private volatile ConfigurableApplicationContext context;
    private Process child; private BenchmarkProtocol protocol; private Thread stderr; private boolean childDiagnosticsComplete = true;
    private List<Long> users = List.of(); private final List<Map<String, Object>> drains = new ArrayList<>();
    private final BenchmarkSpec.Case load = new BenchmarkSpec.Case("probe", 8, 0, "spread-tiles");
    private Map<String, String> inputs; private Map<String, String> classes; private JsonNode initial;
    private Map<String, Object> consistency = Map.of(); private boolean normalShutdown;
    private Phase17CVerificationMain(Phase17CPlan plan) {
        this.plan = plan; trace = new Phase17CTrace(100000, 16L * 1024 * 1024); observation = new Phase17CObservation(trace, plan.wal());
    }
    public static void main(String[] args) throws Exception {
        // guard 완료 전 output 생성·DB/WAL 변경·Spring 시작 없음
        var plan = Phase17CPlan.read(args); var run = new Phase17CVerificationMain(plan);
        Files.createDirectory(plan.output());
        Throwable failure = lifecycle(run::execute, run::close, run::save);
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("C action failed; evidence=" + plan.output(), failure);
    }
    @FunctionalInterface interface Step { void run() throws Exception; }
    @FunctionalInterface interface Evidence { void save(Throwable failure) throws Exception; }

    /** 실제 Main과 작은 fixture가 공유하는 종료 소유권 경계. 실패 뒤에도 close/진단 수행, Error 우선순위 보존 */
    static Throwable lifecycle(Step execute, Step close, Evidence save) {
        Throwable failure = null;
        try { execute.run(); } catch (Throwable problem) { failure = problem; }
        try { close.run(); } catch (Throwable secondary) { failure = Phase15BenchmarkMain.preserve(failure, secondary); }
        try { save.save(failure); } catch (Throwable secondary) { failure = Phase15BenchmarkMain.preserve(failure, secondary); }
        return failure;
    }

    /** initial은 recoverInput의 소유/정상 종료/입력 대조를 통과한 manifest만 사용 */
    static Phase17CAnalyzer.StartupExpectation startupExpectation(String action, JsonNode initial) {
        return switch (action) {
            case "smoke" -> new Phase17CAnalyzer.StartupExpectation("initializeAllWhite", 0, 0, false);
            case "recover-drained" -> {
                if (initial == null || !initial.path("checkpoint").isIntegralNumber() || !initial.path("checkpoint").canConvertToLong())
                    throw new IllegalStateException("Validated initial checkpoint required");
                yield new Phase17CAnalyzer.StartupExpectation("loadAll", 0, initial.path("checkpoint").longValue(), true);
            }
            case "recover-unflushed" -> new Phase17CAnalyzer.StartupExpectation("loadAll", 2, 5, true);
            case "prepare-unflushed" -> null;
            default -> throw new IllegalArgumentException("Unknown startup action");
        };
    }
    private void execute() throws Exception {
        inputs = inputHashes(Phase17CPlan.absolute(plan.json().path("sourceRoot").asText())); classes = classHashes();
        BenchmarkJson.write(plan.output().resolve("inputs.json"), inputs); BenchmarkJson.write(plan.output().resolve("classes.json"), classes);
        BenchmarkJson.write(plan.output().resolve("runtime.json"), runtime());
        if (plan.recovery()) initial = recoverInput();
        var credentials = BenchmarkEnvironment.credentials();
        if (!credentials.dbHost().equals(plan.json().path("mysqlHost").asText()) || credentials.dbPort() != 3307
                || !credentials.redisHost().equals(plan.json().path("redisHost").asText()) || credentials.redisPort() != plan.json().path("redisPort").asInt()
                || credentials.redisDatabase() != plan.json().path("redisDatabase").asInt()) throw new IllegalArgumentException("Injected target differs from plan");
        fixture = new BenchmarkFixtures(credentials, plan.catalog(), true, Phase17CPlan.absolute(plan.json().path("mysqlRoot").asText()));
        Phase17CFixtures.live(fixture, plan);
        if (plan.recovery()) {
            var list = new ArrayList<Long>(); for (var id : initial.path("users")) list.add(id.longValue()); users = List.copyOf(list);
            fixture.fixtureFiles();
        } else users = bounded(() -> Phase17CFixtures.prepare(fixture, plan), plan.seconds("databasePrepare"), "fixture-prepare");
        if (plan.action().equals("prepare-unflushed")) { prepareWal(); return; }
        var keys = BenchmarkEnvironment.keys();
        var properties = BenchmarkEnvironment.properties(credentials, plan.catalog(), plan.wal(), keys, true);
        properties.put("pixel-place.wal.max-segment-bytes", plan.caseJson().path("segmentBytes").asLong());
        properties.put("pixel-place.write.mode", plan.mode());
        properties.put("pixel-place.write.group.max-batch-size", 16); properties.put("pixel-place.write.group.max-outstanding", 128);
        properties.put("pixel-place.write.group.queue-timeout", "1s"); properties.put("pixel-place.write.group.shutdown-grace", "10s");
        var app = BenchmarkEnvironment.application(properties);
        // startup runner/첫 scheduled flush보다 앞선 BPP 설치. 일반 Config Data와 component scan에는 등록하지 않음
        app.addInitializers(observation::install);
        bounded(() -> { context = app.run(); return true; }, plan.seconds("ready"), "application-ready");
        verifyRuntime();
        if (plan.action().equals("smoke")) smoke(keys);
        else if (plan.action().equals("recover-drained")) {
            var attempts = new ArrayList<Attempt>(); for (var a : initial.path("attempts")) attempts.add(BenchmarkJson.MAPPER.treeToValue(a, Attempt.class));
            drain(0, initial.path("checkpoint").asLong());
            consistency = BenchmarkConsistency.verify(fixture, context, users, attempts, load, 17003, true); complete(consistency);
            if (context.getBean(EventSeqManager.class).currentLastIssued() != initial.path("checkpoint").asLong())
                throw new IllegalStateException("Recovered sequence seed differs from drained WAL tail");
            requireNoAppend();
        } else {
            drain(0, 5);
            consistency = Phase17CFixtures.unflushedState(fixture, users, context.getBean(InMemoryTileBoard.class), true);
            requireNoAppend();
            if (context.getBean(EventSeqManager.class).currentLastIssued() != 5 || trace.count("markDirty", "enter") != 0
                    || trace.events().stream().noneMatch(e -> e.operation().equals("drainDirtyTiles") && e.phase().equals("return") && e.result().equals("0"))
                    || trace.events().stream().noneMatch(e -> e.operation().equals("flushOnce") && e.phase().equals("return") && e.result().equals("COMMITTED") && e.thread().contains("flush")))
                throw new IllegalStateException("Dirty-zero automatic scheduler commit evidence missing");
        }
        if (!observation.preparation().get("bytesPreserved").equals(true)) throw new IllegalStateException("Preparation changed WAL bytes");
    }
    private void prepareWal() throws Exception {
        var records = Phase17CFixtures.records(users);
        var props = new WalProperties(); props.setActiveFile(plan.wal()); props.setMaxSegmentBytes(1);
        var measurement = new PixelMeasurement(true, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        // 실제 parser/codec/Windows storage만 사용하며 Spring/scheduler를 생성하지 않음
        try (var storage = new Phase17CObservedStorage(props, new WalRecordParser(BenchmarkJson.MAPPER), new WalRecordJsonCodec(BenchmarkJson.MAPPER), measurement, new Phase17CWindowsObservation(trace), trace)) {
            for (var record : records) storage.appendAndFsync(record);
            var batch = storage.readAfter(0);
            if (!batch.records().equals(records) || batch.walLastEventSeq() != 5) throw new IllegalStateException("Prepared WAL fixture mismatch");
        }
        if (Phase17CObservation.snapshot(plan.wal()).size() != 2) throw new IllegalStateException("Fixture requires exactly two closed writer files");
        consistency = Phase17CFixtures.unflushedState(fixture, users, null, false);
        BenchmarkJson.write(plan.output().resolve("fixture-definition.json"), Map.of("definition", "Phase17CFixtures.records", "definitionSourceHash", inputs.get("src/benchmark/java/dev/cgt/benchmark/Phase17CFixtures.java"), "seq", List.of(2, 5), "tail", 5, "writerClosed", true));
    }
    private final List<Attempt> attempts = new ArrayList<>();
    private void smoke(BenchmarkEnvironment.Keys keys) throws Exception {
        var codec = context.getBean(WalRecordJsonCodec.class); long bytes = 0;
        for (int i = 0; i < 65; i++) {
            int[] pixel = pixel(load, 17003, i);
            bytes += codec.serializeLine(new dev.cgt.pixelplace.wal.domain.WalRecord(65, users.get(i), 0, pixel[0]/256, pixel[1]/256, pixel[0], pixel[1], pixel[2], LocalDateTime.of(2026,9,29,0,0,0,999999999))).length;
        }
        if (bytes <= 1024 || bytes > 1048576) throw new IllegalStateException("Codec smoke size cannot meet rotation budget");
        BenchmarkJson.write(plan.output().resolve("codec-budget.json"), Map.of("records",65,"lineTotalUpperBound",bytes,"segmentBytes",1024));
        int[] pixel = pixel(load, 17003, 0);
        var token = new ServiceJwtTokens(context.getBean(AuthProperties.class), Clock.systemUTC()).issueAccess(users.getFirst()).getTokenValue();
        var request = HttpRequest.newBuilder(URI.create(endpoint()+"/api/pixels")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token)
                .header("Origin","http://localhost:3000").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"x\":"+pixel[0]+",\"y\":"+pixel[1]+",\"color\":"+pixel[2]+"}")).build();
        long sent = System.nanoTime();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
            attempts.add(Phase15LoadClient.response(0,new BenchmarkUsers.Assignment(0,1),"prepare",sent,sent,client.send(request,HttpResponse.BodyHandlers.ofString()),null,pixel));
        }
        requireAccepted(attempts,1); drain(1, attempts.getFirst().eventSeq());
        var spec = spec(); startChild(keys);
        protocol.send("START", Map.of("spec",spec,"caseIndex",0,"endpoint",endpoint(),"fixtureUsers",users,"evidence",plan.output().toString()));
        protocol.receive("READY",120); protocol.send("WARMUP",Map.of()); var warm = protocol.receive("WARMUP_DONE",200);
        if (warm.path("sent").asInt()!=32 || warm.path("unknown").asInt()!=0) throw new IllegalStateException("First fixed smoke stage incomplete");
        drain(33,warm.path("latestAcceptedSeq").asLong());
        protocol.send("MEASURE",Map.of()); protocol.receive("SEND_DONE",30); protocol.receive("DONE",180);
        if (!child.waitFor(120,TimeUnit.SECONDS)) { BenchmarkJson.write(plan.output().resolve("child-timeout.json"), childIdentity()); child.waitFor(); throw new IllegalStateException("Child observation deadline exceeded"); }
        BenchmarkJson.write(plan.output().resolve("child-exit.json"),Map.of("pid",child.pid(),"exit",child.exitValue(),"normal",child.exitValue()==0));
        if (child.exitValue()!=0) throw new IllegalStateException("Client JVM failed");
        attempts.addAll(BenchmarkConsistency.readAttempts(plan.output().resolve("client-results.jsonl"),64)); requireAccepted(attempts,65);
        drain(65,attempts.stream().mapToLong(Attempt::eventSeq).max().orElseThrow());
        consistency=BenchmarkConsistency.verify(fixture,context,users,attempts,load,17003,true); complete(consistency);
        var counts=context.getBean(PixelMeasurement.class).walCounts(); Phase17CAnalyzer.coverage(counts,65,plan.mode());
        if (counts.rotations()==0 || trace.count("file.delete","return")==0) throw new IllegalStateException("Actual rotation/retention absent");
        BenchmarkJson.write(plan.output().resolve("coverage.json"),counts);
    }
    private void drain(long expected, long tail) throws Exception {
        var measurement=context.getBean(PixelMeasurement.class); var activity=context.getBean(BenchmarkServletConfiguration.Activity.class);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(plan.seconds("drain")),idle=0;
        while(System.nanoTime()<until) {
            var executor=context.getBean(PixelWriteExecutor.class).snapshot();
            boolean pending=context.getBean(PendingAmbiguousFlushStore.class).current().isPresent();
            if(Phase15BenchmarkMain.serverIdle(activity.active.get(),activity.completed.get(),activity.started.get(),expected,measurement.activeCommands(),pending) && Phase15BenchmarkMain.executorIdle(executor)) {
                if(idle==0)idle=System.nanoTime(); var capture=measurement.lastCapture(); long cp=fixture.checkpoint();
                boolean noOp=capture!=null&&trace.events().stream().anyMatch(e->e.operation().equals("flushOnce")&&e.phase().equals("return")&&e.result().equals("NO_OP")&&e.nanos()>=capture.observedNanos());
                if(capture!=null&&capture.observedNanos()>=idle&&capture.records()==0&&capture.tail()==cp&&capture.checkpoint()==cp&&cp==tail&&noOp&&context.getBean(ServiceReadiness.class).isReady()) {
                    drains.add(Map.of("expectedServlets",expected,"started",activity.started.get(),"completed",activity.completed.get(),"active",activity.active.get(),"executor",executor,"pending",false,"capture",capture,"idleSince",idle,"checkpoint",cp,"ready",true));
                    plan.budget(); return;
                }
            } else idle=0;
            Thread.sleep(25);
        }
        throw new IllegalStateException("Normal drain evidence incomplete");
    }
    private void verifyRuntime() throws Exception {
        if(!context.getBean(ServiceReadiness.class).isReady() || context.getBeansOfType(PixelWriteExecutor.class).size()!=1) throw new IllegalStateException("Runtime READY/executor mismatch");
        var executor=context.getBean(PixelWriteExecutor.class);
        Class<?> expected=plan.mode().equals("single")?SinglePixelWriteExecutor.class:GroupPixelWriteExecutor.class;
        if(executor.getClass()!=expected || !context.getBean(dev.cgt.pixelplace.flush.scheduling.FlushScheduleProperties.class).fixedDelay().equals(Duration.ofSeconds(1)))
            throw new IllegalStateException("Actual executor or automatic scheduler differs from C contract");
        var measurement=context.getBean(PixelMeasurement.class);
        if(!measurement.enabled() || context.getBeansOfType(PixelMeasurement.class).size()!=1)throw new IllegalStateException("Shared enabled measurement required");
        for(Object bean:List.of(executor,observation.original("segmentedWalStorage"),observation.original("flushWorker"),context.getBean(StartupRecoveryService.class),context.getBean(PixelWriteService.class),context.getBean(FlushPlanCaptureService.class)))
            requireSameField(bean,"measurement",measurement);
        var p=context.getBean(WriteExecutionProperties.class);p.validate();
        if(!p.getMode().equals(plan.mode())||p.getGroup().getMaxBatchSize()!=16||p.getGroup().getMaxOutstanding()!=128
                ||!p.getGroup().getQueueTimeout().equals(Duration.ofSeconds(1))||!p.getGroup().getShutdownGrace().equals(Duration.ofSeconds(10)))throw new IllegalStateException("Write configuration mismatch");
        if(!(context.getBean(WalStoragePreparation.class) instanceof FileWalStoragePreparation)||!(context.getBean(WalFileDurability.class) instanceof Phase17CWindowsObservation))throw new IllegalStateException("Production preparation/durability required");
        Object storage=context.getBean(SegmentedWalStorage.class);
        if(context.getBean(WalSegmentRetention.class)!=storage)throw new IllegalStateException("Retention storage identity");
        for(String name:List.of("fileWalAppender","fileWalReplaySource","fileWalStoragePreparation")) {
            Object bean=observation.original(name);if(bean==null)bean=context.getBean(name);
            var field=org.springframework.aop.support.AopUtils.getTargetClass(bean).getDeclaredField("storage");field.setAccessible(true);
            if(field.get(bean)!=storage)throw new IllegalStateException("Shared storage mismatch");
        }
        context.getBean("flushTaskScheduler");context.getBean(org.springframework.security.web.SecurityFilterChain.class);
        BenchmarkJson.write(plan.output().resolve("beans.json"),Map.of("productionDurability",true,"sharedStorage",true,"sharedMeasurement",true,"preparation",context.getBean(WalStoragePreparation.class).getClass().getName(),"executor",executor.getClass().getName(),"mode",p.getMode(),"flushDelay",context.getEnvironment().getProperty("pixel-place.flush.fixed-delay"),"walFilesystem",Files.getFileStore(plan.wal().getParent()).type(),"provider",plan.wal().getFileSystem().provider().getClass().getName()));
    }
    /** 읽기 전용 identity 검사. proxy target에서 업무 메서드를 호출하지 않음 */
    static void requireSameField(Object bean,String name,Object expected)throws Exception {
        if(bean instanceof org.springframework.aop.framework.Advised advised) {
            var source=advised.getTargetSource();Object target=source.getTarget();
            try{requireSameField(target,name,expected);}finally{source.releaseTarget(target);}return;
        }
        for(Class<?> type=bean.getClass();type!=null;type=type.getSuperclass()) {
            try{var field=type.getDeclaredField(name);field.setAccessible(true);if(field.get(bean)!=expected)throw new IllegalStateException("Shared runtime identity mismatch: "+name);return;}
            catch(NoSuchFieldException absent){ /* 관측 subclass 아래 production 선언까지 확인 */ }
        }
        throw new IllegalStateException("Runtime identity field absent: "+name);
    }
    private void requireNoAppend() {
        var counts=context.getBean(PixelMeasurement.class).walCounts();
        if(counts.completedRecords()!=0||counts.recordForces()!=0||trace.count("appendAndFsync","enter")!=0||trace.count("appendBatchAndFsync","enter")!=0)throw new IllegalStateException("Recovery appended records");
    }
    private JsonNode recoverInput() throws Exception {
        Path file=Phase17CPlan.absolute(System.getProperty("phase17c.initial",""));Phase17CPlan.owned(file,Phase17CPlan.absolute(plan.json().path("outputRoot").asText()).resolve("app"));
        var manifest=BenchmarkJson.read(file);var result=BenchmarkJson.read(file.getParent().resolve("runner-result.json"));
        if(!result.path("success").asBoolean()||result.path("exit").asInt(-1)!=0||!result.path("manifestHash").asText().equals(BenchmarkJson.hash(file))
                ||!manifest.path("normalShutdown").asBoolean()||!manifest.path("case").asText().equals(plan.caseName())||!manifest.path("catalog").asText().equals(plan.catalog())
                ||!manifest.path("mode").asText().equals(plan.mode())||!manifest.path("planHash").asText().equals(BenchmarkJson.hash(plan.file()))
                ||!manifest.path("action").asText().equals(plan.action().equals("recover-drained")?"smoke":"prepare-unflushed"))throw new IllegalStateException("Initial ownership/normal exit mismatch");
        Phase17CPlan.exact(manifest.path("wal").asText(),plan.wal().toString());
        if(!BenchmarkJson.MAPPER.valueToTree(inputs).equals(manifest.path("inputs"))||!BenchmarkJson.MAPPER.valueToTree(classes).equals(manifest.path("classes"))
                ||!BenchmarkJson.MAPPER.valueToTree(Phase17CObservation.snapshot(plan.wal())).equals(manifest.path("walFiles")))throw new IllegalStateException("Recovery input/byte correspondence changed");
        long prior=manifest.path("pid").asLong();
        if(prior==ProcessHandle.current().pid()||ProcessHandle.of(prior).filter(ProcessHandle::isAlive).isPresent())throw new IllegalStateException("Prior writer JVM still present");
        return manifest;
    }
    private void startChild(BenchmarkEnvironment.Keys keys) throws Exception {
        Path temp=Phase17CPlan.absolute(plan.json().path("outputRoot").asText()).resolve("temp").resolve(plan.output().getFileName()+"-child");Files.createDirectory(temp);
        Path args=plan.output().resolve("child.args");
        String cp=System.getProperty("java.class.path");
        Files.writeString(args,"-Xmx512m\n-XX:+UseG1GC\n\"-Djava.io.tmpdir="+temp.toString().replace('\\','/')+"\"\n\"-Djna.tmpdir="+temp.toString().replace('\\','/')+"\"\n-Djdk.httpclient.disableRetryConnect=true\n-Djdk.httpclient.enableAllMethodRetry=false\n-cp\n\""+cp.replace("\\","\\\\").replace("\"","\\\"")+"\"\ndev.cgt.benchmark.Phase17CLoadClient\n"+plan.output().getFileName()+"\n",StandardOpenOption.CREATE_NEW);
        var builder=new ProcessBuilder(plan.json().path("java").asText(),"@"+args);var old=new HashMap<>(builder.environment());builder.environment().clear();
        for(String key:List.of("SystemRoot","WINDIR"))if(old.containsKey(key))builder.environment().put(key,old.get(key));
        builder.environment().put("TEMP",temp.toString());builder.environment().put("TMP",temp.toString());builder.environment().put("PIXEL_PLACE_BENCH_JWT_KEY",keys.jwt());builder.environment().put("PIXEL_PLACE_BENCH_COOKIE_KEY",keys.cookie());
        child=builder.start();BenchmarkJson.write(plan.output().resolve("child-runtime.json"),childIdentity());
        protocol=new BenchmarkProtocol(child.getInputStream(),child.getOutputStream(),plan.output().getFileName().toString());
        stderr=Thread.ofPlatform().name("phase17c-child-diagnostics").start(()->{
            try(var in=child.errorReader();var out=Files.newBufferedWriter(plan.output().resolve("child-stderr.log"),StandardOpenOption.CREATE_NEW)) {
                int count=0,next;while((next=in.read())!=-1){if(++count<=16384)out.write(next=='\n'?'\n':'.');else childDiagnosticsComplete=false;}
            }catch(Exception failure){childDiagnosticsComplete=false;}
        });
    }
    private Map<String,Object> childIdentity(){return Map.of("pid",child.pid(),"start",child.info().startInstant().orElseThrow().toString(),"java",plan.json().path("java").asText(),"args",plan.output().resolve("child.args").toString(),"alive",child.isAlive());}
    private String endpoint(){return "http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();}
    private BenchmarkSpec spec(){return new BenchmarkSpec("exploratory",null,null,null,null,List.of(load),4,4,1,4,15,180,120,120,65,65,8,2L*1024*1024*1024,"once",null,1,17003,1000);}
    private static void requireAccepted(List<Attempt> list,int count){if(list.size()!=count||list.stream().anyMatch(a->a.outcome()!=Outcome.accepted)||list.stream().map(Attempt::requestId).distinct().count()!=count)throw new IllegalStateException("Fixed smoke terminal results incomplete");}
    private static void complete(Map<String,Object> result){if(!Boolean.TRUE.equals(result.get("complete")))throw new IllegalStateException("Consistency failure");}
    private <T>T bounded(Callable<T> task,int seconds,String name)throws Exception {
        return bounded(task, seconds, TimeUnit.SECONDS, name, () ->
                BenchmarkJson.write(plan.output().resolve(name+"-timeout.json"),Map.of("incomplete",true,"seconds",seconds,"ownedThread",name)));
    }
    /** 관측 기한 이후 실제 소유 작업이 완료되어도 timeout 실패 유지. I/O 취소·강제 종료 없음 */
    static <T>T bounded(Callable<T> task,long timeout,TimeUnit unit,String name,Step timedOut)throws Exception {
        var future=new FutureTask<T>(task);Thread.ofPlatform().name("phase17c-"+name).start(future);
        try{return future.get(timeout,unit);}catch(TimeoutException deadline){
            Throwable failure=deadline;
            try{timedOut.run();}catch(Throwable evidenceFailure){failure=Phase15BenchmarkMain.preserve(failure,evidenceFailure);}
            // timeout은 실패로 고정하되 실제 I/O 소유 thread의 정상 반환까지 유지
            try{future.get();}catch(ExecutionException later){failure=Phase15BenchmarkMain.preserve(failure,later.getCause());}
            if(failure instanceof Error error)throw error;throw (Exception)failure;
        }catch(ExecutionException failure){if(failure.getCause() instanceof Error error)throw error;throw new IllegalStateException(name+" failed",failure.getCause());}
    }
    private void close()throws Exception {
        Throwable failure=null;
        try{if(protocol!=null)protocol.close();if(child!=null&&child.isAlive()){if(!child.waitFor(120,TimeUnit.SECONDS)){BenchmarkJson.write(plan.output().resolve("cleanup-child-timeout.json"),childIdentity());child.waitFor();throw new IllegalStateException("Child late normal exit");}}}catch(Throwable p){failure=p;}
        try{if(protocol!=null)protocol.awaitReaderEnd(120);}catch(Throwable p){failure=Phase15BenchmarkMain.preserve(failure,p);}
        try{if(stderr!=null){stderr.join(120000);if(stderr.isAlive())throw new IllegalStateException("Child stream still active");}if(!childDiagnosticsComplete)throw new IllegalStateException("Child evidence incomplete");}catch(Throwable p){failure=Phase15BenchmarkMain.preserve(failure,p);}
        try{if(context!=null){
            var scheduler=context.getBean("flushTaskScheduler",org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor();
            bounded(()->{context.close();awaitTermination(scheduler,120,TimeUnit.SECONDS);return true;},120,"context-close");
        }}catch(Throwable p){failure=Phase15BenchmarkMain.preserve(failure,p);}
        try{if(fixture!=null){if((context==null||!context.isActive())&&(child==null||!child.isAlive()))fixture.cleanupOwnedKeys(users);fixture.close();}}catch(Throwable p){failure=Phase15BenchmarkMain.preserve(failure,p);}
        normalShutdown=failure==null;
        if(failure instanceof Error e)throw e;if(failure!=null)throw new IllegalStateException("C normal resource shutdown incomplete",failure);
    }
    /** context.close 반환만으로 마지막 flush callback 완료를 가정하지 않음. 기존 scheduler 종료 정책에는 개입 없음 */
    static void awaitTermination(ExecutorService producer,long timeout,TimeUnit unit)throws Exception {
        if(!producer.awaitTermination(timeout,unit))throw new IllegalStateException("Observation producer still active after close");
    }
    private void save(Throwable failure)throws Exception {
        saveAfterClose(failure,normalShutdown,this::saveDiagnostics,()->{
            validateTrace(trace,plan.action(),initial,observation.preparationFiles(),plan.wal());
        },this::saveManifest);
    }
    /** 종료 이후에만 사용하는 전체 판정. writer 분기는 startup만 제외하고 물리/close 완결성 유지 */
    static void validateTrace(Phase17CTrace trace,String action,JsonNode initial,Map<String,String> files,Path wal) {
        Phase17CAnalyzer.physical(trace);
        var expected=startupExpectation(action,initial);
        if(expected!=null){Phase17CAnalyzer.trace(trace,expected);Phase17CAnalyzer.preparation(trace,files,wal);}
    }
    /** 종료 실패의 진단은 보존하되 최종 판정·성공 manifest로 진입하지 않는 실제 save 경로 */
    static void saveAfterClose(Throwable failure,boolean normalShutdown,Evidence diagnostics,Step validate,Step publish)throws Exception {
        if(failure!=null){diagnostics.save(failure);return;}
        try{
            if(!normalShutdown)throw new IllegalStateException("Normal resource shutdown not confirmed");
            validate.run();
        }catch(Exception|Error problem){
            try{diagnostics.save(problem);}catch(Throwable secondary){
                var primary=Phase15BenchmarkMain.preserve(problem,secondary);
                if(primary instanceof Error error)throw error;throw (Exception)primary;
            }
            throw problem;
        }
        // CREATE_NEW 진단은 최종 판정 결과를 포함해 한 번만 저장. 저장 실패 뒤 성공 게시 없음
        diagnostics.save(null);publish.run();
    }
    private void saveDiagnostics(Throwable failure)throws Exception {
        BenchmarkJson.write(plan.output().resolve("trace.json"),trace.report());BenchmarkJson.write(plan.output().resolve("preparation.json"),observation.preparation());
        BenchmarkJson.write(plan.output().resolve("drain.json"),drains);BenchmarkJson.write(plan.output().resolve("consistency.json"),consistency);
        if(failure!=null)BenchmarkJson.write(plan.output().resolve("failure.json"),Map.of("complete",false,"kind",failure.getClass().getName(),"normalShutdown",normalShutdown));
    }
    private void saveManifest()throws Exception {
        plan.budget();if(!trace.complete()||!inputs.equals(inputHashes(Phase17CPlan.absolute(plan.json().path("sourceRoot").asText())))||!classes.equals(classHashes()))throw new IllegalStateException("Evidence/input changed");
        var m=new LinkedHashMap<String,Object>();m.put("complete",true);m.put("normalShutdown",normalShutdown);m.put("runId",plan.json().path("runId").asText());m.put("planHash",BenchmarkJson.hash(plan.file()));m.put("case",plan.caseName());m.put("action",plan.action());m.put("mode",plan.mode());m.put("catalog",plan.catalog());m.put("wal",plan.wal().toString());m.put("walFiles",Phase17CObservation.snapshot(plan.wal()));m.put("users",users);m.put("attempts",attempts);m.put("pid",ProcessHandle.current().pid());m.put("start",ProcessHandle.current().info().startInstant().orElseThrow().toString());m.put("checkpoint",consistency.get("checkpoint"));m.put("inputs",inputs);m.put("classes",classes);m.put("redisDatabase",plan.json().path("redisDatabase").asInt());
        BenchmarkJson.write(plan.output().resolve("manifest.json"),m);
    }
    private Map<String,Object> runtime()throws Exception{return Map.of("pid",ProcessHandle.current().pid(),"start",ProcessHandle.current().info().startInstant().orElseThrow().toString(),"java",Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"jdk",System.getProperty("java.runtime.version"),"javaHash",BenchmarkJson.hash(Path.of(System.getProperty("java.home"),"bin/java.exe")),"classpath",System.getProperty("java.class.path"),"arguments",ManagementFactory.getRuntimeMXBean().getInputArguments(),"temp",System.getProperty("java.io.tmpdir"),"jnaTemp",System.getProperty("jna.tmpdir"));}
    static Map<String,String> inputHashes(Path source)throws Exception {
        var result=new TreeMap<String,String>();for(String root:List.of("src","scripts"))try(var paths=Files.walk(source.resolve(root))){for(Path p:paths.filter(Files::isRegularFile).filter(p->!p.toString().endsWith(".zip")&&!p.toString().endsWith(".pyc")).sorted().toList())result.put(source.relativize(p).toString().replace('\\','/'),BenchmarkJson.hash(p));}
        for(String p:List.of("build.gradle","settings.gradle","pixel_place.sql"))result.put(p,BenchmarkJson.hash(source.resolve(p)));return result;
    }
    static Map<String,String> classHashes()throws Exception {
        var result=new TreeMap<String,String>();for(String entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator)){
            Path p=Path.of(entry);if(Files.isDirectory(p)){try(var paths=Files.walk(p)){for(Path f:paths.filter(Files::isRegularFile).sorted().toList())result.put(f.toAbsolutePath().toString(),BenchmarkJson.hash(f));}}
            else if(Files.isRegularFile(p))result.put(p.toAbsolutePath().toString(),BenchmarkJson.hash(p));else throw new IllegalStateException("Missing classpath entry");
        }return result;
    }
}
