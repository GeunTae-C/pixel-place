package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.util.List;
import java.util.stream.LongStream;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 크기 경계는 실제 codec 길이로 구성. segment 번호와 eventSeq는 의도적으로 분리 */
class SegmentedWalAppendTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints={-1,0,1})
    void recordBoundaryRotatesOnlyWhenSumExceedsMaximum(int extra) throws Exception {
        int size = CODEC.serializeLine(record(2)).length;
        try(var storage = storage(directory.resolve("wal"), 2L*size+extra)) {
            storage.appendAndFsync(record(2));
            byte[] first = Files.readAllBytes(storage.segmentPath(0));
            storage.appendAndFsync(record(5));
            assertEquals(extra < 0, Files.exists(storage.segmentPath(1)));
            if(extra < 0) assertArrayEquals(first, Files.readAllBytes(storage.segmentPath(0)));
            else assertEquals(2L*size, Files.size(storage.segmentPath(0)));
            assertEquals(List.of(record(2),record(5)),storage.readAfter(0).records());
        }
    }

    @ParameterizedTest
    @ValueSource(longs={1, 9223372036854775807L})
    void oversizedSingleRecordAndLongMaximumNeverSplitOrCreateEmptyChain(long maximum) throws Exception {
        try(var storage = storage(directory.resolve("wal"), maximum)) {
            for(long seq : new long[]{2,5,9,14,21}) storage.appendAndFsync(record(seq));
            assertEquals(List.of(record(2),record(5),record(9),record(14),record(21)),storage.readAfter(0).records());
            assertEquals(maximum == 1 ? 5 : 1,storage.inspectFiles().size());
            assertTrue(storage.inspectFiles().stream().allMatch(file -> file.size()>0));
        }
    }

    @ParameterizedTest
    @ValueSource(ints={0,1})
    void existingActiveAtOrAboveThresholdRotatesBeforeRecord(int beyond) throws Exception {
        int size = CODEC.serializeLine(record(2)).length;
        try(var storage = storage(directory.resolve("wal"), size-beyond)) {
            records(storage.segmentPath(0),2);
            byte[] before = Files.readAllBytes(storage.segmentPath(0));
            assertEquals(2, storage.readAfter(0).walLastEventSeq());
            assertFalse(Files.exists(storage.segmentPath(1)));
            storage.appendAndFsync(record(9));
            assertArrayEquals(before,Files.readAllBytes(storage.segmentPath(0)));
            assertEquals(9,storage.readAfter(0).walLastEventSeq());
            assertTrue(Files.exists(storage.segmentPath(1)));
        }
    }

    @Test
    void lastSegmentNumberExhaustionPoisonsWithoutChangingFiles() throws Exception {
        try(var storage = storage(directory.resolve("wal"),1)) {
            Path last = storage.segmentPath(Long.MAX_VALUE);
            records(last,9);
            byte[] before = Files.readAllBytes(last);
            assertThrows(IllegalStateException.class, () -> storage.appendAndFsync(record(14)));
            assertArrayEquals(before, Files.readAllBytes(last));
            assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
            try(var files=Files.list(directory)) { assertEquals(1, files.count()); }
        }
    }

    @ParameterizedTest
    @ValueSource(ints={0,4,7})
    void restartedSuffixAdoptsEmptyActiveWithoutCreatingAnotherSegment(int first) throws Exception {
        Path base = directory.resolve("wal");
        try(var fixture = storage(base,1)) {
            records(fixture.segmentPath(first),9);
            records(fixture.segmentPath(first+1));
        }
        try(var restarted = storage(base,1)) {
            assertEquals(9,restarted.readAfter(9).walLastEventSeq());
            restarted.appendAndFsync(record(14));
            assertFalse(Files.exists(restarted.segmentPath(first+2)));
            assertEquals(List.of(record(14)),restarted.readAfter(9).records());
            assertEquals(14,restarted.readAfter(0).walLastEventSeq());
            if(first>0) assertFalse(Files.exists(base));
        }
    }

    @Test
    void restartAfterOldCloseAdoptsPreviousActiveAndReevaluatesRotation() throws Exception {
        Path base = directory.resolve("wal");
        try(var before=storage(base,1)) { before.appendAndFsync(record(2)); }
        try(var after=storage(base,1)) {
            after.appendAndFsync(record(5));
            assertEquals(2,after.inspectFiles().size());
            assertEquals(5,after.readAfter(0).walLastEventSeq());
        }
    }

    @Test
    void everyRecordAndNewRotationCandidateUseForceTrueOnly() throws Exception {
        try(var storage = new ControlledStorage(directory.resolve("wal"),1)) {
            storage.appendAndFsync(record(2));
            storage.appendAndFsync(record(5));
            verify(storage.writers.get(0),times(1)).force(true);
            verify(storage.writers.get(1),times(2)).force(true);
            for(var channel:storage.writers) verify(channel,never()).force(false);
        }
    }

    @Test
    void configurationMutationCannotMoveOrResizeInitializedStore() throws Exception {
        Path base=directory.resolve("wal");
        var properties=properties(base,Long.MAX_VALUE);
        try(var storage=new SegmentedWalStorage(properties,PARSER,CODEC)) {
            properties.setActiveFile(directory.resolve("other")); properties.setMaxSegmentBytes(1);
            storage.appendAndFsync(record(2)); storage.appendAndFsync(record(5));
            assertEquals(1,storage.inspectFiles().size());
            assertTrue(Files.exists(base)); assertFalse(Files.exists(directory.resolve("other")));
        }
    }

    @Test
    void scanSuccessDoesNotSkipFullValidationAtFirstWriterUse() throws Exception {
        try(var storage=storage(directory.resolve("wal"),1000)) {
            records(storage.segmentPath(0),2);
            assertEquals(2,storage.readAfter(0).walLastEventSeq());
            Files.writeString(storage.segmentPath(0),"partial");
            assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(5)));
            assertEquals("partial",Files.readString(storage.segmentPath(0)));
        }
    }
}
