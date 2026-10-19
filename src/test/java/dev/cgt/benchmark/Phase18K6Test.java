package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 공식 고정 k6·실제 로컬 HTTP/WS의 작은 전달 검증. 운영 서비스와 저장소 사용 없음 */
class Phase18K6Test {
    @Test void windowsK6ScriptOutputAndDescendantFailuresBlockProgress()throws Exception{
        String executable=System.getenv("PHASE18_TEST_K6");assertNotNull(executable);
        Path root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"k6-process-"+UUID.randomUUID()).toAbsolutePath();
        var builder=new ProcessBuilder("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-File",Path.of("scripts/test-phase18-k6-process.ps1").toAbsolutePath().toString(),"-Output",root.toString(),"-K6",executable);
        builder.redirectOutput(root.resolveSibling(root.getFileName()+".stdout.log").toFile());builder.redirectError(root.resolveSibling(root.getFileName()+".stderr.log").toFile());
        var process=builder.start();assertTrue(process.waitFor(50,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),root.toString());
        assertTrue(BenchmarkJson.read(root.resolve("results.json")).path("Complete").asBoolean());
    }
    static Phase18Plan small(boolean scheduled)throws Exception{
        var j=(ObjectNode)Phase18Plan.JSON.valueToTree(Phase18PlanTest.valid());
        ((ObjectNode)j.path("bounds")).put("outputBytes",134217728);
        var c=(ObjectNode)j.path("cases").get(0);var w=(ObjectNode)c.path("workload");
        w.put("writeRate",scheduled?0:2);w.put("readRate",scheduled?0:2);w.put("wsConnections",2);w.put("warmupSeconds",1);w.put("measurementSeconds",1);
        w.put("bootstrap",false);w.put("requestMillis",1000);w.put("readyMillis",10000);w.put("drainMillis",3000);w.put("userPool",scheduled?2:0);w.put("userMode",scheduled?"reuse":"once");
        if(scheduled)w.set("schedule",Phase18Plan.JSON.valueToTree(new Phase18Cap.Schedule("users",2,200,60,0,0)));
        return Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);
    }
    @Test void realK6FixedArrivalAndThresholdNonzeroPreservePayloadsAndExit()throws Exception{
        run(false,false);run(false,true);
    }
    @Test void realK6UserScheduleRequiresWsAndExactInitialReadSet()throws Exception{
        run(true,false);
    }
    @Test void appStopUsesBridgeAndPreservesFailureAfterRealK6Terminates()throws Exception{
        String executable=System.getenv("PHASE18_TEST_K6");assertNotNull(executable);
        var p=small(false);var keys=BenchmarkEnvironment.keys();
        var tokens=ServiceJwtTokens.benchmarkAccess(new AuthProperties(keys.jwt(),keys.cookie(),Duration.ofMinutes(15)),Clock.systemUTC());
        Path root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"k6-stop-"+UUID.randomUUID());Files.createDirectory(root);
        var users=java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList();
        var stopped=new java.util.concurrent.atomic.AtomicBoolean();
        try(var server=new Phase18K6FixtureServer(tokens)){
            try(var session=new Phase18K6Session(new Phase18Plan.Loaded(p,"0".repeat(64)),users,server.endpoint(),keys.jwt(),tokens,System.currentTimeMillis()+60000,root,
                new Phase18K6Session.App(){public void phase(String name){}public void drain(Set<String> ids,long tail){}public void check(){if(stopped.get())throw new IllegalStateException("fixture_app_stop");}},Clock.systemUTC())){
                var observer=Thread.ofPlatform().start(()->{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(server.observed.isEmpty()&&System.nanoTime()<end){try{Thread.sleep(10);}catch(InterruptedException e){return;}}stopped.set(true);});
                var args=List.of("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-File",p.ownership().repo()+"/scripts/run-phase18-k6.ps1","-InputPath",root.resolve("k6-input.json").toString(),"-K6",executable);
                try{
                    var failure=assertThrows(Exception.class,()->session.runProcess(new Phase18Process.Command(args,Path.of(p.ownership().repo()),root.resolve("process"),60000,10000,1048576),keys));
                    assertTrue(failure.getMessage().contains("fixture_app_stop")||failure.getMessage().contains("app_control_stop"),failure.toString());
                }finally{observer.interrupt();observer.join(1000);}
                assertTrue(stopped.get());assertNotNull(session.failure());assertTrue(Phase18Process.unresolved().isEmpty());
            }
            var runner=BenchmarkJson.read(root.resolve("k6-runner.json"));assertFalse(runner.path("Complete").asBoolean());
            for(var item:runner.path("Processes"))assertTrue(item.path("Released").asBoolean());
            assertFalse(BenchmarkJson.read(root.resolve("k6-producer.json")).path("producerComplete").asBoolean());
            assertFalse(Files.exists(root.resolve("k6-measurement-exit.json")),"STOP prevents the next phase");
        }
    }
    @Test void appStopAbortPreservesUnresolvedEvidenceForInFlightRequests()throws Exception {
        String executable=System.getenv("PHASE18_TEST_K6");assertNotNull(executable);
        var tree=(ObjectNode)Phase18Plan.JSON.valueToTree(small(false));
        var w=(ObjectNode)tree.path("cases").get(0).path("workload");
        w.put("writeRate",20);w.put("readRate",0);w.put("warmupSeconds",3);
        w.put("writeInFlight",20);w.put("requestMillis",3000);w.put("drainMillis",5000);
        var p=Phase18Plan.JSON.treeToValue(tree,Phase18Plan.class);var keys=BenchmarkEnvironment.keys();
        var tokens=ServiceJwtTokens.benchmarkAccess(new AuthProperties(keys.jwt(),keys.cookie(),Duration.ofMinutes(15)),Clock.systemUTC());
        Path root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"k6-stop-inflight-"+UUID.randomUUID());Files.createDirectory(root);
        var users=java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList();
        var four=new java.util.concurrent.CountDownLatch(4);
        var stopped=new java.util.concurrent.CountDownLatch(1);
        var holder=new java.util.concurrent.atomic.AtomicReference<Phase18K6Session>();
        var replyAttempts=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.LongConsumer delay=seq->{
            four.countDown();
            try {
                if(seq==1){assertTrue(four.await(5,TimeUnit.SECONDS));holder.get().stop("app_control_stop");stopped.countDown();}
                else {assertTrue(stopped.await(5,TimeUnit.SECONDS));Thread.sleep(750);}
                replyAttempts.incrementAndGet();
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        };
        try(var server=new Phase18K6FixtureServer(tokens,false,false,delay)){
            try(var session=new Phase18K6Session(new Phase18Plan.Loaded(p,"0".repeat(64)),users,server.endpoint(),keys.jwt(),tokens,System.currentTimeMillis()+60000,root,
                    new Phase18K6Session.App(){public void check(){}public void phase(String name){}public void drain(Set<String> ids,long tail){}},Clock.systemUTC())){
                holder.set(session);
                var builder=new ProcessBuilder("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-File",p.ownership().repo()+"/scripts/run-phase18-k6.ps1","-InputPath",root.resolve("k6-input.json").toString(),"-K6",executable);
                builder.environment().put("PIXEL_PLACE_BENCH_JWT_KEY",keys.jwt());
                builder.redirectOutput(root.resolve("wrapper.stdout.log").toFile());builder.redirectError(root.resolve("wrapper.stderr.log").toFile());
                var process=builder.start();assertTrue(process.waitFor(65,TimeUnit.SECONDS),"finite STOP wrapper");
                assertNotEquals(0,process.exitValue(),"original STOP remains nonzero");
                assertEquals("app_control_stop",session.failure());
            }
            var producer=BenchmarkJson.read(root.resolve("k6-producer.json"));
            var runner=BenchmarkJson.read(root.resolve("k6-runner.json"));
            for(var item:runner.path("Processes"))assertTrue(item.path("Released").asBoolean());
            BenchmarkJson.write(root.resolve("stop-inflight-observation.json"),Map.of("targetEffects",server.observed.size(),
                    "replyAttempts",replyAttempts.get(),"unresolved",producer.path("unresolved").size(),
                    "firstFailure",producer.path("firstFailure").asText(),"producerComplete",producer.path("producerComplete").asBoolean(),
                    "nextPhaseIssued",Files.exists(root.resolve("k6-measurement-exit.json"))));
            assertTrue(server.observed.size()>=4,"at least four issued effects before STOP");
            assertFalse(producer.path("producerComplete").asBoolean());
            assertFalse(Files.exists(root.resolve("k6-measurement-exit.json")));
            // 원인 미분류 app STOP은 안전 중단으로 유지. 과거 응답을 생성하거나 전역 유예로 숨기지 않음
            long accepted;
            try(var lines=Files.lines(root.resolve("k6-records.jsonl"))){
                accepted=lines.map(Phase18Plan.JSON::readTree).filter(r->r.path("type").asText().equals("http")&&r.path("outcome").asText().equals("ACCEPTED")).count();
            }
            assertTrue(producer.path("unresolved").size()>0,"global abort interruption reproduced");
            assertEquals(server.observed.size(),accepted+producer.path("unresolved").size(),"all issued effects explained in fixture");
        }finally{stopped.countDown();}
    }
    private void run(boolean scheduled,boolean threshold)throws Exception{
        String executable=System.getProperty("phase18.test.k6",System.getenv("PHASE18_TEST_K6"));
        assertNotNull(executable,"Explicit pinned k6 executable required; no success skip");assertTrue(Files.isRegularFile(Path.of(executable)));
        var p=small(scheduled);var keys=BenchmarkEnvironment.keys();var auth=new AuthProperties(keys.jwt(),keys.cookie(),Duration.ofMinutes(15));
        var tokens=ServiceJwtTokens.benchmarkAccess(auth,Clock.systemUTC());
        var root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"k6-fixture-"+UUID.randomUUID());Files.createDirectory(root);
        var users=java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList();var phases=new ArrayList<String>();
        try(var server=new Phase18K6FixtureServer(tokens)){
            try(var session=new Phase18K6Session(new Phase18Plan.Loaded(p,"0".repeat(64)),users,server.endpoint(),keys.jwt(),tokens,System.currentTimeMillis()+60000,root,
                new Phase18K6Session.App(){public void check(){}public void phase(String name){phases.add(name);}public void drain(Set<String> ids,long tail){assertTrue(server.observed.stream().map(v->v.get("id")).toList().containsAll(ids));}},Clock.systemUTC())){
                var builder=new ProcessBuilder("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-File",p.ownership().repo()+"/scripts/run-phase18-k6.ps1","-InputPath",root.resolve("k6-input.json").toString(),"-K6",executable);
                builder.environment().put("PIXEL_PLACE_BENCH_JWT_KEY",keys.jwt());builder.environment().put("PHASE18_K6_FORCE_THRESHOLD",threshold?"1":"0");
                builder.redirectOutput(root.resolve("wrapper.stdout.log").toFile());builder.redirectError(root.resolve("wrapper.stderr.log").toFile());
                var process=builder.start();assertTrue(process.waitFor(65,TimeUnit.SECONDS),"finite wrapper must terminate");
                assertEquals(0,process.exitValue(),"Wrapper failed: "+root);assertNull(session.failure());
            }
            assertTrue(server.failures.isEmpty(),server.failures.toString());
            var producer=BenchmarkJson.read(root.resolve("k6-producer.json"));assertTrue(producer.path("producerComplete").asBoolean());
            var runner=BenchmarkJson.read(root.resolve("k6-runner.json"));assertTrue(runner.path("Complete").asBoolean());
            var measure=BenchmarkJson.read(root.resolve("k6-measurement-exit.json"));assertEquals(threshold?99:0,measure.path("ActualExit").asInt());
            var rows=Files.readAllLines(root.resolve("k6-records.jsonl")).stream().map(Phase18Plan.JSON::readTree).toList();
            for(var observed:server.observed){
                var row=rows.stream().filter(r->r.path("id").asText().equals(observed.get("id"))).findFirst().orElseThrow();
                assertEquals("ACCEPTED",row.path("outcome").asText());assertTrue(row.path("validationEpochMillis").asLong()>=row.path("bodyEpochMillis").asLong());
                if(observed.get("kind").equals("read"))assertEquals(observed.get("hash"),row.path("tileHash").asText());
                else {
                    var event=Phase18Plan.JSON.valueToTree(observed.get("event"));
                    var canonical=Files.readAllLines(root.resolve("k6-ws-canonical.jsonl")).stream().map(Phase18Plan.JSON::readTree).toList();
                    var matches=canonical.stream().filter(e->e.path("eventSeq").asLong()==event.path("eventSeq").asLong()).toList();
                    assertEquals(1,matches.size());
                    for(String field:List.of("eventSeq","tileVersion","x","y","color"))assertEquals(event.path(field).asLong(),matches.getFirst().path(field).asLong());
                }
            }
            var loaded=new Phase18Plan.Loaded(p,"0".repeat(64));
            var consumed=Phase18K6Results.read(root,loaded,users);
            assertEquals(server.observed.size(),consumed.sent());assertEquals(true,consumed.ws().get("complete"));
            Path records=root.resolve("k6-records.jsonl");byte[] original=Files.readAllBytes(records);
            var http=rows.stream().filter(v->v.path("type").asText().equals("http")).findFirst().orElseThrow();
            try{
                Files.writeString(records,Phase18Plan.JSON.writeValueAsString(http)+"\n",StandardOpenOption.APPEND);
                assertThrows(IllegalArgumentException.class,()->Phase18K6Results.read(root,loaded,users),"duplicate ID");
            }finally{Files.write(records,original);}
            assertEquals(scheduled?List.of("initial-read","warmup","measurement"):List.of("warmup","measurement"),phases);
            try(var paths=Files.list(root)){
                for(Path path:paths.filter(Files::isRegularFile).filter(path->!path.toString().endsWith(".bin")).toList()){
                    String text=Files.readString(path);assertFalse(text.contains(keys.jwt()),"secret key in evidence");
                    assertFalse(text.contains("Bearer "),"authorization header in evidence");
                    assertFalse(text.matches("(?s).*eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+.*"),"token in evidence");
                }
            }
        }
    }
}
