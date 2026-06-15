package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 파일 WAL appender는 append + fsync 성공을 write 내구성 경계로 삼으므로 실제 임시 파일에 기록해 검증함
class FileWalAppenderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WalRecordParser parser = new WalRecordParser(objectMapper);

    @Test
    // active WAL이 없으면 append 시점에 파일을 만들고 승인 이벤트 한 줄을 기록해야 함
    void appendAndFsyncCreatesWalFileAndWritesOneLine() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.close();

        List<String> lines = Files.readAllLines(wal);
        assertEquals(1, lines.size());
        assertEquals(1L, parser.parseLine(lines.get(0), 1).eventSeq());
    }

    @Test
    // append는 직렬화되어야 하므로 여러 호출 결과가 eventSeq 순서대로 여러 줄에 남아야 함
    void appendAndFsyncWritesMultipleLinesInEventSeqOrder() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.appendAndFsync(record(2L));
        appender.appendAndFsync(record(3L));
        appender.close();

        List<String> lines = Files.readAllLines(wal);
        assertEquals(3, lines.size());
        assertEquals(1L, parser.parseLine(lines.get(0), 1).eventSeq());
        assertEquals(2L, parser.parseLine(lines.get(1), 2).eventSeq());
        assertEquals(3L, parser.parseLine(lines.get(2), 3).eventSeq());
    }

    @Test
    // appender가 기록한 줄은 기존 WalRecordParser가 다시 읽을 수 있어야 boot recovery와 호환됨
    void writtenLineCanBeParsedByWalRecordParser() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.close();

        WalRecord parsed = parser.parseLine(Files.readAllLines(wal).get(0), 1);
        assertEquals(768, parsed.x());
        assertEquals(1280, parsed.y());
        assertEquals(17, parsed.color());
    }

    @Test
    // 운영 환경에서 WAL 디렉터리가 아직 없을 수 있으므로 append 시점에 parent directory를 만듦
    void appendAndFsyncCreatesMissingParentDirectory() {
        Path wal = tempDir.resolve("missing").resolve("nested").resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.close();

        assertTrue(Files.isRegularFile(wal));
    }

    @Test
    // activeFile 경로가 디렉터리면 JSON Lines append와 fsync 계약을 만족할 수 없어 실패해야 함
    void appendAndFsyncFailsWhenActiveFileIsDirectory() {
        FileWalAppender appender = appender(tempDir);

        assertThrows(IllegalStateException.class, () -> appender.appendAndFsync(record(1L)));
    }

    @Test
    // null record는 내구성 원본에 쓸 수 있는 승인 이벤트가 아니므로 파일 접근 전에 거부함
    void appendAndFsyncRejectsNullRecord() {
        FileWalAppender appender = appender(tempDir.resolve("active.wal"));

        assertThrows(NullPointerException.class, () -> appender.appendAndFsync(null));
    }

    @Test
    // 기록된 WAL은 FileWalReplaySource가 다시 읽어 replay 대상 이벤트로 반환해야 함
    void writtenRecordsCanBeReadByFileWalReplaySource() {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);
        appender.appendAndFsync(record(1L));
        appender.appendAndFsync(record(2L));
        appender.close();

        WalReplayBatch batch = replaySource(wal).readAfter(0L);

        assertEquals(2, batch.records().size());
        assertEquals(1L, batch.records().get(0).eventSeq());
        assertEquals(2L, batch.records().get(1).eventSeq());
        assertEquals(2L, batch.walLastEventSeq());
    }

    @Test
    // 새 WAL 파일 첫 append는 파일 생성 메타데이터 내구화를 위해 force(true) 정책을 선택함
    void forcePolicyUsesMetadataForceForNewWalFile() {
        FileWalAppender appender = appender(tempDir.resolve("new.wal"));

        assertTrue(appender.shouldForceMetadataAfterOpen(true));
    }

    @Test
    // 기존 WAL 파일 append는 파일 내용 내구화만 필요하므로 force(false) 정책을 선택함
    void forcePolicyUsesContentForceForExistingWalFile() {
        FileWalAppender appender = appender(tempDir.resolve("existing.wal"));

        assertFalse(appender.shouldForceMetadataAfterOpen(false));
    }

    @Test
    // 새 파일 첫 fsync가 성공한 뒤에는 이후 append가 force(false) 경로로 넘어가야 함
    void firstMetadataForceIsClearedAfterSuccessfulAppend() {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));

        assertFalse(appender.shouldForceMetadata());
        appender.close();
    }

    private FileWalAppender appender(Path activeFile) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(activeFile);
        return new FileWalAppender(properties, new WalRecordJsonCodec(objectMapper));
    }

    private FileWalReplaySource replaySource(Path activeFile) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(activeFile);
        return new FileWalReplaySource(properties, parser);
    }

    private WalRecord record(long eventSeq) {
        return new WalRecord(
                eventSeq,
                7L,
                0,
                3,
                5,
                768,
                1280,
                17,
                LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_000_000)
        );
    }
}
