package dev.cgt.pixelplace.wal.application;

import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/*
 * WalRecord를 JSON Lines WAL 형식의 byte 배열로 변환함
 * 파일 append 구현체가 JSON 필드와 개행 규칙을 직접 알지 않게 분리해,
 * 이후 group commit이나 다른 저장 구현으로 바뀌어도 WAL 포맷 책임은 이곳에 남김
 */
@Component
public class WalRecordJsonCodec {

    private final ObjectMapper objectMapper;

    public WalRecordJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /*
     * 한 승인 이벤트는 WAL에서 반드시 한 줄이어야 함
     * null record는 내구성 원본에 쓸 수 있는 이벤트가 아니므로 write 실패로 이어지게 즉시 거부함
     */
    public byte[] serializeLine(WalRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        try {
            String json = objectMapper.writeValueAsString(record);
            return (json + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            // 직렬화 실패는 WAL append 실패이며, 이후 메모리 반영이나 쿨다운 적용으로 진행되면 안됨
            throw new IllegalStateException("Failed to serialize WAL record.", e);
        }
    }
}
