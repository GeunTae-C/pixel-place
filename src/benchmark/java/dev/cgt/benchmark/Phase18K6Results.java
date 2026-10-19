package dev.cgt.benchmark;

import tools.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** k6 epoch 기록의 계획/accepted/read/WS 대조. 기존 DB/WAL 검증에 실제 사실만 전달 */
final class Phase18K6Results {
    record Data(List<Phase18Consistency.Accepted> accepted,List<Phase18ReadReplay.Sample> samples,
                long reads,long sent,long notSent,long unstarted,long rejected,Map<String,Object> ws) { }
    private Phase18K6Results() { }
    static long number(JsonNode row,String field) {
        var value=row.path(field);
        Phase18Plan.require(value.isIntegralNumber()&&value.canConvertToLong(),"k6 integer "+field);
        return value.longValue();
    }
    private static String line(BufferedReader input,int limit)throws IOException {
        var value=new StringBuilder();int ch;
        while((ch=input.read())!=-1&&ch!='\n'){
            Phase18Plan.require(value.length()<limit,"k6 line allocation bound");
            if(ch!='\r')value.append((char)ch);
        }
        return ch==-1&&value.isEmpty()?null:value.toString();
    }
    static Data read(Path root,Phase18Plan.Loaded loaded,List<Long> users)throws Exception {
        var c=loaded.plan().cases().getFirst();var w=c.workload();
        var input=BenchmarkJson.read(root.resolve("k6-input.json"));var producer=BenchmarkJson.read(root.resolve("k6-producer.json"));
        Phase18Plan.require(input.path("schema").asText().equals("phase18-k6-v2")&&input.path("planHash").asText().equals(loaded.planHash())
            &&producer.path("schema").asText().equals("phase18-k6-v2")&&producer.path("planHash").asText().equals(loaded.planHash())
            &&producer.path("producerComplete").asBoolean()&&producer.path("firstFailure").isNull()
            &&producer.path("unresolved").isArray()&&producer.path("unresolved").isEmpty(),"k6 complete producer identity");
        if(Phase18Cap1.enabled(loaded.plan())){
            var limits=input.path("limits");
            Phase18Plan.require(number(limits,"requests")==Phase18K6Session.requestLimit(loaded.plan())
                &&number(limits,"analysisMemoryBytes")==Phase18K6Session.analysisMemory(loaded.plan()),"k6 frozen CAP1 result allocation");
        }
        if(input.path("limits").has("wsTransport")&&w.wsConnections()>0){
            var transport=producer.path("wsTransport");var bridge=producer.path("wsBridge");
            Phase18Plan.require(bridge.path("transportComplete").asBoolean()&&transport.path("failure").isNull()
                &&transport.path("pending").asLong(-1)==0&&transport.path("pendingBytes").asLong(-1)==0&&transport.path("inFlight").asLong(-1)==0
                &&transport.path("waiting").asLong(-1)==0&&transport.path("unconfirmedBatches").asLong(-1)==0
                &&transport.path("received").asLong(-1)==bridge.path("saved").asLong(-2)&&transport.path("acknowledged").asLong(-1)==bridge.path("saved").asLong(-2),"k6 WS transport exact storage confirmation");
            var limits=input.path("limits").path("wsTransport");
            if(limits.has("cacheCount")){
                long entries=number(transport,"maxCacheEntries"),bytes=number(transport,"maxCacheBytes");
                long hits=number(transport,"cacheHits"),misses=number(transport,"cacheMisses");
                Phase18Plan.require(entries>=0&&entries<=number(limits,"cacheCount")&&bytes>=0&&bytes<=number(limits,"cacheBytes")
                    &&hits>=0&&misses>=0&&hits+misses==number(transport,"received")-2L*w.wsConnections(),"k6 bounded serialization cache");
            }
        }
        var runtime=BenchmarkJson.read(root.resolve("k6-runtime.json"));
        Phase18Plan.require(runtime.path("BinarySha256").asText().equalsIgnoreCase(input.path("k6BinarySha256").asText())
            &&runtime.path("ScriptSha256").asText().equalsIgnoreCase(input.path("scriptSha256").asText())
            &&input.path("k6BinarySha256").asText().matches("[0-9a-f]{64}"),"actual k6 binary/script identity");
        var runner=BenchmarkJson.read(root.resolve("k6-runner.json"));
        Phase18Plan.require(runner.path("Complete").asBoolean()&&runner.path("FirstFailure").isNull()&&runner.path("Processes").isArray()&&!runner.path("Processes").isEmpty(),"k6 runner complete");
        for(var process:runner.path("Processes"))Phase18Plan.require(process.path("Released").asBoolean()
            &&process.path("Failure").isNull()&&Set.of(0L,99L).contains(number(process,"ActualExit")),"k6 process ownership/exit");
        var anchors=new HashMap<String,Long>();var finished=new HashSet<String>();
        for(var boundary:producer.path("phaseBoundaries")) {
            String phase=boundary.path("phase").asText();
            if(boundary.path("event").asText().equals("k6-scenario-start"))Phase18Plan.require(anchors.put(phase,number(boundary,"epochMillis"))==null,"k6 phase anchor unique");
            if(boundary.path("event").asText().equals("drained"))finished.add(phase);
        }
        Phase18Plan.require(finished.containsAll(List.of("warmup","measurement")),"k6 phases drained");
        var counts=new LinkedHashMap<String,Long>();var seen=new HashMap<String,BitSet>();var unstarted=new HashMap<String,BitSet>();
        for(String phase:List.of("bootstrap","initial-read","warmup","measurement"))for(String kind:List.of("write","read")){
            String key=kind+"/"+phase;long count=Phase18Cap.count(w,kind,phase);counts.put(key,count);
            seen.put(key,new BitSet(Math.toIntExact(count)));unstarted.put(key,new BitSet(Math.toIntExact(count)));
        }
        var accepted=new ArrayList<Phase18Consistency.Accepted>();var samples=new ArrayList<Phase18ReadReplay.Sample>();
        var events=new HashMap<Long,Phase18Events.Event>();var seqs=new HashSet<Long>();
        long reads=0,sent=0,notSent=0,rejected=0,rows=0,bootstrap=0,initialReads=0;var opened=new BitSet(Math.toIntExact(w.wsConnections()));var closed=new BitSet(Math.toIntExact(w.wsConnections()));
        Path file=root.resolve("k6-records.jsonl");
        Phase18Plan.require(Files.size(file)<=Phase18K6Session.recordBytes(loaded.plan()),"k6 record byte bound");
        try(var stream=Files.newBufferedReader(file)){
            String line;while((line=line(stream,4096))!=null){
                Phase18Plan.require(line.length()<=4096&&++rows<=Phase18K6Session.requestLimit(loaded.plan())+w.wsConnections()*3L,"k6 row bound");
                var row=Phase18Plan.JSON.readTree(line);String type=row.path("type").asText();
                if(type.equals("ws")){
                    int connection=Math.toIntExact(number(row,"connection"));Objects.checkIndex(connection,w.wsConnections());
                    String event=row.path("event").asText();
                    if(event.equals("open")){Phase18Plan.require(!opened.get(connection),"k6 duplicate WS open");opened.set(connection);}
                    else if(event.equals("close")){Phase18Plan.require(opened.get(connection)&&!closed.get(connection)&&number(row,"code")==1000,"k6 WS close");closed.set(connection);}
                    else throw new IllegalArgumentException("k6 WS lifecycle error");
                    continue;
                }
                Phase18Plan.require(type.equals("http")||type.equals("unstarted"),"k6 record type");
                String kind=row.path("kind").asText(),phase=row.path("phase").asText(),key=kind+"/"+phase;
                long ordinal=number(row,"ordinal");var count=counts.get(key);
                Phase18Plan.require(count!=null&&ordinal>=0&&ordinal<count&&row.path("id").asText().equals(Phase18Plan.requestId(c,kind,phase,ordinal)),"k6 planned request identity");
                int index=Math.toIntExact(ordinal);Phase18Plan.require(!seen.get(key).get(index)&&!unstarted.get(key).get(index),"k6 duplicate request");
                if(type.equals("unstarted")){
                    Phase18Plan.require(Phase18Cap.scheduled(w)&&number(row,"dueOffsetMillis")==Phase18Cap.offset(w,kind,ordinal,phase)/1_000_000,"k6 original unstarted schedule");
                    unstarted.get(key).set(index);continue;
                }
                seen.get(key).set(index);
                Phase18Plan.require(row.path("schema").asText().equals("phase18-k6-v2")&&anchors.containsKey(phase),"k6 HTTP schema/anchor");
                long due=anchors.get(phase)+(phase.equals("bootstrap")?0:Phase18Cap.offset(w,kind,ordinal,phase)/1_000_000);
                Phase18Plan.require(number(row,"dueEpochMillis")==due,"k6 original due");
                int[] pixel=kind.equals("write")?Phase18LoadClient.pixel(c,phase,ordinal):Phase18Cap.readPixel(w,phase,ordinal);
                for(int i=0;i<3;i++)Phase18Plan.require(number(row,List.of("x","y","color").get(i))==pixel[i],"k6 request payload identity");
                String outcome=row.path("outcome").asText();
                if(outcome.equals("NOT_SENT")){
                    Phase18Plan.require(!row.has("startedEpochMillis")&&!row.has("bodyEpochMillis")&&!row.has("event")
                        &&Set.of("arrival_late","processing_capacity","user_not_ready").contains(row.path("reason").asText()),"k6 not-sent evidence");Phase18Plan.require(!phase.equals("bootstrap")&&!phase.equals("initial-read"),"required preparation accepted");notSent++;continue;
                }
                long start=number(row,"startedEpochMillis"),body=number(row,"bodyEpochMillis"),validated=number(row,"validationEpochMillis");
                long end=anchors.get(phase)+(phase.equals("bootstrap")?Math.max(1000,w.requestMillis()):phase.equals("initial-read")?Phase18Cap.initialSeconds(w)*1000:(phase.equals("warmup")?w.warmupSeconds():w.measurementSeconds())*1000);
                Phase18Plan.require(start>=anchors.get(phase)&&start<end&&body>=start&&validated>=body,"k6 actual send window/order");
                if(Phase18Cap.scheduled(w)||phase.equals("initial-read"))Phase18Plan.require(start>=due&&start<due+Math.max(1,Phase18Cap.late(w,kind,phase)/1_000_000),"k6 scheduled send deadline");
                var duration=row.path("k6DurationMillis");Phase18Plan.require(duration.isNumber()&&Double.isFinite(duration.doubleValue())&&duration.doubleValue()>=0,"k6 observed HTTP duration");
                sent++;
                if(outcome.equals("REJECTED")){
                    Phase18Plan.require(kind.equals("write")&&!phase.equals("bootstrap")&&!row.has("event")
                        &&(number(row,"httpStatus")==503&&row.path("reason").asText().equals("cap_write_busy")
                        ||number(row,"httpStatus")==429&&row.path("reason").asText().equals("cap_cooldown")),"k6 allowed rejection");rejected++;continue;
                }
                Phase18Plan.require(outcome.equals("ACCEPTED")&&number(row,"httpStatus")==200,"k6 UNKNOWN/non-accepted result");
                if(kind.equals("write")){
                    var e=row.path("event");var event=new Phase18Events.Event(number(e,"eventSeq"),number(e,"tileVersion"),Math.toIntExact(number(e,"x")),Math.toIntExact(number(e,"y")),Math.toIntExact(number(e,"color")));
                    int user=Math.toIntExact(number(row,"userOrdinal"));Objects.checkIndex(user,users.size());
                    Phase18Plan.require(row.path("userId").asText().equals(users.get(user).toString())&&seqs.add(event.eventSeq())
                        &&event.x()==pixel[0]&&event.y()==pixel[1]&&event.color()==pixel[2],"k6 accepted seq/user/payload");
                    if(phase.equals("bootstrap"))bootstrap++;
                    accepted.add(new Phase18Consistency.Accepted(event.eventSeq(),event.tileVersion(),event.x(),event.y(),event.color(),user));
                    if(!phase.equals("bootstrap"))events.put(event.eventSeq(),event);
                } else {
                    reads++;if(phase.equals("initial-read"))initialReads++;Phase18Plan.require(number(row,"decodedLength")==65536&&row.path("tileVersion").isTextual(),"k6 validated read");
                    long version=Long.parseLong(row.path("tileVersion").textValue());Phase18Plan.require(version>=0,"k6 read version");
                    if(Phase18ReadReplay.sampled(Phase18Cap.readOrdinal(w,phase,ordinal),loaded.plan().counts(c).reads())){
                        Phase18Plan.require(row.path("tileHash").asText().matches("[0-9a-f]{64}"),"k6 required read sample");
                        samples.add(new Phase18ReadReplay.Sample(pixel[0]/256,pixel[1]/256,version,row.path("tileHash").asText()));
                    }
                }
            }
        }
        long missing=0;
        for(var entry:counts.entrySet()){
            long absent=entry.getValue()-seen.get(entry.getKey()).cardinality();
            if(Phase18Cap.scheduled(w))Phase18Plan.require(absent==unstarted.get(entry.getKey()).cardinality(),"k6 scheduled exact terminal set");
            if(entry.getKey().endsWith("initial-read")||entry.getKey().equals("write/bootstrap"))Phase18Plan.require(absent==0,"k6 required preparation completeness");
            missing+=absent;
        }
        Phase18Plan.require(bootstrap==counts.get("write/bootstrap")&&initialReads==counts.get("read/initial-read"),"required preparation accepted set");
        Phase18Plan.require(sent==number(producer,"started")&&sent+notSent==number(producer,"terminal"),"k6 terminal producer correspondence");
        Phase18Plan.require(opened.cardinality()==w.wsConnections()&&opened.equals(closed),"k6 all WS lifecycle");
        Phase18ReadReplay.verify(samples,accepted.stream().map(r->new Phase18Events.Event(r.eventSeq(),r.tileVersion(),r.x(),r.y(),r.color())).toList());
        Map<String,Object> ws=verifyWs(root,loaded,events,producer.path("ws"));
        return new Data(List.copyOf(accepted),List.copyOf(samples),reads,sent,notSent,missing,rejected,ws);
    }
    static Map<String,Object> verifyWs(Path root,Phase18Plan.Loaded loaded,Map<Long,Phase18Events.Event> expected,JsonNode report)throws Exception {
        int connections=Math.toIntExact(loaded.plan().cases().getFirst().workload().wsConnections());
        if(connections==0)expected=Map.of();
        int reserved=Math.toIntExact(loaded.plan().counts(loaded.plan().cases().getFirst()).wsEvents());
        var seen=new HashSet<Long>();int canonical=0;
        try(var stream=Files.newBufferedReader(root.resolve("k6-ws-canonical.jsonl"))){
            String line;while((line=line(stream,512))!=null){
                Phase18Plan.require(line.length()<=512&&++canonical<=reserved,"k6 canonical bound");
                var e=Phase18Plan.JSON.readValue(line,Phase18Events.Event.class);
                Phase18Plan.require(e.equals(expected.get(e.eventSeq()))&&seen.add(e.eventSeq()),"k6 WS exact payload/extra/duplicate");
            }
        }
        Phase18Plan.require(seen.size()==expected.size(),"k6 WS missing canonical");
        int words=(reserved+63)/64;
        Phase18Plan.require(Files.size(root.resolve("k6-ws-receipts.bin"))==(long)connections*words*8
            &&Files.size(root.resolve("k6-ws-times.bin"))==(long)connections*reserved*8,"k6 WS binary sizes");
        try(var input=new DataInputStream(Files.newInputStream(root.resolve("k6-ws-receipts.bin")))){
            for(int connection=0;connection<connections;connection++){
                for(String field:List.of("duplicates","missing","extra"))Phase18Plan.require(report.path(field).isArray()&&report.path(field).size()==connections
                    &&report.path(field).get(connection).isIntegralNumber()&&report.path(field).get(connection).longValue()==0,"k6 WS "+field);
                for(int word=0;word<words;word++){
                    int bits=Math.min(64,Math.max(0,canonical-word*64));long expectedBits=bits==64?-1L:(1L<<bits)-1;
                    Phase18Plan.require(input.readLong()==expectedBits,"k6 exact connection receipt bits");
                }
            }
        }
        return Map.of("complete",true,"canonical",canonical,"connections",connections,"deliveries",(long)canonical*connections);
    }
}
