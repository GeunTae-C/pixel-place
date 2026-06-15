package dev.cgt.pixelplace.wal.application;

import dev.cgt.pixelplace.wal.domain.WalRecord;

import java.util.List;

/*
 * WAL 복구 결과를 replay 대상과 발급 seed 기준으로 나누어 전달함
 * records는 lastFlushedEventSeq 이후라서 메모리 board에 다시 적용해야 하는 이벤트 목록
 * walLastEventSeq는 replay 대상 여부와 무관하게 active WAL 전체에서 발견한 마지막 eventSeq이며,
 * boot 이후 EventSeqManager의 lastIssuedEventSeq 초기화에 사용함
 */
public record WalReplayBatch(List<WalRecord> records, long walLastEventSeq) {

    public WalReplayBatch {
        records = List.copyOf(records);
    }
}
