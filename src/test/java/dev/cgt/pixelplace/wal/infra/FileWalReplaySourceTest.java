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

// 파일 기반 WAL replay가 lastFlushedEventSeq 이후 적용 대상과 WAL 전체 마지막 eventSeq를 분리해서 반환하는지 검증한다.
class FileWalReplaySourceTest {

    @TempDir
    Path tempDir;

    @Test
    // WAL 파일이 아직 없을 수 있는 최초 부팅은 빈 replay와 walLastEventSeq=0으로 시작한다.
    void missingWalReturnsEmptyBatch() {
        FileWalReplaySource source = source(tempDir.resolve("missing.wal"));

        WalReplayBatch batch = source.readAfter(10L);

        assertEquals(0, batch.records().size());
        assertEquals(0L, batch.walLastEventSeq());
    }

    @Test
    // replay 대상은 lastFlushedEventSeq 초과 이벤트뿐이지만 walLastEventSeq는 파일 마지막 이벤트를 따라야 한다.
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
    // lastFlushedEventSeq가 WAL 끝 이상이어도 seed 계산을 위해 walLastEventSeq는 유지한다.
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
    // active WAL 경로가 디렉터리면 파일 fsync와 순차 replay 계약이 깨진 상태로 본다.
    void rejectsNonRegularWalPath() {
        assertThrows(IllegalStateException.class, () -> source(tempDir).readAfter(0L));
    }

    @Test
    // eventSeq는 WAL 파일 안에서 엄격히 증가해야 replay 순서를 신뢰할 수 있다.
    void rejectsNonIncreasingEventSeq() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, """
                {"eventSeq":2,"userId":7,"z":0,"tx":0,"ty":0,"x":0,"y":0,"color":1,"createdAt":"2026-04-03T06:00:00.001"}
                {"eventSeq":2,"userId":7,"z":0,"tx":1,"ty":0,"x":256,"y":0,"color":2,"createdAt":"2026-04-03T06:00:00.002"}
                """);

        assertThrows(IllegalStateException.class, () -> source(wal).readAfter(0L));
    }

    @Test
    // 잘못된 레코드 값은 복구 중 조용히 누락하지 않고 실패시킨다.
    void rejectsInvalidRecordValue() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        Files.writeString(wal, """
                {"eventSeq":1,"userId":7,"z":0,"tx":0,"ty":0,"x":0,"y":0,"color":300,"createdAt":"2026-04-03T06:00:00.001"}
                """);

        assertThrows(IllegalArgumentException.class, () -> source(wal).readAfter(0L));
    }

    private FileWalReplaySource source(Path activeFile) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(activeFile);
        WalRecordParser parser = new WalRecordParser(new ObjectMapper());
        return new FileWalReplaySource(properties, parser);
    }
}
