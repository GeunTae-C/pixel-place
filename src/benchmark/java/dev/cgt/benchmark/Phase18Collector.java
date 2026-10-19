package dev.cgt.benchmark;

import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.pixel.application.PixelWriteExecutor;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 유한 주기 관측과 안전 STOP. 정상 capture 공백은 유한 age/fallback로 다루며 추가 flush/scan 금지 */
final class Phase18Collector implements AutoCloseable {
    record Input(long now, String phase, long heap, int outstanding, boolean running, boolean ready,
            PixelMeasurement.Capture capture, boolean pending, Map<String,Object> wal, long diskFree, long observerFailures, Long startedRecords) {
        Input(long now,String phase,long heap,int outstanding,boolean running,boolean ready,PixelMeasurement.Capture capture,
                boolean pending,Map<String,Object> wal,long diskFree,long observerFailures){this(now,phase,heap,outstanding,running,ready,capture,pending,wal,diskFree,observerFailures,null);}
    }
    record Decision(String captureStatus, Long ageNanos, long missingNanos, Long backlogAtCapture,
            Long backlogUpperBound, String fallback, String stop) { }
    /** 순수 안전 판정. seq gap은 건수로 사용하지 않으며 계획 전체 쓰기를 보수적인 상한으로만 사용 */
    static final class Safety {
        private final Phase18Plan plan; private final Phase18ObservationPolicy policy;
        private final long start; private long previous = Long.MIN_VALUE, missingSince = Long.MIN_VALUE, walMissingSince = Long.MIN_VALUE;
        private long priorStarted,capCapture=Long.MIN_VALUE,captureBaseline;
        private record CounterSample(long upperNanos,long started) { }
        private final ArrayDeque<CounterSample> counterHistory=new ArrayDeque<>();
        Safety(Phase18Plan plan, Phase18ObservationPolicy policy, long start) { this.plan=plan; this.policy=policy; this.start=start; }
        Decision evaluate(Input in) {
            var c=plan.cases().getFirst();var o=c.observation();
            String status=BenchmarkObserver.captureStatus(in.capture(),previous,in.pending());
            Long age=in.capture()==null?null:in.now()-in.capture().observedNanos();
            if(in.capture()!=null)previous=in.capture().observedNanos();
            boolean usable=!in.pending()&&age!=null&&age>=0&&age<=TimeUnit.MILLISECONDS.toNanos(o.maxAgeMillis());
            if(usable)missingSince=Long.MIN_VALUE;else if(missingSince==Long.MIN_VALUE)missingSince=in.now();
            long missing=missingSince==Long.MIN_VALUE?0:in.now()-missingSince;
            boolean metadata="observed".equals(in.wal().get("status"));
            if(metadata)walMissingSince=Long.MIN_VALUE;else if(walMissingSince==Long.MIN_VALUE)walMissingSince=in.now();
            long upper=plan.counts(c).writes();String stop="";boolean cap=Phase18Cap.enabled(plan);
            if(cap){
                Phase18Plan.require(in.startedRecords()!=null&&in.startedRecords()>=priorStarted,"CAP authoritative started-record counter");
                if(in.capture()!=null&&in.capture().observedNanos()!=capCapture){
                    capCapture=in.capture().observedNanos();captureBaseline=0;
                    // 직전 표본의 느린 I/O 동안 capture가 갱신될 수 있다. 시각이 확실히 앞선
                    // 가장 최근 카운터를 사용하며, 적격 표본이 없으면 기존 보수0을 유지한다.
                    for(var sample:counterHistory)if(sample.upperNanos()<=capCapture)captureBaseline=sample.started();
                }
                // 직전 표본은 capture보다 앞서야 함. 중간 신규 append와 아직 WAL 진입 전 outstanding도 포함
                upper=Math.addExact(in.capture()==null?0:in.capture().records(),Math.addExact(in.startedRecords()-captureBaseline,in.outstanding()));
                priorStarted=in.startedRecords();
                counterHistory.addLast(new CounterSample(in.now(),priorStarted));
                if(counterHistory.size()>64)counterHistory.removeFirst();
            }
            if(!in.running()||!in.ready())stop="runtime-not-running-ready";
            else if(in.observerFailures()!=0)stop="required-observer-failure";
            else if(in.outstanding()>policy.maxOutstanding())stop="outstanding-limit";
            // safety-heap은 실제 측정 구간에만 작은 heap 한도를 적용하는 명시 음성 fixture
            else if(in.heap()>policy.maxHeapBytes()&&(!policy.fixture().equals("safety-heap")||in.phase().equals("measurement")))stop="heap-limit";
            else if(in.diskFree()<plan.bounds().minimumFreeBytes())stop="disk-limit";
            else if(in.now()-start>=TimeUnit.SECONDS.toNanos(plan.bounds().totalSeconds()))stop="deadline";
            else if(in.capture()!=null&&!in.pending()&&in.capture().records()>policy.maxBacklogRecords())stop="backlog-limit";
            else if((cap&&upper>policy.maxBacklogRecords())||!usable&&((!cap&&upper>policy.maxBacklogRecords())||missing>TimeUnit.MILLISECONDS.toNanos(o.maxMissingMillis())
                    ||age!=null&&age>TimeUnit.MILLISECONDS.toNanos(o.maxAgeMillis())))stop="capture-safety-evidence-lost";
            else if(!metadata&&(!"NoSuchFileException".equals(in.wal().get("reason"))))stop="wal-collector-failure";
            else if(!metadata&&in.now()-walMissingSince>TimeUnit.MILLISECONDS.toNanos(o.maxMissingMillis()))stop="wal-metadata-missing-limit";
            else if(metadata&&((Number)in.wal().get("bytes")).longValue()>plan.bounds().walBytes())stop="wal-bytes-limit";
            return new Decision(status,age,missing,usable?(long)in.capture().records():null,upper,
                    cap?"CAP capture records + started since preceding sample + outstanding":usable?"capture is a point-in-time observation; planned writes bounds later arrivals":"planned-writes-upper-bound",stop);
        }
    }
    private final Phase18Plan.Loaded loaded; private final Phase18ObservationPolicy policy;
    private final Phase18Environment environment;
    private final PixelMeasurement measurement; private final PixelWriteExecutor executor;
    private final PendingAmbiguousFlushStore pending; private final ServiceReadiness readiness;
    private final MeterRegistry registry; private final Phase18Trace trace; private final Phase18ParentControl control;
    private final BenchmarkObserver.JvmResources resources=new BenchmarkObserver.JvmResources();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"phase18-collector");t.setDaemon(true);return t;});
    private final Safety safety; private final BufferedWriter writer; private final Path output,wal;
    private long samples,bytes,totalCost,maxCost; private Throwable failure; private boolean closed;
    private volatile boolean fixtureArmed;
    Phase18Collector(Phase18Plan.Loaded loaded, Phase18ObservationPolicy policy, Phase18Environment environment, ConfigurableApplicationContext context,
            Phase18Trace trace, Phase18ParentControl control, Path output) throws Exception {
        this.loaded=loaded;this.policy=policy;this.environment=environment;this.trace=trace;this.control=control;this.output=output;
        measurement=context.getBean(PixelMeasurement.class);executor=context.getBean(PixelWriteExecutor.class);
        pending=context.getBean(PendingAmbiguousFlushStore.class);readiness=context.getBean(ServiceReadiness.class);registry=context.getBean(MeterRegistry.class);
        wal=Path.of(loaded.plan().ownership().wal());safety=new Safety(loaded.plan(),policy,System.nanoTime());
        writer=Files.newBufferedWriter(output.resolve("resource-samples.jsonl"),StandardOpenOption.CREATE_NEW);
        scheduler.scheduleWithFixedDelay(this::sample,0,loaded.plan().cases().getFirst().observation().sampleMillis(),TimeUnit.MILLISECONDS);
    }
    // 64 KiB 분석기 line ceiling과 별개인 S2 writer 계약. 필드/수치/가변 map/개행의 UTF-8 상한
    static final long S2_SAMPLE_BYTES=6144;
    static Map<String,Object> maximumSample(Phase18Plan p){
        var row=numbers("nanoTime","pid","cpuNanos","heapUsed","heapMax","gcCount","gcMillis","gcCountDelta","gcMillisDelta","hostFreeMemory","hostTotalMemory");
        row.put("utc","9".repeat(40));row.put("cpuCoresUsed",-Double.MAX_VALUE);
        row.put("schemaVersion",1);row.put("planHash","f".repeat(64));row.put("clockId","9".repeat(64));
        row.put("phase","measurement");row.put("commandActive",Long.MIN_VALUE);
        var executor=numbers("queued","claimedUnsettled","workerInFlight","outstanding","unpublishedTerminal","activeCount");
        executor.put("state","STOPPING");executor.put("workerAlive",false);row.put("executor",executor);
        row.put("capture",numbers("observedNanos","checkpoint","tail","records"));row.put("pending",false);
        var safety=numbers("ageNanos","missingNanos","backlogAtCapture","backlogUpperBound");
        safety.put("captureStatus","not-observed");safety.put("fallback","capture is a point-in-time observation; planned writes bounds later arrivals");
        safety.put("stop","capture-safety-evidence-lost");row.put("safety",safety);
        var metadata=numbers("bytes","segments");metadata.put("status","observed");metadata.put("reason","NoSuchFileException");row.put("walMetadata",metadata);
        row.put("minimumDiskFreeBytes",Long.MIN_VALUE);row.put("walVolumeFreeBytes",Long.MIN_VALUE);
        row.put("walPath",Path.of(p.ownership().wal()).toString());
        var counts=numbers("singleCalls","batchCalls","batchRecords","completedRecords","recordForces","coveredRecords","emptyForces","failedForces","rotations");
        var batches=new LinkedHashMap<String,Object>();
        for(int i=1;i<=p.cases().getFirst().runtime().batchMax();i++)batches.put(Integer.toString(i),Long.MIN_VALUE);
        counts.put("batchSizes",batches);row.put("walCounts",counts);
        var cycle=numbers("startedNanos","previousStartedNanos","completedNanos");cycle.put("outcome","committed");row.put("lastCycle",cycle);
        var collectors=new LinkedHashMap<String,Object>();
        for(var gc:ManagementFactory.getGarbageCollectorMXBeans())collectors.put(gc.getName(),numbers("gcId","endJvmUptimeMillis","heapAfterBytes"));
        row.put("afterGc",Map.of("status","not-observed","collectors",collectors,"meaning","last completed GC per collector, not sample-time heap"));
        var io=numbers("readOperations","writeOperations","otherOperations","readBytes","writeBytes","otherBytes");
        io.put("status","observed");io.put("reason","x".repeat(128));io.put("scope","whole process; includes network and evidence I/O");row.put("processIo",io);
        row.put("collectionNanos",Long.MIN_VALUE);return row;
    }
    private static LinkedHashMap<String,Object> numbers(String... fields){
        var row=new LinkedHashMap<String,Object>();for(String field:fields)row.put(field,Long.MIN_VALUE);return row;
    }
    static long sampleByteUpperBound(Phase18Plan p){
        long bytes=Phase18Plan.JSON.writeValueAsBytes(maximumSample(p)).length+1L;
        Phase18Plan.require(bytes<=S2_SAMPLE_BYTES,"S2 serialized sample profile");return S2_SAMPLE_BYTES;
    }
    static void requireSampleCapacity(long samples,long bytes,long size,Phase18Plan p,Phase18ObservationPolicy policy){
        Phase18Plan.require(samples<p.bounds().samples()&&Math.addExact(bytes,size)<=policy.maxSampleBytes(),"collector output bound");
        if(System.getProperty("phase18.k6")!=null&&(Phase18K6Session.s2(p)||Phase18Cap1.enabled(p)))
            Phase18Plan.require(size<=S2_SAMPLE_BYTES,"S2 serialized sample profile");
    }
    void armFixture(){fixtureArmed=true;}
    private synchronized void sample() {
        if(failure!=null)return;long start=System.nanoTime();
        try {
            if(fixtureArmed&&policy.fixture().equals("collector-failure"))throw new IOException("Required collector fixture failure");
            control.check();
            var p=loaded.plan();var row=new LinkedHashMap<String,Object>(resources.sample());
            // 실행 중 DB/redo/binlog·증거·build 증가도 사전 총량에 대조. 한도 상실은 같은 STOP 경로로 전파
            environment.budget(p);
            var capture=measurement.lastCapture();var state=executor.snapshot();var capWal=measurement.walCounts();boolean hasPending=pending.current().isPresent();
            var metadata=walMetadata(wal,policy.maxWalFiles());
            long walFree=Phase18Paths.usable(wal);
            long free=Math.min(walFree,Math.min(Phase18Paths.usable(output),Phase18Paths.usable(Path.of(p.ownership().sessionRoot()))));
            var decision=safety.evaluate(new Input(System.nanoTime(),measurement.phase().name(),((Number)row.get("heapUsed")).longValue(),state.outstanding(),
                    state.accepting(),readiness.isReady(),capture,hasPending,metadata,free,measurement.observerFailures()+(trace.complete()?0:1),Phase18Cap.enabled(p)?Math.addExact(capWal.singleCalls(),capWal.batchRecords()):null));
            row.put("schemaVersion",1);row.put("planHash",loaded.planHash());row.put("clockId",Phase18Raw.clock());
            row.put("phase",measurement.phase().name());row.put("executor",state);row.put("commandActive",measurement.activeCommands());
            row.put("capture",capture);row.put("pending",hasPending);row.put("safety",decision);row.put("walMetadata",metadata);row.put("minimumDiskFreeBytes",free);
            row.put("walPath",wal.toString());row.put("walVolumeFreeBytes",walFree);
            row.put("walCounts",measurement.walCounts());row.put("lastCycle",measurement.lastCycle());row.put("afterGc",afterGc());
            row.put("processIo",Phase18ProcessIo.sample());row.put("collectionNanos",System.nanoTime()-start);
            String line=Phase18Plan.JSON.writeValueAsString(row)+"\n";long size=line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            requireSampleCapacity(samples,bytes,size,p,policy);
            writer.write(line);writer.flush();samples++;bytes+=size;
            if(!decision.stop().isEmpty())throw new IllegalStateException("Safety stop: "+decision.stop());
        } catch(Exception|Error problem) {
            failure=problem;control.stop("COLLECTOR_SAFETY",problem);
            if(problem instanceof Error e)throw e;
        } finally {long elapsed=System.nanoTime()-start;totalCost+=elapsed;maxCost=Math.max(maxCost,elapsed);}
    }
    static Map<String,Object> walMetadata(Path wal,int maximumFiles) throws IOException {
        long bytes=0;int files=0;
        try(var paths=Files.newDirectoryStream(wal.getParent())) {
            for(Path path:paths)if(path.getFileName().toString().matches("pixel-place\\.wal(?:\\.seg-[0-9]{19})?")){
                if(++files>maximumFiles)throw new IOException("WAL metadata file bound");bytes=Math.addExact(bytes,Files.size(path));
            }
            return Map.of("status","observed","bytes",bytes,"segments",files);
        } catch(NoSuchFileException race) {return Map.of("status","missing","reason","NoSuchFileException");}
    }
    static Map<String,Object> afterGc() {
        var result=new LinkedHashMap<String,Object>();var pools=new HashSet<String>();
        for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==java.lang.management.MemoryType.HEAP)pools.add(pool.getName());
        for(var gc:ManagementFactory.getGarbageCollectorMXBeans())if(gc instanceof com.sun.management.GarbageCollectorMXBean actual){
            var info=actual.getLastGcInfo();if(info!=null)result.put(gc.getName(),Map.of("gcId",info.getId(),"endJvmUptimeMillis",info.getEndTime(),
                    "heapAfterBytes",info.getMemoryUsageAfterGc().entrySet().stream().filter(e->pools.contains(e.getKey())).mapToLong(e->e.getValue().getUsed()).sum()));
        }
        return Map.of("status",result.isEmpty()?"not-observed":"observed","collectors",result,"meaning","last completed GC per collector, not sample-time heap");
    }
    @Override public void close() throws Exception {
        scheduler.shutdown();
        Phase17CVerificationMain.bounded(()->{
            while(!scheduler.awaitTermination(1,TimeUnit.SECONDS)){/* 기한 초과 뒤에도 실제 수집/파일 소유 유지 */}
            synchronized(this){writer.close();closed=true;}return true;
        },loaded.plan().cases().getFirst().workload().drainMillis(),TimeUnit.MILLISECONDS,"collector-close",
                ()->BenchmarkJson.write(output.resolve("collector-close-timeout.json"),Map.of("nextTaskAllowed",false)));
    }
    void write() throws Exception {
        Phase18Plan.require(closed,"collector closed before summary");var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",1);result.put("planHash",loaded.planHash());result.put("complete",failure==null&&trace.complete());
        result.put("failure",failure==null?null:failure.getClass().getSimpleName());result.put("samples",samples);result.put("bytes",bytes);
        result.put("samplesHash",BenchmarkJson.hash(output.resolve("resource-samples.jsonl")));
        result.put("collectorTotalNanos",totalCost);result.put("collectorMaxNanos",maxCost);result.put("enabled",measurement.enabled());
        result.put("timers",measurement.enabled()?BenchmarkObserver.timers(registry):null);result.put("walCounts",measurement.walCounts());
        result.put("broadcastFailures",measurement.enabled()?measurement.broadcastFailures():null);
        result.put("observerFailures",measurement.observerFailures());result.put("disabledReason",measurement.enabled()?null:"measurement off: timers, cycle, broadcast failures, batch distribution");
        result.put("timerMeaning","count/total cumulative; windowMaxMillis rolling 60s x 3; request and batch scopes overlap; never sum p95");
        result.put("clockMeaning","nanoTime durations within this clockId only; UTC/control envelopes for cross-JVM alignment, no exact durable-to-WS/commit duration");
        result.put("missingAuxiliary",Map.of("durableToCommit","not sampled; checkpoint observation is only an upper bound", "sqlLockCommit","transaction total only; nested timers overlap", "hostDisk","separate host-disk.jsonl and devices.json; never attributed to WAL alone"));
        BenchmarkJson.write(output.resolve("collector.json"),result);
    }
}
