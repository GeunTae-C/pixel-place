package dev.cgt.pixelplace.wal.application;

/** DB 반영이 확정된 양의 경계로만 closed prefix 정리를 요청하는 port. DB 조회 책임 없음 */
public interface WalSegmentRetention {
    /** 전체 파일군 검증 후 active·마지막 실제 record를 보존하며 연속 prefix만 삭제 */
    WalRetentionResult deleteCommittedPrefix(long confirmedCheckpoint);
}
