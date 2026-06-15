package dev.cgt.pixelplace.tile.application;

import java.util.List;
import java.util.Objects;

/*
 * DB z=0 타일 snapshot 로딩 결과를 나타냄
 *
 * recovery는 이 결과를 보고 all-white 초기화와 DB snapshot 로드 중 하나를 선택함
 * 따라서 allMissing 플래그와 snapshots 목록은 서로 모순된 상태가 되면 안됨
 *
 * 이 타입은 "전부 없음"과 "무언가 로드됨"의 모순만 막음
 * z=0 전체 1024개 로드 여부와 중복/누락 검증은 InMemoryTileBoard 또는 TileSnapshotLoader 계층의 책임
 */
public record TileLoadResult(
        boolean allMissing,
        List<TileStateSnapshot> snapshots
) {

    public TileLoadResult {
        // recovery 입력값은 생성 이후 바뀌면 안 되므로 null을 거부하고 불변 목록으로 고정함
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots must not be null."));

        if (allMissing && !snapshots.isEmpty()) {
            // allMissing은 DB에 z=0 snapshot이 없다는 의미이므로 snapshot 데이터를 함께 가질 수 없음
            throw new IllegalArgumentException("allMissing result must not contain snapshots.");
        }

        if (!allMissing && snapshots.isEmpty()) {
            // loaded 상태인데 snapshot이 비어 있으면 recovery가 빈 DB 로드와 초기 부팅을 구분할 수 없음
            throw new IllegalArgumentException("loaded result must contain snapshots.");
        }
    }

    public static TileLoadResult allMissingResult() {
        return new TileLoadResult(true, List.of());
    }

    public static TileLoadResult fullyLoaded(List<TileStateSnapshot> snapshots) {
        return new TileLoadResult(false, snapshots);
    }
}
