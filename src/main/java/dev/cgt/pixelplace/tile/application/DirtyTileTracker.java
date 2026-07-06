package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.List;

/*
 * write 성공 후 변경 tile을 flush 대상 dirty 상태로 표시하는 application port
 * 10단계에서는 drain만 제공하고 실패 재등록 정책은 후속 flush 단계 책임
 */
public interface DirtyTileTracker {

    /*
     * 변경된 tile의 최신 dirty 상태 기록
     * eventSeq는 checkpoint 기준, tileVersion은 tile snapshot 최신성 기준
     */
    void markDirty(TileKey tileKey, long eventSeq, long tileVersion);

    /*
     * 현재 dirty 대상 목록 회수
     * 반환된 항목은 tracker 내부에서 제거되며 DB flush 자체는 담당하지 않음
     */
    List<DirtyTile> drainDirtyTiles();
}
