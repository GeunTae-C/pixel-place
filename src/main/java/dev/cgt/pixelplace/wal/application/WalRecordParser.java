package dev.cgt.pixelplace.wal.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/*
 * JSON Lines WAL의 단일 라인을 복구용 레코드로 변환하고 검증함
 * WAL 파일의 한 줄을 WalRecord로 파싱하고 복구 가능한 정상 이벤트인지 검증함
 * WAL은 메모리 authoritative state를 다시 만드는 원본이므로, 형식 오류나 좌표 불일치는 조용히 건너뛰지 않고 recovery 실패로 올림
 */
@Component
public class WalRecordParser {

    private final ObjectMapper objectMapper;

    public WalRecordParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /*
     * WAL 한 줄을 파싱함
     * 반환된 레코드는 MVP의 z=0, 256색 팔레트, 좌표 기반 타일 매핑 불변식을 만족해야 함
     */
    public WalRecord parseLine(String line, long lineNumber) {
        if (line == null || line.isBlank()) {
            // 빈 줄을 허용하면 WAL 손상과 정상 종료를 구분할 수 없으므로 복구를 중단함
            throw new IllegalArgumentException("WAL line is blank. lineNumber=" + lineNumber);
        }

        WalRecord record;
        try {
            record = objectMapper.readValue(line, WalRecord.class);
        } catch (JacksonException e) {
            // JSON Lines WAL은 한 줄이 한 승인 이벤트이므로 파싱 실패는 해당 이벤트 유실 가능성으로 봄
            throw new IllegalArgumentException("Failed to parse WAL record. lineNumber=" + lineNumber, e);
        }

        validate(record, lineNumber);
        return record;
    }

    private void validate(WalRecord record, long lineNumber) {
        if (record.eventSeq() <= 0) {
            // eventSeq는 replay 순서와 발급 seed의 기준이라 0 이하를 허용하지 않음
            throw new IllegalArgumentException("Invalid WAL eventSeq. lineNumber=" + lineNumber);
        }
        if (record.userId() <= 0) {
            throw new IllegalArgumentException("Invalid WAL userId. lineNumber=" + lineNumber);
        }
        if (record.z() != BoardConstants.Z0_LEVEL) {
            // MVP는 z=0 원본 타일만 authoritative state로 복구함
            throw new IllegalArgumentException("Unsupported WAL z level. lineNumber=" + lineNumber);
        }
        if (record.x() < 0 || record.x() >= BoardConstants.BOARD_SIZE
                || record.y() < 0 || record.y() >= BoardConstants.BOARD_SIZE) {
            throw new IllegalArgumentException("Invalid WAL coordinate. lineNumber=" + lineNumber);
        }
        if (record.color() < 0 || record.color() >= BoardConstants.PALETTE_SIZE) {
            throw new IllegalArgumentException("Invalid WAL color. lineNumber=" + lineNumber);
        }

        int expectedTx = record.x() / BoardConstants.TILE_SIZE;
        int expectedTy = record.y() / BoardConstants.TILE_SIZE;
        if (record.tx() != expectedTx || record.ty() != expectedTy) {
            // WAL의 타일 좌표와 픽셀 좌표가 어긋나면 flush/replay가 서로 다른 타일을 보게 됨
            throw new IllegalArgumentException("WAL tile coordinate mismatch. lineNumber=" + lineNumber);
        }
        if (record.createdAt() == null) {
            throw new IllegalArgumentException("Missing WAL createdAt. lineNumber=" + lineNumber);
        }
    }
}
