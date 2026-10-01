package dev.cgt.benchmark;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 동결 단일 trial의 순차 initial/recovery runner. 다른 case/단계 자동 실행 없음 */
final class Phase18Transport {
    private Phase18Transport() { }
    static void requireFixture(Phase18Plan plan) {
        Phase18Execution.requireService(plan);
    }
    static void run(Phase18Plan.Loaded loaded,Path planFile,Path environmentFile)throws Exception {
        run(loaded,planFile,environmentFile,loaded.plan().bounds().totalSeconds()*1000,child->{});
    }
    /** 제한 음성 fixture는 최초 누적 기한만 단축. 실행 중 기한 재시작·연장 불가 */
    static void run(Phase18Plan.Loaded loaded,Path planFile,Path environmentFile,long executionMillis,Phase18Process.Body observation)throws Exception {
        var p=loaded.plan();requireFixture(p);Phase18Environment.read(environmentFile,p);
        if(Phase18Execution.observed(p))Phase18ObservationPolicy.read(Phase18Paths.checked(System.getProperty("phase18.observationPolicy","")),loaded);
        Phase18Plan.require(executionMillis>0&&executionMillis<=p.bounds().totalSeconds()*1000,"fixture may only shorten cumulative deadline");
        var execution=Phase18Deadline.start(p,executionMillis);
        long started=System.nanoTime(),deadline=execution.deadlineNanos();
        var root=Path.of(p.ownership().evidenceRoot());var run=root.resolve(p.cases().getFirst().runId());
        Phase18Plan.require(!Files.exists(run)&&!Files.exists(Path.of(p.ownership().wal()).getParent()),"fresh fixture output/WAL");
        Path lockPath=root.resolve("a2.lock");
        try(var lock=FileChannel.open(lockPath,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            try{
                Files.createDirectory(run);
                BenchmarkJson.write(run.resolve("runner-budget.json"),Map.of("executionMillis",executionMillis,"startedNanos",started,"deadlineNanos",deadline,"executionDeadlineEpochMillis",execution.expiresEpochMillis(),"pid",ProcessHandle.current().pid()));
                for(String action:List.of("initial","recovery")){
                    var cmd=command(p,run.resolve(action+"-process"),run.resolve(action+".args"),"2g","dev.cgt.benchmark.Phase18BenchmarkMain",
                            List.of(action,planFile.toString(),environmentFile.toString()),execution);
                    var receipt=Phase18Process.run(withinDeadline(cmd,deadline,System.nanoTime()),observation);
                    var manifest=run.resolve(action+"/manifest.json");var content=BenchmarkJson.read(manifest);
                    Phase18Plan.require(content.path("complete").asBoolean()&&content.path("normalShutdown").asBoolean(),"app success receipt");
                    BenchmarkJson.write(run.resolve(action+"/runner-result.json"),Map.of("success",true,"exit",receipt.exit(),"manifestHash",BenchmarkJson.hash(manifest),"pid",receipt.pid(),"startUtc",receipt.startUtc()));
                }
                execution.check();
                Phase18Plan.require(System.nanoTime()<deadline&&BenchmarkPaths.bytes(run)<=p.bounds().outputBytes(),"transport cumulative time/output budget");
                BenchmarkJson.write(run.resolve("transport.json"),Map.of("schemaVersion",1,"planHash",loaded.planHash(),"initialAndNewJvmRecovery",true,"officialPilot",p.stage().equals("A-4"),"elapsedNanos",System.nanoTime()-started,"deadlineSeconds",p.bounds().totalSeconds()));
            }finally{if(Phase18Process.unresolved().isEmpty()){lock.close();Files.delete(lockPath);}}
        }
    }
    /** initial 기한을 recovery에서 새로 시작하지 않음. 기한 초과 뒤 실제 child 소유는 기존 Process가 유지 */
    static Phase18Process.Command withinDeadline(Phase18Process.Command command,long deadline,long now) {
        long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.subtractExact(deadline,now));
        Phase18Plan.require(remaining>0,"transport cumulative deadline before child");
        return new Phase18Process.Command(command.arguments(),command.cwd(),command.evidence(),Math.min(remaining,command.observationMillis()),command.cleanupMillis(),command.logLimit(),command.execution());
    }
    static Phase18Process.Command command(Phase18Plan p,Path processEvidence,Path argumentFile,String heap,String main,List<String> arguments)throws Exception {
        return command(p,processEvidence,argumentFile,heap,main,arguments,Phase18Deadline.start(p,Math.multiplyExact(p.bounds().totalSeconds(),1000)));
    }
    static Phase18Process.Command command(Phase18Plan p,Path processEvidence,Path argumentFile,String heap,String main,List<String> arguments,Phase18Deadline execution)throws Exception {
        execution.check();
        var values=new ArrayList<>(List.of("-D"+Phase18Deadline.PROPERTY+"="+execution.expiresEpochMillis(),"-Xmx"+heap,"-XX:+UseG1GC","-Djava.io.tmpdir="+p.ownership().temp(),"-Djna.tmpdir="+p.ownership().jna(),
                "-Djdk.httpclient.disableRetryConnect=true","-Djdk.httpclient.enableAllMethodRetry=false","-Dphase18.runtimeReceipt="+System.getProperty("phase18.runtimeReceipt"),
                "-cp",System.getProperty("java.class.path"),main));values.addAll(arguments);
        if(Phase18Execution.observed(p))values.addFirst("-Dphase18.observationPolicy="+System.getProperty("phase18.observationPolicy"));
        for(String value:values)Phase18Plan.require(!value.contains("\n")&&!value.contains("\r"),"argument line");
        var text=new StringBuilder();for(String value:values)text.append('"').append(value.replace("\\","\\\\").replace("\"","\\\"")).append("\"\n");
        Files.writeString(argumentFile,text.toString(),StandardOpenOption.CREATE_NEW);
        return new Phase18Process.Command(List.of(p.ownership().java(),"@"+argumentFile),Path.of(p.ownership().repo()),processEvidence,
                p.bounds().totalSeconds()*1000,p.cases().getFirst().workload().drainMillis(),p.bounds().logBytesPerStream(),execution);
    }
}
