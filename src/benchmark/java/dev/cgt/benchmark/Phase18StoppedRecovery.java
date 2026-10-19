package dev.cgt.benchmark;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import dev.cgt.pixelplace.wal.application.*;
import dev.cgt.pixelplace.wal.infra.*;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.pixel.application.PixelWriteExecutor;
import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 실패 initial의 원본 응답을 복원하지 않는 별도 사본 진단. 기존 production replay·전수 정합성 재사용 */
public final class Phase18StoppedRecovery {
 public static void main(String[] a)throws Exception {
  Phase18Plan.require(a.length==5,"original run, clone root, clone WAL, diagnostic output, frozen expiry");
  Path original=Path.of(a[0]),clone=Path.of(a[1]),wal=Path.of(a[2]),output=Path.of(a[3]);long expiry=Long.parseLong(a[4]);
  var p=Phase18Plan.JSON.readValue(Files.readString(original.resolve("plan-cap.json")),Phase18Plan.class);var c=p.cases().getFirst();
  Phase18Plan.require(BenchmarkJson.hash(original.resolve("plan-cap.json")).equals("3cb2d83cea3c8624ef83c37840237a0b3110df0b34c564f5b8ac64115c700556"),"specific preserved W250 Plan");
  Phase18Paths.execution(clone.toString());Phase18Paths.execution(wal.toString());
  Phase18Plan.require(wal.equals(Path.of("F:/pixel-place-wal/phase18",clone.getParent().getFileName().toString(),"diagnostic/pixel-place.wal")),"same owned cold-copy WAL session");
  Phase18Plan.require(System.currentTimeMillis()<expiry,"original diagnostic expiry");
  Phase18Plan.require(clone.toString().replace('\\','/').matches("F:/pixel-place-work/sessions/cap1-ws-diagnostic-[a-z0-9-]+/mysql")&&!wal.equals(Path.of(p.ownership().wal())),"isolated diagnostic storage");
  for(var input:p.inputs())if(input.path().startsWith("src/main/"))Phase18Plan.require(BenchmarkJson.hash(Path.of(p.ownership().repo(),input.path())).equals(input.sha256()),"original production source binding");
  var props=new WalProperties();props.setActiveFile(wal);props.setMaxSegmentBytes(c.runtime().segmentBytes());List<WalRecord> records;
  var meter=new SimpleMeterRegistry();try(var storage=new SegmentedWalStorage(props,new WalRecordParser(Phase18Plan.JSON),new WalRecordJsonCodec(Phase18Plan.JSON),new PixelMeasurement(false,meter),new WindowsWalFileDurability())){
   var batch=storage.readAfter(0);records=batch.records();Phase18Plan.require(records.size()==62&&batch.walLastEventSeq()==62,"complete original W250 WAL");
  }
  meter.close();
  // 이 진단은 특정 실패 원본에만 연결. 전체 planned 좌표 중 중복 후보까지 확인
  var actual=new HashMap<String,WalRecord>();for(var v:records)Phase18Plan.require(actual.put(v.x()+"/"+v.y()+"/"+v.color(),v)==null,"distinct durable effects");
  var expected=new HashSet<Long>();var diagnoses=new ArrayList<Object>();
  for(var row:Files.readAllLines(original.resolve(c.runId()+"/initial/k6-records.jsonl"))){var j=Phase18Plan.JSON.readTree(row);if(!j.path("outcome").asText().equals("ACCEPTED"))continue;var v=actual.get(j.path("x").asInt()+"/"+j.path("y").asInt()+"/"+j.path("color").asInt());Phase18Plan.require(v!=null&&v.eventSeq()==j.path("event").path("eventSeq").asLong()&&v.userId()==j.path("userId").asLong()&&expected.add(v.eventSeq()),"every confirmed accepted preserved");}
  Phase18Plan.require(expected.size()==60,"original confirmed accepted count");
  for(long n:List.of(59L,60L)){
   int[] pixel=Phase18LoadClient.pixel(c,"warmup",n);var key=pixel[0]+"/"+pixel[1]+"/"+pixel[2];int matches=0;
   for(String phase:List.of("warmup","measurement"))for(long k=0;k<Phase18Cap.count(c.workload(),"write",phase);k++)if(Arrays.equals(pixel,Phase18LoadClient.pixel(c,phase,k)))matches++;
   var v=actual.get(key);Phase18Plan.require(matches==1&&v!=null&&expected.add(v.eventSeq()),"one planned request explains one remaining WAL effect");
   diagnoses.add(Map.of("requestId",Phase18Plan.requestId(c,"write","warmup",n),"state","identified_effect_exists","wal",v,"uniqueAmongAllPlannedWrites",true,"historicalHttpResponse","UNRESOLVED"));
  }
  Phase18Plan.require(expected.size()==records.size(),"no unexplained durable effects");
  var credentials=BenchmarkEnvironment.credentials();Phase18Plan.require(credentials.dbPort()==3307&&credentials.dbHost().equals("127.0.0.1"),"owned clone database port");
  try(var fixture=new BenchmarkFixtures(credentials,p.ownership().catalog(),true,clone)){
   fixture.requireOwnedStorage();fixture.fixtureFiles();
   var users=new ArrayList<Long>();try(var db=fixture.connect(true);var s=db.createStatement();var rows=s.executeQuery("SELECT id,kakao_user_id FROM users ORDER BY kakao_user_id")) {while(rows.next()){Phase18Plan.require(rows.getLong(2)==9_018_000_000_000L+users.size(),"original fixture identity order");users.add(rows.getLong(1));}}
   Phase18Plan.require(users.size()==p.counts(c).users(),"complete original user fixture");
   var before=database(fixture,records);BenchmarkJson.write(output.resolve("database-before-recovery.json"),before);
   var writes=new ArrayList<Phase18Consistency.Accepted>();var versions=new HashMap<String,Long>();for(var v:records){int user=users.indexOf(v.userId());Phase18Plan.require(user>=0,"durable user belongs to original fixture");long version=versions.merge(v.tx()+"/"+v.ty(),1L,Long::sum);writes.add(new Phase18Consistency.Accepted(v.eventSeq(),version,v.x(),v.y(),v.color(),user));}
   var properties=BenchmarkEnvironment.properties(credentials,p.ownership().catalog(),wal,BenchmarkEnvironment.keys(),c.runtime().measurement());
   properties.put("pixel-place.write.mode",c.runtime().mode());properties.put("pixel-place.write.group.max-batch-size",16);properties.put("pixel-place.write.group.max-outstanding",128);properties.put("pixel-place.write.group.queue-timeout","1s");properties.put("pixel-place.write.group.shutdown-grace","10s");properties.put("pixel-place.wal.max-segment-bytes",c.runtime().segmentBytes());
   var app=BenchmarkEnvironment.application(properties);app.addPrimarySources(List.of(Phase18K6Auth.class));
   Map<String,Object> verified;long pid=ProcessHandle.current().pid();
   try(var context=app.run()){
    var measurement=context.getBean(PixelMeasurement.class);long until=Math.min(expiry,System.currentTimeMillis()+180000),idle=0;boolean drained=false;
    while(System.currentTimeMillis()<until){var exec=context.getBean(PixelWriteExecutor.class).snapshot();boolean pending=context.getBean(PendingAmbiguousFlushStore.class).current().isPresent();
     if(measurement.activeCommands()==0&&!pending&&Phase15BenchmarkMain.executorIdle(exec)&&context.getBean(ServiceReadiness.class).isReady()){
      if(idle==0)idle=System.nanoTime();var capture=measurement.lastCapture();if(capture!=null&&capture.observedNanos()>=idle&&capture.records()==0&&capture.checkpoint()==62&&capture.tail()==62&&fixture.checkpoint()==62){drained=true;BenchmarkJson.write(output.resolve("recovery-drain.json"),Map.of("capture",capture,"executor",exec,"pending",false,"ready",true));break;}
     }else idle=0;Thread.sleep(25);
    }
    Phase18Plan.require(drained,"fresh post-recovery drain");
    verified=Phase18Consistency.verify(fixture,context,users,writes,List.of(),0);
    Phase18Plan.require(measurement.walCounts().completedRecords()==0,"recovery did not append events");
    BenchmarkJson.write(output.resolve("database-after-recovery.json"),database(fixture,records));
   }
   BenchmarkJson.write(output.resolve("recovery-diagnostic.json"),Map.ofEntries(Map.entry("diagnosticOnly",true),Map.entry("originalPlanHash",BenchmarkJson.hash(original.resolve("plan-cap.json"))),Map.entry("originalClassification","INVALID_OR_STOPPED"),Map.entry("confirmedAccepted",60),Map.entry("requests",diagnoses),Map.entry("unexplainedEffects",0),Map.entry("serverDurableEvents",records.size()),Map.entry("newJvmPid",pid),Map.entry("consistency",verified),Map.entry("normalContextClose",true),Map.entry("ddlOrReseed",false),Map.entry("historicalHttpResponse","UNRESOLVED")));
  }
 }
 private static Map<String,Object> database(BenchmarkFixtures fixture,List<WalRecord> records)throws Exception{
  long checkpoint=fixture.checkpoint();Phase18Plan.require(checkpoint>=0&&checkpoint<=62,"DB checkpoint in retained WAL");
  var map=new HashMap<Long,WalRecord>();for(var v:records)if(v.eventSeq()<=checkpoint)map.put(v.eventSeq(),v);
  var pixels=new HashMap<TileKey,byte[]>();var versions=new HashMap<TileKey,Long>();for(var k:new CanonicalZ0TileKeys().orderedKeys()){pixels.put(k,TileState.allWhite().pixels());versions.put(k,0L);}
  int count=0,tiles=0;var payloads=new ArrayList<Object>();
  try(var db=fixture.connect(true);var s=db.createStatement()){
   try(var rows=s.executeQuery("SELECT event_seq,user_id,z,tx,ty,x,y,color,created_at FROM pixel_events ORDER BY event_seq")){
    while(rows.next()){var v=map.remove(rows.getLong(1));Phase18Plan.require(v!=null&&v.userId()==rows.getLong(2)&&v.z()==rows.getInt(3)&&v.tx()==rows.getInt(4)&&v.ty()==rows.getInt(5)&&v.x()==rows.getInt(6)&&v.y()==rows.getInt(7)&&v.color()==rows.getInt(8)&&v.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).equals(rows.getObject(9,LocalDateTime.class)),"DB checkpoint prefix exact WAL payload/time");var key=new TileKey(0,v.tx(),v.ty());pixels.get(key)[v.y()%256*256+v.x()%256]=(byte)v.color();versions.merge(key,1L,Long::sum);payloads.add(v);count++;}
   }
   Phase18Plan.require(map.isEmpty(),"checkpoint covers every retained event");var seen=new HashSet<TileKey>();
   try(var rows=s.executeQuery("SELECT z,tx,ty,data,tile_version FROM tiles")){while(rows.next()){var key=new TileKey(rows.getInt(1),rows.getInt(2),rows.getInt(3));Phase18Plan.require(pixels.containsKey(key)&&seen.add(key)&&Arrays.equals(rows.getBytes(4),pixels.get(key))&&rows.getLong(5)==versions.get(key),"all DB tile bytes/version match checkpoint");tiles++;}}
  }
  Phase18Plan.require(tiles==1024,"original canonical DB tiles");return Map.of("checkpoint",checkpoint,"events",count,"tiles",tiles,"exactWalPrefix",true,"tileBytesAndVersions",true,"eventPayloads",payloads);
 }
}

