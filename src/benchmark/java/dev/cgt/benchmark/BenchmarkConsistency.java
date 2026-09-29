package dev.cgt.benchmark;

import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.*;
import java.util.*;

import static dev.cgt.benchmark.BenchmarkResults.*;

/** HTTP accepted/unknown을 DB event·현재 WAL·canonical memory/DB bytes와 대응시키는 종료 검증 */
final class BenchmarkConsistency {
    private BenchmarkConsistency() { }
    record Event(long sequence, long user, int z, int tx, int ty, int x, int y, int color) { }
    static List<Attempt> readAttempts(Path file, int limit) throws Exception {
        var results = new ArrayList<Attempt>();
        try (var lines = Files.newBufferedReader(file)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (results.size() >= limit || line.length() > 8192) throw new IllegalStateException("Attempt allocation budget exceeded");
                results.add(BenchmarkJson.MAPPER.readValue(line, Attempt.class));
            }
        }
        return results;
    }
    static Map<String, Object> verify(BenchmarkFixtures fixture, ConfigurableApplicationContext context,
            List<Long> users, List<Attempt> attempts, BenchmarkSpec.Case loadCase, long seed) throws Exception {
        return verify(fixture, context, users, attempts, loadCase, seed, false);
    }
    static Map<String, Object> verify(BenchmarkFixtures fixture, ConfigurableApplicationContext context,
            List<Long> users, List<Attempt> attempts, BenchmarkSpec.Case loadCase, long seed, boolean verifyRetainedInterval) throws Exception {
        Set<Long> requestIds = new HashSet<>(); Map<Long, Attempt> accepted = new HashMap<>();
        Map<Long, List<Attempt>> uncertain = new HashMap<>(); Map<Long, Integer> ordinals = new HashMap<>();
        for (int i = 0; i < users.size(); i++) ordinals.put(users.get(i), i);
        int problems = 0, unresolved = 0, unknownRecorded = 0;
        for (Attempt a : attempts) {
            if (!requestIds.add(a.requestId())) problems++;
            if (a.outcome() == Outcome.client_not_sent) continue;
            if (a.fixtureUserOrdinal() == null || a.fixtureUserOrdinal() < 0 || a.fixtureUserOrdinal() >= users.size()) { problems++; continue; }
            if (a.outcome() == Outcome.accepted) {
                if (a.eventSeq() == null || accepted.put(a.eventSeq(), a) != null) problems++;
            } else if (a.unknown()) uncertain.computeIfAbsent(users.get(a.fixtureUserOrdinal()), ignored -> new ArrayList<>()).add(a);
        }
        Map<Long, Event> events = new LinkedHashMap<>();
        Map<TileKey, byte[]> expectedPixels = new HashMap<>(); Map<TileKey, Long> versions = new HashMap<>();
        for (var key : new CanonicalZ0TileKeys().orderedKeys()) { expectedPixels.put(key, TileState.allWhite().pixels()); versions.put(key, 0L); }
        Map<Long, Integer> previousUserAttempt = new HashMap<>(); Set<Long> matched = new HashSet<>();
        Map<String, Integer> phaseCounts = new TreeMap<>();
        long checkpoint;
        try (var connection = fixture.connect(true)) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT event_seq,user_id,z,tx,ty,x,y,color FROM pixel_events ORDER BY event_seq")) {
                while (rows.next()) {
                    Event event = new Event(rows.getLong(1), rows.getLong(2), rows.getInt(3), rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getInt(8));
                    if (events.size() > attempts.size() || events.put(event.sequence, event) != null) throw new IllegalStateException("Unexpected extra DB events");
                    var key = new TileKey(event.z, event.tx, event.ty);
                    if (!versions.containsKey(key) || event.x < 0 || event.x >= 8192 || event.y < 0 || event.y >= 8192
                            || event.tx != event.x / 256 || event.ty != event.y / 256 || event.color < 0 || event.color > 255) { problems++; continue; }
                    long version = versions.merge(key, 1L, Long::sum);
                    expectedPixels.get(key)[(event.y % 256) * 256 + event.x % 256] = (byte) event.color;
                    Attempt match = uniqueMatch(event, accepted, uncertain, matched, users, loadCase, seed);
                    if (match == null) { problems++; continue; }
                    if (match.unknown()) unknownRecorded++;
                    if (!matches(match, event, users, loadCase, seed) || !matched.add(match.requestId())) { problems++; continue; }
                    if (match.outcome() == Outcome.accepted && !Objects.equals(match.tileVersion(), version)) problems++;
                    Integer previous = previousUserAttempt.put(event.user, match.userAttemptOrdinal());
                    if (previous != null && previous >= match.userAttemptOrdinal()) problems++;
                    phaseCounts.merge(match.phase(), 1, Integer::sum);
                }
            }
            for (var a : accepted.values()) if (!matched.contains(a.requestId())) problems++;
            for (var list : uncertain.values()) for (var a : list) if (!matched.contains(a.requestId())) unresolved++;
            checkpoint = fixture.checkpoint();
            var board = context.getBean(InMemoryTileBoard.class); Set<TileKey> found = new HashSet<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT z,tx,ty,data,tile_version FROM tiles")) {
                while (rows.next()) {
                    var key = new TileKey(rows.getInt(1), rows.getInt(2), rows.getInt(3));
                    if (!expectedPixels.containsKey(key) || !found.add(key)) { problems++; continue; }
                    byte[] db = rows.getBytes(4); long version = rows.getLong(5); var memory = board.getRequired(key);
                    if (version != versions.get(key) || version != memory.tileVersion() || !Arrays.equals(db, expectedPixels.get(key)) || !Arrays.equals(db, memory.pixels())) problems++;
                }
            }
            if (found.size() != 1024 || board.size() != 1024) problems++;
        }
        // 부하·자동 flush 소진 이후 정합성 단계의 1회 scan. metrics sampling에 이 경로를 사용하지 않음
        var batch = context.getBean(WalReplaySource.class).readAfter(0);
        for (var record : batch.records()) {
            Event e = events.get(record.eventSeq());
            if (e == null || e.user != record.userId() || e.x != record.x() || e.y != record.y() || e.color != record.color()
                    || e.z != record.z() || e.tx != record.tx() || e.ty != record.ty()) problems++;
        }
        if (batch.walLastEventSeq() != checkpoint || events.isEmpty() || events.keySet().stream().mapToLong(Long::longValue).max().orElse(0) != checkpoint) problems++;
        if (verifyRetainedInterval) {
            // 이미 읽은 DB/WAL 목록의 역방향 대조. prefix 이력 제외와 합법적 seq gap 보존
            Phase17CAnalyzer.retained(events.values().stream().map(e -> new dev.cgt.pixelplace.wal.domain.WalRecord(
                    e.sequence, e.user, e.z, e.tx, e.ty, e.x, e.y, e.color, null)).toList(), batch.records(), checkpoint, batch.walLastEventSeq());
        }
        var result = new LinkedHashMap<String, Object>(); result.put("complete", problems == 0 && unresolved == 0);
        result.put("mismatches", problems); result.put("unknownRecorded", unknownRecorded); result.put("unknownUnresolved", unresolved);
        result.put("accepted", accepted.size()); result.put("databaseEvents", events.size()); result.put("phaseEventCounts", phaseCounts);
        result.put("canonicalTiles", 1024); result.put("checkpoint", checkpoint); result.put("walTail", batch.walLastEventSeq());
        result.put("retainedWalRecords", batch.records().size()); result.put("historicalEventsVerifiedInDatabase", events.size() - batch.records().size());
        return result;
    }
    static Attempt uniqueMatch(Event event, Map<Long, Attempt> accepted, Map<Long, List<Attempt>> uncertain,
            Set<Long> matched, List<Long> users, BenchmarkSpec.Case loadCase, long seed) {
        Attempt exact = accepted.get(event.sequence);
        if (exact != null) return matches(exact, event, users, loadCase, seed) ? exact : null;
        var candidates = uncertain.getOrDefault(event.user, List.of()).stream()
                .filter(a -> !matched.contains(a.requestId()) && matches(a, event, users, loadCase, seed)).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }
    private static boolean matches(Attempt a, Event e, List<Long> users, BenchmarkSpec.Case c, long seed) {
        int[] pixel = pixel(c, seed, a.requestId());
        return a.fixtureUserOrdinal() != null && users.get(a.fixtureUserOrdinal()) == e.user && pixel[0] == e.x && pixel[1] == e.y && pixel[2] == e.color;
    }
}
