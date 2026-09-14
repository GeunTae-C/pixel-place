package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.file.*;
import java.util.List;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 모든 파일의 검증과 실제 tail·민감하지 않은 실패 경계. checkpoint 아래 손상도 거부 */
class SegmentedWalScanTest {
    @TempDir Path directory;

    @Test
    void scansEverySegmentAndKeepsTailIndependentOfCheckpoint() throws Exception {
        var storage = storage(directory.resolve("wal"), 1);
        records(storage.segmentPath(0), 2,5);
        records(storage.segmentPath(1), 9);
        records(storage.segmentPath(2), 14,21);
        byte[] legacy = Files.readAllBytes(storage.segmentPath(0));
        var batch = storage.readAfter(9);
        assertEquals(List.of(record(14),record(21)), batch.records());
        assertEquals(21, batch.walLastEventSeq());
        assertArrayEquals(legacy, Files.readAllBytes(storage.segmentPath(0)));
        assertThrows(UnsupportedOperationException.class, () -> batch.records().clear());
    }

    @ParameterizedTest
    @ValueSource(ints={0,4})
    void emptyLatestActiveKeepsPreviousActualTail(int first) throws Exception {
        var storage = storage(directory.resolve("wal"), 1);
        records(storage.segmentPath(first), 9);
        records(storage.segmentPath(first+1));
        assertEquals(9, storage.readAfter(9).walLastEventSeq());
        assertTrue(storage.readAfter(9).records().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings={"2,2", "5,2", "2/2", "5/2"})
    void duplicateAndReverseSequencesFailEvenBelowCheckpoint(String input) throws Exception {
        var storage = storage(directory.resolve("wal"), 1);
        String[] seqs = input.split("[,/]");
        if(input.contains("/")) {
            records(storage.segmentPath(0), Long.parseLong(seqs[0]));
            records(storage.segmentPath(1), Long.parseLong(seqs[1]));
        } else records(storage.segmentPath(0), Long.parseLong(seqs[0]), Long.parseLong(seqs[1]));
        assertThrows(IllegalStateException.class, () -> storage.readAfter(100));
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void missingNewlineInActiveOrClosedFailsWithoutTruncation(boolean closed) throws Exception {
        var storage = storage(directory.resolve("wal"), 1);
        byte[] valid = CODEC.serializeLine(record(2));
        byte[] partial = java.util.Arrays.copyOf(valid, valid.length-1);
        Files.write(storage.segmentPath(0), partial);
        if(closed) records(storage.segmentPath(1), 5);
        assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
        assertArrayEquals(partial, Files.readAllBytes(storage.segmentPath(0)));
    }

    @ParameterizedTest
    @ValueSource(strings={"blank", "json", "field", "utf8"})
    void oldClosedCorruptionFailsWholeBatchAndPoisonsStorage(String kind) throws Exception {
        var storage = spy(storage(directory.resolve("wal"), 1));
        byte[] corrupt = switch(kind) {
            case "blank" -> new byte[]{'\n'};
            case "json" -> "not-json\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "field" -> CODEC.serializeLine(record(0));
            default -> new byte[]{(byte)0xc3, (byte)0x28, '\n'};
        };
        Files.write(storage.segmentPath(0), corrupt);
        records(storage.segmentPath(1), 9);
        assertThrows(RuntimeException.class, () -> storage.readAfter(9));
        assertArrayEquals(corrupt, Files.readAllBytes(storage.segmentPath(0)));
        clearInvocations(storage);
        assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
        verify(storage, never()).openDirectory(any());
    }

    @Test
    void parserRuntimeFailureDoesNotExposeOriginalThrowableGraph() throws Exception {
        String sensitive = "PRIVATE_RECORD_SENTINEL";
        WalRecordParser parser = mock(WalRecordParser.class);
        RuntimeException failure = new IllegalArgumentException(sensitive, new RuntimeException(sensitive));
        failure.addSuppressed(new RuntimeException(sensitive));
        when(parser.parseLine(anyString(), anyLong())).thenThrow(failure);
        var storage = new SegmentedWalStorage(properties(directory.resolve("wal"),1),parser,CODEC);
        records(storage.segmentPath(0), 2);
        var thrown = assertThrows(IllegalArgumentException.class, () -> storage.readAfter(0));
        assertTrue(thrown.getMessage().contains("segment=0"));
        assertTrue(thrown.getMessage().contains("lineNumber=1"));
        StringWriter text = new StringWriter(); thrown.printStackTrace(new PrintWriter(text));
        assertFalse(text.toString().contains(sensitive));
    }

    @Test
    void negativeCheckpointIsRejectedWithoutIoOrPoison() throws Exception {
        var storage = spy(storage(directory.resolve("wal"), 1));
        assertThrows(IllegalArgumentException.class, () -> storage.readAfter(-1));
        verify(storage, never()).openDirectory(any());
        assertEquals(0, storage.readAfter(0).walLastEventSeq());
    }

    @Test
    void readerCloseErrorTakesPriorityOverReadIOExceptionAndKeepsIdentity() throws Exception {
        var storage = spy(storage(directory.resolve("wal"), 1));
        records(storage.segmentPath(0), 2);
        BufferedReader reader = mock(BufferedReader.class);
        IOException read = new IOException("read failure");
        Error close = new AssertionError("close failure");
        when(reader.readLine()).thenThrow(read);
        doThrow(close).when(reader).close();
        Path active = storage.segmentPath(0);
        doReturn(reader).when(storage).openReader(active);
        assertSame(close, assertThrows(AssertionError.class, () -> storage.readAfter(0)));
        assertArrayEquals(new Throwable[]{read}, close.getSuppressed());
        assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
    }
}
