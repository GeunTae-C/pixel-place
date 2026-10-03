package dev.cgt.benchmark;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/** compile task의 source 집합과 실제 로딩 class/JAR bytes를 같은 receipt에 연결 */
final class Phase18Runtime {
    private Phase18Runtime() { }
    static void verify(Phase18Plan plan) throws Exception {
        verifyExecutionPaths(plan, System.getenv(), System.getProperties());
        String property = System.getProperty("phase18.runtimeReceipt", "");
        Path file = Phase18Paths.execution(property);
        Phase18Plan.require(Files.size(file) <= 1_048_576, "runtime receipt size");
        var receipt = Phase18Plan.JSON.readTree(Files.readString(file));
        Phase18Plan.require(receipt.path("schemaVersion").asInt() == 1, "runtime receipt version");
        var sources = new TreeMap<String, String>();
        for (var row : receipt.path("inputs")) Phase18Plan.require(sources.put(row.path("path").asText(), row.path("sha256").asText()) == null, "duplicate source receipt");
        Phase18Plan.require(sources.equals(Phase18Plan.inventory(Path.of(plan.ownership().repo()))), "compiled source receipt mismatch");
        var actual = new TreeMap<String, String>();
        for (String item : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path p = Phase18Paths.execution(item);
            Phase18Plan.require(p.startsWith(Path.of(plan.ownership().build()))
                    || p.startsWith(Path.of("F:/pixel-place-work/gradle-home")), "current compiled output/F library cache required");
            if (Files.isDirectory(p)) try (var stream = Files.walk(p)) {
                for (Path child : stream.filter(Files::isRegularFile).toList()) actual.put(child.toString(), BenchmarkJson.hash(child));
            } else actual.put(p.toString(), BenchmarkJson.hash(p));
        }
        var expected = new TreeMap<String, String>();
        for (var row : receipt.path("runtime")) Phase18Plan.require(expected.put(Path.of(row.path("path").asText()).toString(), row.path("sha256").asText()) == null, "duplicate runtime receipt");
        Phase18Plan.require(actual.equals(expected), "actual class/JAR bytes mismatch");
    }

    /** runner/앱/발생기에서 같은 동결 경로와 상속 환경 확인. 최초 기동만의 검사로 대체 금지 */
    static void verifyExecutionPaths(Phase18Plan plan, Map<String,String> environment, Properties properties) throws Exception {
        for (String name : List.of("PATH", "JAVA_HOME", "GRADLE_USER_HOME", "GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "TEMP", "TMP", "CLASSPATH", "PIXEL_PLACE_WAL_ACTIVE_FILE"))
            Phase18Paths.rejectHistoricalExecution(environment.get(name));
        for (String name : List.of("java.io.tmpdir", "jna.tmpdir", "java.class.path", "java.library.path", "user.dir"))
            Phase18Paths.rejectHistoricalExecution(properties.getProperty(name));
        Phase18Plan.require(Phase18Paths.execution(properties.getProperty("java.io.tmpdir")).equals(Path.of(plan.ownership().temp()))
                && Phase18Paths.execution(properties.getProperty("jna.tmpdir")).equals(Path.of(plan.ownership().jna())), "actual plan temp/JNA mismatch");
        for (String name : List.of("TEMP", "TMP"))
            Phase18Plan.require(Path.of(environment.getOrDefault(name, "")).equals(Path.of(plan.ownership().temp())), "child temp environment mismatch");
        Phase18Plan.require(Path.of(environment.getOrDefault("GRADLE_USER_HOME", "")).equals(Path.of("F:/pixel-place-work/gradle-home")), "actual F Gradle cache environment");
    }
}
