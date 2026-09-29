package dev.cgt.benchmark;

import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** C의 source/WAL/output/DB root를 서로 구분하는 실행 전 경계. 기존 15단계 guard 확장 책임 없음 */
public record Phase17CPlan(Path file, JsonNode json, String caseName, String action, Path output) {
    public static final Set<String> CASES = Set.of("single-smoke", "group-smoke", "single-unflushed", "group-unflushed");
    public static Map<String, String> arguments(String[] args) {
        var result = new LinkedHashMap<String, String>();
        if (args.length != 6) throw new IllegalArgumentException("Exactly --plan/--case/--action required");
        for (int i = 0; i < args.length; i += 2) {
            if (!Set.of("--plan", "--case", "--action").contains(args[i]) || result.put(args[i], args[i + 1]) != null)
                throw new IllegalArgumentException("Unknown or duplicate C argument");
        }
        combination(result.get("--case"), result.get("--action"));
        return result;
    }
    public static void combination(String name, String action) {
        if (!CASES.contains(name) || !(name.endsWith("-smoke") ? Set.of("smoke", "recover-drained")
                : Set.of("prepare-unflushed", "recover-unflushed")).contains(action))
            throw new IllegalArgumentException("Invalid C case/action");
    }
    public static Path absolute(String value) {
        if (value == null || !value.matches("(?s)^[A-Za-z]:[\\\\/].*") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Fully qualified local drive path required");
        return Path.of(value).normalize();
    }
    public static Path exact(String value, String planned) {
        Path path = absolute(value);
        if (!path.equals(absolute(planned))) throw new IllegalArgumentException("Path differs from C plan");
        return path;
    }
    public static void owned(Path child, Path root) throws Exception {
        if (!child.startsWith(root)) throw new IllegalArgumentException("Path outside exact owner root");
        BenchmarkGuards.rejectLinks(child);
    }
    public static Phase17CPlan read(String[] args) throws Exception {
        var a = arguments(args); Path file = absolute(a.get("--plan"));
        BenchmarkGuards.rejectLinks(file);
        if (Files.size(file) > 65536) throw new IllegalArgumentException("C plan size limit");
        JsonNode json = BenchmarkJson.MAPPER.readTree(Files.readString(file).replaceFirst("^\\uFEFF", ""));
        String name = a.get("--case"), action = a.get("--action");
        Path source = absolute(json.path("sourceRoot").asText());
        exact(Path.of("").toAbsolutePath().toString(), source.toString());
        Path walRoot = absolute(json.path("walRoot").asText()), large = absolute(json.path("outputRoot").asText());
        if (!walRoot.equals(source.resolve("data").resolve(json.path("runId").asText() + "-wal")) || walRoot.startsWith(source.resolve("data/wal")))
            throw new IllegalArgumentException("C WAL owner mismatch");
        for (Path p : List.of(source, walRoot, large, absolute(json.path("mysqlRoot").asText()))) BenchmarkGuards.rejectLinks(p);
        Path output = absolute(System.getProperty("phase17c.output", ""));
        owned(output, large.resolve("app"));
        if (!output.getParent().equals(large.resolve("app")) || Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("New exact action output required");
        var plan = new Phase17CPlan(file, json, name, action, output);
        fixed(json);
        exact(plan.wal().toString(), walRoot.resolve(name).resolve("pixel-place.wal").toString());
        owned(plan.wal(), walRoot);
        BenchmarkGuards.catalog(plan.catalog().substring("pixel_place_bench_".length()));
        if (!plan.mode().equals(name.split("-")[0]) || plan.caseJson().path("segmentBytes").asLong() != (name.endsWith("unflushed") ? 1 : 1024))
            throw new IllegalArgumentException("Mode/segment differs from fixed case");
        var catalogs = new HashSet<String>();
        for (String c : CASES) if (!catalogs.add(json.path("cases").path(c).path("catalog").asText())) throw new IllegalArgumentException("Duplicate catalog");
        BenchmarkGuards.redis(json.path("redisDatabase").asInt(), json.path("productionRedisDatabase").asInt(), json.path("redisHost").asText());
        if (json.path("mysqlPort").asInt() != 3307 || !json.path("mysqlHost").asText().equals("127.0.0.1"))
            throw new IllegalArgumentException("Dedicated MySQL required");
        for (String d : List.of("ready", "drain", "shutdown", "databasePrepare", "request", action)) if (plan.seconds(d) <= 0)
            throw new IllegalArgumentException("Positive observation deadline required");
        classpath(System.getProperty("java.class.path"));
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p = absolute(entry);
            if (!Files.exists(p)) throw new IllegalArgumentException("Missing C runtime classpath entry");
            if (p.startsWith(large.resolve("build"))) owned(p, large.resolve("build"));
            else owned(p, large.resolve("gradle-home"));
        }
        for (String prop : List.of("java.io.tmpdir", "jna.tmpdir")) owned(absolute(System.getProperty(prop, "")), large.resolve("temp"));
        if (!plan.recovery() && Files.exists(plan.wal().getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Initial WAL directory already exists");
        plan.budget();
        return plan;
    }
    public static void classpath(String value) {
        if (value == null || value.isBlank() || Arrays.stream(value.split(java.io.File.pathSeparator))
                .anyMatch(p -> Path.of(p).getFileName().toString().matches("(?i).*(devtools|mockito|junit).*\\.jar")
                        || p.replace('\\','/').matches("(?i).*/classes/java/test(?:/.*)?")))
            throw new IllegalArgumentException("Forbidden verification runtime classpath");
    }
    static void fixed(JsonNode json) {
        var s=json.path("smoke");
        if(s.path("seed").asLong()!=17003 || !s.path("loadCase").asText().equals("spread-tiles") || s.path("requests").asInt()!=65
                ||s.path("offeredPerSecond").asInt()!=8||s.path("inFlight").asInt()!=4||!s.path("measurement").asBoolean()||s.path("websocketSessions").asInt()!=0
                ||!s.path("stages").equals(BenchmarkJson.MAPPER.valueToTree(List.of(1,32,32)))) throw new IllegalArgumentException("Fixed C smoke plan mismatch");
        for(var e:Map.of("ready",120,"drain",180,"shutdown",120,"databasePrepare",300,"request",15).entrySet())
            if(json.path("deadlines").path(e.getKey()).asInt()!=e.getValue())throw new IllegalArgumentException("Fixed C app deadline mismatch");
        for(String c:CASES) {
            var value=json.path("cases").path(c);String catalog=value.path("catalog").asText();
            if(!catalog.startsWith("pixel_place_bench_")||value.path("users").asInt()!=(c.endsWith("unflushed")?2:65))throw new IllegalArgumentException("C fixture plan mismatch");
            BenchmarkGuards.catalog(catalog.substring(18));
        }
    }
    public void budget() throws Exception {
        var b = json.path("budget"); Path large = absolute(json.path("outputRoot").asText());
        if (BenchmarkPaths.bytes(large) > b.path("outputBytes").asLong()
                || BenchmarkPaths.bytes(absolute(json.path("walRoot").asText())) > b.path("walTotalBytes").asLong()
                || BenchmarkPaths.bytes(absolute(json.path("mysqlRoot").asText())) - b.path("databaseBaselineBytes").asLong() > b.path("databaseGrowthBytes").asLong()
                || Files.getFileStore(large).getUsableSpace() < b.path("minimumEFreeBytes").asLong()
                || Files.getFileStore(absolute(json.path("walRoot").asText())).getUsableSpace() < b.path("minimumCFreeBytes").asLong())
            throw new IllegalStateException("C cumulative space budget exceeded");
    }
    JsonNode caseJson() { return json.path("cases").path(caseName); }
    public Path wal() { return absolute(caseJson().path("wal").asText()); }
    public String mode() { return caseJson().path("mode").asText(); }
    public String catalog() { return caseJson().path("catalog").asText(); }
    public boolean recovery() { return action.startsWith("recover-"); }
    public int seconds(String name) { return json.path("deadlines").path(name).asInt(); }
}
