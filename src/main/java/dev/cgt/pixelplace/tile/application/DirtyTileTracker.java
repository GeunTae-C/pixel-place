package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.List;

/*
 * live write 이후 변경 tile을 보조 dirty 상태로 표시하는 application port
 * startup recovery replay는 채우지 않으므로 flush target/checkpoint와 필수 snapshot 대상의 source of truth가 아님
 */
public interface DirtyTileTracker {

    /*
     * 실패 재등록, 관측, 추가 snapshot 병합에 사용할 최신 dirty 상태 기록
     * eventSeq와 tileVersion은 checkpoint 자체가 아니라 tile별 최신 live write 관측값
     */
    void markDirty(TileKey tileKey, long eventSeq, long tileVersion);

    /*
     * 현재 보조 dirty 목록 회수
     * 반환 항목은 tracker 내부에서 제거되며 WAL 범위 결정과 DB flush 자체는 담당하지 않음
     */
    List<DirtyTile> drainDirtyTiles();
}
