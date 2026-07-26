package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

/*
 * live write 이후 변경 tile의 최신 관측값을 보존하는 보조 dirty 상태
 * checkpoint source가 아니며 실패 재등록, 관측, 추가 snapshot 병합용 eventSeq와 tileVersion 보존
 */
public record DirtyTile(
        TileKey tileKey,
        long latestEventSeq,
        long latestTileVersion
) {

    public DirtyTile {
        if (tileKey == null) {
            // 보조 dirty 상태도 대상 tile을 식별할 수 없으면 재등록과 추가 snapshot 병합 불가
            throw new IllegalArgumentException("tileKey is required.");
        }
        if (latestEventSeq <= 0) {
            // latestEventSeq는 checkpoint 자체가 아니라 마지막 live write 관측값
            throw new IllegalArgumentException("latestEventSeq must be positive.");
        }
        if (latestTileVersion <= 0) {
            // latestTileVersion은 실패 재등록과 snapshot 관측 비교에 필요한 양수 값
            throw new IllegalArgumentException("latestTileVersion must be positive.");
        }
    }
}
