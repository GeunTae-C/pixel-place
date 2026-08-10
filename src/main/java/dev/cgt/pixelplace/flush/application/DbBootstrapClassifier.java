package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.springframework.stereotype.Component;

import java.util.Collection;

/*
 * checkpoint와 DB 전체 tile key 형태만으로 bootstrap 상태를 판정하는 무상태 classifier
 * partial DB를 자동 보정하면 checkpoint/recovery 경계가 숨겨지므로 불일치 상태만 명시적으로 반환
 */
@Component
public class DbBootstrapClassifier {

    private final CanonicalZ0TileKeys canonicalZ0TileKeys;

    public DbBootstrapClassifier(CanonicalZ0TileKeys canonicalZ0TileKeys) {
        this.canonicalZ0TileKeys = canonicalZ0TileKeys;
    }

    /* bootstrap-pending, canonical initialized, 그 밖의 불일치 조합 판정 */
    public DbBootstrapState classify(long lastFlushedEventSeq, Collection<TileKey> tileKeys) {
        if (lastFlushedEventSeq < 0 || tileKeys == null) {
            return DbBootstrapState.INCONSISTENT;
        }
        if (lastFlushedEventSeq == 0 && tileKeys.isEmpty()) {
            return DbBootstrapState.BOOTSTRAP_PENDING;
        }
        if (canonicalZ0TileKeys.exactlyMatches(tileKeys)) {
            return DbBootstrapState.INITIALIZED;
        }
        return DbBootstrapState.INCONSISTENT;
    }
}
