package dev.cgt.benchmark;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.cgt.benchmark.BenchmarkResults.*;

/** 실제 runtime 전에 protocol 역순·오염된 환경·불확실 HTTP·stale/missing 관측 경계를 검증 */
class BenchmarkRuntimeContractTest {
    @TempDir Path temporary;
    @Test void sharedLogGrowthRemainsChargedAfterOldOrNewFilesDisappear() throws Exception {
        var credentials = new BenchmarkEnvironment.Credentials("127.0.0.1", 3306, "fixture", "not-a-secret", "127.0.0.1", 6379, 2, "", "");
        assertEquals(0, BenchmarkStorageBudget.charge(temporary, new BenchmarkStorageBudget.Snapshot(true, 10, 20, Map.of("old", 100L), Map.of()), credentials));
        assertEquals(55, BenchmarkStorageBudget.charge(temporary, new BenchmarkStorageBudget.Snapshot(true, 15, 20, Map.of("old", 120L, "new", 30L), Map.of()), credentials));
        assertEquals(75, BenchmarkStorageBudget.charge(temporary, new BenchmarkStorageBudget.Snapshot(true, 10, 20, Map.of("latest", 20L), Map.of()), credentials));
    }
    @Test void existingTrialCannotSilentlyResetMissingSharedStorageBaseline() throws Exception {
        var directory = temporary.resolve("phase15-A-existing"); Files.createDirectory(directory); Files.writeString(directory.resolve("ownership.json"), "{}");
        var credentials = new BenchmarkEnvironment.Credentials("127.0.0.1", 3306, "fixture", "not-a-secret", "127.0.0.1", 6379, 2, "", "");
        assertThrows(IllegalStateException.class, () -> BenchmarkStorageBudget.charge(temporary, new BenchmarkStorageBudget.Snapshot(true, 0, 0, Map.of(), Map.of()), credentials));
    }
    @Test void controlTimeoutBoundsBothMissingInputAndBlockedOutput() throws Exception {
        var source = new PipedInputStream(); var writer = new PipedOutputStream(source);
        try (writer; var protocol = new BenchmarkProtocol(source, new ByteArrayOutputStream(), "trial")) {
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> protocol.receive("START", 1));
            assertTrue(System.nanoTime() - start >= 900_000_000);
        }
        var released = new java.util.concurrent.CountDownLatch(1);
        var blocked = new OutputStream() {
            @Override public void write(int value) throws IOException {
                try { released.await(); } catch (InterruptedException failure) { throw new IOException(failure); }
            }
        };
        var protocol = new BenchmarkProtocol(new ByteArrayInputStream(new byte[0]), blocked, "trial");
        protocol.controlTimeoutSeconds(1);
        try { assertThrows(IOException.class, () -> protocol.send("START", Map.of())); }
        finally { released.countDown(); protocol.close(); }
    }
    @Test void cleanupPreservesOriginalRawErrorIdentityAndDistinctFailures() {
        var original = new AssertionError("original"); var later = new AssertionError("later");
        assertSame(original, Phase15BenchmarkMain.preserve(original, later));
        assertSame(original, Phase15BenchmarkMain.preserve(original, later));
        assertEquals(1, original.getSuppressed().length);
        var runtime = new IllegalStateException("runtime");
        assertSame(later, Phase15BenchmarkMain.preserve(runtime, later));
        assertSame(runtime, later.getSuppressed()[0]);
    }
    @Test void zeroActiveCountAloneDoesNotProveDispatchCompletionOrResolvePending() {
        assertFalse(Phase15BenchmarkMain.serverIdle(0, 9, 9, 10, 0, false));
        assertFalse(Phase15BenchmarkMain.serverIdle(0, 10, 10, 10, 1, false));
        assertFalse(Phase15BenchmarkMain.serverIdle(1, 9, 10, 10, 0, false));
        assertFalse(Phase15BenchmarkMain.serverIdle(0, 10, 10, 10, 0, true));
        assertTrue(Phase15BenchmarkMain.serverIdle(0, 10, 10, 10, 0, false));
    }
    @Test void eventMatchingUsesAssignedUserOrdinalAndRejectsAmbiguousOrExtraEvents() {
        var c = new BenchmarkSpec.Case("probe", 1, 0, "same-pixel");
        List<Long> users = List.of(100L, 200L, 300L);
        var accepted = new Attempt(1, 2, 1, "measurement", 1, 2L, 3, Outcome.accepted, 200, 7L, 1L, null);
        var unknown = new Attempt(2, 1, 1, "measurement", 4, 5L, 6, Outcome.timeout, null, null, null, null);
        var event = new BenchmarkConsistency.Event(7, 300, 0, 0, 0, 17, 19, 1);
        assertSame(accepted, BenchmarkConsistency.uniqueMatch(event, Map.of(7L, accepted), Map.of(), Set.of(), users, c, 15));
        var uncertainEvent = new BenchmarkConsistency.Event(8, 200, 0, 0, 0, 17, 19, 2);
        assertSame(unknown, BenchmarkConsistency.uniqueMatch(uncertainEvent, Map.of(), Map.of(200L, List.of(unknown)), Set.of(), users, c, 15));
        assertNull(BenchmarkConsistency.uniqueMatch(uncertainEvent, Map.of(), Map.of(200L, List.of(unknown)), Set.of(2L), users, c, 15));
        var duplicate = new Attempt(258, 1, 2, "measurement", 7, 8L, 9, Outcome.timeout, null, null, null, null);
        assertNull(BenchmarkConsistency.uniqueMatch(uncertainEvent, Map.of(), Map.of(200L, List.of(unknown, duplicate)), Set.of(), users, c, 15));
        assertNull(BenchmarkConsistency.uniqueMatch(event, Map.of(), Map.of(200L, List.of(unknown)), Set.of(), users, c, 15));
    }
    @Test void protocolRejectsForeignTrialDuplicateReverseAndTimeout() throws Exception {
        try (var protocol = new BenchmarkProtocol(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), "trial")) {
            assertThrows(IllegalStateException.class, () -> protocol.send("MEASURE", Map.of()));
            assertThrows(IllegalStateException.class, () -> protocol.validate(BenchmarkJson.MAPPER.valueToTree(Map.of("type", "START", "trialId", "other", "phase", "prepare")), "START"));
            protocol.send("START", Map.of());
            assertThrows(IllegalStateException.class, () -> protocol.send("START", Map.of()));
            assertThrows(IOException.class, () -> protocol.receive("READY", 1));
        }
    }
    @Test void protocolAllowsOnlyExactPhasesAndRetainsLocalTransitionTime() throws Exception {
        String ready = BenchmarkJson.MAPPER.writeValueAsString(Map.of("type", "READY", "trialId", "trial", "phase", "prepare")) + "\n";
        try (var protocol = new BenchmarkProtocol(new ByteArrayInputStream(ready.getBytes(java.nio.charset.StandardCharsets.UTF_8)), new ByteArrayOutputStream(), "trial")) {
            protocol.send("START", Map.of()); protocol.receive("READY", 1); protocol.send("WARMUP", Map.of());
            assertEquals(3, protocol.transitions.size()); assertNotNull(protocol.transitions.getFirst().get("nanoTime"));
        }
    }
    @Test void globalOverridesAreExcludedBeforeAnyBeanOrFileAccess() {
        Map<String, String> prior = new HashMap<>();
        Map<String, String> hostile = Map.of("spring.config.import", "file:must-not-read.yml", "spring.config.additional-location", "file:must-not-read.yml",
                "spring.profiles.active", "stub", "spring.datasource.url", "jdbc:mysql://localhost/pixel_place", "spring.data.redis.database", "0", "pixel-place.wal.active-file", "data/wal/pixel-place.wal");
        try {
            hostile.forEach((key, value) -> { prior.put(key, System.getProperty(key)); System.setProperty(key, value); });
            var credentials = new BenchmarkEnvironment.Credentials("127.0.0.1", 3306, "fixture", "not-a-secret", "127.0.0.1", 6379, 2, "", "");
            var props = BenchmarkEnvironment.properties(credentials, "pixel_place_bench_trial", temporary.resolve("wal"), BenchmarkEnvironment.keys(), true);
            var env = BenchmarkEnvironment.isolated(props);
            for (var entry : props.entrySet()) assertEquals(String.valueOf(entry.getValue()), env.getProperty(entry.getKey()));
            assertFalse(env.getPropertySources().contains("systemEnvironment")); assertFalse(env.getPropertySources().contains("systemProperties"));
            assertArrayEquals(new String[]{"benchmark"}, env.getActiveProfiles()); assertEquals("", env.getProperty("spring.config.import"));
        } finally { prior.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }); }
    }
    @Test void sqlConnectionGuardRejectsProductionAndRegressionBeforeMutation() throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery("SELECT DATABASE()")).thenReturn(rows); when(rows.next()).thenReturn(true);
        for (String actual : List.of("pixel_place", "pixel_place_test", "pixel_place_bench_other")) {
            when(rows.getString(1)).thenReturn(actual);
            assertThrows(IllegalStateException.class, () -> BenchmarkGuards.requireCatalog(connection, "pixel_place_bench_trial"));
        }
        assertThrows(IllegalArgumentException.class, () -> BenchmarkGuards.requireCatalog(connection, "pixel_place"));
        verify(statement, never()).executeUpdate(anyString()); verify(connection, never()).prepareStatement(anyString());
    }
    @Test void everyHttpTerminalOutcomeIsExclusiveAndAcceptedRequiresAllActualFields() {
        var response = mock(HttpResponse.class); var user = new BenchmarkUsers.Assignment(3, 1); int[] expected = {1, 2, 3};
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"accepted\":true,\"eventSeq\":7,\"tileVersion\":2,\"x\":1,\"y\":2,\"color\":3}");
        assertEquals(Outcome.accepted, Phase15LoadClient.response(1, user, "measurement", 1, 2, response, null, expected).outcome());
        for (String invalid : List.of("{}", "{\"accepted\":true}", "broken", "{\"accepted\":false,\"eventSeq\":7,\"tileVersion\":2,\"x\":1,\"y\":2,\"color\":3}")) {
            when(response.body()).thenReturn(invalid);
            assertEquals(Outcome.malformed_200, Phase15LoadClient.response(1, user, "measurement", 1, 2, response, null, expected).outcome());
        }
        when(response.statusCode()).thenReturn(429); assertEquals(Outcome.policy_4xx, Phase15LoadClient.response(1, user, "measurement", 1, 2, response, null, expected).outcome());
        when(response.statusCode()).thenReturn(503); assertTrue(Phase15LoadClient.response(1, user, "measurement", 1, 2, response, null, expected).unknown());
        assertEquals(Outcome.timeout, Phase15LoadClient.response(1, user, "measurement", 1, 2, null, new java.util.concurrent.CompletionException(new HttpTimeoutException("timeout")), expected).outcome());
        assertEquals(Outcome.network_error, Phase15LoadClient.response(1, user, "measurement", 1, 2, null, new IOException("network"), expected).outcome());
    }
    @Test void captureDoesNotBecomeCurrentWhenRepeatedPendingAndFileMetadataIsMissing() throws Exception {
        var sample = new PixelMeasurement.Capture(100, 10, 20, 2);
        assertEquals("fresh", BenchmarkObserver.captureStatus(sample, 90, false));
        assertEquals("stale", BenchmarkObserver.captureStatus(sample, 100, false));
        assertEquals("pending-gap", BenchmarkObserver.captureStatus(sample, 100, true));
        assertEquals("not-observed", BenchmarkObserver.captureStatus(null, 100, false));
        assertEquals("missing", BenchmarkObserver.walMetadata(temporary.resolve("absent/wal")).get("status"));
        Files.writeString(temporary.resolve("wal"), "metadata only");
        assertEquals(13L, BenchmarkObserver.walMetadata(temporary.resolve("wal")).get("bytes"));
    }
}
