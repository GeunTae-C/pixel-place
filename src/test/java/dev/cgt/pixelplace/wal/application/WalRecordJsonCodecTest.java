package dev.cgt.pixelplace.wal.application;

import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// WAL JSON codec은 writer와 parser 사이의 포맷 계약을 담당하므로 JSON Lines 개행과 재파싱 가능성을 검증함
class WalRecordJsonCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WalRecordJsonCodec codec = new WalRecordJsonCodec(objectMapper);
    private final WalRecordParser parser = new WalRecordParser(objectMapper);

    @Test
    // WAL은 UTF-8 JSON Lines 파일이므로 serialize 결과는 UTF-8 문자열로 복원 가능해야 함
    void serializeLineReturnsUtf8JsonLineBytes() {
        byte[] bytes = codec.serializeLine(record(1L));
        String line = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(line.contains("\"eventSeq\":1"));
        assertTrue(line.contains("\"userId\":7"));
    }

    @Test
    // JSON Lines에서 한 승인 이벤트의 경계는 줄바꿈이므로 serialize 결과는 반드시 개행으로 끝나야 함
    void serializeLineEndsWithNewLine() {
        byte[] bytes = codec.serializeLine(record(1L));
        String line = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(line.endsWith("\n"));
    }

    @Test
    // writer가 만든 한 줄은 recovery parser가 그대로 읽을 수 있어야 WAL 포맷 호환성이 유지됨
    void serializedLineCanBeParsedByWalRecordParser() {
        byte[] bytes = codec.serializeLine(record(1L));
        String line = new String(bytes, StandardCharsets.UTF_8);

        WalRecord parsed = parser.parseLine(line, 1);

        assertEquals(1L, parsed.eventSeq());
        assertEquals(7L, parsed.userId());
        assertEquals(17, parsed.color());
        assertEquals(LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_000_000), parsed.createdAt());
    }

    @Test
    // null record는 WAL에 기록할 수 있는 승인 이벤트가 아니므로 즉시 실패해야 함
    void serializeLineRejectsNullRecord() {
        assertThrows(NullPointerException.class, () -> codec.serializeLine(null));
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
