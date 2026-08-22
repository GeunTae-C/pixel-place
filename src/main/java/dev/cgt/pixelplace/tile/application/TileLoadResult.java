package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.List;
import java.util.Objects;

/*
 * startup recovery가 같은 DB view에서 관측한 전체 row key와 z=0 memory load 후보 전달
 * partial/invalid shape를 숨기지 않고 공통 bootstrap classifier와 recovery 검증 경계까지 보존
 */
public record TileLoadResult(
        List<TileKey> databaseTileKeys,
        List<TileStateSnapshot> snapshots
) {

    public TileLoadResult {
        databaseTileKeys = List.copyOf(Objects.requireNonNull(
                databaseTileKeys,
                "databaseTileKeys must not be null."
        ));
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots must not be null."));
    }

    /* 호출 호환용 derived helper이며 checkpoint를 포함한 bootstrap 최종 판정 책임은 갖지 않음 */
    public boolean allMissing() {
        return databaseTileKeys.isEmpty();
    }

    /* stub profile의 checkpoint 0 bootstrap 입력과 결합되는 정확한 empty/empty DB 관측값 */
    public static TileLoadResult allMissingResult() {
        return new TileLoadResult(List.of(), List.of());
    }
}
