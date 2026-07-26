package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        DirtyTile next = new DirtyTile(tileKey, eventSeq, tileVersion);
        DirtyTile current = dirtyTiles.get(tileKey);

        if (current == null || eventSeq > current.latestEventSeq()) {
            dirtyTiles.put(tileKey, next);
        }
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
}
