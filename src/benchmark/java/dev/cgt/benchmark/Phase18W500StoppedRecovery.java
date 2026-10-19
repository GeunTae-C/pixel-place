package dev.cgt.benchmark;

import tools.jackson.databind.JsonNode;
import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.pixel.application.PixelWriteExecutor;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.*;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import dev.cgt.pixelplace.wal.infra.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static dev.cgt.benchmark.Phase18Plan.require;

/** 2026-10-15 W500 중단 원본만의 사본 진단. 과거 HTTP 수신·CAP 판정 복원 책임 없음 */
public final class Phase18W500StoppedRecovery {
    private static final String PLAN_HASH = "b43d77dfc460fe28cd06be1f00c7ac8e67c1c99635eb9b612d1f74f04dfdb6a3";
    private static final String WAL_HASH = "e1cda253afefc0271bace978bd42db7deea08e2e58313072635fc06a7a591d30";
    private static final String SESSION = "cap1-ws-diagnostic-20261015-01";
    private static long expiry;

    /** 원본 입력 고정 → 사본 전수 대조 → 기존 startup recovery → 전수 정합성/정상 종료 */
    public static void main(String[] args) throws Exception {
        require(args.length == 1, "frozen diagnostic expiry required");
        expiry = Long.parseLong(args[0]); check();
        Path original = Path.of("F:/pixel-place-work/evidence/phase18-cap1-w500-20261015-084327");
        Path clone = Path.of("F:/pixel-place-work/sessions", SESSION, "mysql");
        Path wal = Path.of("F:/pixel-place-wal/phase18", SESSION, "diagnostic/pixel-place.wal");
        Path proofRoot = Path.of("F:/pixel-place-work/evidence", SESSION);
        Path output = Path.of("F:/pixel-place-work/evidence/cap1-ws-diagnostic-20261015-02");
        require(BenchmarkJson.hash(original.resolve("plan-cap.json")).equals(PLAN_HASH), "specific W500 Plan");
        var plan = Phase18Plan.JSON.readValue(Files.readString(original.resolve("plan-cap.json")), Phase18Plan.class);
        var c = plan.cases().getFirst();
        require(plan.ownership().catalog().equals("pixel_place_bench_cap1_w500_20261015_084327"), "protected catalog identity");
        for (var input : plan.inputs()) {
            if (input.path().startsWith("src/main/")) {
                require(BenchmarkJson.hash(Path.of(plan.ownership().repo(), input.path())).equals(input.sha256()),
                        "original production source binding");
            }
        }
        Phase18Paths.execution(clone.toString()); Phase18Paths.execution(wal.toString());
        require(BenchmarkJson.hash(wal).equals(WAL_HASH), "exact preserved WAL copy before recovery");
        var copy = Phase18Plan.JSON.readTree(Files.readString(proofRoot.resolve("mysql-cold-copy.json")));
        require(copy.path("complete").asBoolean() && copy.path("sourceFrozen").asBoolean()
                && copy.path("originalUnchanged").asBoolean() && copy.path("destination").asText().equals(clone.toString().replace('\\','/')),
                "complete frozen copy evidence");
        var props = new WalProperties(); props.setActiveFile(wal); props.setMaxSegmentBytes(c.runtime().segmentBytes());
        List<WalRecord> records; long tail;
        var meter = new SimpleMeterRegistry();
        try (var storage = new SegmentedWalStorage(props, new WalRecordParser(Phase18Plan.JSON),
                new WalRecordJsonCodec(Phase18Plan.JSON), new PixelMeasurement(false, meter), new WindowsWalFileDurability())) {
            var batch = storage.readAfter(0); records = batch.records(); tail = batch.walLastEventSeq();
        } finally { meter.close(); }
        // 전체 retained 파일은 1개이며 삭제 이력 없는 원본 hash로 동결. seq gap 자체를 금지하는 일반 정책 아님
        require(records.size() == 53468 && tail == 53468, "specific original full WAL count/tail");
        long prior = 0; int gaps = 0; var byPayload = new HashMap<String, WalRecord>();
        var usersInWal = new HashSet<Long>();
        for (var r : records) {
            require(r.eventSeq() > prior, "strict WAL sequence");
            if (r.eventSeq() != prior + 1) gaps++;
            prior = r.eventSeq();
            require(byPayload.put(key(r.x(), r.y(), r.color()), r) == null, "ambiguous durable payload");
            require(usersInWal.add(r.userId()), "unexpected user reuse within this original run");
        }
        Path initial = original.resolve(c.runId()).resolve("initial");
        var producer = Phase18Plan.JSON.readTree(Files.readString(initial.resolve("k6-producer.json")));
        var server = Phase18Plan.JSON.readTree(Files.readString(initial.resolve("server-closed.json")));
        require(!producer.path("producerComplete").asBoolean() && producer.path("firstFailure").asText().equals("app_control_stop")
                && server.path("schedulerTerminated").asBoolean(), "preserved original failure and server closure");
        var accepted = new ArrayList<JsonNode>(); var notSent = new ArrayList<JsonNode>();
        var terminalIds = new HashSet<String>(); var wsOpenConnections = new HashSet<Integer>();
        try (var rows = Files.newBufferedReader(initial.resolve("k6-records.jsonl"))) {
            String line;
            while ((line = rows.readLine()) != null) {
                check(); require(line.length() < 65536, "bounded HTTP row");
                var row = Phase18Plan.JSON.readTree(line);
                if (row.path("type").asText().equals("ws")) {
                    int connection = row.path("connection").asInt(-1);
                    require(row.path("event").asText().equals("open") && row.path("receiveOrdinal").asLong() == 1
                            && connection >= 0 && connection < 100 && wsOpenConnections.add(connection),
                            "original WS control rows separate from HTTP");
                    continue;
                }
                require(row.path("type").asText().equals("http") && !row.path("id").asText().isEmpty(),
                        "known original HTTP row type and ID");
                require(terminalIds.add(row.path("id").asText()), "unique terminal ID");
                if (row.path("outcome").asText().equals("ACCEPTED")) accepted.add(row);
                else { require(row.path("outcome").asText().equals("NOT_SENT"), "original terminal classification"); notSent.add(row); }
            }
        }
        require(wsOpenConnections.size() == 100 && accepted.size() == 53423 && notSent.size() == 55 && producer.path("unresolved").size() == 45,
                "specific original HTTP classifications");
        // 원본 Plan 전체 할당에서 모호성 검사. unresolved ID는 원시 배열에서만 읽음
        var allocations = new HashMap<String, String>(); var duplicateAllocations = new HashSet<String>();
        for (String phase : List.of("bootstrap", "warmup", "measurement")) {
            long count = phase.equals("bootstrap") ? 1 : Phase18Cap.count(c.workload(), "write", phase);
            for (long n = 0; n < count; n++) {
                int[] pixel = Phase18LoadClient.pixel(c, phase, n);
                String key = key(pixel[0], pixel[1], pixel[2]);
                if (allocations.put(key, Phase18Plan.requestId(c, "write", phase, n)) != null) duplicateAllocations.add(key);
            }
        }
        var explained = new HashSet<Long>(); var requestIds = new HashSet<String>();
        for (var row : accepted) {
            var r = byPayload.get(key(row.path("x").asInt(), row.path("y").asInt(), row.path("color").asInt()));
            require(r != null && r.eventSeq() == row.path("event").path("eventSeq").asLong()
                    && r.userId() == row.path("userId").asLong() && explained.add(r.eventSeq()), "every accepted effect exact");
            validateAssignment(c, row); require(requestIds.add(row.path("id").asText()), "one accepted ID");
        }
        var diagnoses = new ArrayList<Map<String,Object>>();
        for (var row : producer.path("unresolved")) {
            String id = row.path("id").asText(); String phase = row.path("phase").asText();
            require(phase.equals("measurement") && row.path("kind").asText().equals("write")
                    && row.path("outcome").asText().equals("UNRESOLVED") && !terminalIds.contains(id) && requestIds.add(id), "original unresolved ID");
            long ordinal = Long.parseLong(id.substring(id.lastIndexOf('/') + 1));
            require(ordinal >= 0 && ordinal < Phase18Cap.count(c.workload(),"write",phase)
                    && id.equals(Phase18Plan.requestId(c,"write",phase,ordinal)), "exact unresolved Plan identity");
            int[] pixel = Phase18LoadClient.pixel(c, phase, ordinal); String key = key(pixel[0],pixel[1],pixel[2]);
            var r = byPayload.get(key);
            require(!duplicateAllocations.contains(key) && id.equals(allocations.get(key)) && r != null
                    && explained.add(r.eventSeq()), "unique planned unresolved durable effect");
            var timing = server.path("requests").path(id);
            require(timing.path("started").asLong() > 0 && timing.path("completed").asLong() >= timing.path("started").asLong(),
                    "original server request timing; not response evidence");
            var d = new LinkedHashMap<String,Object>();
            d.put("id",id); d.put("phase",phase); d.put("ordinal",ordinal); d.put("x",pixel[0]); d.put("y",pixel[1]); d.put("color",pixel[2]);
            d.put("effect","identified_effect_exists"); d.put("wal",r);
            d.put("uniqueAmongAllPlannedWrites",true); d.put("historicalHttpResponse","UNRESOLVED");
            d.put("assignmentBasis","unique original Plan payload; user identity from validated WAL and preserved fixture, not assumed claim order");
            diagnoses.add(d);
        }
        require(explained.size() == records.size(), "no unexplained WAL effects");
        var serverIds = new HashSet<String>(); server.path("requests").properties().forEach(entry -> serverIds.add(entry.getKey()));
        require(serverIds.equals(requestIds), "all server-request IDs explained separately from HTTP response");
        for (var row : notSent) {
            validateAssignment(c,row);
            String key = key(row.path("x").asInt(),row.path("y").asInt(),row.path("color").asInt());
            require(!duplicateAllocations.contains(key) && !byPayload.containsKey(key)
                    && !serverIds.contains(row.path("id").asText()), "NOT_SENT absence in complete WAL and server requests");
        }
        var credentials = BenchmarkEnvironment.credentials();
        require(credentials.dbHost().equals("127.0.0.1") && credentials.dbPort() == 3307 && credentials.redisDatabase() == 2,
                "isolated clone and verified Redis index");
        try (var fixture = new BenchmarkFixtures(credentials,plan.ownership().catalog(),true,clone)) {
            fixture.requireOwnedStorage(); fixture.fixtureFiles();
            var users = new ArrayList<Long>(); var userOrdinals = new HashMap<Long,Integer>();
            try (var db = fixture.connect(true); var st = db.createStatement();
                 var rows = st.executeQuery("SELECT id,kakao_user_id FROM users ORDER BY kakao_user_id")) {
                while(rows.next()) {
                    require(rows.getLong(2) == 9_018_000_000_000L + users.size(), "preserved fixture order");
                    require(userOrdinals.put(rows.getLong(1),users.size()) == null, "unique fixture identity"); users.add(rows.getLong(1));
                }
            }
            require(users.size() == plan.counts(c).users(), "whole original user fixture");
            for (var row : accepted) require(users.get(row.path("userOrdinal").asInt()) == row.path("userId").asLong()
                    && row.path("attempt").asInt() == 1, "original accepted user assignment");
            var before = database(fixture,records,tail); BenchmarkJson.write(output.resolve("database-before-recovery.json"),before);
            for (var d : diagnoses) {
                var r = (WalRecord)d.get("wal"); require(userOrdinals.containsKey(r.userId()),"unresolved fixture user");
                d.put("userOrdinal",userOrdinals.get(r.userId())); d.put("databaseBefore",r.eventSeq() <= (long)before.get("checkpoint"));
                d.remove("wal"); d.put("eventSeq",r.eventSeq()); d.put("userId",r.userId());
            }
            BenchmarkJson.write(output.resolve("request-effects-before-recovery.json"),Map.of("confirmedAccepted",accepted.size(),
                    "requests",diagnoses,"notSent",notSent.size(),"unexplainedEffects",0,"walRecords",records.size(),"tail",tail,"observedSeqGaps",gaps));
            var writes = new ArrayList<Phase18Consistency.Accepted>(); var versions = new HashMap<String,Long>();
            for (var r : records) {
                require(userOrdinals.containsKey(r.userId()),"all WAL users in fixture");
                long version = versions.merge(r.tx()+"/"+r.ty(),1L,Long::sum);
                writes.add(new Phase18Consistency.Accepted(r.eventSeq(),version,r.x(),r.y(),r.color(),userOrdinals.get(r.userId())));
            }
            var properties = BenchmarkEnvironment.properties(credentials,plan.ownership().catalog(),wal,BenchmarkEnvironment.keys(),c.runtime().measurement());
            properties.put("pixel-place.write.mode",c.runtime().mode());
            properties.put("pixel-place.write.group.max-batch-size",16); properties.put("pixel-place.write.group.max-outstanding",128);
            properties.put("pixel-place.write.group.queue-timeout","1s"); properties.put("pixel-place.write.group.shutdown-grace","10s");
            properties.put("pixel-place.wal.max-segment-bytes",c.runtime().segmentBytes());
            properties.put("pixel-place.flush.fixed-delay","1s");
            var app = BenchmarkEnvironment.application(properties); app.addPrimarySources(List.of(Phase18K6Auth.class));
            Map<String,Object> consistency;
            try (var context = app.run()) {
                var measurement = context.getBean(PixelMeasurement.class); long until = Math.min(expiry-180000,System.currentTimeMillis()+180000);
                long idle=0; boolean drained=false;
                while(System.currentTimeMillis()<until) {
                    var exec=context.getBean(PixelWriteExecutor.class).snapshot();
                    boolean pending=context.getBean(PendingAmbiguousFlushStore.class).current().isPresent();
                    if(measurement.activeCommands()==0 && !pending && Phase15BenchmarkMain.executorIdle(exec)
                            && context.getBean(ServiceReadiness.class).isReady()) {
                        if(idle==0) idle=System.nanoTime();
                        var capture=measurement.lastCapture();
                        if(capture!=null && capture.observedNanos()>=idle && capture.records()==0 && capture.checkpoint()==tail
                                && capture.tail()==tail && fixture.checkpoint()==tail) {
                            drained=true; BenchmarkJson.write(output.resolve("recovery-drain.json"),Map.of("capture",capture,"executor",exec,"pending",false,"ready",true)); break;
                        }
                    } else idle=0;
                    Thread.sleep(25);
                }
                require(drained,"fresh JVM recovery and observed drain");
                consistency=Phase18Consistency.verify(fixture,context,users,writes,List.of(),0);
                require(measurement.walCounts().completedRecords()==0,"recovery appended no new requests");
                BenchmarkJson.write(output.resolve("database-after-recovery.json"),database(fixture,records,tail));
            }
            for(var d:diagnoses) d.put("databaseAfter",true);
            BenchmarkJson.write(output.resolve("recovery-diagnostic.json"),Map.ofEntries(
                    Map.entry("diagnosticOnly",true),Map.entry("originalPlanHash",PLAN_HASH),Map.entry("originalClassification","INVALID_OR_STOPPED"),
                    Map.entry("confirmedAccepted",accepted.size()),Map.entry("requests",diagnoses),Map.entry("notSent",notSent.size()),
                    Map.entry("notSentEffectAbsence",true),Map.entry("unexplainedEffects",0),Map.entry("serverDurableEvents",records.size()),
                    Map.entry("newJvmPid",ProcessHandle.current().pid()),Map.entry("consistency",consistency),
                    Map.entry("normalContextClose",true),Map.entry("ddlOrReseed",false),Map.entry("historicalHttpResponse","UNRESOLVED")));
        }
    }

    private static void check() { require(System.currentTimeMillis() < expiry-180000,"diagnostic original expiry and cleanup reserve"); }
    private static String key(int x,int y,int color) { return x+"/"+y+"/"+color; }
    private static void validateAssignment(Phase18Plan.Case c,JsonNode row) {
        String phase=row.path("phase").asText(); long n=row.path("ordinal").asLong(-1);
        require(Set.of("bootstrap","warmup","measurement").contains(phase) && n>=0
                && n<(phase.equals("bootstrap")?1:Phase18Cap.count(c.workload(),"write",phase))
                && row.path("id").asText().equals(Phase18Plan.requestId(c,"write",phase,n)), "HTTP Plan assignment");
        int[] pixel=Phase18LoadClient.pixel(c,phase,n);
        require(pixel[0]==row.path("x").asInt() && pixel[1]==row.path("y").asInt()
                && pixel[2]==row.path("color").asInt(), "HTTP allocated payload");
    }

    /** 완전한 원본 WAL과 DB checkpoint prefix 및 1024 tile bytes/version의 전수 대조 */
    private static Map<String,Object> database(BenchmarkFixtures fixture,List<WalRecord> records,long tail) throws Exception {
        check(); long checkpoint=fixture.checkpoint(); require(checkpoint>=0 && checkpoint<=tail,"checkpoint within complete WAL");
        var remaining=new HashMap<Long,WalRecord>(); for(var r:records) if(r.eventSeq()<=checkpoint) remaining.put(r.eventSeq(),r);
        var pixels=new HashMap<TileKey,byte[]>(); var versions=new HashMap<TileKey,Long>();
        for(var k:new CanonicalZ0TileKeys().orderedKeys()) { pixels.put(k,TileState.allWhite().pixels()); versions.put(k,0L); }
        int events=0,tiles=0; long last=0;
        try(var db=fixture.connect(true);var st=db.createStatement()) {
            try(var rows=st.executeQuery("SELECT event_seq,user_id,z,tx,ty,x,y,color,created_at FROM pixel_events ORDER BY event_seq")) {
                while(rows.next()) {
                    check(); var r=remaining.remove(rows.getLong(1));
                    require(r!=null && r.eventSeq()>last && r.userId()==rows.getLong(2) && r.z()==rows.getInt(3)
                            && r.tx()==rows.getInt(4) && r.ty()==rows.getInt(5) && r.x()==rows.getInt(6) && r.y()==rows.getInt(7)
                            && r.color()==rows.getInt(8) && r.createdAt().truncatedTo(ChronoUnit.MILLIS).equals(rows.getObject(9,LocalDateTime.class)),
                            "DB exact WAL prefix including original timestamp");
                    last=r.eventSeq(); var k=new TileKey(0,r.tx(),r.ty());
                    pixels.get(k)[r.y()%256*256+r.x()%256]=(byte)r.color(); versions.merge(k,1L,Long::sum); events++;
                }
            }
            require(remaining.isEmpty() && last==checkpoint,"checkpoint covers exactly all prefix effects");
            var seen=new HashSet<TileKey>();
            try(var rows=st.executeQuery("SELECT z,tx,ty,data,tile_version FROM tiles")) {
                while(rows.next()) {
                    var k=new TileKey(rows.getInt(1),rows.getInt(2),rows.getInt(3));
                    require(pixels.containsKey(k) && seen.add(k) && Arrays.equals(rows.getBytes(4),pixels.get(k))
                            && rows.getLong(5)==versions.get(k),"DB all tile bytes and versions");
                    tiles++;
                }
            }
        }
        require(tiles==1024,"canonical DB tile set");
        return Map.of("checkpoint",checkpoint,"events",events,"tiles",tiles,"exactWalPrefix",true,"tileBytesAndVersions",true,"walTail",tail);
    }
}
