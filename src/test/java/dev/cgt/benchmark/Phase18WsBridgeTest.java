package dev.cgt.benchmark;
import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 bridge의 개수·직렬화 byte·부분 처리 오류 경계. fixture engine과 독립된 HTTP 대조 */
class Phase18WsBridgeTest {
 private final class Fixture implements AutoCloseable {
  final Path root;final Phase18K6Session session;final String key;final HttpClient http=HttpClient.newHttpClient();final String address;
  Fixture()throws Exception {
   var j=(ObjectNode)Phase18Plan.JSON.valueToTree(Phase18K6Test.small(false));var w=(ObjectNode)j.path("cases").get(0).path("workload");((ObjectNode)j.path("bounds")).put("outputBytes",1073741824L);w.put("writeRate",300);w.put("readRate",0);
   var p=Phase18Plan.JSON.treeToValue(j,Phase18Plan.class);var keys=BenchmarkEnvironment.keys();key=keys.jwt();var tokens=ServiceJwtTokens.benchmarkAccess(new AuthProperties(key,keys.cookie(),Duration.ofMinutes(15)),Clock.systemUTC());
   root=Path.of("F:/pixel-place-work/evidence",System.getenv("PIXEL_PLACE_WORK_SESSION"),"ws-boundary-"+UUID.randomUUID());Files.createDirectory(root);
   session=new Phase18K6Session(new Phase18Plan.Loaded(p,"0".repeat(64)),java.util.stream.LongStream.rangeClosed(1,p.counts(p.cases().getFirst()).users()).boxed().toList(),"http://127.0.0.1:9",key,tokens,System.currentTimeMillis()+60000,root,new Phase18K6Session.App(){public void phase(String s){}public void check(){}public void drain(Set<String> ids,long t){}},Clock.systemUTC());address=BenchmarkJson.read(root.resolve("k6-input.json")).path("bridge").asText();
  }
  HttpResponse<String> post(String route,String body)throws Exception{return http.send(HttpRequest.newBuilder(URI.create(address+route)).header("X-Bridge-Key",key).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
  String batch(int id,List<Object> events){return Phase18Plan.JSON.writeValueAsString(Map.of("batchId",id,"events",events));}
  public void close()throws Exception{http.close();session.close();}
 }
 private Map<String,Object> event(String kind,long ordinal){return Map.of("event",kind,"connection",0,"receiveOrdinal",ordinal,"epochMillis",System.currentTimeMillis());}
 private Map<String,Object> message(long ordinal,long seq,int color){return Map.of("event","message","connection",0,"receiveOrdinal",ordinal,"epochMillis",System.currentTimeMillis(),"body",Phase18Plan.JSON.writeValueAsString(Map.of("type","pixel","eventSeq",seq,"tileVersion",1,"x",1,"y",1,"color",color)));}
 @Test void count512AcknowledgesWholeBatchAnd513FailsBeforeProcessing()throws Exception{
  try(var f=new Fixture()){
   assertEquals(200,f.post("/ws",f.batch(1,List.of(event("open",1)))).statusCode());var events=new ArrayList<Object>();for(int i=0;i<512;i++)events.add(message(i+2,i+1,1));
   var response=f.post("/ws",f.batch(2,events));assertEquals(200,response.statusCode());var ack=Phase18Plan.JSON.readTree(response.body());assertTrue(ack.path("saved").asBoolean());assertEquals(512,ack.path("count").asInt());assertEquals(2,ack.path("batchId").asInt());
   events.add(message(514,513,1));assertEquals(409,f.post("/ws",f.batch(3,events)).statusCode());assertNotNull(f.session.failure());
  }
 }
 @Test void exactUtf8BodyLimitAcceptedAndOneExtraByteRejected()throws Exception{
  try(var f=new Fixture()){
   String body=f.batch(1,List.of(event("open",1)));body+= " ".repeat(Phase18K6Session.WS_BATCH_BYTES-body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
   assertEquals(200,f.post("/ws",body).statusCode());assertEquals(409,f.post("/ws",body+" ").statusCode());assertTrue(f.session.failure().contains("bridge input bound"));
  }
 }
 @Test void partialBatchPayloadMismatchKeepsFirstFailureAndCannotComplete()throws Exception{
  Path root;
  try(var f=new Fixture()){
   root=f.root;assertEquals(200,f.post("/ws",f.batch(1,List.of(event("open",1)))).statusCode());
   assertEquals(409,f.post("/ws",f.batch(2,List.of(message(2,1,1),message(3,1,2)))).statusCode());String first=f.session.failure();assertNotNull(first);
   assertEquals(200,f.post("/stop","{}").statusCode());assertEquals(first,f.session.failure());
   assertEquals(409,f.post("/ws-transport-complete","{}").statusCode());assertEquals(first,f.session.failure());
  }
  var p=BenchmarkJson.read(root.resolve("k6-producer.json"));assertFalse(p.path("producerComplete").asBoolean());assertEquals(1,p.path("ws").path("deliveries").get(0).asLong());assertEquals(1,p.path("wsBridge").path("saved").asLong());
 }
 @Test void stopIsReturnedInEveryStoredBatchWithoutWaitingForStateTimer()throws Exception{
  Path root;
  try(var f=new Fixture()){
   root=f.root;assertEquals(200,f.post("/ws",f.batch(1,List.of(event("open",1)))).statusCode());
   assertEquals(200,f.post("/stop","{\"reason\":\"selected_stop\"}").statusCode());
   var response=f.post("/ws",f.batch(2,List.of(message(2,1,1))));assertEquals(200,response.statusCode());
   var ack=Phase18Plan.JSON.readTree(response.body());assertTrue(ack.path("saved").asBoolean());assertTrue(ack.path("stop").asBoolean());assertTrue(ack.path("closing").asBoolean());
   assertEquals(1,ack.path("count").asInt());assertEquals("selected_stop",f.session.failure());
  }
  assertFalse(BenchmarkJson.read(root.resolve("k6-producer.json")).path("producerComplete").asBoolean());
 }

}
