package dev.cgt.benchmark;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 시험 namespace 소유권 확인. 기존 catalog/WAL/Redis를 인수하거나 자동 정리하지 않는 사전 경계 */
public final class BenchmarkGuards {
    private BenchmarkGuards() { }
    public static String catalog(String runId) {
        BenchmarkSpec.require(runId != null && runId.matches("[a-z0-9_]{1,46}"), "run id");
        return "pixel_place_bench_" + runId;
    }
    public static void requireCatalog(Connection connection, String expected) throws SQLException {
        BenchmarkSpec.require(expected != null && expected.startsWith("pixel_place_bench_")
                && expected.equals(catalog(expected.substring(18))), "catalog namespace");
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
            if (!rows.next() || !expected.equals(rows.getString(1))) throw new IllegalStateException("Benchmark catalog mismatch; no mutation allowed");
        }
    }
    public static void redis(int database, int productionDatabase, String host) {
        BenchmarkSpec.require(Set.of("localhost", "127.0.0.1", "::1").contains(host), "local Redis host");
        BenchmarkSpec.require(database >= 1 && database <= 15 && database != productionDatabase && database != 1, "isolated Redis DB");
    }
    public static Path newTrialDirectory(Path root, String runId) throws IOException {
        return newTrialDirectory(root, runId, "A");
    }
    public static Path newTrialDirectory(Path root, String runId, String stage) throws IOException {
        catalog(runId);
        BenchmarkSpec.require(Set.of("A", "C-3", "C-4").contains(stage), "evidence stage");
        Path absolute = root.toAbsolutePath().normalize();
        rejectLinks(absolute);
        Path trial = absolute.resolve("phase15-" + stage + "-" + runId);
        rejectLinks(trial);
        if (Files.exists(trial, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Existing trial path cannot be reused");
        Path runtime = Path.of("data/wal").toAbsolutePath().normalize();
        BenchmarkSpec.require(!trial.startsWith(runtime), "runtime WAL alias");
        Files.createDirectory(trial);
        Files.createDirectory(trial.resolve("wal"));
        return trial;
    }
    public static void rejectLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !p.toRealPath().equals(p.toAbsolutePath().normalize()))
                throw new IllegalStateException("Linked or aliased benchmark path is forbidden");
        }
    }
    /** root DDL의 네 CREATE와 seed만 선택. 검증 완료 전 SQL 실행 없음 */
    public static List<String> schema(String sql) {
        String text = sql.lines().map(line -> line.replaceFirst("--.*$", "")).reduce("", (a, b) -> a + "\n" + b);
        List<String> selected = new ArrayList<>(); Set<String> names = new HashSet<>();
        int create = 0, use = 0, seed = 0;
        for (String raw : text.split(";")) {
            String statement = raw.trim(); if (statement.isEmpty()) continue;
            String n = statement.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT).replace("`", "");
            if (n.equals("CREATE DATABASE IF NOT EXISTS PIXEL_PLACE CHARACTER SET UTF8MB4 COLLATE UTF8MB4_UNICODE_CI")) { create++; continue; }
            if (n.equals("USE PIXEL_PLACE")) { use++; continue; }
            if (Set.of("SELECT * FROM TILES", "SELECT * FROM PIXEL_EVENTS", "SELECT * FROM WAL_CHECKPOINT").contains(n)) continue;
            BenchmarkSpec.require(!n.matches("(?s).*\\b[A-Z][A-Z0-9_]*\\s*\\.\\s*[A-Z][A-Z0-9_]*\\b.*") && !n.contains("/*"), "schema qualified SQL");
            var matcher = java.util.regex.Pattern.compile("^CREATE TABLE IF NOT EXISTS (TILES|PIXEL_EVENTS|WAL_CHECKPOINT|USERS) \\(.*\\) ENGINE=INNODB$").matcher(n);
            if (matcher.matches()) {
                BenchmarkSpec.require(names.add(matcher.group(1)), "duplicate table");
            } else if (n.equals("INSERT INTO WAL_CHECKPOINT ( CHECKPOINT_NAME, LAST_FLUSHED_EVENT_SEQ ) VALUES ( 'MAIN', 0 ) ON DUPLICATE KEY UPDATE CHECKPOINT_NAME = CHECKPOINT_NAME")) {
                seed++;
            } else throw new IllegalArgumentException("Unexpected root SQL; no statements executed");
            selected.add(statement);
        }
        BenchmarkSpec.require(create == 1 && use == 1 && seed == 1 && names.size() == 4 && selected.size() == 5, "root DDL shape");
        return List.copyOf(selected);
    }
}
