package dev.cgt.benchmark;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 별도 발생기 JVM의 CPU/heap/GC/전체 I/O 표본. 고정 보유량·출력 상한과 필수 수집 실패 전파 */
final class Phase18JvmSampler implements AutoCloseable {
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"phase18-client-sampler");t.setDaemon(true);return t;});
    private final BenchmarkObserver.JvmResources resources=new BenchmarkObserver.JvmResources();
    private final BufferedWriter writer;private final Phase18Plan.Loaded loaded;private final Consumer<Throwable> stop;private final Path output;
    private long count,bytes;private Throwable failure;
    Phase18JvmSampler(Phase18Plan.Loaded loaded,Path output,Consumer<Throwable> stop)throws Exception{
        this.loaded=loaded;this.stop=stop;this.output=output;writer=Files.newBufferedWriter(output.resolve("client-resources.jsonl"),StandardOpenOption.CREATE_NEW);
        executor.scheduleWithFixedDelay(this::sample,0,loaded.plan().cases().getFirst().observation().sampleMillis(),TimeUnit.MILLISECONDS);
    }
    private synchronized void sample(){
        if(failure!=null)return;
        try{
            var row=new LinkedHashMap<String,Object>(resources.sample());row.put("schemaVersion",1);row.put("clockId",Phase18Raw.clock());row.put("planHash",loaded.planHash());
            row.put("afterGc",Phase18Collector.afterGc());row.put("processIo",Phase18ProcessIo.sample());
            Phase18Plan.require(((Number)row.get("heapUsed")).longValue()<=loaded.plan().bounds().memoryBytes(),"client heap limit");
            String line=Phase18Plan.JSON.writeValueAsString(row)+"\n";long size=line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            Phase18Plan.require(count<loaded.plan().bounds().samples()&&bytes+size<=loaded.plan().bounds().outputBytes()/4,"client samples bound");
            writer.write(line);writer.flush();count++;bytes+=size;
        }catch(Exception|Error problem){failure=problem;stop.accept(problem);if(problem instanceof Error e)throw e;}
    }
    @Override public void close()throws Exception{
        executor.shutdown();Phase17CVerificationMain.bounded(()->{
            while(!executor.awaitTermination(1,TimeUnit.SECONDS)){/* 실제 자원 소유 종료를 기다림 */}
            synchronized(this){writer.close();}return true;
        },loaded.plan().cases().getFirst().workload().drainMillis(),TimeUnit.MILLISECONDS,"client-sampler-close",
                ()->BenchmarkJson.write(output.resolve("client-sampler-close-timeout.json"),Map.of("nextTaskAllowed",false)));
        BenchmarkJson.write(output.resolve("client-collector.json"),Map.of("schemaVersion",1,"planHash",loaded.planHash(),"complete",failure==null,
                "samples",count,"bytes",bytes,"samplesHash",BenchmarkJson.hash(output.resolve("client-resources.jsonl"))));
        Phase18Process.rethrow(failure);
    }
}
