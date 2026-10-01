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
            PixelMeasurement.Capture capture, boolean pending, Map<String,Object> wal, long diskFree, long observerFailures) { }
    record Decision(String captureStatus, Long ageNanos, long missingNanos, Long backlogAtCapture,
            Long backlogUpperBound, String fallback, String stop) { }
    /** 순수 안전 판정. seq gap은 건수로 사용하지 않으며 계획 전체 쓰기를 보수적인 상한으로만 사용 */
    static final class Safety {
        private final Phase18Plan plan; private final Phase18ObservationPolicy policy;
        private final long start; private long previous = Long.MIN_VALUE, missingSince = Long.MIN_VALUE, walMissingSince = Long.MIN_VALUE;
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
            long upper=plan.counts(c).writes();String stop="";
            if(!in.running()||!in.ready())stop="runtime-not-running-ready";
            else if(in.observerFailures()!=0)stop="required-observer-failure";
            else if(in.outstanding()>policy.maxOutstanding())stop="outstanding-limit";
            // safety-heap은 실제 측정 구간에만 작은 heap 한도를 적용하는 명시 음성 fixture
            else if(in.heap()>policy.maxHeapBytes()&&(!policy.fixture().equals("safety-heap")||in.phase().equals("measurement")))stop="heap-limit";
            else if(in.diskFree()<plan.bounds().minimumFreeBytes())stop="disk-limit";
            else if(in.now()-start>=TimeUnit.SECONDS.toNanos(plan.bounds().totalSeconds()))stop="deadline";
            else if(in.capture()!=null&&!in.pending()&&in.capture().records()>policy.maxBacklogRecords())stop="backlog-limit";
            else if(!usable&&(upper>policy.maxBacklogRecords()||missing>TimeUnit.MILLISECONDS.toNanos(o.maxMissingMillis())
                    ||age!=null&&age>TimeUnit.MILLISECONDS.toNanos(o.maxAgeMillis())))stop="capture-safety-evidence-lost";
            else if(!metadata&&(!"NoSuchFileException".equals(in.wal().get("reason"))))stop="wal-collector-failure";
            else if(!metadata&&in.now()-walMissingSince>TimeUnit.MILLISECONDS.toNanos(o.maxMissingMillis()))stop="wal-metadata-missing-limit";
            else if(metadata&&((Number)in.wal().get("bytes")).longValue()>plan.bounds().walBytes())stop="wal-bytes-limit";
            return new Decision(status,age,missing,usable?(long)in.capture().records():null,upper,
                    usable?"capture is a point-in-time observation; planned writes bounds later arrivals":"planned-writes-upper-bound",stop);
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
    void armFixture(){fixtureArmed=true;}
    private synchronized void sample() {
        if(failure!=null)return;long start=System.nanoTime();
        try {
            if(fixtureArmed&&policy.fixture().equals("collector-failure"))throw new IOException("Required collector fixture failure");
            control.check();
            var p=loaded.plan();var row=new LinkedHashMap<String,Object>(resources.sample());
            // 실행 중 DB/redo/binlog·증거·build 증가도 사전 총량에 대조. 한도 상실은 같은 STOP 경로로 전파
            environment.budget(p);
            var state=executor.snapshot();var capture=measurement.lastCapture();boolean hasPending=pending.current().isPresent();
            var metadata=walMetadata(wal,policy.maxWalFiles());
            long free=Math.min(Phase18Paths.usable(output),Phase18Paths.usable(Path.of(p.ownership().sessionRoot())));
            var decision=safety.evaluate(new Input(System.nanoTime(),measurement.phase().name(),((Number)row.get("heapUsed")).longValue(),state.outstanding(),
                    state.accepting(),readiness.isReady(),capture,hasPending,metadata,free,measurement.observerFailures()+(trace.complete()?0:1)));
            row.put("schemaVersion",1);row.put("planHash",loaded.planHash());row.put("clockId",Phase18Raw.clock());
            row.put("phase",measurement.phase().name());row.put("executor",state);row.put("commandActive",measurement.activeCommands());
            row.put("capture",capture);row.put("pending",hasPending);row.put("safety",decision);row.put("walMetadata",metadata);row.put("minimumDiskFreeBytes",free);
            row.put("walCounts",measurement.walCounts());row.put("lastCycle",measurement.lastCycle());row.put("afterGc",afterGc());
            row.put("processIo",Phase18ProcessIo.sample());row.put("collectionNanos",System.nanoTime()-start);
            String line=Phase18Plan.JSON.writeValueAsString(row)+"\n";long size=line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            Phase18Plan.require(samples<p.bounds().samples()&&bytes+size<=policy.maxSampleBytes(),"collector output bound");
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
