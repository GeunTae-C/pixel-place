package dev.cgt.benchmark;

import com.sun.net.httpserver.*;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import tools.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** k6 VU 간 준비·사용자 소유와 유한 결과 생산 경계. 정확한 WS 집합/시각은 기존 primitive 저장소로 전달 */
final class Phase18K6Session implements AutoCloseable {
    static final int MAX_REQUESTS=140000, MAX_CONNECTIONS=200, MAX_DELIVERIES=16000000;
    static final long MAX_BYTES=640L*1024*1024;
    static final int WS_BATCH_COUNT=512, WS_BATCH_BYTES=524288, WS_PENDING_COUNT=8192, WS_PENDING_BYTES=4194304, WS_CACHE_COUNT=16384, WS_CACHE_BYTES=16777216;
    private long wsBatchId,wsBatchCount,wsSaved,wsMaxBatchNanos,wsTotalBatchNanos;
    private JsonNode wsTransport;
    private boolean wsTransportComplete;
    interface App {
        void phase(String name) throws Exception;
        void drain(Set<String> sent,long tail) throws Exception;
        void check() throws Exception;
    }
    record Request(String id,String kind,String phase,long ordinal,long due,int[] pixel,
                   BenchmarkUsers.Assignment user,int claimFloorMillis) { }
    private final Phase18Plan.Loaded loaded;
    private final Phase18Plan.Case c;
    private final Phase18Plan.Workload w;
    private final List<Long> users;
    private final ServiceJwtTokens tokens;
    private final BenchmarkUsers pool;
    private final App app;
    private final Clock clock;
    private final long expires;
    private final String endpoint,key;
    private final BufferedWriter records;
    private final HttpServer server;
    private final ExecutorService handlers=Executors.newFixedThreadPool(4);
    private final Map<String,Request> requests=new LinkedHashMap<>();
    private final Set<String> terminal=new HashSet<>(),sent=new HashSet<>(),initialSuccess=new HashSet<>();
    private final Set<Integer> opened=new HashSet<>(),closed=new HashSet<>();
    private final Phase18Events wsEvents;
    private final Phase18K6Causal causal;
    private int lastCausalAck,causalLower,causalUpper;
    private final long[] receiveOrdinals;
    private int pendingWrites,pendingReads;
    private final Map<String,Long> anchors=new HashMap<>();
    private final List<Object> boundaries=new ArrayList<>();
    private final Path output;
    private String phase="prepare",firstFailure;
    private long anchor,end,tail,bytes,deliveries,clockSamples;
    private boolean phaseFinished=true,wsClosing,draining;

    Phase18K6Session(Phase18Plan.Loaded loaded,List<Long> users,String endpoint,String key,
                     ServiceJwtTokens tokens,long expires,Path output,App app,Clock clock) throws Exception {
        this.loaded=loaded;this.c=loaded.plan().cases().getFirst();this.w=c.workload();this.users=List.copyOf(users);
        this.endpoint=endpoint;this.key=key;this.tokens=tokens;this.expires=expires;this.output=output;this.app=app;this.clock=clock;
        requireSupport(loaded.plan());
        Phase18Plan.require(endpoint.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}")&&key!=null&&key.length()>=32,"private loopback bridge");
        Phase18Plan.require(tokens.expiresInSeconds()==21600&&expires>clock.millis()&&expires-clock.millis()<21600000-30000,"measurement token covers original expiry");
        Phase18Plan.require(users.size()==loaded.plan().counts(c).users(),"k6 fixture user count");
        pool=new BenchmarkUsers(users.size(),w.userMode().equals("once"),w.reuseMarginMillis(),w.bootstrap());
        wsEvents=new Phase18Events(Math.toIntExact(loaded.plan().counts(c).wsEvents()),Math.toIntExact(w.wsConnections()),output.resolve("k6-ws-times.bin"),true,Phase18Cap1.enabled(loaded.plan()));
        causal=Phase18Cap1.revised(loaded.plan())?new Phase18K6Causal(Math.toIntExact(loaded.plan().counts(c).wsEvents()),Math.toIntExact(w.wsConnections()),output.resolve("k6-ws-causal.bin")):null;
        receiveOrdinals=new long[Math.toIntExact(w.wsConnections())];
        records=Files.newBufferedWriter(output.resolve("k6-records.jsonl"),StandardOpenOption.CREATE_NEW);
        wsTransportComplete=w.wsConnections()==0;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);
        server.setExecutor(handlers);server.createContext("/",this::handle);
        try{BenchmarkJson.write(output.resolve("k6-input.json"),Map.ofEntries(
            Map.entry("schema","phase18-k6-v2"),Map.entry("plan",loaded.plan()),Map.entry("planHash",loaded.planHash()),
            Map.entry("bridge",address()),Map.entry("target",endpoint),Map.entry("expiresEpochMillis",expires),
            Map.entry("clockEvidence",causal==null?Map.of():causal.evidence()),Map.entry("policy","k6-transition-20261010"),Map.entry("k6Version","2.3.0"),
            Map.entry("k6BinarySha256",binaryHash()),Map.entry("scriptSha256",BenchmarkJson.hash(Path.of(loaded.plan().ownership().repo(),"scripts/phase18-k6.js"))),
            Map.entry("measurementAccessTtlSeconds",21600),Map.entry("productionAccessTtlSeconds",900),
            Map.entry("clock",causal==null?"same-host epoch milliseconds; per-RPC send/receive bracket, no cross-JVM nanoTime subtraction":"bridge-causal-brackets-v1; original callback epoch retained; same bridge monotonic clock only"),
            Map.entry("limits",Map.of("requests",requestLimit(loaded.plan()),"connections",MAX_CONNECTIONS,"deliveries",deliveryLimit(loaded.plan()),"bytes",recordBytes(loaded.plan()),"metricsBytes",metricsBytes(loaded.plan()),"analysisMemoryBytes",analysisMemory(loaded.plan()),"processMemoryBytes",processMemoryBytes(loaded.plan()),"combinedMemoryBytes",combinedMemoryBytes(loaded.plan()),"goRuntime",Map.of("gcPercent",100,"memoryBytes",512L*1024*1024),"wsTransport",Map.of("batchCount",WS_BATCH_COUNT,"batchBytes",WS_BATCH_BYTES,"pendingCount",WS_PENDING_COUNT,"pendingBytes",WS_PENDING_BYTES,"cacheCount",WS_CACHE_COUNT,"cacheBytes",WS_CACHE_BYTES)))));
        }catch(Exception|Error e){server.stop(0);handlers.shutdown();try{records.close();wsEvents.closeTiming();}catch(Exception secondary){e.addSuppressed(secondary);}throw e;}
        server.start();
    }
    private static String binaryHash()throws Exception {
        String binary=System.getProperty("phase18.k6",System.getenv("PHASE18_TEST_K6"));
        return binary==null?"":BenchmarkJson.hash(Path.of(binary));
    }
    static boolean s2(Phase18Plan p){
        return p.phase().equals("CAP")&&p.stage().equals("CAP-0")
                &&p.cases().getFirst().role().equals("cap-smoke-s2");
    }
    static boolean expandedS2(Phase18Plan p){return s2(p)&&p.bounds().samples()>Phase18Plan.SAMPLES;}
    /** 새 S2 준비 계산만 담당. 런타임 관측량으로 동결 한도 변경 금지 */
    static long expandLimit(long limit,long usage){
        Phase18Plan.require(limit>0&&usage>=0,"finite storage expansion inputs");
        long twice=Math.multiplyExact(usage,2);
        while(twice>=limit)limit=Math.multiplyExact(limit,5);
        return limit;
    }
    record S2Output(long samples,long sampleBytes,long traceEntries,long traceBytes,long observationBytes,
                    long metricsBytes,long runBytes,long evidenceBytes){ }
    static S2Output s2Output(Phase18Plan p){
        var c=p.cases().getFirst();var w=c.workload();
        Phase18Plan.require(s2(p)&&p.bounds().totalSeconds()==2400&&w.drainMillis()==180000
                &&w.measurementSeconds()==220&&c.observation().sampleMillis()==250
                &&w.wsConnections()==2&&w.writeInFlight()==4&&w.readInFlight()==4,"supported S2 output input");
        // 최초 만료 + cleanup180s를 각 JVM에 보수 적용. 한도와 사용량을 분리하여 부모 증폭 방지
        long seconds=2580,samples=2+seconds*4,sampleBytes=samples*6144;
        long entries=4*seconds+2048,trace=4*seconds*512+2048*2048+512*1024;
        long observation=trace+1048576,metrics=(11*seconds+4*4410+256)*4096+16L*1024*1024;
        long run=2*(sampleBytes+observation)+metrics+32L*1024*1024;
        return new S2Output(samples,sampleBytes,entries,trace,observation,metrics,run,run+64L*1024*1024);
    }
    static void requireSupport(Phase18Plan p) {
        Phase18Plan.require(p.phase().equals("CAP")||Set.of("A-1","A-2").contains(p.stage()),"k6 current CAP/A-2 scope TOOL_LIMIT");
        var c=p.cases().getFirst();var n=p.counts(c);
        Phase18Plan.require(WS_BATCH_BYTES>4096*6+256,"maximum serialized WS item fits bounded batch");
        Phase18Plan.require(n.writes()+n.reads()<=requestLimit(p)&&c.workload().wsConnections()<=MAX_CONNECTIONS&&n.wsDeliveries()<=deliveryLimit(p),
            "k6 bounded terminal/connection/delivery TOOL_LIMIT");
        Phase18Plan.require(c.workload().writeInFlight()<=128&&c.workload().readInFlight()<=128,"finite k6 VUs");
        Phase18Plan.require(!Phase18Cap.scheduled(c.workload())||c.workload().schedule().activeUsers()<=128,"finite scheduled k6 owners");
        Phase18Plan.require(p.bounds().outputBytes()>=outputBytes(p),"k6 native output reservation TOOL_LIMIT");
        Phase18Plan.require(128L*1024*1024+(n.writes()+n.reads())*2300<=analysisMemory(p),"k6 analysis memory reservation TOOL_LIMIT");
        Phase18Plan.require(n.memoryBytes()+(n.writes()+n.reads())*2300+n.wsDeliveries()*(Phase18Cap1.revised(p)?15:8)+32L*1024*1024<=1536L*1024*1024,"k6 app bounded retention TOOL_LIMIT");
    }
    /** 모든 k6 phase가 공유하는 원시 metric 예약. phase마다 같은 예약을 중복 부여하면 안 됨 */
    // Go soft heap 512MiB는 유지. CAP-1의 동시 두 native process와 비-Go 여유를 별도 합산
    static int requestLimit(Phase18Plan p){return Phase18Cap1.enabled(p)?Phase18Cap1.MAX_TERMINALS:MAX_REQUESTS;}
    static int deliveryLimit(Phase18Plan p){return Phase18Cap1.enabled(p)?Phase18Cap1.MAX_DELIVERIES:MAX_DELIVERIES;}
    static long recordBytes(Phase18Plan p){return Phase18Cap1.enabled(p)?Phase18Cap1.RECORD_BYTES:MAX_BYTES;}
    static long analysisMemory(Phase18Plan p){return Phase18Cap1.enabled(p)?Phase18Cap1.ANALYSIS_MEMORY:512L*1024*1024;}
    static long processMemoryBytes(Phase18Plan p){return (Phase18Cap1.revised(p)?1280L:Phase18Cap1.enabled(p)?768L:512L)*1024*1024;}
    static long combinedMemoryBytes(Phase18Plan p){return (Phase18Cap1.revised(p)?1792L:Phase18Cap1.enabled(p)?1280L:512L)*1024*1024;}
    static long metricsBytes(Phase18Plan p){
        if(Phase18Cap1.enabled(p))return Phase18Cap1.limit(96L*1024*1024,Phase18Cap1.output(p).metricsBytes());
        if(expandedS2(p))return expandLimit(101188608,s2Output(p).metricsBytes());
        var n=p.counts(p.cases().getFirst());
        return Math.addExact(96L*1024*1024,Math.addExact(Math.multiplyExact(n.writes()+n.reads(),65536),Math.multiplyExact(n.wsDeliveries(),128)));
    }
    static long outputBytes(Phase18Plan p){
        if(Phase18Cap1.enabled(p))return Phase18Cap1.output(p).runBytes();
        if(expandedS2(p))return s2Output(p).runBytes();
        var n=p.counts(p.cases().getFirst());
        return Math.addExact(metricsBytes(p),Math.addExact(n.diskBytes(),Math.addExact((n.writes()+n.reads())*4096,16L*1024*1024)));
    }
    String address(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    void runProcess(Phase18Process.Command command,BenchmarkEnvironment.Keys keys)throws Exception{
        Phase18Process.runExternalWithKeys(command,keys,this::check,()->stop("app_or_process_failure"));
        check();
    }
    synchronized void stop(String cause){if(firstFailure==null)firstFailure=cause;wsClosing=true;}
    synchronized String failure(){return firstFailure;}
    synchronized void check() throws Exception {
        if(firstFailure!=null)throw new IllegalStateException(firstFailure);
        if(clock.millis()>=expires){stop("original_expiry");throw new IllegalStateException(firstFailure);}
        app.check();
    }
    private void handle(HttpExchange exchange) throws IOException {
        int code=200;Object reply;
        try {
            var supplied=exchange.getRequestHeaders().getFirst("X-Bridge-Key");
            if(supplied==null||!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
                code=403;reply=Map.of("error","forbidden");
            }else{
                int bound=exchange.getRequestURI().getPath().equals("/ws")?WS_BATCH_BYTES:262144;
                byte[] body=exchange.getRequestBody().readNBytes(bound+1);
                Phase18Plan.require(body.length<=bound,"bridge input bound");
                JsonNode input=body.length==0?Phase18Plan.JSON.createObjectNode():Phase18Plan.JSON.readTree(body);
                reply=dispatch(exchange.getRequestURI().getPath(),input);
            }
        }catch(Exception e){
            String detail=e instanceof IllegalArgumentException&&e.getMessage()!=null&&e.getMessage().startsWith("Phase18 rejected: ")?e.getMessage():e.getClass().getSimpleName();
            stop("bridge_"+detail);code=409;reply=Map.of("error","bridge_failed","reason",firstFailure);
        }
        byte[] data=Phase18Plan.JSON.writeValueAsBytes(reply);
        exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,data.length);
        try(var stream=exchange.getResponseBody()){stream.write(data);}finally{exchange.close();}
    }
    private Object dispatch(String path,JsonNode input) throws Exception {
        // 서버 소진 중에도 WS 수신·상위 STOP을 처리해야 하므로 긴 drain은 bridge lock 밖에서 수행
        if(path.equals("/finish"))return finish(input.path("phase").asText());
        synchronized(this){
        return switch(path){
            case "/state" -> {
                try{app.check();}catch(Exception e){stop("app_control_stop");}
                yield Map.of("phase",phase,"readyConnections",opened.size(),"closedConnections",closed.size(),"stop",firstFailure!=null||clock.millis()>=expires,"closing",wsClosing,"epochMillis",clock.millis());
            }
            case "/clock-sample" -> {
                long before=Phase18K6Results.number(input,"before"),after=Phase18K6Results.number(input,"after"),bridge=Phase18K6Results.number(input,"bridge");
                Phase18Plan.require(after>=before&&++clockSamples<=3602,"bounded WS clock samples");
                if(input.has("wsTransport"))wsTransport=input.path("wsTransport").deepCopy();
                boundaries.add(Map.of("event","ws-clock","before",before,"after",after,"bridge",bridge,"transport",input.path("wsTransport")));
                yield Map.of("saved",true);
            }
            case "/stop" -> {if(input.has("wsTransport"))wsTransport=input.path("wsTransport").deepCopy();stop(input.path("reason").asText("controller_stop"));yield Map.of("stopped",true);}
            case "/begin" -> begin(input.path("phase").asText());
            case "/claim" -> claim(input);
            case "/cancel" -> cancel(input);
            case "/result" -> result(input);
            case "/ws" -> {
                var events=input.path("events");long id=input.path("batchId").asLong(-1);
                Phase18Plan.require(id==wsBatchId+1&&events.isArray()&&events.size()>0&&events.size()<=WS_BATCH_COUNT,"ordered bounded WS batch");
                if(causal!=null){
                    causalLower=Math.toIntExact(Phase18K6Results.number(input,"callbackFloorMillis"));causalUpper=causal.millis(true);
                    Phase18Plan.require(causalLower>=0&&causalLower<=lastCausalAck&&causalLower<=causalUpper,"causal acknowledged callback floor");
                }
                long started=System.nanoTime();
                // 단일 FIFO 전송. 실패 batch는 부분 반영을 성공 확인하거나 재시도하지 않음
                for(var event:events)ws(event);
                long elapsed=System.nanoTime()-started;wsMaxBatchNanos=Math.max(wsMaxBatchNanos,elapsed);wsTotalBatchNanos+=elapsed;
                wsBatchId=id;wsBatchCount++;wsSaved+=events.size();
                // 수신 callback 집중으로 state timer가 늦어져도 매 저장 확인에서 STOP 전달
                if(causal!=null)lastCausalAck=causal.millis(false);
                yield Map.of("saved",true,"batchId",id,"count",events.size(),"stop",firstFailure!=null||clock.millis()>=expires,"closing",wsClosing,"clockFloorMillis",lastCausalAck);
            }
            case "/ws-transport-complete" -> {
                Phase18Plan.require(input.path("failure").isNull()&&input.path("pending").asLong(-1)==0&&input.path("pendingBytes").asLong(-1)==0
                    &&input.path("inFlight").asLong(-1)==0&&input.path("unconfirmedBatches").asLong(-1)==0&&input.path("received").asLong(-1)==wsSaved
                    &&input.path("acknowledged").asLong(-1)==wsSaved&&closed.size()==w.wsConnections(),"fully acknowledged WS transport close");
                wsTransport=input.deepCopy();wsTransportComplete=true;yield Map.of("saved",true);
            }
            case "/close-ws" -> {
                check();boolean received=opened.size()==w.wsConnections()&&wsEvents.complete();
                if(received)wsClosing=true;yield Map.of("closing",received);
            }
            default -> throw new IllegalArgumentException("unknown bridge route");
        };
        }
    }
    synchronized Map<String,Object> begin(String next) throws Exception {
        check();Phase18Plan.require(phaseFinished,"prior phase finished");
        boolean valid=switch(next){
            case "bootstrap" -> phase.equals("prepare")&&w.bootstrap();
            case "initial-read" -> (phase.equals("prepare")||phase.equals("bootstrap"))&&w.schedule().kind().equals("users");
            case "warmup" -> w.schedule().kind().equals("users")?phase.equals("initial-read"):(w.bootstrap()?phase.equals("bootstrap"):phase.equals("prepare"));
            case "measurement" -> phase.equals("warmup");
            default -> false;
        };
        Phase18Plan.require(valid,"ordered k6 phase");
        if(!next.equals("bootstrap"))Phase18Plan.require(opened.size()==w.wsConnections()&&closed.isEmpty(),"all WS buffers ready before reads/warmup");
        if(next.equals("warmup")&&w.schedule().kind().equals("users"))requireInitialSet();
        if(next.equals("warmup"))boundaries.add(Map.of("event","READY","epochMillis",clock.millis(),"wsConnections",opened.size(),"initialValidated",initialSuccess.size()));
        phase=next;phaseFinished=false;anchor=0;end=0;app.phase(next);
        boundaries.add(Map.of("phase",next,"event","app-phase-enter","epochMillis",clock.millis()));
        return Map.of("phase",phase,"epochMillis",clock.millis());
    }
    private long seconds(){return switch(phase){case "bootstrap"->Math.max(1,w.requestMillis()/1000);case "initial-read"->Phase18Cap.initialSeconds(w);case "warmup"->w.warmupSeconds();default->w.measurementSeconds();};}
    synchronized Object claim(JsonNode input) throws Exception {
        String kind=input.path("kind").asText(),requestedPhase=input.path("phase").asText();long ordinal=input.path("ordinal").asLong(-1);
        Phase18Plan.require(Set.of("write","read").contains(kind)&&phase.equals(requestedPhase)&&!phaseFinished,"active kind/phase");
        long count=Phase18Cap.count(w,kind,phase);Phase18Plan.require(ordinal>=0&&ordinal<count,"planned request ordinal");
        if(anchor==0){anchor=input.path("scenarioStart").asLong();Phase18Plan.require(anchor>0&&Math.abs(clock.millis()-anchor)<5000,"observed k6 scenario anchor");end=Math.addExact(anchor,seconds()*1000);anchors.put(phase,anchor);boundaries.add(Map.of("phase",phase,"event","k6-scenario-start","epochMillis",anchor,"observedEpochMillis",clock.millis()));}
        long due=anchor+(phase.equals("bootstrap")?0:Phase18Cap.offset(w,kind,ordinal,phase)/1000000);
        String id=Phase18Plan.requestId(c,kind,phase,ordinal);Phase18Plan.require(!requests.containsKey(id),"unique request ID");
        int[] pixel=kind.equals("write")?Phase18LoadClient.pixel(c,phase,ordinal):Phase18Cap.readPixel(w,phase,ordinal);
        try{app.check();}catch(Exception e){stop("app_control_stop");}
        long now=clock.millis();String reason=reason(kind,now,due,false,false);BenchmarkUsers.Assignment user=null;
        if(reason.isEmpty()&&kind.equals("write")) {
            if(phase.equals("bootstrap"))user=new BenchmarkUsers.Assignment(0,1);
            else if(Phase18Cap.scheduled(w))user=pool.acquire((int)(ordinal%w.schedule().activeUsers()),due*1000000);
            else user=pool.acquire(now*1000000);
            if(user==null)reason="user_not_ready";
        }
        if(reason.isEmpty()&&(kind.equals("write")?pendingWrites:pendingReads)>=(kind.equals("write")?w.writeInFlight():w.readInFlight())){
            reason="processing_capacity";
            if(user!=null&&!phase.equals("bootstrap")){pool.cancel(user);user=null;}
        }
        var request=new Request(id,kind,phase,ordinal,due,pixel,user,causal==null?0:causal.millis(false));requests.put(id,request);
        if(kind.equals("write"))pendingWrites++;else pendingReads++;
        if(!reason.isEmpty()){notSent(request,reason);return Map.of("send",false,"reason",reason);}
        var response=new LinkedHashMap<String,Object>();response.put("send",true);response.put("id",id);response.put("due",due);response.put("end",end);response.put("expires",expires);
        response.put("lateMillis",Phase18Cap.scheduled(w)||phase.equals("initial-read")?Math.max(1,Phase18Cap.late(w,kind,phase)/1000000):21600000L);
        response.put("x",pixel[0]);response.put("y",pixel[1]);response.put("color",pixel[2]);response.put("epochMillis",clock.millis());
        if(user!=null){var token=tokens.issueAccess(users.get(user.ordinal()));Phase18Plan.require(token.getExpiresAt().toEpochMilli()>expires+30000,"fresh token covers final request");response.put("token",token.getTokenValue());}
        if(causal!=null){
            // token 준비까지 끝난 시점의 최종 송신 허가. 후속 claim은 원래 STOP/만료를 계속 거부
            try{app.check();}catch(Exception e){stop("app_control_stop");}
            String finalReason=reason(kind,clock.millis(),due,false,false);
            if(!finalReason.isEmpty()){
                if(user!=null&&!phase.equals("bootstrap"))pool.cancel(user);
                notSent(request,finalReason);return Map.of("send",false,"reason",finalReason);
            }
            response.put("authorized",true);
        }
        return response;
    }
    private String reason(String kind,long now,long due,boolean userMissing,boolean capacityMissing){
        if(firstFailure!=null||now>=expires)return "stopped";
        if(now>=end||(Phase18Cap.scheduled(w)||phase.equals("initial-read"))&&(now<due||now>=due+Math.max(1,Phase18Cap.late(w,kind,phase)/1000000)))return "arrival_late";
        return userMissing?"user_not_ready":capacityMissing?"processing_capacity":"";
    }
    private Object cancel(JsonNode input)throws Exception{
        Request request=requests.get(input.path("id").asText());Phase18Plan.require(request!=null&&!terminal.contains(request.id()),"cancel owned request");
        String why=input.path("reason").asText();Phase18Plan.require(Set.of("stopped","arrival_late","processing_capacity").contains(why),"pre-send cancellation reason");
        if(request.user()!=null&&!request.phase().equals("bootstrap"))pool.cancel(request.user());notSent(request,why);return Map.of("saved",true);
    }
    private void notSent(Request request,String reason)throws Exception{
        var row=base(request);row.put("outcome","NOT_SENT");row.put("reason",reason);row.put("observedEpochMillis",clock.millis());write(row);terminal.add(request.id());
        if(request.kind().equals("write"))pendingWrites--;else pendingReads--;
    }
    private Map<String,Object> base(Request r){
        var row=new LinkedHashMap<String,Object>();row.put("type","http");row.put("schema","phase18-k6-v2");row.put("id",r.id());row.put("kind",r.kind());row.put("phase",r.phase());row.put("ordinal",r.ordinal());row.put("dueEpochMillis",r.due());row.put("x",r.pixel()[0]);row.put("y",r.pixel()[1]);row.put("color",r.pixel()[2]);
        if(r.user()!=null){row.put("userOrdinal",r.user().ordinal());row.put("userId",users.get(r.user().ordinal()).toString());row.put("attempt",r.user().attemptOrdinal());}return row;
    }
    private Object result(JsonNode input)throws Exception{
        Request r=requests.get(input.path("id").asText());Phase18Plan.require(r!=null&&!terminal.contains(r.id()),"result for one owned request");
        var row=base(r);if(causal!=null){row.put("claimFloorMillis",r.claimFloorMillis());row.put("resultCeilingMillis",causal.millis(true));}
        long started=input.path("startedEpochMillis").asLong(),body=input.path("bodyEpochMillis").asLong();int status=input.path("status").asInt();
        row.put("observedStage",end>0&&clock.millis()>=end?"drain":phase);
        Phase18Plan.require(started>=anchor&&body>=started,"observed HTTP clock order");sent.add(r.id());
        String outcome="UNKNOWN",reason="unvalidated_response";boolean accepted=false;
        try {
            if(status==200&&r.kind().equals("write")){
                var value=Phase18Plan.JSON.readTree(input.path("body").asText());
                Phase18Plan.require(value.size()==6&&value.path("accepted").isBoolean()&&value.path("accepted").asBoolean(),"accepted DTO");
                for(String field:List.of("eventSeq","tileVersion","x","y","color"))Phase18Plan.require(value.path(field).isIntegralNumber()&&value.path(field).canConvertToLong(),"accepted integer");
                for(String field:List.of("x","y","color"))Phase18Plan.require(value.path(field).canConvertToInt(),"accepted coordinate");
                var event=new Phase18Events.Event(value.path("eventSeq").longValue(),value.path("tileVersion").longValue(),value.path("x").intValue(),value.path("y").intValue(),value.path("color").intValue());
                Phase18Plan.require(event.x()==r.pixel()[0]&&event.y()==r.pixel()[1]&&event.color()==r.pixel()[2],"accepted payload mapping");
                row.put("event",event);tail=Math.max(tail,event.eventSeq());accepted=true;
                if(!r.phase().equals("bootstrap")&&w.wsConnections()>0)wsEvents.accepted(event);
            }else if(status==200&&r.kind().equals("read")){
                Phase18Plan.require(input.path("decodedLength").asInt()==65536&&input.path("contentType").asText().equals("application/octet-stream")
                    &&input.path("encoding").asText().equals("gzip")&&input.path("cacheControl").asText().equals("no-store"),"decoded tile/header contract");
                String version=input.path("version").asText(),hash=input.path("sha256").asText();
                Phase18Plan.require(version.matches("0|[1-9][0-9]{0,18}")&&Long.parseLong(version)>=0&&hash.matches("[0-9a-f]{64}"),"tile version/content hash");
                row.put("tileVersion",version);row.put("decodedLength",65536);
                long global=Phase18Cap.readOrdinal(w,r.phase(),r.ordinal());
                if(Phase18ReadReplay.sampled(global,loaded.plan().counts(c).reads()))row.put("tileHash",hash);
                accepted=true;
            }else if(!r.phase().equals("bootstrap")&&Phase18Cap.rejection(r.kind(),status,Phase18Plan.JSON.readTree(input.path("body").asText("{}")))) {
                outcome="REJECTED";reason=status==503?"cap_write_busy":"cap_cooldown";
            }
        }catch(Exception ignored){accepted=false;reason="malformed_response";}
        if(accepted){outcome="ACCEPTED";reason="";if(r.phase().equals("initial-read"))initialSuccess.add(r.id());}
        long validated=clock.millis();row.put("startedEpochMillis",started);row.put("bodyEpochMillis",body);row.put("validationEpochMillis",validated);
        row.put("bridgeBeforeEpochMillis",input.path("bridgeBeforeEpochMillis").asLong());row.put("bridgeAfterEpochMillis",input.path("bridgeAfterEpochMillis").asLong());row.put("bridgeEpochMillis",input.path("bridgeEpochMillis").asLong());row.put("httpStatus",status);row.put("outcome",outcome);row.put("reason",reason);
        double duration=input.path("durationMillis").asDouble(-1);Phase18Plan.require(Double.isFinite(duration)&&duration>=0,"observed k6 HTTP duration");row.put("k6DurationMillis",duration);
        if(r.user()!=null&&!r.phase().equals("bootstrap"))pool.complete(r.user(),Math.max(body,validated)*1000000,outcome.equals("UNKNOWN"));
        write(row);terminal.add(r.id());
        if(r.kind().equals("write"))pendingWrites--;else pendingReads--;
        if(outcome.equals("UNKNOWN"))stop("unknown_http_result");
        return Map.of("saved",true,"accepted",accepted,"stop",firstFailure!=null,"validationEpochMillis",validated);
    }
    private Object ws(JsonNode input)throws Exception{
        int connection=input.path("connection").asInt(-1);Phase18Plan.require(connection>=0&&connection<w.wsConnections(),"planned WS connection");
        String event=input.path("event").asText();long at=input.path("epochMillis").asLong();
        Map<String,Object> row=null;
        if(!event.equals("message")){row=new LinkedHashMap<>();row.put("type","ws");row.put("connection",connection);row.put("event",event);row.put("epochMillis",at);row.put("receiveOrdinal",input.path("receiveOrdinal").asLong());row.put("observedEpochMillis",clock.millis());row.put("phase",phase);row.put("observedStage",draining||end>0&&clock.millis()>=end?"drain":phase);}
        long receiveOrdinal=input.path("receiveOrdinal").asLong();
        Phase18Plan.require(receiveOrdinal==++receiveOrdinals[connection],"WS callback sequence per connection");
        switch(event){
            case "open" -> {Phase18Plan.require((!w.bootstrap()||phase.equals("bootstrap")&&phaseFinished)&&opened.add(connection),"WS after bootstrap drain / unique buffer ready");}
            case "message" -> {Phase18Plan.require(opened.contains(connection)&&!closed.contains(connection)&&++deliveries<=deliveryLimit(loaded.plan()),"bounded live WS delivery");String body=input.path("body").asText();Phase18Plan.require(body.length()<=4096,"WS payload bound");var payload=Phase18Events.parse(Phase18Plan.JSON.readTree(body));int ordinal=wsEvents.received(connection,payload,at);if(causal!=null)causal.received(connection,ordinal,causalLower,causalUpper);}
            case "close" -> {Phase18Plan.require(opened.contains(connection)&&closed.add(connection),"one WS close");row.put("code",input.path("code").asInt());String reason=input.path("reason").asText();Phase18Plan.require(reason.length()<=256,"WS close reason bound");row.put("reason",reason);if(!wsClosing)stop("unexpected_ws_close");}
            case "error" -> stop("ws_error");
            default -> throw new IllegalArgumentException("WS event");
        }
        if(!event.equals("message"))write(row);return Map.of("saved",true);
    }
    private void requireInitialSet(){
        long count=Phase18Cap.count(w,"read","initial-read");Phase18Plan.require(initialSuccess.size()==count,"initial GET count");
        for(long i=0;i<count;i++)Phase18Plan.require(initialSuccess.contains(Phase18Plan.requestId(c,"read","initial-read",i)),"exact initial GET IDs");
    }
    Object finish(String name)throws Exception{
        Set<String> toDrain;long acceptedTail;
        synchronized(this){
        check();Phase18Plan.require(phase.equals(name)&&!phaseFinished&&!draining,"finish active phase");
        for(var r:requests.values())if(r.phase().equals(phase))Phase18Plan.require(terminal.contains(r.id()),"no unresolved request before drain");
        if(phase.equals("initial-read"))requireInitialSet();
        if(!phase.equals("bootstrap")&&seconds()>0)Phase18Plan.require(anchor>0&&clock.millis()>=end,"full phase period observed");
        if(phase.equals("bootstrap"))Phase18Plan.require(terminal.size()==1&&tail>0,"required bootstrap accepted");
        draining=true;toDrain=Set.copyOf(sent);acceptedTail=tail;
        }
        // HTTP 완료와 서버 소진 분리. 실제 앱의 기존 drain은 callback에서 재사용
        app.drain(toDrain,acceptedTail);
        synchronized(this){
        check();draining=false;phaseFinished=true;
        boundaries.add(Map.of("phase",phase,"event","drained","epochMillis",clock.millis(),"started",sent.size(),"plannedWrites",Phase18Cap.count(w,"write",phase),"plannedReads",Phase18Cap.count(w,"read",phase)));
        return Map.of("drained",true,"phase",phase,"durationComplete",true);
        }
    }
    private void write(Object value)throws Exception{
        String text=Phase18Plan.JSON.writeValueAsString(value)+"\n";long size=text.getBytes(StandardCharsets.UTF_8).length;
        Phase18Plan.require(bytes+size<=recordBytes(loaded.plan()),"required output byte limit");records.write(text);records.flush();bytes+=size;
    }
    public void close()throws Exception{
        server.stop(0);handlers.shutdown();
        if(!handlers.awaitTermination(Math.min(5000,Math.max(1,expires-clock.millis())),TimeUnit.MILLISECONDS))stop("bridge_handlers_unresolved");
        finishOutput();
    }
    private synchronized void finishOutput()throws Exception{
        Throwable failure=null;
        try{
            // 일정형은 원래 예정 ID 전부를 인계. 관측하지 않은 송신/완료 시각 생성 금지
            if(Phase18Cap.scheduled(w))for(String p:List.of("initial-read","warmup","measurement"))for(String kind:List.of("write","read")){
                long count=Phase18Cap.count(w,kind,p);
                for(long ordinal=0;ordinal<count;ordinal++){
                    String id=Phase18Plan.requestId(c,kind,p,ordinal);if(requests.containsKey(id))continue;
                    long offset=Phase18Cap.offset(w,kind,ordinal,p)/1000000;
                    var row=new LinkedHashMap<String,Object>();row.put("type","unstarted");row.put("id",id);row.put("kind",kind);row.put("phase",p);row.put("ordinal",ordinal);row.put("dueOffsetMillis",offset);row.put("reason",firstFailure==null?"not_started":"stopped_before_start");
                    if(anchors.containsKey(p)){long due=anchors.get(p)+offset;row.put("dueEpochMillis",due);row.put("futureAtClose",due>clock.millis());}write(row);
                }
            }
            records.close();
            try(var canonical=Files.newBufferedWriter(output.resolve("k6-ws-canonical.jsonl"),StandardOpenOption.CREATE_NEW)){
                for(int i=0;i<wsEvents.size();i++){canonical.write(Phase18Plan.JSON.writeValueAsString(wsEvents.event(i)));canonical.newLine();}
            }
            wsEvents.saveReceipts(output.resolve("k6-ws-receipts.bin"));
            wsEvents.closeTiming();if(causal!=null)causal.close();
        }catch(Exception e){failure=e;stop("required_output_close");try{records.close();wsEvents.closeTiming();}catch(Exception secondary){failure=Phase18Process.preserve(failure,secondary);}}
        var summary=new LinkedHashMap<String,Object>();summary.put("schema","phase18-k6-v2");summary.put("planHash",loaded.planHash());summary.put("phaseBoundaries",boundaries);summary.put("firstFailure",firstFailure);
        summary.put("planned",loaded.plan().counts(c));summary.put("started",sent.size());summary.put("terminal",terminal.size());summary.put("opened",opened);summary.put("closed",closed);summary.put("bytes",bytes);
        summary.put("unresolved",requests.values().stream().filter(r->!terminal.contains(r.id())).map(r->Map.of("id",r.id(),"kind",r.kind(),"phase",r.phase(),"dueEpochMillis",r.due(),"outcome","UNRESOLVED","sendState","NOT_OBSERVED")).toList());
        summary.put("producerComplete",firstFailure==null&&wsTransportComplete&&phase.equals("measurement")&&phaseFinished&&closed.equals(opened)&&opened.size()==w.wsConnections());
        summary.put("wsTransport",wsTransport);summary.put("wsBridge",Map.of("batches",wsBatchCount,"saved",wsSaved,"maxBatchNanos",wsMaxBatchNanos,"totalBatchNanos",wsTotalBatchNanos,"transportComplete",wsTransportComplete));
        summary.put("ws",wsEvents.report());summary.put("wsStorage","canonical + exact connection bitsets + epoch-millisecond int64 timing; no per-delivery JSON");
        summary.put("finalClassification","NOT_EVALUATED");summary.put("consistencyRecoveryConnected",false);
        try{BenchmarkJson.write(output.resolve("k6-producer.json"),summary);}catch(Exception e){failure=Phase18Process.preserve(failure,e);}
        Phase18Process.rethrow(failure);
    }
}
