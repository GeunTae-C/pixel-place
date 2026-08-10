package dev.cgt.pixelplace.tile.domain;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * recovery와 runtime bootstrap 판정이 공유하는 canonical z=0 전체 key 기준
 * ty→tx 순서의 1,024개 immutable 목록을 한 번만 생성하여 숫자·순서 불일치 방지
 */
@Component
public class CanonicalZ0TileKeys {

    private static final List<TileKey> ORDERED_KEYS = createOrderedKeys();
    private static final Set<TileKey> KEY_SET = Set.copyOf(ORDERED_KEYS);

    private static List<TileKey> createOrderedKeys() {
        List<TileKey> keys = new ArrayList<>(BoardConstants.Z0_TILE_COUNT);
        for (int ty = 0; ty < BoardConstants.Z0_TILE_COUNT_PER_AXIS; ty++) {
            for (int tx = 0; tx < BoardConstants.Z0_TILE_COUNT_PER_AXIS; tx++) {
                keys.add(new TileKey(BoardConstants.Z0_LEVEL, tx, ty));
            }
        }
        return List.copyOf(keys);
    }

    /* canonical z=0 전체 key의 결정적 ty→tx 순서 immutable 목록 */
    public List<TileKey> orderedKeys() {
        return ORDERED_KEYS;
    }

    /* MVP authoritative tile 범위에 속하는 key인지 판정 */
    public boolean contains(TileKey key) {
        return key != null
                && key.z() == BoardConstants.Z0_LEVEL
                && key.tx() >= 0
                && key.tx() < BoardConstants.Z0_TILE_COUNT_PER_AXIS
                && key.ty() >= 0
                && key.ty() < BoardConstants.Z0_TILE_COUNT_PER_AXIS;
    }

    /* count뿐 아니라 중복·누락·범위 밖 row까지 포함한 canonical 전체 일치 판정 */
    public boolean exactlyMatches(Collection<TileKey> keys) {
        if (keys == null || keys.size() != BoardConstants.Z0_TILE_COUNT) {
            return false;
        }
        return new HashSet<>(keys).equals(KEY_SET);
    }
}
