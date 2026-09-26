package dev.cgt.benchmark;

import java.nio.file.*;

/** 사용자 지정 E: 저장 경계. 프로젝트·기존 data 경로를 새 측정 저장소로 사용하지 않음 */
final class BenchmarkPaths {
    private BenchmarkPaths() { }
    static Path storageRoot() {
        String value = System.getenv("PIXEL_PLACE_BENCH_STORAGE_ROOT");
        if (value == null) throw new IllegalArgumentException("Explicit E: benchmark storage root required");
        Path root = Path.of(value).toAbsolutePath().normalize();
        if (!root.toString().matches("(?i)^E:\\\\pixel-place-phase15\\\\[a-z0-9_-]+$")) throw new IllegalArgumentException("Dedicated E: root required");
        return root;
    }
    static void requireOwned(Path path) throws Exception {
        BenchmarkGuards.rejectLinks(path);
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot())) throw new IllegalStateException("Large writes outside approved E: storage root");
    }
    static long bytes(Path directory) throws Exception {
        long total = 0;
        try (var files = Files.walk(directory)) {
            for (var path : files.filter(Files::isRegularFile).toList()) {
                try { total = Math.addExact(total, Files.size(path)); }
                catch (NoSuchFileException raced) { /* 소유 임시파일 종료 경합만 허용 */ }
            }
        }
        return total;
    }
}
