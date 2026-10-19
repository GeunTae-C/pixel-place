package dev.cgt.benchmark;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 18 전용 동결 입력. 계획량은 결과 행과 무관하며 기존 15/17 guard를 변경하지 않음 */
public record Phase18Plan(int schemaVersion, String phase, String stage, String state, String sessionId,
        String sourceBaseline, Frozen predecessor, List<Frozen> inputs, Ownership ownership,
        Bounds bounds, List<Case> cases) {
    public static final int VERSION = 2;
    public static final long TERMINALS = 200_000, SAMPLES = 10_000, WS_EVENTS = 200_000;
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();

    public Phase18Plan { inputs = List.copyOf(inputs); cases = List.copyOf(cases); }
    public record Frozen(String path, String sha256) { }
    public record Ownership(String repo, String java, String sessionRoot, String evidenceRoot,
            String build, String cache, String temp, String jna, String wal,
            String dbInstance, String catalog, int redisIndex, String owner) { }
    public record Bounds(long writeTerminals, long readTerminals, long users, long wsEvents, long samples,
            long memoryBytes, long outputBytes, long walBytes, long databaseBytes, long binlogBytes,
            long buildBytes, long minimumFreeBytes, long totalSeconds, long logBytesPerStream) { }
    public record Runtime(String mode, int batchMax, int outstandingMax, long queueMillis,
            long shutdownMillis, long segmentBytes, long flushMillis, boolean measurement,
            List<String> jvmOptions, int innodbFlushLogAtTrxCommit, int syncBinlog) {
        public Runtime { jvmOptions = List.copyOf(jvmOptions); }
    }
    public record Observation(long sampleMillis, long maxAgeMillis, long maxMissingMillis,
            long stopAckMillis, List<String> required, List<String> auxiliary, String fallback, String stopRule) {
        public Observation { required = List.copyOf(required); auxiliary = List.copyOf(auxiliary); }
    }
    public record Workload(long writeRate, long readRate, String writePattern, String readPattern, long seed,
            long wsConnections, long warmupSeconds, long measurementSeconds, long writeInFlight,
            long readInFlight, long requestMillis, long readyMillis, long controlMillis, long drainMillis,
            String userMode, long userPool, long reuseMarginMillis, long inputTolerancePpm,
            long inputWindowSeconds, long simultaneousSeconds, boolean bootstrap, Phase18Cap.Schedule schedule) {
        public Workload(long writeRate,long readRate,String writePattern,String readPattern,long seed,long wsConnections,
                long warmupSeconds,long measurementSeconds,long writeInFlight,long readInFlight,long requestMillis,long readyMillis,
                long controlMillis,long drainMillis,String userMode,long userPool,long reuseMarginMillis,long inputTolerancePpm,
                long inputWindowSeconds,long simultaneousSeconds,boolean bootstrap) {
            this(writeRate,readRate,writePattern,readPattern,seed,wsConnections,warmupSeconds,measurementSeconds,writeInFlight,
                readInFlight,requestMillis,readyMillis,controlMillis,drainMillis,userMode,userPool,reuseMarginMillis,inputTolerancePpm,
                inputWindowSeconds,simultaneousSeconds,bootstrap,Phase18Cap.Schedule.legacy());
        }
    }
    public record Case(String caseId, String runId, int repeatIndex, String role, String kind,
            String plannedStartUtc, Workload workload, Runtime runtime, Observation observation) { }
    public record Counts(long warmupWrites, long measurementWrites, long warmupReads, long measurementReads,
            long bootstrap, long initialReads, long users, long samples, long wsEvents, long wsDeliveries, long wsBitsetBytes,
            long memoryBytes, long diskBytes, long seconds) {
        public long writes() { return Math.addExact(bootstrap, Math.addExact(warmupWrites, measurementWrites)); }
        public long reads() { return Math.addExact(initialReads,Math.addExact(warmupReads, measurementReads)); }
    }
    public record Loaded(Phase18Plan plan, String planHash) { }

    /** 읽기 전용 검사. 파일 생성·서비스 접속 없이 입력 전체와 해시 집합 대조 */
    public static Loaded read(Path path) throws Exception {
        Phase18Paths.execution(path.toString());
        require(Files.size(path) <= 1_048_576, "plan bytes");
        byte[] bytes;
        try (var in = Files.newInputStream(path)) { bytes = in.readNBytes(1_048_577); }
        require(bytes.length <= 1_048_576, "plan grew");
        Phase18Plan plan;
        try {
            JsonNode tree = JSON.readTree(bytes);
            exactNumbers(tree);
            if(tree.path("schemaVersion").asInt()==2)for(JsonNode item:tree.path("cases")) {
                var workload=(tools.jackson.databind.node.ObjectNode)item.path("workload");
                if(!workload.has("schedule"))workload.set("schedule",JSON.valueToTree(Phase18Cap.Schedule.legacy()));
            }
            plan = JSON.treeToValue(tree, Phase18Plan.class);
        } catch (RuntimeException invalid) {
            // parser 원문/비밀 입력을 오류 로그에 복제하지 않음
            throw new IllegalArgumentException("Invalid phase18 JSON shape or integer encoding");
        }
        plan.validate();
        plan.verifyInputs();
        return new Loaded(plan, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private static void exactNumbers(JsonNode node) {
        require(node != null && !node.isNull(), "null field");
        if (node.isNumber()) require(node.isIntegralNumber() && node.canConvertToLong(), "integer or overflow");
        if (node.isArray() || node.isObject()) for (JsonNode child : node) exactNumbers(child);
    }

    /** 40,000을 넘는 18 입력도 유한한 자체 상한 안에서만 수용 */
    public void validate() throws Exception {
        require(schemaVersion == VERSION && Set.of("A", "B", "C", "D").contains(phase) || Phase18Cap.enabled(this), "version/phase");
        require(Set.of("A-1", "A-2", "A-3", "A-4", "B", "C", "D", "CAP-0", "CAP-1", "CAP-2", "CAP-3").contains(stage), "stage");
        require(stage.startsWith(phase)&&stage.startsWith("CAP-")==Phase18Cap.enabled(this), "phase/stage");
        require(Set.of("draft", "ready").contains(state), "state"); id(sessionId);
        require(sourceBaseline.matches("[0-9a-f]{40}"), "baseline");
        require(!inputs.isEmpty() && inputs.size() <= 4096 && !cases.isEmpty() && cases.size() <= 100, "input/case count");
        Path repo = Phase18Paths.execution(ownership.repo), root = Phase18Paths.execution(ownership.sessionRoot);
        Path evidence = Phase18Paths.execution(ownership.evidenceRoot), java = Phase18Paths.execution(ownership.java);
        require(root.equals(Phase18Paths.execution("F:/pixel-place-work/sessions/" + sessionId)), "current F session root");
        require(Files.isDirectory(repo) && Files.isRegularFile(java) && java.getFileName().toString().equalsIgnoreCase("java.exe"), "repo/JDK");
        require(repo.equals(Path.of("").toAbsolutePath()), "actual repository");
        require(root.getFileName().toString().equals(sessionId) && evidence.getFileName().toString().equals(sessionId), "session ownership");
        require(sessionId.startsWith("phase18-"), "session prefix");
        require(evidence.equals(Phase18Paths.execution("F:/pixel-place-work/evidence/" + sessionId)), "current F evidence ownership");
        require(!root.startsWith(repo) && !repo.startsWith(root), "separate working output root");
        var areas = new ArrayList<Path>();
        for (String p : List.of(ownership.build, ownership.cache, ownership.temp, ownership.jna)) {
            Path area = Phase18Paths.checked(p); Phase18Paths.child(area, root); areas.add(area);
        }
        require(Path.of(ownership.build).equals(root.resolve("build")) && Path.of(ownership.cache).equals(root.resolve("project-cache"))
                && Path.of(ownership.temp).equals(root.resolve("temp")) && Path.of(ownership.jna).equals(root.resolve("jna")), "current F work areas");
        areas.add(evidence); Phase18Paths.disjoint(areas);
        require(!ownership.owner.isBlank() && !ownership.owner.contains("TODO"), "owner");
        if (!ownership.wal.isEmpty()) {
            Path wal = Phase18Paths.checked(ownership.wal);
            Path walRoot = Phase18Paths.walRoot(ownership.wal, repo, sessionId);
            // F 새 배치는 run별 파일군만 허용. session/build/evidence와 정리 경계 중첩 금지
            if (wal.getRoot().toString().equalsIgnoreCase("F:\\"))
                require(cases.stream().anyMatch(c -> wal.equals(walRoot.resolve(c.runId).resolve("pixel-place.wal"))), "F WAL run ownership");
            Phase18Paths.disjoint(List.of(root, evidence, walRoot));
            Phase18Paths.disjoint(List.of(root, repo.resolve("data/wal"), Path.of("F:/pixel-place-wal/runtime")));
            require(Phase18Paths.store(wal).type().equalsIgnoreCase("NTFS"), "actual WAL NTFS volume");
        }
        if (!ownership.catalog.isEmpty()) {
            require(ownership.catalog.startsWith("pixel_place_bench_"), "catalog");
            BenchmarkGuards.catalog(ownership.catalog.substring(18));
        }
        require(ownership.redisIndex == -1 || ownership.redisIndex >= 2 && ownership.redisIndex <= 15, "Redis isolation");
        for (long n : new long[]{bounds.writeTerminals, bounds.readTerminals, bounds.users, bounds.wsEvents, bounds.samples,
                bounds.memoryBytes, bounds.outputBytes, bounds.buildBytes, bounds.minimumFreeBytes, bounds.totalSeconds, bounds.logBytesPerStream})
            require(n > 0, "positive budget");
        require(bounds.walBytes >= 0 && bounds.databaseBytes >= 0 && bounds.binlogBytes >= 0, "storage budget");
        require(bounds.writeTerminals <= (Phase18Cap1.enabled(this)?Phase18Cap1.MAX_TERMINALS:TERMINALS) && bounds.readTerminals <= TERMINALS && bounds.users <= TERMINALS
                && bounds.wsEvents <= (Phase18Cap1.enabled(this)?Phase18Cap1.MAX_EVENTS:WS_EVENTS) && bounds.samples <= (Phase18Cap1.enabled(this)?Phase18Cap1.limit(SAMPLES,Phase18Cap1.output(this).samples()):Phase18K6Session.expandedS2(this)?50_000:SAMPLES), "allocation ceiling");
        if(Phase18K6Session.expandedS2(this)){
            var usage=Phase18K6Session.s2Output(this);
            require(bounds.samples==Phase18K6Session.expandLimit(SAMPLES,usage.samples())
                    &&bounds.outputBytes==Phase18K6Session.expandLimit(268435456,usage.runBytes()),"expanded S2 plan limits");
        }
        require(bounds.memoryBytes <= 536_870_912L && bounds.totalSeconds <= 86_400 && bounds.logBytesPerStream <= 1_048_576, "hard ceiling");
        if(Phase18Cap1.enabled(this)){
            // 자식 hard limit의 합을 실제 추가 점유로 대입하지 않는다. 각 hard guard는 유지한다.
            Path walArea=ownership.wal.isEmpty()?null:Phase18Paths.walRoot(ownership.wal,repo,sessionId);
            // Plan에는 DB baseline이 없으므로 DB U는 보수적으로 전량 예약. live Environment는 실제 증가분을 뺀다.
            long remaining=Phase18Cap1.remainingPhysicalF(this,Files.exists(root)?BenchmarkPaths.bytes(root):0,0,
                walArea!=null&&Files.exists(walArea)?BenchmarkPaths.bytes(walArea):0,Files.exists(evidence)?BenchmarkPaths.bytes(evidence):0);
            require(Phase18Paths.usable(evidence)>=Math.addExact(bounds.minimumFreeBytes,remaining),"CAP1 shared F physical peak");
            require(Phase18Paths.usable(root)>=Math.addExact(bounds.minimumFreeBytes,remaining),"CAP1 F physical peak");
            if(!ownership.wal.isEmpty())require(Phase18Paths.usable(Path.of(ownership.wal))>=Math.addExact(bounds.minimumFreeBytes,remaining),"CAP1 WAL physical peak");
        }else{
        long totalDisk = Math.addExact(bounds.outputBytes, Math.addExact(bounds.buildBytes,
                Math.addExact(bounds.walBytes, Math.addExact(bounds.databaseBytes, bounds.binlogBytes))));
        require(totalDisk <= 32L * 1024 * 1024 * 1024, "total disk reservation");
        for (Path p : List.of(root, evidence))
            require(Phase18Paths.usable(p) >= Math.addExact(bounds.minimumFreeBytes, totalDisk), "free space reservation");
        if (!ownership.wal.isEmpty())
            require(Phase18Paths.usable(Path.of(ownership.wal)) >= Math.addExact(bounds.minimumFreeBytes, totalDisk), "WAL volume free space reservation");
        }
        var caseIds = new HashSet<String>(); var runIds = new HashSet<String>(); long seconds = 0, disk = 0;
        for (Case c : cases) {
            id(c.caseId); id(c.runId); require(caseIds.add(c.caseId) && runIds.add(c.runId), "duplicate case/run");
            require(c.repeatIndex > 0 && c.repeatIndex <= 100, "repeat");
            require((Phase18Cap.enabled(this)?Set.of("cap-smoke-s1","cap-smoke-s2","cap-target","cap-diagnostic-ab"):Set.of("pilot", "target", "diagnostic", "soak")).contains(c.role), "role");
            require(Set.of("tools", "prepare", "write", "read", "mixed", "soak").contains(c.kind), "kind");
            if (state.equals("ready") && !c.kind.equals("tools"))
                require(!ownership.wal.isEmpty() && !ownership.dbInstance.isBlank() && !ownership.catalog.isEmpty()
                        && ownership.redisIndex >= 2, "required field environment");
            Instant.parse(c.plannedStartUtc);
            Workload w = c.workload;
            require(w.writeRate >= 0 && w.readRate >= 0 && w.writeRate <= 10_000 && w.readRate <= 10_000, "rate");
            require(w.warmupSeconds >= 0 && w.measurementSeconds > 0, "duration");
            require(Set.of("same-pixel", "spread-tiles").contains(w.writePattern) && Set.of("hot", "spread").contains(w.readPattern), "pattern");
            require(w.wsConnections >= 0 && w.wsConnections <= (Phase18Cap.enabled(this)?3200:100), "WS connections");
            require(w.writeInFlight >= 0 && w.writeInFlight <= 128 && w.readInFlight >= 0 && w.readInFlight <= 128,
                    "in flight ceiling");
            require((w.writeRate == 0 || w.writeInFlight > 0) && (w.readRate == 0 || w.readInFlight > 0), "dispatch capacity");
            for (long t : new long[]{w.requestMillis, w.readyMillis, w.controlMillis, w.drainMillis, w.reuseMarginMillis})
                require(t > 0 && t <= 600_000, "deadline");
            require(Set.of("once", "reuse").contains(w.userMode) && w.userPool >= 0 && w.userPool <= bounds.users, "user mode/pool");
            require(w.inputTolerancePpm >= 0 && w.inputTolerancePpm <= 1_000_000 && w.inputWindowSeconds > 0
                    && w.inputWindowSeconds <= w.measurementSeconds && w.simultaneousSeconds >= 0
                    && w.simultaneousSeconds <= w.measurementSeconds, "input fulfillment");
            require(!c.kind.equals("read") || w.writeRate == 0 && w.readRate > 0 && !w.bootstrap, "read only");
            require(!c.kind.equals("write") || w.writeRate > 0 && w.readRate == 0, "write case");
            require(Phase18Cap.enabled(this) || !c.kind.equals("mixed") || w.writeRate > 0 && w.readRate > 0 && w.simultaneousSeconds > 0, "mixed interval");
            require(!c.role.equals("target") || w.writeRate > 0, "write target");
            Runtime r = c.runtime;
            require(Set.of("group", "single").contains(r.mode) && r.batchMax == 16 && r.outstandingMax == 128
                    && r.queueMillis == 1000 && r.shutdownMillis == 10_000 && r.segmentBytes > 0 && r.flushMillis == 1000,
                    "runtime invariant");
            require(r.innodbFlushLogAtTrxCommit == 1 && r.syncBinlog == 1, "database durability");
            require(r.jvmOptions.equals(List.of("-Xmx512m", "-XX:+UseG1GC")), "bounded JVM options");
            Observation o = c.observation;
            for (long t : new long[]{o.sampleMillis, o.maxAgeMillis, o.maxMissingMillis, o.stopAckMillis})
                require(t > 0 && t <= 600_000, "observation deadline");
            require(!o.required.isEmpty() && !o.fallback.isBlank() && !o.stopRule.isBlank(), "observation contract");
            for (String text : List.of(o.fallback, o.stopRule, ownership.dbInstance))
                require(!state.equals("ready") || !text.toUpperCase(Locale.ROOT).contains("TODO"), "ready placeholder");
            require(new HashSet<>(o.required).size() == o.required.size() && Collections.disjoint(o.required, o.auxiliary), "metric names");
            require(Phase18Cap.enabled(this)||w.schedule.equals(Phase18Cap.Schedule.legacy()),"legacy schedule invariant");
            if(Phase18Cap.enabled(this))Phase18Cap.validate(this,c);
            Counts n = counts(c);
            require(n.writes() <= bounds.writeTerminals && n.reads() <= bounds.readTerminals && n.users <= bounds.users
                    && n.samples <= bounds.samples && n.wsEvents <= bounds.wsEvents && n.memoryBytes <= bounds.memoryBytes, "case allocation budget");
            seconds = Math.addExact(seconds, n.seconds); disk = Math.addExact(disk, n.diskBytes);
            Instant.parse(c.plannedStartUtc).plusSeconds(n.seconds);
        }
        require(seconds <= bounds.totalSeconds && disk <= bounds.outputBytes, "sequential time/output budget");
    }

    /** phase별 ordinal 수를 산식으로 보유. 수십만 payload 선할당 없음 */
    public Counts counts(Case c) {
        Workload w = c.workload; long seconds = Math.addExact(w.warmupSeconds, w.measurementSeconds);
        long ww=Phase18Cap.count(w,"write","warmup"), mw=Phase18Cap.count(w,"write","measurement");
        long wr=Phase18Cap.count(w,"read","warmup"), mr=Phase18Cap.count(w,"read","measurement");
        long writes = Math.addExact(w.bootstrap ? 1 : 0, Math.addExact(ww, mw));
        long initialReads=Phase18Cap.count(w,"read","initial-read");
        long reads = Math.addExact(initialReads,Math.addExact(wr, mr)), terminals = Math.addExact(writes, reads);
        long users = w.userMode.equals("once") ? writes : Math.addExact(w.bootstrap ? 1 : 0, w.userPool);
        require(writes == 0 || users > 0, "users required");
        long overheadMillis = Math.addExact(Math.multiplyExact(3, w.drainMillis), Math.addExact(w.readyMillis,
                Math.addExact(w.controlMillis, c.observation.stopAckMillis)));
        if(Phase18Cap.enabled(this)){
            // bootstrap+서버 소진2, warmup+서버2, measurement callback/WS/close/recheck4, 최종 서버1
            long drains=8+(w.bootstrap?1:0)+(w.schedule.kind().equals("users")?1:0);
            overheadMillis=Math.addExact(Math.multiplyExact(drains,w.drainMillis),Math.addExact(Math.multiplyExact(3,w.readyMillis),
                    Math.addExact(w.controlMillis,Math.addExact(c.observation.stopAckMillis,375000))));
            // 375s = 새 build/계획180 + 복구120 + 분석60 + 정상 shutdown10 + bootstrap 진단5
        }
        long durationMillis = Math.addExact(Math.multiplyExact(Math.addExact(seconds,Phase18Cap.initialSeconds(w)), 1000), overheadMillis);
        if(Phase18Cap1.enabled(this))durationMillis=Math.multiplyExact(Phase18Cap1.seconds(w),1000);
        long observationMillis=Phase18Cap1.enabled(this)?Math.addExact(durationMillis,180000):durationMillis;
        long samples = Math.addExact(2, observationMillis / c.observation.sampleMillis);
        // 공통 canonical 저장소는 고유 이벤트만 보유. WS 연결 전 bootstrap은 기대 집합에서 제외
        long events = w.wsConnections == 0 ? 0 : Math.addExact(ww, mw);
        long deliveries = Math.multiplyExact(events, w.wsConnections);
        long bitsets = Math.multiplyExact(Math.multiplyExact(Math.addExact(events, 63) / 64, 8), w.wsConnections);
        long wsMemory = Math.addExact(Math.multiplyExact(events, 128),
                Math.addExact(bitsets, Math.multiplyExact(w.wsConnections, 256)));
        // 연결별 payload 복제 없이 canonical rows·bitset·유한 연결 요약의 계획상 출력 예약
        long wsDisk = Math.addExact(Math.multiplyExact(events, 256),
                Math.addExact(bitsets, Math.multiplyExact(w.wsConnections, 1024)));
        // A-2 실제 HTTP/Jackson/JWT 초기 heap 관측을 반영한 기본 예약과 수신~검증 전체 슬롯의 동시 body 예약
        long processing = Math.addExact(Math.multiplyExact(w.readInFlight, 524_288), Math.multiplyExact(w.writeInFlight, 34_816));
        long memory = Math.addExact(Math.addExact(134_217_728, processing), Math.addExact(Math.multiplyExact(terminals, 512),
                Math.addExact(Math.multiplyExact(users, 256), Math.addExact(wsMemory, Math.multiplyExact(samples, 256)))));
        if(Phase18Cap.enabled(this))memory=Math.addExact(memory,Math.addExact(Math.multiplyExact(w.wsConnections,32768),16777216));
        long disk = Math.addExact(1_048_576, Math.addExact(Math.multiplyExact(terminals, 2048),
                Math.addExact(wsDisk, Math.multiplyExact(samples, 1024))));
        if(Phase18Cap.enabled(this))disk=Math.addExact(disk,Math.addExact(Math.multiplyExact(deliveries,72),Math.addExact(Math.multiplyExact(terminals,512),1048576)));
        return new Counts(ww, mw, wr, mr, w.bootstrap ? 1 : 0, initialReads, users, samples, events, deliveries, bitsets, memory, disk,
                Math.addExact(durationMillis, 999) / 1000);
    }

    public static String requestId(Case c, String kind, String scheduledPhase, long ordinal) {
        require(Set.of("write", "read").contains(kind) && (Set.of("bootstrap", "warmup", "measurement").contains(scheduledPhase)||c.workload.schedule.kind().equals("users")&&scheduledPhase.equals("initial-read")), "request namespace");
        long rate = kind.equals("write") ? c.workload.writeRate : c.workload.readRate;
        long count = Phase18Cap.count(c.workload,kind,scheduledPhase);
        require(ordinal >= 0 && ordinal < count, "planned ordinal");
        return c.runId + "/" + c.caseId + "/" + kind + "/" + scheduledPhase + "/" + ordinal;
    }

    public static long scheduledOffsetNanos(long ordinal, long rate) {
        require(ordinal >= 0 && rate > 0, "schedule");
        return Math.multiplyExact(ordinal, 1_000_000_000L) / rate;
    }

    /** 추가·삭제를 포함한 현재 입력 집합 비교. planHash는 plan 원본 bytes의 외부 SHA-256 */
    public void verifyInputs() throws Exception {
        Path repo = Path.of(ownership.repo); var actual = inventory(repo); var expected = new TreeMap<String, String>();
        require(sourceBaseline.equals(head(repo)), "HEAD differs from source baseline");
        for (Frozen f : inputs) {
            require(f.sha256.matches("[0-9a-f]{64}") && expected.put(f.path, f.sha256) == null, "input hash/duplicate");
        }
        require(actual.equals(expected), "frozen input mismatch");
        Path previous = Phase18Paths.execution(predecessor.path);
        require(predecessor.sha256.matches("[0-9a-f]{64}") && BenchmarkJson.hash(previous).equals(predecessor.sha256), "predecessor receipt");
    }

    /** Git 명령 실행이나 index 변경 없이 HEAD/ref만 읽음. worktree의 commondir도 같은 규칙 적용 */
    static String head(Path repo) throws Exception {
        Path git = repo.resolve(".git");
        if (Files.isRegularFile(git)) {
            String link = Files.readString(git).trim(); require(link.startsWith("gitdir: "), "gitdir");
            git = repo.resolve(link.substring(8)).normalize();
        }
        String value = Files.readString(git.resolve("HEAD")).trim();
        if (!value.startsWith("ref: ")) return value;
        String ref = value.substring(5); require(ref.startsWith("refs/") && !ref.contains(".."), "git ref");
        Path common = Files.exists(git.resolve("commondir")) ? git.resolve(Files.readString(git.resolve("commondir")).trim()).normalize() : git;
        Path loose = common.resolve(ref);
        if (Files.exists(loose)) return Files.readString(loose).trim();
        for (String line : Files.readAllLines(common.resolve("packed-refs")))
            if (line.endsWith(" " + ref)) return line.substring(0, line.indexOf(' '));
        throw new IllegalArgumentException("Baseline ref missing");
    }

    /** Plan 표시와 실행 action이 공유하는 A-1 준비 gate */
    public void requireTools() {
        require(phase.equals("A") && stage.equals("A-1") && state.equals("ready") && cases.size() == 1
                && cases.getFirst().kind.equals("tools"), "VerifyTools scope/readiness");
        var c = cases.getFirst(); var w = c.workload;
        require(w.writeRate == 0 && w.readRate == 0 && w.wsConnections == 0 && !w.bootstrap
                && ownership.wal.isEmpty() && ownership.dbInstance.isEmpty() && ownership.catalog.isEmpty()
                && ownership.redisIndex == -1, "service-free fixture");
        require(bounds.outputBytes >= 32L * 1024 * 1024 && bounds.totalSeconds >= 120 && bounds.memoryBytes >= 268_435_456
                && c.observation.stopAckMillis >= 1000 && c.observation.stopAckMillis <= 3000
                && w.drainMillis >= 3000 && w.drainMillis <= 10_000, "fixture reservation");
    }

    public static SortedMap<String, String> inventory(Path repo) throws Exception {
        var files = new TreeSet<Path>();
        for (String folder : List.of("src", "scripts")) try (var stream = Files.walk(repo.resolve(folder))) {
            stream.filter(Files::isRegularFile).filter(p -> !p.toString().toLowerCase(Locale.ROOT).endsWith(".zip") && !p.toString().endsWith(".pyc")).forEach(files::add);
        }
        for (String name : List.of("build.gradle", "settings.gradle", "gradlew.bat", "pixel_place.sql", "docs/phase18-tools.md")) files.add(repo.resolve(name));
        var result = new TreeMap<String, String>();
        for (Path p : files) { Phase18Paths.checked(p.toString()); result.put(repo.relativize(p).toString().replace('\\', '/'), BenchmarkJson.hash(p)); }
        return Collections.unmodifiableSortedMap(result);
    }

    static void id(String s) { require(s != null && s.matches("[a-z0-9][a-z0-9-]{0,63}"), "identifier"); }
    static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException("Phase18 rejected: " + reason); }
}
