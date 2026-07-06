package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * DirtyTileTracker MVP 구현체
 * synchronized로 tile별 최신 dirty 상태만 보호하며 DB/checkpoint 책임은 갖지 않음
 */
@Component
public class SynchronizedDirtyTileTracker implements DirtyTileTracker {

    private final Map<TileKey, DirtyTile> dirtyTiles = new LinkedHashMap<>();

    /*
     * write 성공 이후 dirty tile 최신 상태 기록
     * 같은 tile은 더 큰 eventSeq만 최신 flush 대상으로 인정
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
     * flush worker용 dirty 목록 회수
     * 반환 이후 tracker 내부 상태 제거, 실패 재등록 정책은 후속 단계 책임
     */
    @Override
    public synchronized List<DirtyTile> drainDirtyTiles() {
        List<DirtyTile> drained = new ArrayList<>(dirtyTiles.values());
        dirtyTiles.clear();
        return drained;
    }
}
