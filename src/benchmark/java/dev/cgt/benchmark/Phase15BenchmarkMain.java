package dev.cgt.benchmark;

import dev.cgt.pixelplace.auth.config.AuthProperties;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.pixel.infra.RedisPixelCooldown;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.wal.infra.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static dev.cgt.benchmark.BenchmarkResults.*;

/** JavaExec 한 번에 실제 서버 한 trial만 소유. 다음 trial과 새 JVM recovery 호출은 순차 스크립트 책임 */
public final class Phase15BenchmarkMain {
    private final Path ROOT = BenchmarkPaths.storageRoot().resolve("agent-runs");
    private Path evidence;
    private ConfigurableApplicationContext context;
    private BenchmarkFixtures fixture;
    private BenchmarkObserver observer;
    private Process child;
    private BenchmarkProtocol protocol;
    private Thread stderrConsumer;
    private final List<String> childDiagnostics = Collections.synchronizedList(new ArrayList<>());
    private List<Long> users = List.of();
    private BenchmarkSpec spec;
    private String stage = "input", runId;
    private boolean normalShutdown;
    private boolean cleanupPerformed;
    private String evidenceStage = "A", writeMode = "single";
    private final List<Map<String, Object>> drains = new ArrayList<>();

    public static void main(String[] args) {
        var main = new Phase15BenchmarkMain(); int exit = 0;
        try { main.execute(arguments(args)); }
        catch (Throwable failure) {
            exit = 1;
            try { main.failure(failure); } catch (Exception ignored) { }
            System.err.println("BENCHMARK_INCOMPLETE stage=" + main.stage + " kind=" + failure.getClass().getSimpleName());
        } finally {
            try { main.stop(); } catch (Throwable failure) { exit = 2; System.err.println("BENCHMARK_CLEANUP_INCOMPLETE kind=" + failure.getClass().getSimpleName()); }
        }
        System.exit(exit);
    }
    private void execute(Map<String, String> args) throws Exception {
        Files.createDirectories(ROOT); BenchmarkGuards.rejectLinks(ROOT);
        try (var channel = FileChannel.open(ROOT.resolve(".phase15-benchmark.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another benchmark owns local resources");
            Throwable primary = null;
            try {
                if ("recovery-check".equals(args.get("mode"))) recovery(args);
                else if ("legacy-storage-ledger".equals(args.get("mode"))) legacyStorageLedger();
                else if ("trial".equals(args.get("mode"))) trial(args);
                else throw new IllegalArgumentException("Use trial or recovery-check mode");
            } catch (Exception | Error failure) {
                primary = failure; throw failure;
            } finally {
                // 실패 경로도 종료·자원 정리까지 독점. 미종료 프로세스와 다음 trial의 Redis 소유 충돌 방지
                try { stop(); } catch (Exception | Error cleanup) {
                    if (primary == null) throw cleanup;
                    Throwable combined = preserve(primary, cleanup);
                    if (combined != primary) throw (Error) combined;
                }
            }
        }
    }
    private void legacyStorageLedger() throws Exception {
        var current = BenchmarkEnvironment.credentials();
        var original = new BenchmarkEnvironment.Credentials("127.0.0.1", 3306, current.dbUsername(), current.dbPassword(),
                current.redisHost(), current.redisPort(), current.redisDatabase(), current.redisUsername(), current.redisPassword());
        fixture = new BenchmarkFixtures(original, BenchmarkGuards.catalog("ledger_read_only"), true);
        long shared = BenchmarkStorageBudget.charge(ROOT, fixture.storageSnapshot(), original), evidenceBytes = 0;
        Set<String> catalogs = new HashSet<>();
        try (var dirs = Files.list(Path.of("build/agent-runs"))) {
            for (var dir : dirs.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith("phase15-A-")).toList()) {
                evidenceBytes = Math.addExact(evidenceBytes, BenchmarkPaths.bytes(dir));
                for (String name : List.of("manifest.json", "ownership.json", "catalog-intent.json")) {
                    if (Files.exists(dir.resolve(name))) catalogs.add(BenchmarkJson.read(dir.resolve(name)).path("catalog").asText());
                }
            }
        }
        long database = fixture.bytesForCatalogs(catalogs), cache = BenchmarkPaths.bytes(Path.of("build/agent-gradle"));
        long reserve = 512L * 1024 * 1024;
        BenchmarkJson.write(BenchmarkPaths.storageRoot().resolve("legacy-budget-charge.json"), Map.of(
                "chargedBytes", Math.addExact(shared, Math.addExact(database, Math.addExact(cache, Math.addExact(evidenceBytes, reserve)))),
                "sharedStorageConservativeGrowth", shared, "ownedFixtureFiles", database, "evidence", evidenceBytes,
                "entireOldGradleCacheConservativeCharge", cache, "priorTemporaryAndBuildReserve", reserve,
                "method", "Original 3306 read-only metadata; whole active old binlog/shared InnoDB charged by lower-bound origin; no baseline reset; old performance evidence excluded"));
        System.out.println("BENCHMARK_LEGACY_STORAGE_CHARGE_RECORDED");
    }
    private void trial(Map<String, String> args) throws Exception {
        evidenceStage = args.getOrDefault("stage", "A"); writeMode = args.getOrDefault("write-mode", "single");
        BenchmarkSpec.require(Set.of("A", "C-3", "C-4").contains(evidenceStage), "stage");
        BenchmarkSpec.require(Set.of("single", "group").contains(writeMode), "write mode");
        BenchmarkSpec.require(!evidenceStage.equals("A") || writeMode.equals("single"), "A single baseline");
        Path specPath = Path.of(required(args, "spec")); spec = BenchmarkSpec.read(specPath);
        // 동결 A는 보존 증거로만 분석. 현재 C source를 새 A baseline으로 오인하는 실행 차단
        if (evidenceStage.equals("A")) throw new IllegalArgumentException("A baseline is frozen; current candidate requires a C stage");
        int caseIndex = Integer.parseInt(required(args, "case-index")), repetition = Integer.parseInt(required(args, "repetition"));
        BenchmarkSpec.require(caseIndex >= 0 && caseIndex < spec.cases().size() && repetition >= 0 && repetition < spec.repetitions(), "case/repetition");
        var loadCase = spec.cases().get(caseIndex); var plan = spec.plan(loadCase);
        runId = args.getOrDefault("run-id", "r" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8));
        String catalog = BenchmarkGuards.catalog(runId), session = args.getOrDefault("session", runId);
        BenchmarkGuards.catalog(session);
        boolean enabled = !"off".equals(args.getOrDefault("instrumentation", "on"));
        BenchmarkSpec.require(Set.of("on", "off").contains(args.getOrDefault("instrumentation", "on")), "instrumentation");
        JsonNode matrix = null;
        if (!evidenceStage.equals("A")) {
            matrix = BenchmarkJson.read(Path.of(required(args, "matrix")));
            BenchmarkMatrix.validateTrial(matrix, required(args, "matrix-entry"), spec, caseIndex, repetition, writeMode, enabled, evidenceStage);
            BenchmarkSpec.require(required(args, "trial-kind").equals(evidenceStage.equals("C-3") ? "smoke" : "comparison"), "trial kind");
        }
        var credentials = BenchmarkEnvironment.credentials();
        stage = "resource-preflight";
        fixture = new BenchmarkFixtures(credentials, catalog, true);
        Map<String, Object> budget = budget(session, loadCase, plan);
        evidence = BenchmarkGuards.newTrialDirectory(ROOT, runId, evidenceStage);
        BenchmarkJson.write(evidence.resolve("budget-before.json"), budget);
        stage = "fixture";
        users = fixture.prepare(runId, evidence, plan, args.getOrDefault("database-mode", "create-new"), args.get("precreated-owner"));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("version", 1); manifest.put("trialId", runId); manifest.put("session", session); manifest.put("catalog", catalog);
        manifest.put("stage", evidenceStage); manifest.put("writeMode", writeMode); manifest.put("writeSettings", writeSettings());
        manifest.put("trialKind", args.getOrDefault("trial-kind", "baseline"));
        if (!evidenceStage.equals("A")) {
            String entry = required(args, "matrix-entry");
            manifest.put("matrix", matrix); manifest.put("matrixEntry", entry);
            manifest.put("matrixSha256", BenchmarkJson.hash(Path.of(args.get("matrix"))));
            manifest.put("budgetApproval", BenchmarkStorageBudget.approval(Path.of("scripts/phase15-c-budget.json")));
        }
        manifest.put("spec", spec); manifest.put("specPath", specPath.toAbsolutePath().normalize().toString()); manifest.put("specSha256", BenchmarkJson.hash(specPath));
        manifest.put("caseIndex", caseIndex); manifest.put("repetition", repetition); manifest.put("instrumentation", enabled);
        manifest.put("plan", plan); manifest.put("fixtureUsers", users); manifest.put("fixtureRule", "positive synthetic kakao ids 9015000000000 + fixture ordinal; internal ids from generated keys");
        manifest.put("database", fixture.dbFacts()); manifest.put("redis", fixture.redisFacts);
        var identity = sourceIdentity(); manifest.put("sourceIdentity", identity);
        if (!evidenceStage.equals("A")) {
            Path snapshot = evidence.resolve("source");
            for (var input : identity.entrySet()) {
                Path target = snapshot.resolve(input.getKey()); Files.createDirectories(target.getParent());
                Files.copy(Path.of(input.getKey()), target);
                if (!BenchmarkJson.hash(target).equals(input.getValue())) throw new IllegalStateException("Candidate snapshot changed while copying");
            }
            manifest.put("sourceSnapshot", snapshot.toString());
        }
        manifest.put("fixtureFiles", fixture.fixtureFiles());
        manifest.put("mysqlRuntimeConfigSha256", BenchmarkJson.hash(BenchmarkPaths.storageRoot().resolve("mysql/runtime.ini")));
        manifest.put("wal", evidence.resolve("wal/pixel-place.wal").toString()); manifest.put("initial", Map.of("checkpoint", 0, "tiles", 0, "events", 0, "walRecords", 0));
        manifest.put("serverHeap", "2g"); manifest.put("clientHeap", "512m"); manifest.put("gc", "G1");
        manifest.put("scope", "local PC; independent JVM generator; no operating performance extrapolation");
        BenchmarkJson.write(evidence.resolve("manifest.json"), manifest);
        var keys = BenchmarkEnvironment.keys(); stage = "server-start";
        start(credentials, catalog, keys, enabled); verifyRuntime();
        var measurement = context.getBean(PixelMeasurement.class);
        observer = new BenchmarkObserver(measurement, context.getBean(MeterRegistry.class), context.getBean(PendingAmbiguousFlushStore.class),
                context.getBean(BenchmarkServletConfiguration.Activity.class), context.getBean(PixelWriteExecutor.class), evidence.resolve("wal/pixel-place.wal"), spec, plan);
        BenchmarkJson.write(evidence.resolve("runtime.json"), runtime());
        stage = "bootstrap";
        Attempt bootstrap = bootstrap(loadCase); BenchmarkJson.write(evidence.resolve("bootstrap.json"), bootstrap);
        drain(1, bootstrap.eventSeq(), System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.maxDrainSeconds()));
        var initial = BenchmarkConsistency.verify(fixture, context, users, List.of(bootstrap), loadCase, spec.seed());
        requireComplete(initial); BenchmarkJson.write(evidence.resolve("bootstrap-consistency.json"), initial);
        stage = "child-ready"; startChild(keys);
        protocol.send("START", Map.of("spec", spec, "caseIndex", caseIndex, "endpoint", endpoint(), "fixtureUsers", users, "evidence", evidence.toString()));
        var ready = protocol.receive("READY", spec.readyTimeoutSeconds());
        if (ready.path("webSocketSessions").asInt(-1) != loadCase.webSocketSessions()) throw new IllegalStateException("WS count mismatch");
        stage = "warmup"; measurement.phase(PixelMeasurement.Phase.warmup); protocol.send("WARMUP", Map.of());
        JsonNode warm = protocol.receive("WARMUP_DONE", (long) spec.warmupSeconds() + spec.maxDrainSeconds() + spec.controlTimeoutSeconds());
        if (warm.path("unknown").asLong(-1) != 0) throw new IllegalStateException("Uncertain warmup cannot enter measurement");
        drain(warm.path("sent").asLong() + 1, Math.max(bootstrap.eventSeq(), warm.path("latestAcceptedSeq").asLong()), System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.maxDrainSeconds()));
        stage = "measurement"; measurement.phase(PixelMeasurement.Phase.measurement); protocol.send("MEASURE", Map.of());
        var sendDone = protocol.receive("SEND_DONE", (long) spec.measurementSeconds() + spec.controlTimeoutSeconds());
        long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.maxDrainSeconds());
        stage = "drain"; measurement.phase(PixelMeasurement.Phase.drain);
        var done = protocol.receive("DONE", remainingSeconds(drainDeadline));
        if (!child.waitFor(remainingSeconds(drainDeadline), TimeUnit.SECONDS) || child.exitValue() != 0) throw new IllegalStateException("Client did not terminate successfully");
        var attempts = BenchmarkConsistency.readAttempts(evidence.resolve("client-results.jsonl"), spec.maxAttemptsPerTrial());
        if (attempts.size() + 1 != plan.totalAttempts()) throw new IllegalStateException("Missing terminal client attempts");
        var all = new ArrayList<>(attempts); all.add(bootstrap);
        long maximum = all.stream().filter(a -> a.eventSeq() != null).mapToLong(Attempt::eventSeq).max().orElseThrow();
        drain(done.path("sent").asLong() + 1, maximum, drainDeadline);
        stage = "collect";
        var consistency = BenchmarkConsistency.verify(fixture, context, users, all, loadCase, spec.seed());
        BenchmarkJson.write(evidence.resolve("consistency.json"), consistency); requireComplete(consistency);
        if (!observer.complete()) throw new IllegalStateException("Required server observations incomplete");
        verifyCoverage(measurement.walCounts(), ((Number) consistency.get("databaseEvents")).longValue(), writeMode);
        BenchmarkJson.write(evidence.resolve("drain.json"), Map.of("complete", true, "observations", drains));
        long start = done.path("windowStartNanos").asLong(), end = done.path("windowEndNanos").asLong();
        if (start != sendDone.path("windowStartNanos").asLong() || end != sendDone.path("windowEndNanos").asLong()) throw new IllegalStateException("Client window changed");
        Summary summary = aggregate(attempts, start, end);
        BenchmarkJson.write(evidence.resolve("control-transitions.json"), Map.of("parent", protocol.transitions, "note", "Each JVM has its own nanoTime origin; pipe boundary delay is not subtracted across JVMs"));
        stage = "shutdown"; stop();
        var report = new LinkedHashMap<String, Object>(); report.put("complete", true); report.put("normalShutdown", normalShutdown);
        report.put("trialId", runId); report.put("role", loadCase.role()); report.put("instrumentation", enabled); report.put("measurement", summary);
        report.put("performanceDecision", evidenceStage.equals("A") ? "15-B not performed" : "15-C-4 not performed"); report.put("recoveryCheckRequired", true);
        BenchmarkJson.write(evidence.resolve("summary.json"), report);
        System.out.println("BENCHMARK_TRIAL_COMPLETE evidence=" + evidence.getFileName());
    }

    private Map<String, Object> budget(String session, BenchmarkSpec.Case loadCase, BenchmarkSpec.Plan plan) throws Exception {
        var shared = fixture.storageSnapshot();
        fixture.requireOwnedStorage();
        BenchmarkPaths.requireOwned(Path.of(System.getProperty("java.io.tmpdir")));
        long legacy = BenchmarkJson.read(BenchmarkPaths.storageRoot().resolve("legacy-budget-charge.json")).path("chargedBytes").longValue();
        if (legacy <= 0) throw new IllegalStateException("Previous environment budget charge missing");
        long estimate = Math.addExact(plan.estimatedBytes(), BenchmarkStorageBudget.nextLogEstimate(spec, loadCase, plan, shared.binaryLogEnabled()));
        // 새 MySQL 전체(data/redo/undo/binlog/temp), cache/build, WAL, 증거를 한 소유 root에서 한 번만 합산
        long ownedBytes = BenchmarkPaths.bytes(BenchmarkPaths.storageRoot()), used = Math.addExact(legacy, ownedBytes);
        BenchmarkStorageBudget.Approval approval = null;
        if (!evidenceStage.equals("A")) {
            approval = BenchmarkStorageBudget.approval(Path.of("scripts/phase15-c-budget.json"));
            if (spec.maxTotalBytes() != approval.maximumBytes() || legacy != approval.legacyChargeBytes())
                throw new IllegalStateException("C spec/approval/legacy budget mismatch");
            used = BenchmarkStorageBudget.currentCharge(BenchmarkPaths.storageRoot(), ownedBytes, approval);
        }
        long freeDisk = Files.getFileStore(ROOT).getUsableSpace();
        long freeRam = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize();
        boolean insufficient = used > spec.maxTotalBytes() - estimate || freeDisk < estimate + 5L * 1024 * 1024 * 1024
                || freeRam < 3L * 1024 * 1024 * 1024;
        if (approval != null && !BenchmarkStorageBudget.permits(used, estimate, freeDisk, approval)) insufficient = true;
        var report = new LinkedHashMap<String, Object>(Map.of("session", session, "ownedRootBytes", ownedBytes, "legacyEnvironmentChargeBytes", legacy, "usedBytes", used,
                "nextTrialEstimateBytes", estimate, "maximumBytes", spec.maxTotalBytes(), "freeDisk", freeDisk, "freeMemory", freeRam,
                "dataAndLogVolumeFreeBytes", shared.freeBytesByVolume(),
                "allocationSource", "Whole E: owned root including initialized MySQL, WAL/evidence/temp/build/cache + conservative prior environment charge; 5GiB disk reserve"));
        report.put("allowed", !insufficient);
        if (approval != null) report.put("approval", approval);
        Path budgetPath = ROOT.resolve("phase15-" + (evidenceStage.equals("A") ? "A" : "C") + "-budget");
        Files.createDirectories(budgetPath);
        BenchmarkJson.write(budgetPath.resolve("assessment-" + runId + ".json"), report);
        if (insufficient) throw new IllegalStateException("Resource budget insufficient; no next trial");
        return report;
    }
    private void start(BenchmarkEnvironment.Credentials credentials, String catalog, BenchmarkEnvironment.Keys keys, boolean enabled) throws Exception {
        var properties = BenchmarkEnvironment.properties(credentials, catalog, evidence.resolve("wal/pixel-place.wal"), keys, enabled);
        properties.put("pixel-place.write.mode", writeMode);
        properties.put("pixel-place.write.group.max-batch-size", 16);
        properties.put("pixel-place.write.group.max-outstanding", 128);
        properties.put("pixel-place.write.group.queue-timeout", "1s");
        properties.put("pixel-place.write.group.shutdown-grace", "10s");
        context = bounded(() -> BenchmarkEnvironment.application(properties).run(), spec.readyTimeoutSeconds(), "Server ready timeout");
    }
    private void verifyRuntime() throws Exception {
        if (!context.getBean(ServiceReadiness.class).isReady()) throw new IllegalStateException("Server not ready");
        for (Class<?> type : List.of(FileWalAppender.class, SegmentedWalStorage.class, RedisPixelCooldown.class, FlushWorker.class, OverviewService.class, InMemoryTileBoard.class)) context.getBean(type);
        context.getBean("flushTaskScheduler"); context.getBean(org.springframework.security.web.SecurityFilterChain.class);
        var measurement = context.getBean(PixelMeasurement.class);
        var executors = context.getBeansOfType(PixelWriteExecutor.class);
        Class<?> expected = writeMode.equals("group") ? GroupPixelWriteExecutor.class : SinglePixelWriteExecutor.class;
        if (executors.size() != 1 || context.getBean(PixelWriteExecutor.class).getClass() != expected)
            throw new IllegalStateException("Actual executor selection mismatch");
        Object executor = context.getBean(PixelWriteExecutor.class);
        requireIdentity(PixelCommandService.class, context.getBean(PixelCommandService.class), "writeExecutor", executor);
        requireIdentity(expected, executor, "core", context.getBean(PixelWriteService.class));
        requireIdentity(expected, executor, "coordinator", context.getBean(FlushBoundaryCoordinator.class));
        requireIdentity(expected, executor, "dirty", context.getBean(dev.cgt.pixelplace.tile.application.DirtyTileTracker.class));
        requireIdentity(PixelWriteService.class, context.getBean(PixelWriteService.class), "walAppender", context.getBean(FileWalAppender.class));
        var configuration = context.getBean(WriteExecutionProperties.class); configuration.validate();
        if (!configuration.getMode().equals(writeMode) || configuration.getGroup().getMaxBatchSize() != 16
                || configuration.getGroup().getMaxOutstanding() != 128
                || !configuration.getGroup().getQueueTimeout().equals(Duration.ofSeconds(1))
                || !configuration.getGroup().getShutdownGrace().equals(Duration.ofSeconds(10)))
            throw new IllegalStateException("Effective write configuration mismatch");
        for (Class<?> type : List.of(FileWalAppender.class, SegmentedWalStorage.class, PixelCommandService.class, PixelWriteService.class,
                FlushBoundaryCoordinator.class, FlushPlanCaptureService.class, FlushWorker.class, OverviewService.class, expected)) {
            var field = type.getDeclaredField("measurement"); field.setAccessible(true);
            if (field.get(context.getBean(type)) != measurement) throw new IllegalStateException("Measurement singleton identity mismatch");
        }
        var storage = FileWalAppender.class.getDeclaredField("storage"); storage.setAccessible(true);
        if (storage.get(context.getBean(FileWalAppender.class)) != context.getBean(SegmentedWalStorage.class)) throw new IllegalStateException("WAL storage identity mismatch");
        var readerStorage = FileWalReplaySource.class.getDeclaredField("storage"); readerStorage.setAccessible(true);
        if (readerStorage.get(context.getBean(FileWalReplaySource.class)) != context.getBean(SegmentedWalStorage.class)
                || context.getBean(dev.cgt.pixelplace.wal.application.WalSegmentRetention.class) != context.getBean(SegmentedWalStorage.class))
            throw new IllegalStateException("WAL reader/retention storage identity mismatch");
        if (Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).anyMatch(p -> p.matches("(?i).*(devtools|mockito|junit).*")))
            throw new IllegalStateException("Forbidden benchmark runtime dependency");
    }
    private Map<String, Object> runtime() throws Exception {
        var result = new LinkedHashMap<String, Object>(); result.put("pid", ProcessHandle.current().pid()); result.put("endpoint", endpoint());
        result.put("javaVersion", System.getProperty("java.runtime.version")); result.put("os", System.getProperty("os.name")); result.put("filesystem", Files.getFileStore(evidence).type());
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors()); result.put("serverMaxHeap", Runtime.getRuntime().maxMemory());
        result.put("garbageCollectors", ManagementFactory.getGarbageCollectorMXBeans().stream().map(b -> b.getName()).toList());
        result.put("loggingOverride", "OFF; benchmark control/failure evidence handled separately");
        result.put("springBoot", org.springframework.boot.SpringBootVersion.getVersion()); result.put("springFramework", org.springframework.core.SpringVersion.getVersion());
        result.put("hibernate", org.hibernate.Version.getVersionString()); result.put("tomcat", org.apache.catalina.util.ServerInfo.getServerNumber());
        result.put("classpath", Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(p -> Path.of(p).getFileName().toString()).toList());
        result.put("flushDelay", context.getEnvironment().getProperty("pixel-place.flush.fixed-delay")); result.put("overviewDelayMillis", 10000);
        result.put("walMaxSegmentBytes", context.getBean(WalProperties.class).getMaxSegmentBytes());
        result.put("writeMode", writeMode); result.put("writeSettings", writeSettings());
        result.put("executorClass", context.getBean(PixelWriteExecutor.class).getClass().getName());
        result.put("executorBeans", context.getBeansOfType(PixelWriteExecutor.class).size());
        result.put("sharedMeasurementVerified", true); result.put("sharedStorageVerified", true);
        result.put("instrumentation", context.getBean(PixelMeasurement.class).enabled());
        result.put("settings", Map.of("profiles", List.of(context.getEnvironment().getActiveProfiles()), "configLocation", context.getEnvironment().getProperty("spring.config.location"),
                "ddlAuto", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"), "openInView", context.getEnvironment().getProperty("spring.jpa.open-in-view"),
                "redisDatabase", context.getEnvironment().getProperty("spring.data.redis.database"), "bindAddress", context.getEnvironment().getProperty("server.address")));
        return result;
    }
    private static void requireIdentity(Class<?> type, Object owner, String name, Object expected) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true);
        if (field.get(owner) != expected) throw new IllegalStateException("Runtime dependency identity mismatch: " + name);
    }
    private String endpoint() { return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort(); }
    private Attempt bootstrap(BenchmarkSpec.Case loadCase) throws Exception {
        var tokens = new ServiceJwtTokens(context.getBean(AuthProperties.class), Clock.systemUTC()); int[] pixel = pixel(loadCase, spec.seed(), 0);
        var request = HttpRequest.newBuilder(URI.create(endpoint() + "/api/pixels")).timeout(Duration.ofSeconds(spec.requestTimeoutSeconds()))
                .header("Authorization", "Bearer " + tokens.issueAccess(users.getFirst()).getTokenValue()).header("Origin", "http://localhost:3000")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"x\":" + pixel[0] + ",\"y\":" + pixel[1] + ",\"color\":" + pixel[2] + "}")).build();
        long sent = System.nanoTime();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(spec.requestTimeoutSeconds())).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Attempt result = Phase15LoadClient.response(0, new BenchmarkUsers.Assignment(0, 1), "prepare", sent, sent, response, null, pixel);
            if (result.outcome() != Outcome.accepted) throw new IllegalStateException("Bootstrap HTTP was not accepted");
            return result;
        }
    }
    private void startChild(BenchmarkEnvironment.Keys keys) throws Exception {
        Path argsFile = evidence.resolve("client-java.args");
        String classpath = System.getProperty("java.class.path");
        Files.writeString(argsFile, "-Xmx512m\n-XX:+UseG1GC\n-Djava.io.tmpdir=" + BenchmarkPaths.storageRoot().resolve("tmp").toString().replace('\\', '/') + "\n-Djdk.httpclient.disableRetryConnect=true\n-Djdk.httpclient.enableAllMethodRetry=false\n-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"\ndev.cgt.benchmark.Phase15LoadClient\n" + runId + "\n", StandardOpenOption.CREATE_NEW);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "@" + argsFile);
        Map<String, String> original = new HashMap<>(process.environment()); process.environment().clear();
        for (String allowed : List.of("SystemRoot", "WINDIR", "TEMP", "TMP")) if (original.containsKey(allowed)) process.environment().put(allowed, original.get(allowed));
        process.environment().put("PIXEL_PLACE_BENCH_JWT_KEY", keys.jwt()); process.environment().put("PIXEL_PLACE_BENCH_COOKIE_KEY", keys.cookie());
        child = process.start(); protocol = new BenchmarkProtocol(child.getInputStream(), child.getOutputStream(), runId);
        protocol.controlTimeoutSeconds(spec.controlTimeoutSeconds());
        stderrConsumer = Thread.ofPlatform().daemon(true).name("benchmark-child-stderr").start(() -> {
            try (var reader = child.errorReader(java.nio.charset.StandardCharsets.UTF_8)) {
                String line; while ((line = BenchmarkProtocol.boundedLine(reader)) != null) {
                    // 원문 경로·credential·payload가 섞인 외부 stderr는 저장하지 않음
                    if (childDiagnostics.size() < 100) childDiagnostics.add(line.matches("CLIENT_(CLEANUP_)?FAILURE kind=[A-Za-z0-9]+") ? line : "child diagnostic line omitted");
                }
            } catch (IOException ignored) { childDiagnostics.add("stderr unavailable"); }
        });
    }
    private void drain(long expectedServlets, long acceptedTail, long deadline) throws Exception {
        var activity = context.getBean(BenchmarkServletConfiguration.Activity.class); var measurement = context.getBean(PixelMeasurement.class);
        long idleSince = 0;
        while (System.nanoTime() < deadline) {
            boolean idle = serverIdle(activity.active.get(), activity.completed.get(), activity.started.get(), expectedServlets,
                    measurement.activeCommands(), context.getBean(PendingAmbiguousFlushStore.class).current().isPresent())
                    && executorIdle(context.getBean(PixelWriteExecutor.class).snapshot());
            if (idle) {
                if (idleSince == 0) idleSince = System.nanoTime();
                var capture = measurement.lastCapture(); long checkpoint = fixture.checkpoint();
                boolean capturedAfterIdle = capture != null && capture.observedNanos() >= idleSince && capture.records() == 0
                        && capture.tail() == checkpoint && capture.checkpoint() == checkpoint;
                if (checkpoint >= acceptedTail && capturedAfterIdle && context.getBean(ServiceReadiness.class).isReady()) {
                    var observed = new LinkedHashMap<String,Object>();
                    observed.put("expectedServlets",expectedServlets); observed.put("servletStarted",activity.started.get());
                    observed.put("servletCompleted",activity.completed.get()); observed.put("servletActive",activity.active.get());
                    observed.put("commandActive",measurement.activeCommands());
                    observed.put("pending",context.getBean(PendingAmbiguousFlushStore.class).current().isPresent());
                    observed.put("executor",context.getBean(PixelWriteExecutor.class).snapshot()); observed.put("capture",capture);
                    observed.put("idleSince",idleSince); observed.put("checkpoint",checkpoint);
                    observed.put("acceptedTail",acceptedTail); observed.put("ready",true); drains.add(observed);
                    return;
                }
            } else idleSince = 0;
            Thread.sleep(25);
        }
        throw new IllegalStateException("Server drain incomplete; client timeout does not prove server completion");
    }
    static boolean serverIdle(long activeServlets, long completed, long started, long expected, long activeCommands, boolean pending) {
        // timeout 뒤 아직 dispatch되지 않은 요청도 started/completed exact count로 완료 판정에서 제외
        return activeServlets == 0 && completed == expected && started == expected && activeCommands == 0 && !pending;
    }
    static boolean executorIdle(ExecutionSnapshot s) {
        // workerAlive는 정상 idle에도 true. FAILED closed는 정상 drain이 아님
        return s.state() == ExecutionSnapshot.State.RUNNING && s.queued() == 0 && s.claimedUnsettled() == 0
                && s.workerInFlight() == 0 && s.outstanding() == 0 && s.unpublishedTerminal() == 0 && s.activeCount() == 0;
    }
    static void verifyCoverage(PixelMeasurement.WalCounts c, long events, String mode) {
        if (events <= 0 || c.failedForces() != 0 || c.completedRecords() != events || c.coveredRecords() != events
                || c.emptyForces() != c.rotations() || c.recordForces() <= 0)
            throw new IllegalStateException("Actual file force coverage incomplete");
        if (mode.equals("single") && (c.singleCalls() != events || c.batchCalls() != 0 || c.recordForces() != events))
            throw new IllegalStateException("Single force/event contract mismatch");
        if (mode.equals("group") && (c.singleCalls() != 0 || c.batchCalls() <= 0 || c.batchRecords() != events))
            throw new IllegalStateException("Actual appendBatch record coverage mismatch");
        if (c.batchSizes() != null && (c.batchSizes().values().stream().mapToLong(Long::longValue).sum() != c.batchCalls()
                || c.batchSizes().entrySet().stream().mapToLong(e -> e.getKey() * e.getValue()).sum() != c.batchRecords()))
            throw new IllegalStateException("Actual appendBatch distribution mismatch");
    }
    private Map<String, Object> writeSettings() {
        return Map.of("mode", writeMode, "maxBatchSize", 16, "maxOutstanding", 128,
                "queueTimeoutMillis", 1000, "shutdownGraceMillis", 10000, "collectionWaitMillis", 0);
    }
    private void recovery(Map<String, String> args) throws Exception {
        Path manifestPath = Path.of(required(args, "manifest")).toAbsolutePath().normalize(); BenchmarkGuards.rejectLinks(manifestPath);
        evidence = manifestPath.getParent();
        if (!evidence.getParent().equals(ROOT) || !manifestPath.getFileName().toString().equals("manifest.json")) throw new IllegalArgumentException("Recovery manifest must be a trial manifest");
        JsonNode manifest = BenchmarkJson.read(manifestPath); runId = manifest.path("trialId").asText();
        evidenceStage = manifest.path("stage").asText("A"); writeMode = manifest.path("writeMode").asText("single");
        if (!evidence.getFileName().toString().equals("phase15-" + evidenceStage + "-" + runId) || !manifest.path("catalog").asText().equals(BenchmarkGuards.catalog(runId))) throw new IllegalStateException("Recovery ownership mismatch");
        if (!BenchmarkJson.read(evidence.resolve("summary.json")).path("normalShutdown").asBoolean()) throw new IllegalStateException("Prior normal shutdown not confirmed");
        if (!BenchmarkJson.MAPPER.valueToTree(sourceIdentity()).equals(manifest.path("sourceIdentity"))) throw new IllegalStateException("Recovery source identity changed");
        spec = BenchmarkJson.MAPPER.treeToValue(manifest.path("spec"), BenchmarkSpec.class); spec.validate();
        if (!evidenceStage.equals("A")) {
            BenchmarkMatrix.validateTrial(manifest.path("matrix"), manifest.path("matrixEntry").asText(), spec,
                    manifest.path("caseIndex").asInt(),manifest.path("repetition").asInt(),writeMode,
                    manifest.path("instrumentation").asBoolean(),evidenceStage);
            var approval = BenchmarkStorageBudget.approval(Path.of("scripts/phase15-c-budget.json"));
            long used = BenchmarkStorageBudget.currentCharge(BenchmarkPaths.storageRoot(), BenchmarkPaths.bytes(BenchmarkPaths.storageRoot()),approval);
            long free = Files.getFileStore(ROOT).getUsableSpace();
            if (!BenchmarkStorageBudget.permits(used,64L*1024*1024,free,approval))
                throw new IllegalStateException("Recovery storage budget insufficient");
            BenchmarkJson.write(evidence.resolve("recovery-budget-before.json"),Map.of("chargedBytes",used,
                    "reservedBytes",64L*1024*1024,"maximumBytes",approval.maximumBytes(),"freeBytes",free));
        }
        users = new ArrayList<>(); for (JsonNode id : manifest.path("fixtureUsers")) users.add(id.longValue());
        var credentials = BenchmarkEnvironment.credentials();
        if (!credentials.dbHost().equals(manifest.path("database").path("host").asText()) || credentials.dbPort() != manifest.path("database").path("port").asInt()
                || credentials.redisDatabase() != manifest.path("redis").path("database").asInt()) throw new IllegalStateException("Recovery environment changed");
        fixture = new BenchmarkFixtures(credentials, manifest.path("catalog").asText(), true);
        fixture.requireOwnedStorage(); fixture.fixtureFiles();
        if (!manifest.path("mysqlRuntimeConfigSha256").asText().equals(BenchmarkJson.hash(BenchmarkPaths.storageRoot().resolve("mysql/runtime.ini"))))
            throw new IllegalStateException("Recovery durability configuration changed");
        stage = "recovery-start"; start(credentials, fixture.catalog, BenchmarkEnvironment.keys(), manifest.path("instrumentation").asBoolean()); verifyRuntime();
        var attempts = BenchmarkConsistency.readAttempts(evidence.resolve("client-results.jsonl"), spec.maxAttemptsPerTrial());
        attempts.add(BenchmarkJson.MAPPER.treeToValue(BenchmarkJson.read(evidence.resolve("bootstrap.json")), Attempt.class));
        stage = "recovery-compare";
        Map<String, Object> result = BenchmarkConsistency.verify(fixture, context, users, attempts, spec.cases().get(manifest.path("caseIndex").asInt()), spec.seed());
        requireComplete(result);
        var recoveryRuntime = runtime(); stop();
        var evidenceResult = new LinkedHashMap<>(result); evidenceResult.put("newJvmPid", ProcessHandle.current().pid()); evidenceResult.put("schemaSeedUsersReexecuted", false);
        evidenceResult.put("normalShutdown", normalShutdown); BenchmarkJson.write(evidence.resolve("recovery-check.json"), evidenceResult);
        BenchmarkJson.write(evidence.resolve("recovery-runtime.json"), recoveryRuntime);
        System.out.println("BENCHMARK_RECOVERY_COMPLETE evidence=" + evidence.getFileName());
    }
    private void stop() throws Exception {
        if (cleanupPerformed) return;
        cleanupPerformed = true;
        int timeout = spec == null ? 15 : spec.readyTimeoutSeconds();
        Throwable failure = null;
        try { if (child != null && child.isAlive()) { child.destroy(); if (!child.waitFor(timeout, TimeUnit.SECONDS)) throw new IllegalStateException("Client process still alive"); } }
        catch (Exception | Error problem) { failure = preserve(failure, problem); }
        try { if (protocol != null) { protocol.close(); protocol.awaitReaderEnd(timeout); } }
        catch (Exception | Error problem) { failure = preserve(failure, problem); }
        try { if (stderrConsumer != null) { stderrConsumer.join(TimeUnit.SECONDS.toMillis(timeout)); if (stderrConsumer.isAlive()) throw new IllegalStateException("stderr consumer still alive"); } }
        catch (Exception | Error problem) { failure = preserve(failure, problem); }
        try { if (observer != null) { observer.close(); observer.write(evidence); if (!observer.complete()) throw new IllegalStateException("Observer incomplete at shutdown"); } }
        catch (Exception | Error problem) { failure = preserve(failure, problem); }
        try { if (context != null) { var owned = context; bounded(() -> { owned.close(); return true; }, timeout, "Server close timeout"); context = null; } }
        catch (Exception | Error problem) { failure = preserve(failure, problem); }
        if (fixture != null) {
            try {
                if (!users.isEmpty() && context == null && (child == null || !child.isAlive())) {
                    Map<String, Long> cleanup = fixture.cleanupOwnedKeys(users);
                    Path file = evidence.resolve(Files.exists(evidence.resolve("redis-cleanup.json")) ? "redis-recovery-cleanup.json" : "redis-cleanup.json");
                    BenchmarkJson.write(file, cleanup);
                }
            } catch (Exception | Error problem) { failure = preserve(failure, problem); }
            try { fixture.close(); } catch (RuntimeException | Error problem) { failure = preserve(failure, problem); }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        normalShutdown = true;
    }
    static Throwable preserve(Throwable first, Throwable later) {
        if (first == null) return later;
        Throwable primary = first instanceof Error || !(later instanceof Error) ? first : later;
        Throwable secondary = primary == first ? later : first;
        if (primary != secondary && Arrays.stream(primary.getSuppressed()).noneMatch(t -> t == secondary)) primary.addSuppressed(secondary);
        return primary;
    }
    private void failure(Throwable failure) throws Exception {
        if (evidence == null) return;
        List<String> types = new ArrayList<>();
        for (Throwable t = failure; t != null && types.size() < 64; t = t.getCause()) {
            types.add(t.getClass().getName());
            if (t instanceof org.springframework.beans.factory.BeanCreationException bean) types.add("bean=" + bean.getBeanName());
            if (t.getStackTrace().length > 0) types.add(t.getStackTrace()[0].toString());
        }
        BenchmarkJson.write(evidence.resolve("failure-" + Instant.now().toEpochMilli() + ".json"), Map.of("complete", false, "stage", stage,
                "failureTypesAndLocations", types, "childAlive", child != null && child.isAlive(), "nativeIoCancelled", false, "childDiagnostics", childDiagnostics));
    }
    static Map<String, String> sourceIdentity() throws Exception {
        Map<String, String> files = new TreeMap<>();
        for (String root : List.of("src/main", "src/benchmark")) try (var paths = Files.walk(Path.of(root))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) files.put(path.toString().replace('\\', '/'), BenchmarkJson.hash(path));
        }
        for (String file : List.of("build.gradle", "settings.gradle", "pixel_place.sql", "scripts/measure-phase15.ps1",
                "scripts/phase15-c-budget.json", "scripts/phase15-c-matrix.json", "scripts/phase15-c-input.json", "scripts/phase15-c4-input.json", "scripts/phase15-c-budget.ps1", "scripts/summarize-phase15.py"))
            files.put(file, BenchmarkJson.hash(Path.of(file)));
        return files;
    }
    private static <T> T bounded(Callable<T> action, int seconds, String message) throws Exception {
        var task = new FutureTask<>(action); Thread.ofPlatform().daemon(true).name("benchmark-lifecycle").start(task);
        try { return task.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { throw new IllegalStateException(message + "; native I/O may remain active"); }
    }
    private static long remainingSeconds(long deadline) { return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(Math.max(0, deadline - System.nanoTime()))); }
    private static void requireComplete(Map<String, Object> result) { if (!Boolean.TRUE.equals(result.get("complete"))) throw new IllegalStateException("Consistency incomplete; data retained"); }
    private static String required(Map<String, String> args, String key) { String value = args.get(key); if (value == null) throw new IllegalArgumentException("Missing --" + key); return value; }
    private static Map<String, String> arguments(String[] args) {
        Map<String, String> result = new HashMap<>();
        Set<String> allowed = Set.of("spec", "case-index", "repetition", "mode", "manifest", "run-id", "session", "instrumentation", "database-mode", "precreated-owner",
                "stage", "write-mode", "trial-kind", "matrix", "matrix-entry");
        if (args.length % 2 != 0) throw new IllegalArgumentException("Expected named arguments");
        for (int i = 0; i < args.length; i += 2) {
            String key = args[i].startsWith("--") ? args[i].substring(2) : "";
            if (!allowed.contains(key) || result.put(key, args[i + 1]) != null) throw new IllegalArgumentException("Unexpected or duplicate argument");
        }
        return result;
    }
}
