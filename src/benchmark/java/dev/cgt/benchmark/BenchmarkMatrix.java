package dev.cgt.benchmark;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** C 비교 15회와 별도 smoke 2회의 조건·순서를 실행 전에 고정. 부하를 자동 시작하지 않음 */
final class BenchmarkMatrix {
    record Entry(String id, String role, String mode, int caseIndex, int repetition, boolean instrumentation) { }
    private BenchmarkMatrix() { }

    static List<Entry> entries() {
        var rows = new ArrayList<Entry>();
        rows.add(new Entry("low-single", "low", "single", 2, 0, true));
        rows.add(new Entry("low-group", "low", "group", 2, 0, true));
        for (int repeat = 0; repeat < 3; repeat++) for (int c = 0; c < 2; c++) {
            for (String mode : repeat == 1 ? List.of("group", "single") : List.of("single", "group")) {
                String id = "r" + (repeat + 1) + "-" + (c == 0 ? "same" : "spread") + "-" + mode;
                rows.add(new Entry(id, "target", mode, c, repeat, true));
                if (repeat == 1 && c == 1 && mode.equals("group"))
                    rows.add(new Entry(id + "-off", "instrumentation", mode, c, repeat, false));
            }
        }
        return List.copyOf(rows);
    }
    static List<Entry> smokes() {
        return List.of(new Entry("smoke-single", "smoke", "single", 0, 0, true),
                new Entry("smoke-group", "smoke", "group", 0, 0, true));
    }
    static void validate(JsonNode matrix) {
        if (!matrix.path("entries").equals(BenchmarkJson.MAPPER.valueToTree(entries()))
                || !matrix.path("smokes").equals(BenchmarkJson.MAPPER.valueToTree(smokes()))
                || matrix.path("version").asInt() != 1)
            throw new IllegalArgumentException("C fixed matrix/order mismatch");
    }
    static void validateTrial(JsonNode matrix, String id, BenchmarkSpec spec, int caseIndex, int repetition,
                              String mode, boolean instrumentation, String stage) {
        validate(matrix);
        var allowed = stage.equals("C-3") ? smokes() : entries();
        Entry row = allowed.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow(
                () -> new IllegalArgumentException("Trial outside selected C stage"));
        if (row.caseIndex != caseIndex || row.repetition != repetition || !row.mode.equals(mode) || row.instrumentation != instrumentation)
            throw new IllegalArgumentException("Trial identity differs from matrix");
        var c = spec.cases().get(caseIndex);
        boolean smoke = row.role.equals("smoke"), low = row.role.equals("low");
        if (spec.warmupSeconds() != (smoke ? 2 : 30) || spec.measurementSeconds() != (smoke ? 5 : 120)
                || c.offeredRate() != (smoke ? 2 : low ? 1 : 105) || c.webSocketSessions() != (smoke ? 2 : low ? 0 : 100)
                || !c.pattern().equals(smoke || caseIndex == 1 ? "spread-tiles" : "same-pixel")
                || spec.maxInFlight() != 128 || spec.requestTimeoutSeconds() != 30 || spec.maxDrainSeconds() != 60
                || !spec.userMode().equals("once") || spec.seed() != 15 || spec.maxTotalBytes() != BenchmarkStorageBudget.C_CAP
                || spec.repetitions() != (smoke ? 1 : 3) || spec.cases().size() != (smoke ? 1 : 3)
                || spec.readyTimeoutSeconds() != 120 || spec.controlTimeoutSeconds() != 15 || spec.sampleIntervalMillis() != 1000
                || spec.maxAttemptsPerTrial() != 40000 || spec.maxFixtureUsers() != 40000 || spec.maxOfferedRate() != 200
                || spec.userPoolSize() != null || spec.reuseMarginMillis() != 1000
                || !c.role().equals(smoke || low ? "probe" : "target")
                || !(smoke ? "exploratory" : "decision").equals(spec.purpose())
                || (!smoke && (!Objects.equals(spec.targetAcceptedWritesPerSecond(),100.0)
                    || !Objects.equals(spec.targetP95Millis(),100.0) || !Objects.equals(spec.targetP99Millis(),250.0)
                    || !Objects.equals(spec.maxSystemErrorRate(),0.001))))
            throw new IllegalArgumentException("Trial conditions differ from fixed C plan");
    }
}
