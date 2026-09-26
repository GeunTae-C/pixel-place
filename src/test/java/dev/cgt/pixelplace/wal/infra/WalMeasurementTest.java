package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 force 지연·회전의 별도 empty force와 요청별 내구성 횟수 유지 검증 */
class WalMeasurementTest {
    @TempDir Path temporary;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void actualFileForceCoversOnlyNewPrefixesAcrossBatchRotationAndSequenceGaps(boolean enabled) throws Exception {
        var measure = new PixelMeasurement(enabled, new SimpleMeterRegistry());
        var coverage = new java.util.ArrayList<java.util.List<Long>>();
        var files = new java.util.ArrayList<Path>();
        var previous = new java.util.HashMap<Path, Integer>();
        int limit = CODEC.serializeLine(record(1)).length * 2 + 10;
        try (var storage = new SegmentedWalStorage(properties(temporary.resolve("wal"), limit), PARSER, CODEC, measure) {
            @Override FileChannel openFileChannel(Path path, StandardOpenOption... options) throws IOException {
                var real = super.openFileChannel(path, options);
                if (!Arrays.asList(options).contains(StandardOpenOption.WRITE)) return real;
                var channel = spy(real);
                doAnswer(call -> {
                    call.callRealMethod();
                    var lines = Files.readAllLines(path);
                    int from = previous.getOrDefault(path, 0);
                    // 같은 파일의 과거 record나 seq 차이를 이번 force coverage에 포함하지 않음
                    coverage.add(lines.subList(from, lines.size()).stream().map(line -> PARSER.parseLine(line, 1).eventSeq()).toList());
                    files.add(path); previous.put(path, lines.size()); return null;
                }).when(channel).force(true);
                return channel;
            }
        }) {
            storage.appendBatchAndFsync(java.util.List.of(record(1), record(3), record(8)));
            storage.appendBatchAndFsync(java.util.List.of(record(10)));
            assertEquals(java.util.List.of(java.util.List.of(1L, 3L), java.util.List.of(), java.util.List.of(8L), java.util.List.of(10L)), coverage);
            assertNotEquals(files.get(0), files.get(1)); assertEquals(files.get(1), files.get(2)); assertEquals(files.get(2), files.get(3));
            var c = measure.walCounts();
            assertEquals(2, c.batchCalls()); assertEquals(4, c.batchRecords()); assertEquals(4, c.completedRecords());
            assertEquals(3, c.recordForces()); assertEquals(4, c.coveredRecords()); assertEquals(1, c.emptyForces());
            assertEquals(1, c.rotations()); assertEquals(0, c.failedForces());
            if (enabled) assertEquals(java.util.Map.of(1, 1L, 3, 1L), c.batchSizes()); else assertNull(c.batchSizes());
        }
    }
    @Test void actualRecordAndRotationForceRemainSeparateWithDelayAndFailure() throws Exception {
        var registry = new SimpleMeterRegistry(); var measurement = new PixelMeasurement(true, registry);
        try (var storage = new SegmentedWalStorage(properties(temporary.resolve("wal"), 1), PARSER, CODEC, measurement) {
            @Override FileChannel openFileChannel(Path path, StandardOpenOption... options) throws IOException {
                FileChannel real = super.openFileChannel(path, options);
                if (!Arrays.asList(options).contains(StandardOpenOption.WRITE)) return real;
                var channel = spy(real);
                doAnswer(call -> { Thread.sleep(15); return call.callRealMethod(); }).when(channel).force(true);
                return channel;
            }
        }) {
            storage.appendAndFsync(record(1)); storage.appendAndFsync(record(3));
            assertEquals(2, registry.get("pixel.place.operation").tags("operation", "record_force", "phase", "prepare", "outcome", "success").timer().count());
            assertEquals(1, registry.get("pixel.place.operation").tags("operation", "empty_force", "phase", "prepare", "outcome", "success").timer().count());
            assertTrue(registry.get("pixel.place.operation").tags("operation", "record_force", "phase", "prepare", "outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS) >= 20);
            assertEquals(3, storage.readAfter(0).walLastEventSeq()); assertEquals(2, storage.readAfter(0).records().size());
        }
        var failedRegistry = new SimpleMeterRegistry(); var failedMeasurement = new PixelMeasurement(true, failedRegistry);
        try (var storage = new SegmentedWalStorage(properties(temporary.resolve("failed"), 10000), PARSER, CODEC, failedMeasurement) {
            @Override FileChannel openFileChannel(Path path, StandardOpenOption... options) throws IOException {
                FileChannel real = super.openFileChannel(path, options);
                if (!Arrays.asList(options).contains(StandardOpenOption.WRITE)) return real;
                var channel = spy(real); doThrow(new IOException("force failed")).when(channel).force(true); return channel;
            }
        }) {
            assertThrows(IllegalStateException.class, () -> storage.appendAndFsync(record(1)));
            assertEquals(1, failedRegistry.get("pixel.place.operation").tags("operation", "record_force", "phase", "prepare", "outcome", "failure").timer().count());
            assertEquals(0, failedRegistry.get("pixel.place.operation").tags("operation", "record_force", "phase", "prepare", "outcome", "success").timer().count());
        }
    }
}
