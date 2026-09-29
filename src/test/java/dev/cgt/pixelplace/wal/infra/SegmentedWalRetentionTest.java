package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRetentionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 prefix 삭제·전체 검사·새 suffix 재열거와 A에서 이관된 보존 assertion 검증 */
class SegmentedWalRetentionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"9,2", "5,1", "2,0"})
    void deletesOnlyWholeEligiblePrefixAndPreservesNamespaceOutside(long checkpoint, int deleted) throws Exception {
        Path base = directory.resolve("custom.wal");
        var properties = properties(base, 10000);
        try (var storage = new SegmentedWalStorage(properties, PARSER, CODEC, dev.cgt.pixelplace.measurement.Measurements.disabled(), new dev.cgt.pixelplace.wal.infra.TestWalFileDurability())) {
            properties.setActiveFile(directory.resolve("changed"));
            records(storage.segmentPath(0), 2, 5);
            records(storage.segmentPath(1), 9);
            records(storage.segmentPath(2), 14, 21);
            Path outside = directory.resolve("custom.wal.bak");
            Files.writeString(outside, "unrelated bytes");
            byte[] active = Files.readAllBytes(storage.segmentPath(2));
            assertEquals(WalRetentionResult.completed(deleted), storage.deleteCommittedPrefix(checkpoint));
            for (int i = 0; i < 3; i++) assertEquals(i >= deleted, Files.exists(storage.segmentPath(i)));
            assertArrayEquals(active, Files.readAllBytes(storage.segmentPath(2)));
            assertEquals("unrelated bytes", Files.readString(outside));
            assertFalse(Files.exists(directory.resolve("changed")));
        }
        // 실제 정리 후 이전 객체를 닫고 새 writer가 남은 번호·tail을 채택해야 함
        try (var fresh = storage(base, 10000)) {
            assertEquals(21, fresh.readAfter(0).walLastEventSeq());
            fresh.appendAndFsync(record(25));
            assertEquals(25, fresh.readAfter(21).walLastEventSeq());
            assertFalse(Files.exists(fresh.segmentPath(3)));
            assertEquals(deleted == 0, Files.exists(base));
        }
    }

    @Test
    void checkpointCrossingFileIsNeitherTruncatedNorSkipped() throws Exception {
        try (var storage = storage(directory.resolve("wal"), 10000)) {
            records(storage.segmentPath(0), 2);
            records(storage.segmentPath(1), 5, 9);
            records(storage.segmentPath(2), 14);
            byte[] crossing = Files.readAllBytes(storage.segmentPath(1));
            assertEquals(WalRetentionResult.completed(1), storage.deleteCommittedPrefix(5));
            assertArrayEquals(crossing, Files.readAllBytes(storage.segmentPath(1)));
            assertEquals(List.of(5L, 9L, 14L), storage.readAfter(0).records().stream().map(r -> r.eventSeq()).toList());
        }
    }

    @Test
    void emptyActiveKeepsPreviousTailUntilNewRecordAllowsItsDeletion() throws Exception {
        try (var storage = storage(directory.resolve("wal"), 10000)) {
            records(storage.segmentPath(0), 2);
            records(storage.segmentPath(1), 5);
            records(storage.segmentPath(2));
            assertEquals(WalRetentionResult.completed(1), storage.deleteCommittedPrefix(5));
            assertTrue(Files.exists(storage.segmentPath(1)));
            assertEquals(0, Files.size(storage.segmentPath(2)));
            storage.appendAndFsync(record(9));
            assertEquals(WalRetentionResult.completed(1), storage.deleteCommittedPrefix(5));
            assertEquals(List.of(9L), storage.readAfter(0).records().stream().map(r -> r.eventSeq()).toList());
            assertTrue(Files.exists(storage.segmentPath(2)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyActualRecordFileAndOptionalEmptyActiveAreAlwaysPreserved(boolean emptyActive) throws Exception {
        try (var storage = storage(directory.resolve("wal"), 10000)) {
            records(storage.segmentPath(0), 2);
            if (emptyActive) records(storage.segmentPath(1));
            assertEquals(WalRetentionResult.completed(0), storage.deleteCommittedPrefix(2));
            assertTrue(Files.exists(storage.segmentPath(0)));
            assertEquals(emptyActive, Files.exists(storage.segmentPath(1)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"late-json", "late-newline", "checkpoint-above-tail", "hole", "closed-empty", "duplicate", "namespace", "empty", "isolated-empty"})
    void validatesEntireFileSetBeforeAnyDelete(String failure) throws Exception {
        try (var storage = spy(storage(directory.resolve("wal"), 10000))) {
            if (!failure.equals("empty")) {
                records(storage.segmentPath(0), 2);
                records(storage.segmentPath(1), 5);
                records(storage.segmentPath(2), 9);
            }
            switch (failure) {
                case "late-json" -> Files.writeString(storage.segmentPath(2), "invalid\n");
                case "late-newline" -> Files.writeString(storage.segmentPath(2), "invalid");
                case "hole" -> Files.delete(storage.segmentPath(1));
                case "closed-empty" -> records(storage.segmentPath(1));
                case "duplicate" -> records(storage.segmentPath(2), 5);
                case "namespace" -> Files.writeString(directory.resolve("wal.seg-wrong"), "preserved");
                case "isolated-empty" -> {
                    Files.delete(storage.segmentPath(0)); Files.delete(storage.segmentPath(1));
                    records(storage.segmentPath(2));
                }
            }
            assertThrows(RuntimeException.class, () -> storage.deleteCommittedPrefix(failure.equals("checkpoint-above-tail") ? 10 : 5));
            verify(storage, never()).deleteFile(any());
            assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
        }
    }

    @ParameterizedTest
    @CsvSource({"0,false,false", "1,false,false", "0,true,false", "1,true,false", "0,false,true", "1,false,true", "0,true,true", "1,true,true"})
    void syscallDelayStopsImmediatelyAndNextCallAcceptsActualRemainingSuffix(int failedIndex, boolean security, boolean deletedBeforeError) throws Exception {
        Path base = directory.resolve("wal");
        try (var storage = spy(storage(base, 1))) {
            // 실제 writer 회전으로 closed handle이 닫힌 상태에서 Windows delete 수행
            storage.appendAndFsync(record(2)); storage.appendAndFsync(record(5));
            storage.appendAndFsync(record(9)); storage.appendAndFsync(record(14));
            Path failed = storage.segmentPath(failedIndex);
            Path next = storage.segmentPath(failedIndex + 1);
            doAnswer(inv -> {
                if (deletedBeforeError) inv.callRealMethod();
                if (security) throw new SecurityException("fixture");
                throw new IOException("fixture");
            }).when(storage).deleteFile(failed);
            var result = storage.deleteCommittedPrefix(9);
            assertEquals(WalRetentionResult.delayed(failedIndex, failedIndex,
                    security ? WalRetentionResult.DelayKind.SECURITY : WalRetentionResult.DelayKind.IO), result);
            verify(storage, never()).deleteFile(next);
            assertEquals(!deletedBeforeError, Files.exists(failed));
            assertEquals(14, storage.readAfter(9).walLastEventSeq());
            storage.appendAndFsync(record(21));
            doCallRealMethod().when(storage).deleteFile(failed);
            assertEquals(WalRetentionResult.completed(3 - failedIndex - (deletedBeforeError ? 1 : 0)), storage.deleteCommittedPrefix(9));
            assertEquals(List.of(14L, 21L), storage.readAfter(0).records().stream().map(r -> r.eventSeq()).toList());
        }
        try (var fresh = storage(base, 1)) {
            fresh.appendAndFsync(record(25));
            assertEquals(25, fresh.readAfter(21).walLastEventSeq());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void unexpectedDeleteFailurePoisonsAndPreservesCloseErrorPriority(boolean firstError, boolean closeError) throws Exception {
        try (var storage = spy(new ControlledStorage(directory.resolve("wal"), 1))) {
            storage.appendAndFsync(record(2)); storage.appendAndFsync(record(5)); storage.appendAndFsync(record(9));
            Throwable first = firstError ? new AssertionError("delete") : new IllegalArgumentException("delete");
            Throwable later = closeError ? new AssertionError("close") : new IOException("close");
            Path firstPath = storage.segmentPath(0), nextPath = storage.segmentPath(1);
            doThrow(first).when(storage).deleteFile(firstPath);
            doThrow(later).when(storage.writers.getLast()).close();
            Throwable thrown = assertThrows(Throwable.class, () -> storage.deleteCommittedPrefix(5));
            Throwable primary = firstError || !closeError ? first : later;
            assertSame(primary, thrown);
            assertArrayEquals(new Throwable[]{primary == first ? later : first}, thrown.getSuppressed());
            verify(storage, never()).deleteFile(nextPath);
            clearInvocations(storage);
            assertThrows(IllegalStateException.class, () -> storage.deleteCommittedPrefix(5));
            assertThrows(IllegalStateException.class, () -> storage.appendAndFsync(record(14)));
            assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
            verify(storage, never()).openDirectory(any());
            verify(storage, never()).readAttributes(any());
            verify(storage, never()).deleteFile(any());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidCheckpointDoesNotAccessFilesOrPoison(long checkpoint) throws Exception {
        try (var storage = spy(storage(directory.resolve("wal"), 1))) {
            assertThrows(IllegalArgumentException.class, () -> storage.deleteCommittedPrefix(checkpoint));
            verify(storage, never()).openDirectory(any());
            verify(storage, never()).readAttributes(any());
            verify(storage, never()).deleteFile(any());
            storage.appendAndFsync(record(2));
            assertEquals(WalRetentionResult.completed(0), storage.deleteCommittedPrefix(2));
        }
    }

    @Test
    void resultRejectsImpossibleCountsAndDelayLocationsWithoutMutablePayload() {
        assertThrows(IllegalArgumentException.class, () -> WalRetentionResult.completed(-1));
        assertThrows(NullPointerException.class, () -> new WalRetentionResult(0, null));
        assertThrows(IllegalArgumentException.class, () -> WalRetentionResult.delayed(0, -1, WalRetentionResult.DelayKind.IO));
        assertThrows(NullPointerException.class, () -> WalRetentionResult.delayed(0, 0, null));
        assertEquals(Optional.empty(), WalRetentionResult.completed(0).delay());
    }
}
