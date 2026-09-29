package dev.cgt.benchmark;

import dev.cgt.pixelplace.tile.domain.*;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** 새 C catalog의 초기화와 복구 입력 대조. 복구에서는 schema/seed/사용자를 재생성하지 않음 */
final class Phase17CFixtures {
    private Phase17CFixtures() { }
    static void live(BenchmarkFixtures fixture, Phase17CPlan plan) throws Exception {
        fixture.requireOwnedStorage();
        try (var c = fixture.adminConnect(""); var s = c.createStatement(); var rows = s.executeQuery("SELECT @@server_uuid,@@port,@@datadir,@@sync_binlog,@@innodb_flush_log_at_trx_commit")) {
            rows.next();
            if (!rows.getString(1).equals(plan.json().path("mysqlUuid").asText()) || rows.getInt(2) != 3307 || rows.getInt(4) != 1 || rows.getInt(5) != 1)
                throw new IllegalStateException("Current DB identity/durability mismatch");
            Phase17CPlan.exact(rows.getString(3), plan.json().path("mysqlRoot").asText() + "/data");
        }
    }
    static List<Long> prepare(BenchmarkFixtures fixture, Phase17CPlan plan) throws Exception {
        live(fixture, plan);
        List<String> schema = BenchmarkGuards.schema(Files.readString(Phase17CPlan.absolute(plan.json().path("sourceRoot").asText()).resolve("pixel_place.sql")));
        try (var c = fixture.adminConnect("")) {
            requireNewCatalog(c, plan.catalog());
            BenchmarkJson.write(plan.output().resolve("catalog-intent.json"), Map.of("catalog", plan.catalog(), "runId", plan.json().path("runId").asText(), "mode", "create-new"));
            try (var s = c.createStatement()) { s.executeUpdate("CREATE DATABASE `" + plan.catalog() + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"); }
        }
        List<Long> users = new ArrayList<>();
        try (var c = fixture.connect(false)) {
            for (String sql : schema) { BenchmarkGuards.requireCatalog(c, plan.catalog()); try (var s = c.createStatement()) { s.execute(sql); } }
            fixture.fixtureFiles(); c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("INSERT INTO users(kakao_user_id) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                    for (int i = 0; i < plan.caseJson().path("users").asInt(); i++) {
                        s.setLong(1, 9_017_000_000_000L + i); s.executeUpdate();
                        try (var rows = s.getGeneratedKeys()) { if (!rows.next()) throw new IllegalStateException("Fixture user key missing"); users.add(rows.getLong(1)); }
                    }
                }
                if (plan.caseName().endsWith("unflushed")) {
                    byte[] white = TileState.allWhite().pixels();
                    try (var s = c.prepareStatement("INSERT INTO tiles(z,tx,ty,data,tile_version) VALUES(0,?,?,?,0)")) {
                        for (var key : new CanonicalZ0TileKeys().orderedKeys()) {
                            s.setInt(1, key.tx()); s.setInt(2, key.ty()); s.setBytes(3, white); s.addBatch();
                        }
                        s.executeBatch();
                    }
                }
                c.commit();
            } catch (Exception | Error problem) {
                try { c.rollback(); } catch (Exception | Error secondary) { if (secondary != problem) problem.addSuppressed(secondary); }
                throw problem;
            }
        }
        return List.copyOf(users);
    }
    static void requireNewCatalog(Connection connection,String catalog)throws SQLException {
        try(var find=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
            find.setString(1,catalog);
            try(var rows=find.executeQuery()){if(!rows.next()||rows.getLong(1)!=0)throw new IllegalStateException("Existing C catalog cannot be reused");}
        }
    }
    static List<WalRecord> records(List<Long> users) {
        if (users.size() != 2) throw new IllegalArgumentException("Two fixture identities required");
        return List.of(new WalRecord(2, users.get(0), 0, 0, 0, 0, 0, 17, LocalDateTime.parse("2026-09-28T00:00:00")),
                new WalRecord(5, users.get(1), 0, 1, 0, 256, 0, 23, LocalDateTime.parse("2026-09-28T00:00:01")));
    }
    static Map<String, Object> unflushedState(BenchmarkFixtures fixture, List<Long> users, InMemoryTileBoard memory, boolean committed) throws Exception {
        var expected = records(users); long checkpoint = fixture.checkpoint();
        if (checkpoint != (committed ? 5 : 0)) throw new IllegalStateException("Fixture checkpoint mismatch");
        try (var c = fixture.connect(true); var s = c.createStatement()) {
            var found = new ArrayList<WalRecord>();
            try (var rows = s.executeQuery("SELECT event_seq,user_id,z,tx,ty,x,y,color,created_at FROM pixel_events ORDER BY event_seq")) {
                while (rows.next()) found.add(new WalRecord(rows.getLong(1), rows.getLong(2), rows.getInt(3), rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getInt(8), rows.getObject(9, LocalDateTime.class)));
            }
            if (!(committed ? found.equals(expected) : found.isEmpty())) throw new IllegalStateException("Fixture events/createdAt mismatch");
            Set<TileKey> keys = new HashSet<>();
            try (var rows = s.executeQuery("SELECT z,tx,ty,data,tile_version FROM tiles")) {
                while (rows.next()) {
                    var key = new TileKey(rows.getInt(1), rows.getInt(2), rows.getInt(3)); byte[] pixels = TileState.allWhite().pixels(); long version = 0;
                    if (!new CanonicalZ0TileKeys().orderedKeys().contains(key) || !keys.add(key)) throw new IllegalStateException("Invalid fixture tile key");
                    if (committed) for (var r : expected) if (key.equals(new TileKey(r.z(), r.tx(), r.ty()))) { pixels[(r.y() % 256) * 256 + r.x() % 256] = (byte) r.color(); version++; }
                    if (!Arrays.equals(pixels, rows.getBytes(4)) || version != rows.getLong(5)) throw new IllegalStateException("Fixture DB tile mismatch");
                    if (memory != null && (!Arrays.equals(pixels, memory.getRequired(key).pixels()) || version != memory.getRequired(key).tileVersion())) throw new IllegalStateException("Fixture memory mismatch");
                }
            }
            if (keys.size() != 1024) throw new IllegalStateException("Canonical fixture incomplete");
            return Map.of("complete", true, "checkpoint", checkpoint, "events", found.size(), "canonicalTiles", keys.size());
        }
    }
}
