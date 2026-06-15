package dev.cgt.pixelplace.wal.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// WAL 파서는 recovery가 신뢰할 수 있는 레코드만 넘기도록 형식과 도메인 불변식을 함께 검증함
class WalRecordParserTest {

    private final WalRecordParser parser = new WalRecordParser(new ObjectMapper());

    @Test
    // 정상 JSON Lines 레코드는 복구에 필요한 모든 필드를 보존해야 함
    void parsesValidWalLine() {
        WalRecord record = parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":3,"ty":5,"x":768,"y":1280,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1);

        assertEquals(1L, record.eventSeq());
        assertEquals(7L, record.userId());
        assertEquals(3, record.tx());
        assertEquals(5, record.ty());
        assertEquals(17, record.color());
    }

    @Test
    // MVP에서는 z=0만 복구 대상이므로 다른 레벨은 WAL 손상으로 취급함
    void rejectsUnsupportedZLevel() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":1,"tx":3,"ty":5,"x":768,"y":1280,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1));
    }

    @Test
    // 팔레트 인덱스는 1 byte 저장 모델을 유지하기 위해 0~255만 허용함
    void rejectsInvalidColor() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":3,"ty":5,"x":768,"y":1280,"color":256,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1));
    }

    @Test
    // WAL의 tx, ty가 x, y에서 계산한 타일과 다르면 replay와 flush 기준이 갈라질 수 있음
    void rejectsTileCoordinateMismatch() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":4,"ty":5,"x":768,"y":1280,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1));
    }

    @Test
    // JSON 자체가 깨진 줄은 승인 이벤트의 경계를 신뢰할 수 없으므로 복구 실패가 맞음
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("{", 1));
    }

    @Test
    // 빈 줄은 승인 이벤트 1건의 경계를 표현하지 못하므로 WAL 손상으로 거부함
    void rejectsBlankLine() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("   ", 1));
    }

    @Test
    // eventSeq는 복구 순서와 다음 발급 기준이므로 0 이하를 허용하지 않음
    void rejectsNonPositiveEventSeq() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":0,"userId":7,"z":0,"tx":3,"ty":5,"x":768,"y":1280,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1));
    }

    @Test
    // userId는 승인 주체를 식별하는 값이므로 0 이하를 정상 이벤트로 복구하지 않음
    void rejectsNonPositiveUserId() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":0,"z":0,"tx":3,"ty":5,"x":768,"y":1280,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1));
    }

    @Test
    // 음수 x 좌표는 8192x8192 보드 밖이므로 메모리 타일에 replay할 수 없음
    void rejectsNegativeX() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":0,"ty":5,"x":-1,"y":1280,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1));
    }

    @Test
    // x 좌표의 상한은 BOARD_SIZE 미만이어야 타일 인덱스가 z=0 범위 안에 머묾
    void rejectsXGreaterThanOrEqualBoardSize() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":32,"ty":5,"x":%d,"y":1280,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """.formatted(BoardConstants.BOARD_SIZE), 1));
    }

    @Test
    // 음수 y 좌표는 8192x8192 보드 밖이므로 메모리 타일에 replay할 수 없음
    void rejectsNegativeY() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":3,"ty":0,"x":768,"y":-1,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """, 1));
    }

    @Test
    // y 좌표의 상한은 BOARD_SIZE 미만이어야 타일 인덱스가 z=0 범위 안에 머묾
    void rejectsYGreaterThanOrEqualBoardSize() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":3,"ty":32,"x":768,"y":%d,"color":17,"createdAt":"2026-04-03T06:00:00.123"}
                """.formatted(BoardConstants.BOARD_SIZE), 1));
    }

    @Test
    // createdAt은 순서 기준은 아니지만 승인 시각 감사 정보이므로 누락된 WAL 레코드는 거부함
    void rejectsMissingCreatedAt() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLine("""
                {"eventSeq":1,"userId":7,"z":0,"tx":3,"ty":5,"x":768,"y":1280,"color":17}
                """, 1));
    }
}
