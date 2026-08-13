package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.wal.domain.WalRecord;

import java.util.List;

/*
 * immutable flush plan의 실제 WAL record를 append-only pixel_events row로 저장하는 application port
 * event 순서와 assigned ID는 eventSeq만 사용하며 timestamp나 DB 생성값에 의존하지 않음
 */
public interface PixelEventWriter {

    /* constraint 실패를 transaction body 안에서 드러내는 WAL event 일괄 append 경계 */
    void appendAll(List<WalRecord> walRecords);
}
