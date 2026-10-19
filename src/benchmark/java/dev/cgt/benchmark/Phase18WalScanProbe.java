package dev.cgt.benchmark;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import dev.cgt.pixelplace.wal.infra.SegmentedWalStorage;
import dev.cgt.pixelplace.wal.infra.WalProperties;
import dev.cgt.pixelplace.wal.infra.WindowsWalFileDurability;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.cgt.benchmark.Phase18Plan.require;

/** 보호 WAL과 분리한 유한 fixture의 전체 파싱량·할당량·직렬화 대기 진단. 서버 성능 판정 책임 없음 */
public final class Phase18WalScanProbe {
    private static long expiry;
    private static final SimpleMeterRegistry REGISTRY = new SimpleMeterRegistry();
    private static final PixelMeasurement MEASUREMENT = new PixelMeasurement(false,REGISTRY);
    private static final WalRecordJsonCodec CODEC = new WalRecordJsonCodec(Phase18Plan.JSON);

    /** 동일 production scan 경로의 누적 크기/checkpoint 영향과 실제 monitor/boundary 소유 관계 확인 */
    public static void main(String[] args) throws Exception {
        try { run(args); } finally { REGISTRY.close(); }
    }
    private static void run(String[] args) throws Exception {
        require(args.length == 2,"fixture output and original expiry");
        Path root=Path.of(args[0]); Phase18Paths.execution(root.toString());
        require(root.toString().replace('\\','/').startsWith("F:/pixel-place-work/sessions/cap1-wal-causal-"),
                "dedicated finite fixture namespace");
        expiry=Long.parseLong(args[1]); check(); Files.createDirectory(root);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(),"JDK thread allocation support");
        bean.setThreadAllocatedMemoryEnabled(true);
        var cases=new ArrayList<Map<String,Object>>();
        for(int size:new int[]{15000,60000,129150}) {
            Path file=root.resolve("wal-"+size); fixture(file,size);
            var parser=new CountingParser();
            try(var storage=storage(file,parser)) {
                for(long checkpoint:new long[]{0,size-100,size}) {
                    for(int i=0;i<3;i++) storage.readAfter(checkpoint);
                    var samples=new ArrayList<Map<String,Object>>();
                    for(int i=0;i<5;i++) {
                        check(); long parses=parser.calls;
                        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                        long start=System.nanoTime(); var batch=storage.readAfter(checkpoint); long elapsed=System.nanoTime()-start;
                        long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
                        require(batch.walLastEventSeq()==size && batch.records().size()==size-checkpoint,"fixture exact result");
                        samples.add(Map.of("elapsedNanos",elapsed,"allocatedBytes",bytes,"parseCalls",parser.calls-parses,
                                "returnedRecords",batch.records().size(),"tail",batch.walLastEventSeq()));
                    }
                    cases.add(Map.of("records",size,"fileBytes",Files.size(file),"checkpoint",checkpoint,"samples",samples));
                }
            }
        }
        var contention=new ArrayList<Map<String,Object>>();
        contention.add(contention(root,false)); contention.add(contention(root,true));
        BenchmarkJson.write(root.resolve("wal-scan-probe.json"),Map.of(
                "syntheticOnly",true,"protectedWalRead",false,"cases",cases,"contention",contention,
                "maximumRecords",129150,"maximumFiles",5,"warmupsPerCase",3,"samplesPerCase",5,
                "originalExpiry",expiry,"pid",ProcessHandle.current().pid()));
    }

    private static Map<String,Object> contention(Path root,boolean boundary) throws Exception {
        check(); Path file=root.resolve(boundary?"boundary-wal":"monitor-wal"); fixture(file,1000);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var parser=new CountingParser();parser.entered=entered;parser.release=release;
        var error=new AtomicReference<Throwable>();var finished=new AtomicBoolean();var duration=new AtomicLong();
        var coordinator=new FlushBoundaryCoordinator(MEASUREMENT);
        try(var storage=storage(file,parser)) {
            var scan=new Thread(()->{
                try { if(boundary)coordinator.capture(()->storage.readAfter(1000));else storage.readAfter(1000); }
                catch(Throwable e){error.compareAndSet(null,e);}
            },"finite-wal-scan");
            var append=new Thread(()->{
                long start=System.nanoTime();
                try {
                    if(boundary)coordinator.coordinate(()->{storage.appendAndFsync(record(1001));return null;});
                    else storage.appendAndFsync(record(1001));
                    finished.set(true);
                }catch(Throwable e){error.compareAndSet(null,e);}
                finally{duration.set(System.nanoTime()-start);}
            },"finite-wal-append");
            boolean blocked=false;String state="",lock="";
            try {
                scan.start();require(entered.await(5,TimeUnit.SECONDS),"scan entered production parser");
                append.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<end) {
                    var info=ManagementFactory.getThreadMXBean().getThreadInfo(append.threadId());
                    if(info!=null && info.getLockOwnerId()==scan.threadId()) {
                        blocked=true;state=info.getThreadState().name();lock=info.getLockName();break;
                    }
                    Thread.sleep(5);
                }
                require(blocked && !finished.get(),"append waits on actual scan owner");
            } finally {
                release.countDown();scan.join(10000);if(append.getState()!=Thread.State.NEW)append.join(10000);
                require(!scan.isAlive() && !append.isAlive(),"finite probe threads joined");
            }
            require(error.get()==null && finished.get(),"probe scan and durable append complete");
            require(storage.readAfter(1000).records().size()==1,"probe append effect");
            return Map.of("boundary",boundary,"actualOwnerObserved",blocked,"state",state,"lock",lock,
                    "appendElapsedNanos",duration.get(),"threadsJoined",true,
                    "interpretation","controlled lock causality, not natural service latency");
        }
    }
    private static SegmentedWalStorage storage(Path file,WalRecordParser parser) {
        var p=new WalProperties();p.setActiveFile(file);p.setMaxSegmentBytes(32L*1024*1024);
        return new SegmentedWalStorage(p,parser,CODEC,MEASUREMENT,new WindowsWalFileDurability());
    }
    private static void fixture(Path file,int count) throws Exception {
        try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)) {
            for(int i=1;i<=count;i++){if(i%1000==0)check();out.write(CODEC.serializeLine(record(i)));}
        }
        require(Files.size(file)<32L*1024*1024,"bounded synthetic WAL");
    }
    private static WalRecord record(long seq) {
        return new WalRecord(seq,7,0,0,0,0,0,17,LocalDateTime.of(2026,10,15,0,0,0,123456789));
    }
    private static void check(){require(System.currentTimeMillis()<expiry-180000,"frozen probe expiry and cleanup reserve");}
    private static final class CountingParser extends WalRecordParser {
        long calls;CountDownLatch entered,release;
        CountingParser(){super(Phase18Plan.JSON);}
        @Override public WalRecord parseLine(String line,long number) {
            calls++;
            if(entered!=null) {
                var first=entered;entered=null;first.countDown();
                try {require(release.await(10,TimeUnit.SECONDS),"bounded diagnostic latch");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }
            return super.parseLine(line,number);
        }
    }
}
