package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static dev.cgt.benchmark.BenchmarkResults.*;

/** 부하 전에 거부해야 할 입력·자원 경계와 처리량/지연 분모의 회귀 계약 */
class BenchmarkContractTest {
    @TempDir Path temporary;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Path INPUT = Path.of("src/test/resources/phase15/decision.json");

    @Test void committedInputPlansBootstrapWarmupAndMeasurementWithoutChangingRoles() throws Exception {
        BenchmarkSpec spec = BenchmarkSpec.read(INPUT);
        assertEquals(2, spec.cases().stream().filter(c -> c.role().equals("target")).count());
        var plan = spec.plan(spec.cases().getLast());
        assertEquals(15_751, plan.totalAttempts()); assertEquals(15_751, plan.fixtureUsers());
        assertEquals(3150, plan.warmupAttempts()); assertEquals(12600, plan.measurementAttempts());
    }

    @Test void invalidDecisionInputsAreRejectedBeforeResourceAccess() throws Exception {
        for (String field : List.of("targetAcceptedWritesPerSecond", "targetP95Millis", "targetP99Millis", "maxSystemErrorRate")) {
            reject(field, null); reject(field, -1);
        }
        reject("targetP99Millis", 99); reject("maxSystemErrorRate", 1.01);
        reject("purpose", "auto"); reject("userMode", "auto"); reject("userMode", "reuse");
        reject("maxAttemptsPerTrial", 15000); reject("maxFixtureUsers", 1); reject("cases", List.of());
        for (String field : List.of("warmupSeconds", "measurementSeconds", "repetitions", "maxInFlight",
                "requestTimeoutSeconds", "maxDrainSeconds", "readyTimeoutSeconds", "controlTimeoutSeconds",
                "reuseMarginMillis", "sampleIntervalMillis")) {
            reject(field, 0); reject(field, -1); reject(field, 1.5);
        }
        reject("cases", List.of(Map.of("role", "target", "offeredRate", 99, "webSocketSessions", 100, "pattern", "same-pixel")));
        reject("cases", List.of(Map.of("role", "probe", "offeredRate", 105, "webSocketSessions", 100, "pattern", "same-pixel")));
        reject("measurementSeconds", Integer.MAX_VALUE); reject("reuseMarginMillis", Long.MAX_VALUE);
    }

    @Test void exploratoryInputAllowsMissingTargetsButNeverPromotesPurpose() throws Exception {
        var json = mapper.readTree(Files.readString(INPUT)).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) json).put("purpose", "exploratory").putNull("targetP95Millis");
        Path spec = temporary.resolve("exploratory.json"); Files.writeString(spec, mapper.writeValueAsString(json));
        assertEquals("exploratory", BenchmarkSpec.read(spec).purpose());
    }

    @Test void approvedCumulativeBudgetMatchesActiveInputAndRejectsOverflow() throws Exception {
        long maximum = mapper.readTree(Files.readString(Path.of("scripts/phase15-c-budget.json"))).get("maximumBytes").asLong();
        assertEquals(79_456_894_976L, maximum);
        var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(INPUT));
        Path spec = temporary.resolve("approved.json");
        for (long allowed : new long[]{maximum - 1, maximum}) {
            json.put("maxTotalBytes", allowed); Files.writeString(spec, mapper.writeValueAsString(json));
            assertEquals(allowed, BenchmarkSpec.read(spec).maxTotalBytes());
        }
        for (long rejected : new long[]{0, -1, maximum + 1, Long.MAX_VALUE, Long.MIN_VALUE}) reject("maxTotalBytes", rejected);
    }

    @Test void completionWindowExcludesDrainWhileCohortIncludesItAndCountsOneOutcome() {
        var rows = List.of(
                attempt(1, "warmup", 0, 0L, 110, Outcome.accepted, 200, 1L),
                attempt(2, "measurement", 110, 110L, 150, Outcome.accepted, 200, 2L),
                attempt(3, "measurement", 120, 120L, 250, Outcome.accepted, 200, 3L),
                attempt(4, "measurement", 130, 130L, 180, Outcome.policy_4xx, 429, null),
                attempt(5, "measurement", 140, 140L, 190, Outcome.server_5xx, 503, null),
                attempt(6, "measurement", 150, 150L, 220, Outcome.timeout, null, null),
                attempt(7, "measurement", 160, 160L, 195, Outcome.malformed_200, 200, null),
                attempt(8, "measurement", 170, null, 175, Outcome.client_not_sent, null, null));
        Summary summary = aggregate(rows, 100, 200);
        assertEquals(7, summary.planned()); assertEquals(6, summary.sent());
        assertEquals(2, summary.completedAccepted()); assertEquals(2, summary.cohortAccepted());
        assertEquals(1, summary.drainAccepted()); assertEquals(.5, summary.systemErrorRate());
        assertEquals(1.0 / 7, summary.notSentRate()); assertEquals(2, summary.successRtt().samples());
        assertEquals(130 / 1e6, summary.successRtt().p95Millis());
        assertThrows(IllegalArgumentException.class, () -> aggregate(List.of(rows.getFirst(), rows.getFirst()), 100, 200));
        assertNull(aggregate(List.of(rows.get(3)), 100, 200).successRtt().p99Millis());
    }

    @Test void reusableUsersCannotOverlapReenterEarlyOrFollowUnknownOutcome() {
        var users = new BenchmarkUsers(3, false, 1000);
        var first = users.acquire(1); var second = users.acquire(1);
        assertNotEquals(first.ordinal(), second.ordinal()); assertNull(users.acquire(1));
        users.complete(first, 100, false); users.complete(second, 100, true);
        assertNull(users.acquire(181_000_000_099L));
        var reused = users.acquire(181_000_000_100L);
        assertEquals(first.ordinal(), reused.ordinal()); assertEquals(2, reused.attemptOrdinal());
        assertNull(users.acquire(Long.MAX_VALUE - 1));
    }

    @Test void rootDdlIsRestrictedToFiveStatementsAndForeignMutationsFail() throws Exception {
        String sql = Files.readString(Path.of("pixel_place.sql"));
        assertEquals(5, BenchmarkGuards.schema(sql).size());
        for (String extra : List.of("DELETE FROM users;", "CREATE TABLE extra (id INT);", "USE pixel_place_test;", "SELECT 1 INTO OUTFILE 'x';"))
            assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.schema(sql + extra));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.schema(sql.replace("CREATE TABLE IF NOT EXISTS users", "CREATE TABLE IF NOT EXISTS pixel_place.users")));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.schema(sql.replace("CHECKPOINT_NAME = CHECKPOINT_NAME", "CHECKPOINT_NAME = CHECKPOINT_NAME").replace("checkpoint_name = checkpoint_name", "last_flushed_event_seq = 0")));
    }

    @Test void namespaceGuardsRejectRuntimeCatalogsRedisAndExistingTrial() throws Exception {
        for (String id : List.of("../data/wal", "RUN", "a;drop", "x".repeat(47))) assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.catalog(id));
        assertEquals("pixel_place_bench_trial", BenchmarkGuards.catalog("trial"));
        for (int db : new int[]{-1, 0, 1, 16}) assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.redis(db, 0, "localhost"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.redis(2, 2, "localhost"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.redis(2, 0, "remote"));
        Path trial = BenchmarkGuards.newTrialDirectory(temporary, "trial");
        assertTrue(Files.isDirectory(trial.resolve("wal")));
        assertThrows(IllegalStateException.class, () -> BenchmarkGuards.newTrialDirectory(temporary, "trial"));
    }

    private void reject(String field, Object value) throws Exception {
        var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(INPUT));
        json.set(field, mapper.valueToTree(value));
        Path path = temporary.resolve("bad.json"); Files.writeString(path, mapper.writeValueAsString(json));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSpec.read(path), field + "=" + value);
    }
    private Attempt attempt(long id, String phase, long scheduled, Long sent, long completed, Outcome outcome, Integer status, Long seq) {
        return new Attempt(id, 1, 1, phase, scheduled, sent, completed, outcome, status, seq, seq == null ? null : 1L, null);
    }
}
