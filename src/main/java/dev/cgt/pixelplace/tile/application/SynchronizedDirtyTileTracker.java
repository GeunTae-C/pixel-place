package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/*
 * DirtyTileTracker의 synchronized 기반 보조 상태 구현체
 * tile별 최신 live write 관측만 보호하며 WAL flush 범위와 checkpoint 결정 책임은 갖지 않음
 */
@Component
public class SynchronizedDirtyTileTracker implements DirtyTileTracker {

    private final Map<TileKey, DirtyTile> dirtyTiles = new LinkedHashMap<>();

    /*
     * write 성공 이후 tile별 최신 보조 dirty 상태 기록
     * 같은 tile은 더 큰 eventSeq만 실패 재등록과 추가 snapshot 병합용 최신 관측으로 인정
     */
    @Override
    public synchronized void markDirty(TileKey tileKey, long eventSeq, long tileVersion) {
        merge(new DirtyTile(tileKey, eventSeq, tileVersion));
    }

    /*
     * flush plan의 추가 snapshot 병합과 실패 재등록에 사용할 dirty 목록 회수
     * 반환 이후 tracker 내부 상태 제거, 필수 snapshot 대상과 WAL 범위는 제공하지 않음
     */
    @Override
    public synchronized List<DirtyTile> drainDirtyTiles() {
        List<DirtyTile> drained = new ArrayList<>(dirtyTiles.values());
        dirtyTiles.clear();
        return drained;
    }

    /*
     * 실제 drain 목록 전체를 먼저 검증·복사한 뒤 한 critical section에서 최신값 병합
     * 잘못된 입력이 중간에 있어도 일부 복구 상태를 남기지 않음
     */
    @Override
    public synchronized void restoreDirtyTiles(Collection<DirtyTile> dirtyTilesToRestore) {
        Objects.requireNonNull(dirtyTilesToRestore, "dirtyTiles must not be null");

        List<DirtyTile> validated = new ArrayList<>(dirtyTilesToRestore.size());
        for (DirtyTile dirtyTile : dirtyTilesToRestore) {
            DirtyTile source = Objects.requireNonNull(dirtyTile, "dirtyTiles must not contain null");
            validated.add(new DirtyTile(
                    source.tileKey(),
                    source.latestEventSeq(),
                    source.latestTileVersion()
            ));
        }

        validated.forEach(this::merge);
    }

    private void merge(DirtyTile next) {
        DirtyTile current = dirtyTiles.get(next.tileKey());
        if (current == null || next.latestEventSeq() > current.latestEventSeq()) {
            dirtyTiles.put(next.tileKey(), next);
        }
    }
}
