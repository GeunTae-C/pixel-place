package dev.cgt.benchmark;

import java.nio.file.*;
import java.util.*;

/** 전용 DB 밖의 binary log·공유 InnoDB 증가도 포함하는 실행 전체 용량 경계. 기존 로그 삭제/회전 없음 */
final class BenchmarkStorageBudget {
    static final long C_CAP = 79_456_894_976L, C_FLOOR = 36_960_813_040L;
    record Approval(long maximumBytes, long carriedChargeBytes, long legacyChargeBytes,
                    long ancillaryChargeBytes, long minimumFreeBytes) { }
    record Snapshot(boolean binaryLogEnabled, long sharedInnoDbBytes, long redoCapacityBytes,
                    Map<String, Long> binaryLogs, Map<String, Long> freeBytesByVolume) { }
    private BenchmarkStorageBudget() { }

    /** 승인 입력은 기존 보존 charge를 하한으로 사용. 소유 root에 이미 포함된 DB/binlog를 재가산하지 않음 */
    static Approval approval(Path path) throws Exception {
        var node = BenchmarkJson.read(path);
        var value = new Approval(node.path("maximumBytes").asLong(), node.path("carriedChargeBytes").asLong(),
                node.path("legacyChargeBytes").asLong(), node.path("ancillaryChargeBytes").asLong(), node.path("minimumFreeBytes").asLong());
        validate(value); return value;
    }
    static void validate(Approval value) {
        if (value.maximumBytes != C_CAP || value.carriedChargeBytes < C_FLOOR || value.carriedChargeBytes > C_CAP
                || value.legacyChargeBytes != 2_824_498_477L || value.ancillaryChargeBytes != 67_108_864L
                || value.minimumFreeBytes != 5L * 1024 * 1024 * 1024)
            throw new IllegalArgumentException("C approval inputs disagree");
    }
    static long charged(long owned, Approval value) {
        validate(value);
        if (owned < 0) throw new IllegalArgumentException("Negative owned bytes");
        return Math.max(value.carriedChargeBytes, Math.addExact(owned, Math.addExact(value.legacyChargeBytes, value.ancillaryChargeBytes)));
    }
    /** 외부 순차 ledger의 마지막 관측부터 양의 증가만 가산. 감소한 temp·cache 크기로 누적 사용량 반환 금지 */
    static long continuedCharge(long owned, long previousOwned, long previousCharge, Approval value) {
        validate(value);
        if (owned < 0 || previousOwned < 0 || previousCharge < value.carriedChargeBytes)
            throw new IllegalArgumentException("Invalid cumulative budget observation");
        return Math.addExact(previousCharge, owned > previousOwned ? owned - previousOwned : 0);
    }
    static long currentCharge(Path root, long owned, Approval value) throws Exception {
        Path ledger = root.resolve("agent-runs/phase15-C-4-budget-ledger");
        BenchmarkGuards.rejectLinks(ledger);
        try (var files = Files.list(ledger)) {
            Path last = files.filter(p -> p.getFileName().toString().matches("[0-9]{8}\\.json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElseThrow(
                            () -> new IllegalStateException("C cumulative budget ledger missing"));
            var previous = BenchmarkJson.read(last);
            if (previous.path("maximumBytes").asLong() != value.maximumBytes)
                throw new IllegalStateException("C ledger approval mismatch");
            return continuedCharge(owned, previous.path("ownedBytes").asLong(-1), previous.path("chargedBytes").asLong(-1), value);
        }
    }
    static boolean permits(long charged, long estimate, long free, Approval value) {
        validate(value);
        return charged >= value.carriedChargeBytes && estimate >= 0 && free >= value.minimumFreeBytes
                && charged <= value.maximumBytes && estimate <= value.maximumBytes - charged
                && estimate <= free - value.minimumFreeBytes;
    }

    static long charge(Path root, Snapshot current, BenchmarkEnvironment.Credentials credentials) throws Exception {
        Path directory = root.resolve("phase15-A-budget"); Files.createDirectories(directory); BenchmarkGuards.rejectLinks(directory);
        Path origin = directory.resolve("storage-origin.json");
        if (!Files.exists(origin)) {
            try (var dirs = Files.list(root)) {
                if (dirs.anyMatch(p -> Files.exists(p.resolve("manifest.json")) || Files.exists(p.resolve("ownership.json"))))
                    throw new IllegalStateException("Earlier trial exists without shared-storage baseline; explicit conservative baseline required");
            }
            BenchmarkJson.write(origin, Map.of("databaseHost", credentials.dbHost(), "databasePort", credentials.dbPort(),
                    "sharedInnoDbBaseline", current.sharedInnoDbBytes(), "redoCapacityBaseline", current.redoCapacityBytes(),
                    "binaryLogBaseline", current.binaryLogs(), "method", "Before first fixture mutation"));
        }
        var baseline = BenchmarkJson.read(origin);
        if (!credentials.dbHost().equals(baseline.path("databaseHost").asText()) || credentials.dbPort() != baseline.path("databasePort").asInt())
            throw new IllegalStateException("Shared storage budget belongs to another database server");
        Map<String, Long> before = new TreeMap<>(); baseline.path("binaryLogBaseline").properties().forEach(e -> before.put(e.getKey(), e.getValue().longValue()));
        Map<String, Long> highest = new TreeMap<>(before);
        long shared = current.sharedInnoDbBytes(), redo = current.redoCapacityBytes();
        try (var files = Files.list(directory)) {
            for (var path : files.filter(p -> p.getFileName().toString().startsWith("snapshot-")).toList()) {
                var previous = BenchmarkJson.MAPPER.treeToValue(BenchmarkJson.read(path), Snapshot.class);
                previous.binaryLogs().forEach((name, size) -> highest.merge(name, size, Math::max));
                shared = Math.max(shared, previous.sharedInnoDbBytes()); redo = Math.max(redo, previous.redoCapacityBytes());
            }
        }
        current.binaryLogs().forEach((name, size) -> highest.merge(name, size, Math::max));
        // purge로 과거 표본이 사라져도 관측한 증가분을 예산에서 빼지 않음
        BenchmarkJson.write(directory.resolve("snapshot-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json"), current);
        return Math.addExact(growth(before, highest), Math.addExact(Math.max(0, shared - baseline.path("sharedInnoDbBaseline").asLong()),
                Math.max(0, redo - baseline.path("redoCapacityBaseline").asLong())));
    }
    static long growth(Map<String, Long> baseline, Map<String, Long> observed) {
        long total = 0;
        for (var entry : observed.entrySet()) total = Math.addExact(total, Math.max(0, entry.getValue() - baseline.getOrDefault(entry.getKey(), 0L)));
        return total;
    }
    static long nextLogEstimate(BenchmarkSpec spec, BenchmarkSpec.Case loadCase, BenchmarkSpec.Plan plan, boolean enabled) {
        if (!enabled) return 0;
        // FULL before/after tile image의 상한. 동일 pixel은 fixed-delay 최소 간격으로 snapshot 수 제한
        long writes = plan.totalAttempts();
        long seconds = (long) spec.warmupSeconds() + spec.measurementSeconds() + 3L * spec.maxDrainSeconds() + 3L * spec.readyTimeoutSeconds() + 10L * spec.controlTimeoutSeconds();
        long tileUpdates = loadCase.pattern().equals("same-pixel") ? Math.min(writes, seconds + 1) : writes;
        return Math.addExact(80L * 1024 * 1024, Math.addExact(tileUpdates * (2L * 65536 + 1024), writes * 2048));
    }
}
