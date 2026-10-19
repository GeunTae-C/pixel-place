package dev.cgt.benchmark;
import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 Windows k6가 일부 반영 후 bridge 실패를 숨기지 않고 유한하게 종료하는지 검증 */
class Phase18WsFailureTest {
 @Test void conflictingDuplicateRejectsPartialBatchAndJoinsRealK6()throws Exception {run(false);}
 @Test void revisedConflictingDuplicatePreservesCausalPartialAndNativeClosure()throws Exception {run(true);}
 private void run(boolean revised)throws Exception {
  String binary=System.getenv("PHASE18_TEST_K6");assertNotNull(binary);var p=revised?Phase18K6SupportTest.revisedPlan(105,120):Phase18K6Test.small(false);var keys=BenchmarkEnvironment.keys();
  var tokens=ServiceJwtTokens.benchmarkAccess(new AuthProperties(keys.jwt(),keys.cookie(),Duration.ofMinutes(15)),Clock.systemUTC());
  Path root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"ws-duplicate-failure"+(revised?"-causal":""));Files.createDirectory(root);
  var users=java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList();long start=System.nanoTime();
  long expires=Math.min(System.currentTimeMillis()+60000,Long.parseLong(System.getenv().getOrDefault("PIXEL_PLACE_CAP_EXPIRES",Long.toString(Long.MAX_VALUE))));
  try(var server=new Phase18K6FixtureServer(tokens,true)) {
   try(var session=new Phase18K6Session(new Phase18Plan.Loaded(p,"0".repeat(64)),users,server.endpoint(),keys.jwt(),tokens,expires,root,
    new Phase18K6Session.App(){public void phase(String s){}public void check(){}public void drain(Set<String> ids,long t){}},Clock.systemUTC())) {
    var args=List.of("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-File",p.ownership().repo()+"/scripts/run-phase18-k6.ps1","-InputPath",root.resolve("k6-input.json").toString(),"-K6",binary);
    assertThrows(Exception.class,()->session.runProcess(new Phase18Process.Command(args,Path.of(p.ownership().repo()),root.resolve("process"),60000,10000,1048576),keys));
    // 실패 판정과 소유 회수를 분리. bridge를 닫기 전에 최초60초 만료의 남은 구간만 관측
    if(!Phase18Process.unresolved().isEmpty()){
        long remaining=Math.min(expires-System.currentTimeMillis(),60000-Duration.ofNanos(System.nanoTime()-start).toMillis());
        assertTrue(remaining>0,"original fixture expiry leaves ownership observation time");
        Phase18Process.observeRemaining(remaining);
    }
    assertNotNull(session.failure());assertTrue(Phase18Process.unresolved().isEmpty());
   }
  }
  assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<60000);
  var runner=BenchmarkJson.read(root.resolve("k6-runner.json"));assertFalse(runner.path("Complete").asBoolean());
  for(var item:runner.path("Processes"))assertTrue(item.path("Released").asBoolean());
  var result=BenchmarkJson.read(root.resolve("k6-producer.json"));assertFalse(result.path("producerComplete").asBoolean());
  assertTrue(result.path("firstFailure").asText().contains("bridge_IllegalStateException"),result.toString());
  var transport=result.path("wsTransport");assertEquals("non200",transport.path("failure").path("reason").asText());
  assertEquals(1,transport.path("unconfirmedBatches").asInt());assertTrue(transport.path("pending").asInt()>0);
  assertTrue(runner.path("Processes").toString().contains("108"));
 }
}
