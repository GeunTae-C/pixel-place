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
            long inputWindowSeconds, long simultaneousSeconds, boolean bootstrap) { }
    public record Case(String caseId, String runId, int repeatIndex, String role, String kind,
            String plannedStartUtc, Workload workload, Runtime runtime, Observation observation) { }
    public record Counts(long warmupWrites, long measurementWrites, long warmupReads, long measurementReads,
            long bootstrap, long users, long samples, long wsEvents, long wsDeliveries, long wsBitsetBytes,
            long memoryBytes, long diskBytes, long seconds) {
        public long writes() { return Math.addExact(bootstrap, Math.addExact(warmupWrites, measurementWrites)); }
        public long reads() { return Math.addExact(warmupReads, measurementReads); }
    }
    public record Loaded(Phase18Plan plan, String planHash) { }

    /** 읽기 전용 검사. 파일 생성·서비스 접속 없이 입력 전체와 해시 집합 대조 */
    public static Loaded read(Path path) throws Exception {
        Phase18Paths.checked(path.toString());
        require(Files.size(path) <= 1_048_576, "plan bytes");
        byte[] bytes;
        try (var in = Files.newInputStream(path)) { bytes = in.readNBytes(1_048_577); }
        require(bytes.length <= 1_048_576, "plan grew");
        Phase18Plan plan;
        try {
            JsonNode tree = JSON.readTree(bytes);
            exactNumbers(tree);
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
        require(schemaVersion == VERSION && Set.of("A", "B", "C", "D").contains(phase), "version/phase");
        require(Set.of("A-1", "A-2", "A-3", "A-4", "B", "C", "D").contains(stage), "stage");
        require(stage.startsWith(phase), "phase/stage");
        require(Set.of("draft", "ready").contains(state), "state"); id(sessionId);
        require(sourceBaseline.matches("[0-9a-f]{40}"), "baseline");
        require(!inputs.isEmpty() && inputs.size() <= 4096 && !cases.isEmpty() && cases.size() <= 100, "input/case count");
        Path repo = Phase18Paths.checked(ownership.repo), root = Phase18Paths.checked(ownership.sessionRoot);
        Path evidence = Phase18Paths.checked(ownership.evidenceRoot), java = Phase18Paths.checked(ownership.java);
        require(Files.isDirectory(repo) && Files.isRegularFile(java) && java.getFileName().toString().equalsIgnoreCase("java.exe"), "repo/JDK");
        require(repo.equals(Path.of("").toAbsolutePath()), "actual repository");
        require(root.getFileName().toString().equals(sessionId) && evidence.getFileName().toString().equals(sessionId), "session ownership");
        require(sessionId.startsWith("phase18-"), "session prefix");
        Phase18Paths.child(evidence, repo.resolve("build/agent-runs"));
        require(!root.startsWith(repo) && !repo.startsWith(root), "separate working output root");
        var areas = new ArrayList<Path>();
        for (String p : List.of(ownership.build, ownership.cache, ownership.temp, ownership.jna)) {
            Path area = Phase18Paths.checked(p); Phase18Paths.child(area, root); areas.add(area);
        }
        areas.add(evidence); Phase18Paths.disjoint(areas);
        require(!ownership.owner.isBlank() && !ownership.owner.contains("TODO"), "owner");
        if (!ownership.wal.isEmpty()) {
            Path wal = Phase18Paths.checked(ownership.wal);
            Phase18Paths.child(wal, repo.resolve("data").resolve(sessionId));
            require(!wal.startsWith(repo.resolve("data/wal")), "original WAL");
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
        require(bounds.writeTerminals <= TERMINALS && bounds.readTerminals <= TERMINALS && bounds.users <= TERMINALS
                && bounds.wsEvents <= WS_EVENTS && bounds.samples <= SAMPLES, "allocation ceiling");
        require(bounds.memoryBytes <= 536_870_912L && bounds.totalSeconds <= 86_400 && bounds.logBytesPerStream <= 1_048_576, "hard ceiling");
        long totalDisk = Math.addExact(bounds.outputBytes, Math.addExact(bounds.buildBytes,
                Math.addExact(bounds.walBytes, Math.addExact(bounds.databaseBytes, bounds.binlogBytes))));
        require(totalDisk <= 32L * 1024 * 1024 * 1024, "total disk reservation");
        for (Path p : List.of(root, evidence))
            require(Phase18Paths.usable(p) >= Math.addExact(bounds.minimumFreeBytes, totalDisk), "free space reservation");
        var caseIds = new HashSet<String>(); var runIds = new HashSet<String>(); long seconds = 0, disk = 0;
        for (Case c : cases) {
            id(c.caseId); id(c.runId); require(caseIds.add(c.caseId) && runIds.add(c.runId), "duplicate case/run");
            require(c.repeatIndex > 0 && c.repeatIndex <= 100, "repeat");
            require(Set.of("pilot", "target", "diagnostic", "soak").contains(c.role), "role");
            require(Set.of("tools", "prepare", "write", "read", "mixed", "soak").contains(c.kind), "kind");
            if (state.equals("ready") && !c.kind.equals("tools"))
                require(!ownership.wal.isEmpty() && !ownership.dbInstance.isBlank() && !ownership.catalog.isEmpty()
                        && ownership.redisIndex >= 2, "required field environment");
            Instant.parse(c.plannedStartUtc);
            Workload w = c.workload;
            require(w.writeRate >= 0 && w.readRate >= 0 && w.writeRate <= 10_000 && w.readRate <= 10_000, "rate");
            require(w.warmupSeconds >= 0 && w.measurementSeconds > 0, "duration");
            require(Set.of("same-pixel", "spread-tiles").contains(w.writePattern) && Set.of("hot", "spread").contains(w.readPattern), "pattern");
            require(w.wsConnections >= 0 && w.wsConnections <= 100, "WS connections");
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
            require(!c.kind.equals("mixed") || w.writeRate > 0 && w.readRate > 0 && w.simultaneousSeconds > 0, "mixed interval");
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
        long ww = Math.multiplyExact(w.writeRate, w.warmupSeconds), mw = Math.multiplyExact(w.writeRate, w.measurementSeconds);
        long wr = Math.multiplyExact(w.readRate, w.warmupSeconds), mr = Math.multiplyExact(w.readRate, w.measurementSeconds);
        long writes = Math.addExact(w.bootstrap ? 1 : 0, Math.addExact(ww, mw));
        long reads = Math.addExact(wr, mr), terminals = Math.addExact(writes, reads);
        long users = w.userMode.equals("once") ? writes : Math.addExact(w.bootstrap ? 1 : 0, w.userPool);
        require(writes == 0 || users > 0, "users required");
        long overheadMillis = Math.addExact(Math.multiplyExact(3, w.drainMillis), Math.addExact(w.readyMillis,
                Math.addExact(w.controlMillis, c.observation.stopAckMillis)));
        long durationMillis = Math.addExact(Math.multiplyExact(seconds, 1000), overheadMillis);
        long samples = Math.addExact(2, durationMillis / c.observation.sampleMillis);
        // 공통 canonical 저장소는 고유 이벤트만 보유. WS 연결 전 bootstrap은 기대 집합에서 제외
        long events = w.wsConnections == 0 ? 0 : Math.addExact(ww, mw);
        long deliveries = Math.multiplyExact(events, w.wsConnections);
        long bitsets = Math.multiplyExact(Math.multiplyExact(Math.addExact(events, 63) / 64, 8), w.wsConnections);
        long wsMemory = Math.addExact(Math.multiplyExact(events, 128),
                Math.addExact(bitsets, Math.multiplyExact(w.wsConnections, 256)));
        // 연결별 payload 복제 없이 canonical rows·bitset·유한 연결 요약의 계획상 출력 예약
        long wsDisk = Math.addExact(Math.multiplyExact(events, 256),
                Math.addExact(bitsets, Math.multiplyExact(w.wsConnections, 1024)));
        long memory = Math.addExact(16_777_216, Math.addExact(Math.multiplyExact(terminals, 512),
                Math.addExact(Math.multiplyExact(users, 256), Math.addExact(wsMemory, Math.multiplyExact(samples, 256)))));
        long disk = Math.addExact(1_048_576, Math.addExact(Math.multiplyExact(terminals, 2048),
                Math.addExact(wsDisk, Math.multiplyExact(samples, 1024))));
        return new Counts(ww, mw, wr, mr, w.bootstrap ? 1 : 0, users, samples, events, deliveries, bitsets, memory, disk,
                Math.addExact(durationMillis, 999) / 1000);
    }

    public static String requestId(Case c, String kind, String scheduledPhase, long ordinal) {
        require(Set.of("write", "read").contains(kind) && Set.of("bootstrap", "warmup", "measurement").contains(scheduledPhase), "request namespace");
        long rate = kind.equals("write") ? c.workload.writeRate : c.workload.readRate;
        long count = scheduledPhase.equals("bootstrap") ? (kind.equals("write") && c.workload.bootstrap ? 1 : 0)
                : Math.multiplyExact(rate, scheduledPhase.equals("warmup") ? c.workload.warmupSeconds : c.workload.measurementSeconds);
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
        Path previous = Phase18Paths.checked(predecessor.path);
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
