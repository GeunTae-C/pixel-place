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

/** 실제 Windows k6·동일 bridge의 선택된 전체 규모 전달 시험. 서버 capacity 판정과 분리 */
class Phase18WsTransportTest {
 @Test void fullW250Ws100Warmup30AndMeasurement60RetainsEveryDeliveryAndDrains()throws Exception {full(250);}
 @Test void fullW250Ws100ConnectionMajorBurstsRetainEveryDeliveryAndDrain()throws Exception {full(250,true);}
 @Test void fullW500Ws100Warmup30AndMeasurement60RetainsEveryDeliveryAndDrains()throws Exception {full(500);}
 @Test void fullW500Ws100ConnectionMajorBurstsRetainEveryDeliveryAndDrain()throws Exception {full(500,true);}
 private void full(int rate)throws Exception {full(rate,false);}
 private void full(int rate,boolean bursts)throws Exception {
  String binary=System.getenv("PHASE18_TEST_K6");assertNotNull(binary);
  Path repo=Path.of(System.getProperty("user.dir"));
  Path planPath=Path.of("F:/pixel-place-work/evidence/"+System.getenv("PIXEL_PLACE_WORK_SESSION")+"/ws"+rate+"-plan.json");
  var p=Phase18Plan.JSON.readValue(Files.readString(planPath),Phase18Plan.class);
  assertEquals(rate,p.cases().getFirst().workload().writeRate());
  long events=rate*90L,observed=0;
  var keys=BenchmarkEnvironment.keys();var tokens=ServiceJwtTokens.benchmarkAccess(new AuthProperties(keys.jwt(),keys.cookie(),Duration.ofMinutes(15)),Clock.systemUTC());
  Path root=Path.of("F:/pixel-place-work/evidence/"+System.getenv("PIXEL_PLACE_WORK_SESSION")+"/ws"+rate+(bursts?"-burst":"-full"));Files.createDirectory(root);
  var users=java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList();
  var loaded=new Phase18Plan.Loaded(p,"0".repeat(64));long expiry=Math.min(System.currentTimeMillis()+300000,Long.parseLong(System.getenv().getOrDefault("PIXEL_PLACE_CAP_EXPIRES",Long.toString(Long.MAX_VALUE))));
  try(var server=new Phase18K6FixtureServer(tokens,false,bursts)){
   try(var session=new Phase18K6Session(loaded,users,server.endpoint(),keys.jwt(),tokens,expiry,root,
    new Phase18K6Session.App(){public void phase(String name){}public void check(){}public void drain(Set<String> ids,long tail){assertEquals(ids.size(),server.observed.size());}},Clock.systemUTC())){
    var args=List.of("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-NonInteractive","-File",repo+"/scripts/run-phase18-k6.ps1","-InputPath",root.resolve("k6-input.json").toString(),"-K6",binary);
    session.runProcess(new Phase18Process.Command(args,repo,root.resolve("process"),300000,180000,1048576),keys);
    assertNull(session.failure());
   }
   assertTrue(server.failures.isEmpty(),server.failures.toString());observed=server.observed.size();
   // 실제 후보250/500의30+60초 전체 이벤트 수. bootstrap은 별도1건.
   assertEquals(events+1,observed);
  }
  var result=Phase18K6Results.read(root,loaded,users);assertEquals(observed,result.sent());assertEquals(result.sent(),result.accepted().size());assertEquals(0,result.unstarted());assertEquals(0,result.rejected());
  assertEquals(events+1,result.sent()+result.notSent());assertTrue(result.sent()-1>=events*0.999);
  assertEquals(true,result.ws().get("complete"));
  var producer=BenchmarkJson.read(root.resolve("k6-producer.json"));assertTrue(producer.path("producerComplete").asBoolean());assertEquals(100,producer.path("closed").size());
  var t=producer.path("wsTransport");assertEquals((observed-1)*100+200,t.path("received").asLong());assertEquals((observed-1)*100+200,t.path("acknowledged").asLong());
  for(String field:List.of("pending","pendingBytes","waiting","inFlight","unconfirmedBatches"))assertEquals(0,t.path(field).asLong(-1),field);
  assertTrue(t.path("maxPending").asLong()<=8192);assertTrue(t.path("maxPendingBytes").asLong()<=4194304);
  assertTrue(Phase18Process.unresolved().isEmpty());
 }
}

