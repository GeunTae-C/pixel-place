package dev.cgt.benchmark;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/** compile task의 source 집합과 실제 로딩 class/JAR bytes를 같은 receipt에 연결 */
final class Phase18Runtime {
    private Phase18Runtime() { }
    static void verify(Phase18Plan plan) throws Exception {
        String property = System.getProperty("phase18.runtimeReceipt", "");
        Path file = Phase18Paths.checked(property);
        Phase18Plan.require(Files.size(file) <= 1_048_576, "runtime receipt size");
        var receipt = Phase18Plan.JSON.readTree(Files.readString(file));
        Phase18Plan.require(receipt.path("schemaVersion").asInt() == 1, "runtime receipt version");
        var sources = new TreeMap<String, String>();
        for (var row : receipt.path("inputs")) Phase18Plan.require(sources.put(row.path("path").asText(), row.path("sha256").asText()) == null, "duplicate source receipt");
        Phase18Plan.require(sources.equals(Phase18Plan.inventory(Path.of(plan.ownership().repo()))), "compiled source receipt mismatch");
        var actual = new TreeMap<String, String>();
        for (String item : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path p = Phase18Paths.checked(item);
            if (Files.isDirectory(p)) try (var stream = Files.walk(p)) {
                for (Path child : stream.filter(Files::isRegularFile).toList()) actual.put(child.toString(), BenchmarkJson.hash(child));
            } else actual.put(p.toString(), BenchmarkJson.hash(p));
        }
        var expected = new TreeMap<String, String>();
        for (var row : receipt.path("runtime")) Phase18Plan.require(expected.put(Path.of(row.path("path").asText()).toString(), row.path("sha256").asText()) == null, "duplicate runtime receipt");
        Phase18Plan.require(actual.equals(expected), "actual class/JAR bytes mismatch");
    }
}
