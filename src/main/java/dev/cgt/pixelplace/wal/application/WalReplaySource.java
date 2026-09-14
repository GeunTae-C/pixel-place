package dev.cgt.pixelplace.wal.application;

// recovery가 WAL 저장 방식과 파일 형식을 모르도록 분리한 포트
// 계약상 DB 반영 완료 마지막 eventSeq 이후의 이벤트만 replay 대상으로 다룸
public interface WalReplaySource {

    /* checkpoint 이후 실제 record와 남은 WAL 파일군 전체 durable tail을 함께 반환하는 scan 경계 */
    WalReplayBatch readAfter(long lastFlushedEventSeq);
}
