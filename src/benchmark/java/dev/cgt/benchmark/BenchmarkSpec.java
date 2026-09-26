package dev.cgt.benchmark;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 실행 전에 모든 case의 유한한 시도·사용자·시간·표본 예산을 고정하는 입력 계약 */
public record BenchmarkSpec(String purpose, Double targetAcceptedWritesPerSecond, Double targetP95Millis,
        Double targetP99Millis, Double maxSystemErrorRate, List<Case> cases,
        int warmupSeconds, int measurementSeconds, int repetitions, int maxInFlight,
        int requestTimeoutSeconds, int maxDrainSeconds, int readyTimeoutSeconds, int controlTimeoutSeconds,
        int maxAttemptsPerTrial, int maxFixtureUsers, double maxOfferedRate, long maxTotalBytes,
        String userMode, Integer userPoolSize, long reuseMarginMillis, long seed, int sampleIntervalMillis) {
    public record Case(String role, double offeredRate, int webSocketSessions, String pattern) { }
    public record Plan(int warmupAttempts, int measurementAttempts, int totalAttempts, int fixtureUsers,
                       int maximumSamples, long estimatedBytes) { }

    /** 소수 count의 자동 정수 변환과 알 수 없는 필드를 거부하여 입력 오타의 조용한 보정 방지 */
    public static BenchmarkSpec read(Path path) throws IOException {
        if (Files.size(path) > 1_048_576) throw new IllegalArgumentException("Spec exceeds 1 MiB");
        try {
            var mapper = JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
            BenchmarkSpec spec = mapper.readValue(Files.readString(path), BenchmarkSpec.class);
            spec.validate();
            return spec;
        } catch (RuntimeException invalid) {
            // 원문을 포함할 수 있는 parser 예외는 전달하지 않음
            throw new IllegalArgumentException("Invalid benchmark input: check types, limits and decision targets");
        }
    }

    public void validate() {
        require(Set.of("exploratory", "decision").contains(purpose), "purpose");
        require(Set.of("once", "reuse").contains(userMode), "userMode");
        require(cases != null && !cases.isEmpty() && cases.size() <= 100, "cases");
        for (int value : new int[]{warmupSeconds, measurementSeconds, repetitions, maxInFlight,
                requestTimeoutSeconds, maxDrainSeconds, readyTimeoutSeconds, controlTimeoutSeconds,
                maxAttemptsPerTrial, maxFixtureUsers, sampleIntervalMillis}) require(value > 0, "positive integer");
        require(maxAttemptsPerTrial <= 40_000 && maxFixtureUsers <= 40_000, "trial allocation budget");
        require(maxInFlight <= maxAttemptsPerTrial && repetitions <= 100, "bounded concurrency/repetitions");
        require(reuseMarginMillis > 0 && reuseMarginMillis <= Long.MAX_VALUE / 1_000_000 - 180_000, "reuse margin");
        // C-4 사용자 승인 누적 74GiB. A/B/C-1~3의 보존 spec은 당시 승인값 유지
        require(maxTotalBytes > 0 && maxTotalBytes <= 79_456_894_976L, "evidence budget");
        require(Double.isFinite(maxOfferedRate) && maxOfferedRate > 0 && maxOfferedRate <= 200, "offered limit");
        if ("reuse".equals(userMode)) require(userPoolSize != null && userPoolSize > 0 && userPoolSize <= maxFixtureUsers, "reuse pool");
        if ("decision".equals(purpose)) {
            require(positive(targetAcceptedWritesPerSecond) && positive(targetP95Millis) && positive(targetP99Millis), "targets");
            require(targetP99Millis >= targetP95Millis, "p99 >= p95");
            require(maxSystemErrorRate != null && Double.isFinite(maxSystemErrorRate)
                    && maxSystemErrorRate >= 0 && maxSystemErrorRate <= 1, "error rate");
            require(cases.stream().anyMatch(c -> c != null && "target".equals(c.role)), "target case");
        }
        for (Case c : cases) {
            require(c != null && Set.of("probe", "target").contains(c.role), "case role");
            require(Set.of("same-pixel", "spread-tiles").contains(c.pattern), "pattern");
            require(Double.isFinite(c.offeredRate) && c.offeredRate > 0 && c.offeredRate <= maxOfferedRate, "offered rate");
            require(c.webSocketSessions >= 0 && c.webSocketSessions <= 1000, "WS allocation limit");
            if ("decision".equals(purpose) && "target".equals(c.role))
                require(c.offeredRate >= targetAcceptedWritesPerSecond, "target offered rate");
            plan(c);
        }
    }

    /** bootstrap 1건까지 포함하며 seq 차이를 event 건수로 사용하지 않음 */
    public Plan plan(Case c) {
        long warmup = (long) Math.ceil(c.offeredRate * warmupSeconds);
        long measurement = (long) Math.ceil(c.offeredRate * measurementSeconds);
        long attempts = Math.addExact(1, Math.addExact(warmup, measurement));
        require(attempts <= maxAttemptsPerTrial, "planned attempts");
        int users = "once".equals(userMode) ? Math.toIntExact(attempts) : Math.addExact(1, userPoolSize);
        require(users <= maxFixtureUsers, "fixture users");
        long seconds = (long) warmupSeconds + measurementSeconds + 3L * maxDrainSeconds + 3L * readyTimeoutSeconds + 10L * controlTimeoutSeconds;
        long samples = Math.addExact(2, Math.multiplyExact(seconds, 1000) / sampleIntervalMillis);
        require(samples <= 10_000 && seconds <= 86_400, "bounded sample window");
        // canonical tiles+InnoDB 공간 여유+event/WAL/JSON/표본. 다음 trial gate에서 실제 총량도 별도 대조
        long estimated = Math.addExact(192L * 1024 * 1024, Math.addExact(attempts * 8192, samples * 8192));
        require(estimated <= maxTotalBytes, "trial disk budget");
        return new Plan((int) warmup, (int) measurement, (int) attempts, users, (int) samples, estimated);
    }

    private static boolean positive(Double d) { return d != null && Double.isFinite(d) && d > 0; }
    static void require(boolean condition, String name) {
        if (!condition) throw new IllegalArgumentException("Invalid benchmark " + name);
    }
}
