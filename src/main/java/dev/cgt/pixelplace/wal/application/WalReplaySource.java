package dev.cgt.pixelplace.wal.application;

// recovery가 WAL 저장 방식과 파일 형식을 모르도록 분리한 포트
// 계약상 DB 반영 완료 마지막 eventSeq 이후의 이벤트만 replay 대상으로 다룸
public interface WalReplaySource {

    WalReplayBatch readAfter(long lastFlushedEventSeq);
}
