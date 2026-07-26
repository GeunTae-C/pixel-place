package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

// 파일 기반 WAL replay가 lastFlushedEventSeq 이후 적용 대상과 WAL 전체 마지막 eventSeq를 분리해서 반환하는지 검증함
class FileWalReplaySourceTest {

    @TempDir
    Path tempDir;

    @Test
    // WAL 파일이 아직 없을 수 있는 최초 부팅은 빈 replay와 walLastEventSeq=0으로 시작함
    void missingWalReturnsEmptyBatch() {
        FileWalReplaySource source = source(tempDir.resolve("missing.wal"));

        WalReplayBatch batch = source.readAfter(10L);

        assertEquals(0, batch.records().size());
        assertEquals(0L, batch.walLastEventSeq());
    }

    @Test
    void emptyWalReturnsEmptyBatch() throws IOException {
        Path wal = tempDir.resolve("empty.wal");
        Files.createFile(wal);

        WalReplayBatch batch = source(wal).readAfter(0L);

        assertEquals(0, batch.records().size());
        assertEquals(0L, batch.walLastEventSeq());
    }

    @Test
    // newline으로 닫힌 단일 JSON record의 정상 경계
    void newlineTerminatedJsonIsReplayed() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, recordJson(1L) + "\n");

        WalReplayBatch batch = source(wal).readAfter(0L);

        assertEquals(1, batch.records().size());
        assertEquals(1L, batch.records().get(0).eventSeq());
        assertEquals(1L, batch.walLastEventSeq());
    }

    @Test
    // replay 대상은 lastFlushedEventSeq 초과 이벤트뿐이지만 walLastEventSeq는 파일 마지막 이벤트를 따라야 함
    void readAfterFiltersRecordsAndKeepsWalLastEventSeq() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, """
                {"eventSeq":1,"userId":7,"z":0,"tx":0,"ty":0,"x":0,"y":0,"color":1,"createdAt":"2026-04-03T06:00:00.001"}
                {"eventSeq":2,"userId":7,"z":0,"tx":1,"ty":0,"x":256,"y":0,"color":2,"createdAt":"2026-04-03T06:00:00.002"}
                {"eventSeq":3,"userId":7,"z":0,"tx":1,"ty":1,"x":256,"y":256,"color":3,"createdAt":"2026-04-03T06:00:00.003"}
                """);

        WalReplayBatch batch = source(wal).readAfter(1L);

        assertEquals(2, batch.records().size());
        assertEquals(2L, batch.records().get(0).eventSeq());
        assertEquals(3L, batch.records().get(1).eventSeq());
        assertEquals(3L, batch.walLastEventSeq());
    }

    @Test
    // lastFlushedEventSeq가 WAL 끝 이상이어도 seed 계산을 위해 walLastEventSeq는 유지함
    void readAfterCanReturnNoRecordsButKeepWalLastEventSeq() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, """
                {"eventSeq":1,"userId":7,"z":0,"tx":0,"ty":0,"x":0,"y":0,"color":1,"createdAt":"2026-04-03T06:00:00.001"}
                {"eventSeq":2,"userId":7,"z":0,"tx":1,"ty":0,"x":256,"y":0,"color":2,"createdAt":"2026-04-03T06:00:00.002"}
                """);

        WalReplayBatch batch = source(wal).readAfter(2L);

        assertEquals(0, batch.records().size());
        assertEquals(2L, batch.walLastEventSeq());
    }

    @Test
    // 완전한 JSON 형태여도 newline 없는 tail은 durable record로 간주하면 안 됨
    void completeJsonWithoutFinalNewlineFailsBeforeReplay() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, recordJson(1L));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> source(wal).readAfter(0L)
        );

        assertTrue(exception.getMessage().contains("not newline-terminated"));
    }

    @Test
    void incompleteJsonWithoutFinalNewlineFailsBeforeReplay() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, "{\"eventSeq\":1");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> source(wal).readAfter(0L)
        );

        assertTrue(exception.getMessage().contains("not newline-terminated"));
    }

    @Test
    // WAL framing 손상을 JSON parsing 오류보다 먼저 판정하는 순서 고정
    void newlineTerminationIsValidatedBeforeJsonParsing() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, "{");
        WalRecordParser parser = mock(WalRecordParser.class);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> source(wal, parser).readAfter(0L)
        );

        assertTrue(exception.getMessage().contains("not newline-terminated"));
        verifyNoInteractions(parser);
    }

    @Test
    void strictlyIncreasingEventSeqWithGapsIsReplayed() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        writeTerminatedRecords(wal, 1L, 3L, 7L);

        WalReplayBatch batch = source(wal).readAfter(0L);

        assertEquals(3, batch.records().size());
        assertEquals(7L, batch.walLastEventSeq());
    }

    @Test
    // active WAL 경로가 디렉터리면 파일 fsync와 순차 replay 계약이 깨진 상태로 봄
    void rejectsNonRegularWalPath() {
        assertThrows(IllegalStateException.class, () -> source(tempDir).readAfter(0L));
    }

    @Test
    // 동일 eventSeq는 active WAL 전체 순서를 신뢰할 수 없는 corruption
    void rejectsDuplicateEventSeq() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, """
                {"eventSeq":2,"userId":7,"z":0,"tx":0,"ty":0,"x":0,"y":0,"color":1,"createdAt":"2026-04-03T06:00:00.001"}
                {"eventSeq":2,"userId":7,"z":0,"tx":1,"ty":0,"x":256,"y":0,"color":2,"createdAt":"2026-04-03T06:00:00.002"}
                """);

        assertThrows(IllegalStateException.class, () -> source(wal).readAfter(0L));
    }

    @Test
    void rejectsSmallerEventSeqAfterLargerEventSeq() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        writeTerminatedRecords(wal, 3L, 2L);

        assertThrows(IllegalStateException.class, () -> source(wal).readAfter(0L));
    }

    @Test
    // 반환 대상 filtering 전에 checkpoint 이전 record 순서까지 검증하는 계약
    void rejectsOrderViolationBeforeCheckpoint() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        writeTerminatedRecords(wal, 2L, 1L, 4L);

        assertThrows(IllegalStateException.class, () -> source(wal).readAfter(3L));
    }

    @Test
    void eventSeq100Then102IsValidWithout101() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        writeTerminatedRecords(wal, 100L, 102L);

        WalReplayBatch batch = source(wal).readAfter(0L);

        assertEquals(2, batch.records().size());
        assertEquals(100L, batch.records().get(0).eventSeq());
        assertEquals(102L, batch.records().get(1).eventSeq());
        assertEquals(102L, batch.walLastEventSeq());
    }

    @Test
    // 잘못된 레코드 값은 복구 중 조용히 누락하지 않고 실패시킴
    void rejectsInvalidRecordValue() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, """
                {"eventSeq":1,"userId":7,"z":0,"tx":0,"ty":0,"x":0,"y":0,"color":300,"createdAt":"2026-04-03T06:00:00.001"}
                """);

        assertThrows(IllegalArgumentException.class, () -> source(wal).readAfter(0L));
    }

    private FileWalReplaySource source(Path activeFile) {
        return source(activeFile, new WalRecordParser(new ObjectMapper()));
    }

    private FileWalReplaySource source(Path activeFile, WalRecordParser parser) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(activeFile);
        return new FileWalReplaySource(properties, parser);
    }

    private void writeTerminatedRecords(Path wal, long... eventSeqs) throws IOException {
        StringBuilder content = new StringBuilder();
        for (long eventSeq : eventSeqs) {
            content.append(recordJson(eventSeq)).append('\n');
        }
        Files.writeString(wal, content);
    }

    private String recordJson(long eventSeq) {
        return """
                {"eventSeq":%d,"userId":7,"z":0,"tx":0,"ty":0,"x":0,"y":0,"color":1,"createdAt":"2026-04-03T06:00:00.001"}
                """.formatted(eventSeq).strip();
    }
}
