package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

/*
 * flush worker가 나중에 DB tile snapshot 대상으로 사용할 dirty tile 상태
 * checkpoint 기준인 eventSeq와 tile snapshot 버전인 tileVersion을 함께 보존
 */
public record DirtyTile(
        TileKey tileKey,
        long latestEventSeq,
        long latestTileVersion
) {

    public DirtyTile {
        if (tileKey == null) {
            // dirty 상태는 tile 기준 flush 대상이므로 key 누락은 내부 상태 오류
            throw new IllegalArgumentException("tileKey is required.");
        }
        if (latestEventSeq <= 0) {
            // checkpoint는 전역 eventSeq 기준이므로 양수 eventSeq 없는 dirty 상태 금지
            throw new IllegalArgumentException("latestEventSeq must be positive.");
        }
        if (latestTileVersion <= 0) {
            // 변경된 tile snapshot만 dirty 대상이므로 양수 tileVersion 필요
            throw new IllegalArgumentException("latestTileVersion must be positive.");
        }
    }
}
