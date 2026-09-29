package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 동결 예정 집합·엄격한 입력·소유 경로를 서비스 없이 검사하는 A-1 경계 */
class Phase18PlanTest {
    @TempDir Path temporary;

    static Phase18Plan valid() throws Exception {
        String repo = Path.of("").toAbsolutePath().toString();
        String session = "phase18-contract-fixture", root = "E:/pixel-place-phase15/" + session;
        var inputs = Phase18Plan.inventory(Path.of(repo)).entrySet().stream()
                .map(e -> new Phase18Plan.Frozen(e.getKey(), e.getValue())).toList();
        Path predecessor = Path.of(repo, "docs/review-protocol.md");
        return new Phase18Plan(2, "A", "A-1", "ready", session, "0419ab25f463f576959ef891251ef53c55c7dfeb",
                new Phase18Plan.Frozen(predecessor.toString(), BenchmarkJson.hash(predecessor)), inputs,
                new Phase18Plan.Ownership(repo, Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), root,
                        repo + "/build/agent-runs/" + session, root + "/build", root + "/cache", root + "/temp", root + "/jna", "", "", "", -1, System.getProperty("user.name")),
                new Phase18Plan.Bounds(200_000, 200_000, 200_000, 200_000, 10_000, 536_870_912,
                        1_073_741_824, 0, 0, 0, 134_217_728, 1, 86_400, 65536),
                List.of(new Phase18Plan.Case("tools", "run01", 1, "diagnostic", "tools", "2026-09-30T00:00:00Z",
                        new Phase18Plan.Workload(0, 0, "spread-tiles", "spread", 18001, 0, 0, 1, 4, 4,
                                1000, 3000, 3000, 3000, "once", 0, 1000, 10000, 1, 0, false),
                        new Phase18Plan.Runtime("group", 16, 128, 1000, 10000, 33554432, 1000, false,
                                List.of("-Xmx512m", "-XX:+UseG1GC"), 1, 1),
                        new Phase18Plan.Observation(1000, 3000, 3000, 1000, List.of("process-exit", "stop-ack"),
                                List.of(), "owned-process-identity", "missing-required-evidence"))));
    }
    private ObjectNode json() throws Exception { return (ObjectNode) Phase18Plan.JSON.valueToTree(valid()); }
    private Phase18Plan.Loaded read(String json) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID() + ".json"); Files.writeString(file, json); return Phase18Plan.read(file);
    }
    private Phase18Plan.Loaded read(ObjectNode json) throws Exception { return read(json.toString()); }
    private static ObjectNode work(ObjectNode j) { return (ObjectNode) j.path("cases").get(0).path("workload"); }

    @Test void planIsReadOnlyAndHashesActualBytes() throws Exception {
        var j = json(); var loaded = read(j); var p = loaded.plan();
        assertEquals(0, p.counts(p.cases().getFirst()).writes());
        assertFalse(Files.exists(Path.of(p.ownership().sessionRoot())));
        assertFalse(Files.exists(Path.of(p.ownership().evidenceRoot())));
        assertThrows(UnsupportedOperationException.class, () -> p.cases().clear());
        assertNotEquals(loaded.planHash(), read(j.toString() + "\n").planHash());
    }

    @Test void rejectsUnknownNestedKeysNullMissingCoercionFractionExponentDuplicateAndOverflow() throws Exception {
        var j = json(); j.put("unknown", 1); assertThrows(IllegalArgumentException.class, () -> read(j));
        for (String text : List.of("1.0", "1e0", "NaN", "Infinity", "9223372036854775808", "\"1\"", "null")) {
            String input = json().toString().replace("\"schemaVersion\":2", "\"schemaVersion\":" + text);
            assertThrows(Exception.class, () -> read(input), text);
        }
        var nested = json(); work(nested).put("unknown", 1); assertThrows(IllegalArgumentException.class, () -> read(nested));
        var missing = json(); missing.remove("schemaVersion"); assertThrows(IllegalArgumentException.class, () -> read(missing));
        String duplicate = json().toString().replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2");
        assertThrows(IllegalArgumentException.class, () -> read(duplicate));
        assertThrows(IllegalArgumentException.class, () -> read(json().toString() + " {}"));
        var numeric = json(); work(numeric).put("warmupSeconds", Long.MAX_VALUE); assertThrows(ArithmeticException.class, () -> read(numeric));
    }

    @Test void representativeAndSoakReserveUniqueWsStoreAndPerConnectionBitsets() throws Exception {
        var j = json(); j.put("state", "draft");
        var c = (ObjectNode) j.path("cases").get(0); c.put("kind", "mixed");
        work(j).put("writeRate", 105); work(j).put("readRate", 100); work(j).put("wsConnections", 100);
        work(j).put("warmupSeconds", 30); work(j).put("measurementSeconds", 120);
        work(j).put("simultaneousSeconds", 120); work(j).put("bootstrap", true);
        var representative = read(j).plan(); var n = representative.counts(representative.cases().getFirst());
        assertEquals(15751, n.writes()); assertEquals(15750, n.wsEvents());
        assertEquals(1575000, n.wsDeliveries()); assertEquals(197600, n.wsBitsetBytes());
        work(j).put("measurementSeconds", 1800); work(j).put("simultaneousSeconds", 1800);
        var soak = read(j).plan(); n = soak.counts(soak.cases().getFirst());
        assertEquals(192151, n.writes()); assertEquals(183000, n.reads()); assertEquals(192150, n.wsEvents());
        assertEquals(19215000, n.wsDeliveries()); assertEquals(2402400, n.wsBitsetBytes());
        assertTrue(n.memoryBytes() < 536870912L); assertTrue(n.diskBytes() < 1073741824L);
        for (String budget : List.of("memoryBytes", "outputBytes", "wsEvents")) {
            var tooSmall = j.deepCopy();
            ((ObjectNode) tooSmall.path("bounds")).put(budget, switch (budget) {
                case "memoryBytes" -> n.memoryBytes() - 1; case "outputBytes" -> n.diskBytes() - 1; default -> 192149;
            });
            assertThrows(IllegalArgumentException.class, () -> read(tooSmall), budget);
        }
        work(j).put("wsConnections", 101); assertThrows(IllegalArgumentException.class, () -> read(j));
        work(j).put("wsConnections", 100); work(j).put("measurementSeconds", 1875);
        assertThrows(IllegalArgumentException.class, () -> read(j)); // 200025개 고유 이벤트, 상한 불변
    }

    @Test void bootstrapAndZeroConnectionsDoNotCreateExpectedWsEventsAndArithmeticStaysChecked() throws Exception {
        var j = json(); work(j).put("bootstrap", true); work(j).put("wsConnections", 100);
        var bootstrap = read(j).plan(); var n = bootstrap.counts(bootstrap.cases().getFirst());
        assertEquals(1, n.writes()); assertEquals(0, n.wsEvents()); assertEquals(0, n.wsBitsetBytes());
        work(j).put("writeRate", 2); work(j).put("wsConnections", 0);
        var noWs = read(j).plan(); n = noWs.counts(noWs.cases().getFirst());
        assertEquals(3, n.writes()); assertEquals(0, n.wsEvents()); assertEquals(0, n.wsDeliveries()); assertEquals(0, n.wsBitsetBytes());
        work(j).put("wsConnections", Long.MAX_VALUE);
        var overflow = Phase18Plan.JSON.treeToValue(j, Phase18Plan.class);
        assertThrows(ArithmeticException.class, () -> overflow.counts(overflow.cases().getFirst()));
        var old = json(); old.put("schemaVersion", 1); assertThrows(IllegalArgumentException.class, () -> read(old));
    }

    @Test void acceptsMoreThanLegacy40000AndRejectsCeilingPlusOneWithoutLegacyChanges() throws Exception {
        var j = json(); work(j).put("writeRate", 100); work(j).put("measurementSeconds", 401);
        var p = read(j).plan(); assertEquals(40100, p.counts(p.cases().getFirst()).writes());
        work(j).put("writeRate", 100); work(j).put("measurementSeconds", 2000);
        var limit = read(j).plan(); assertEquals(200000, limit.counts(limit.cases().getFirst()).writes());
        work(j).put("bootstrap", true);
        assertThrows(IllegalArgumentException.class, () -> read(j));
        assertEquals(40000, BenchmarkSpec.read(Path.of("src/test/resources/phase15/decision.json")).maxAttemptsPerTrial());
    }

    @Test void readOnlyCaseAndFrozenRequestNamespacesDoNotDependOnTerminalRows() throws Exception {
        var j = json(); j.put("state", "draft"); ((ObjectNode) j.path("cases").get(0)).put("kind", "read");
        work(j).put("readRate", 3); work(j).put("warmupSeconds", 2); work(j).put("measurementSeconds", 4);
        var p = read(j).plan(); var c = p.cases().getFirst();
        assertEquals(0, p.counts(c).writes()); assertEquals(18, p.counts(c).reads());
        assertEquals("run01/tools/read/measurement/11", Phase18Plan.requestId(c, "read", "measurement", 11));
        assertThrows(IllegalArgumentException.class, () -> Phase18Plan.requestId(c, "read", "measurement", 12));
        assertThrows(IllegalArgumentException.class, () -> Phase18Plan.requestId(c, "write", "bootstrap", 0));
        assertEquals(333333333, Phase18Plan.scheduledOffsetNanos(1, 3));
        ((ObjectNode) j.path("cases").get(0)).put("role", "target"); assertThrows(IllegalArgumentException.class, () -> read(j));
    }

    @Test void duplicateIdsPathsAllocationBudgetsAndMissingInputsRejectBeforeExecution() throws Exception {
        var duplicate = json(); ((tools.jackson.databind.node.ArrayNode) duplicate.path("cases")).add(duplicate.path("cases").get(0));
        assertThrows(IllegalArgumentException.class, () -> read(duplicate));
        for (String field : List.of("memoryBytes", "outputBytes", "totalSeconds")) {
            var j = json(); ((ObjectNode) j.path("bounds")).put(field, 1);
            assertThrows(IllegalArgumentException.class, () -> read(j), field);
        }
        var paths = json(); ((ObjectNode) paths.path("ownership")).put("temp", paths.path("ownership").path("build").asText());
        assertThrows(IllegalArgumentException.class, () -> read(paths));
        var badWal = json(); ((ObjectNode) badWal.path("ownership")).put("wal", Path.of("data/wal/pixel-place.wal").toAbsolutePath().toString());
        assertThrows(IllegalArgumentException.class, () -> read(badWal));
        var stale = json(); ((ObjectNode) stale.path("inputs").get(0)).put("sha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> read(stale));
        var missing = json(); ((tools.jackson.databind.node.ArrayNode) missing.path("inputs")).remove(0);
        assertThrows(IllegalArgumentException.class, () -> read(missing));
        var head = json(); head.put("sourceBaseline", "0".repeat(40)); assertThrows(IllegalArgumentException.class, () -> read(head));
        for (String path : List.of("relative", "C:relative", "\\\\server\\share\\x", "C:/data/../wal", "C:/data/name.", "C:/data/name ", "C:/data/NUL", "C:/data/file:stream"))
            assertThrows(IllegalArgumentException.class, () -> Phase18Paths.checked(path), path);
    }

    @Test void unavailableActionsRejectEvenBeforeReadingNonexistentPlan() {
        for (String action : List.of("Run", "Analyze", "Close", "Pilot")) {
            var e = assertThrows(IllegalArgumentException.class, () -> Phase18Main.main(new String[]{"--action", action, "--plan", "C:/missing.json"}));
            assertTrue(e.getMessage().startsWith("Action unavailable"));
        }
    }
}
