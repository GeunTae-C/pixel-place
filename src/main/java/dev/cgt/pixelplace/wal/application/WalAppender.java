package dev.cgt.pixelplace.wal.application;

import dev.cgt.pixelplace.wal.domain.WalRecord;

/*
 * write path가 WAL 저장 방식에 의존하지 않도록 분리한 append 포트다.
 * WAL append와 fsync가 모두 성공해야 승인 write의 1차 내구성이 확보되므로,
 * 구현체는 실패를 조용히 삼키지 않고 런타임 예외로 전달해야 한다.
 */
public interface WalAppender {

    /*
     * 승인 이벤트 1건을 WAL에 기록하고 fsync까지 완료한다.
     * null record는 기록 가능한 승인 이벤트가 아니므로 이후 메모리 반영이나 쿨다운 적용으로 진행되면 안 된다.
     */
    void appendAndFsync(WalRecord record);
}
