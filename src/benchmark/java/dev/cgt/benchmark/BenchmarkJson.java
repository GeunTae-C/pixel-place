package dev.cgt.benchmark;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 비밀 없는 소유 manifest·표본만 새 파일에 기록. 과거 결과 덮어쓰기 금지 */
final class BenchmarkJson {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    private BenchmarkJson() { }
    static void write(Path file, Object value) throws IOException {
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
    static JsonNode read(Path file) throws IOException { return MAPPER.readTree(Files.readString(file)); }
    static Map<String, Object> stamp(String type, String trial, String phase) {
        var map = new LinkedHashMap<String, Object>(); map.put("type", type); map.put("trialId", trial);
        map.put("phase", phase); map.put("nanoTime", System.nanoTime()); map.put("utc", Instant.now().toString());
        return map;
    }
    static String hash(Path file) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
