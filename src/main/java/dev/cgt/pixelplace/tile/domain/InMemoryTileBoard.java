package dev.cgt.pixelplace.tile.domain;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.TileStateSnapshot;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * read, write, WAL replay가 공유하는 실시간 authoritative z=0 tile board
 * ready 전환 시 canonical 1,024개가 항상 존재해야 하며 모든 mutation을 synchronized 경계에서 직렬화
 */
@Component
public class InMemoryTileBoard {

    private final Map<TileKey, TileState> tiles = new LinkedHashMap<>();

    /* 생성 직후에도 빈 map을 노출하지 않는 bootstrap-pending 기본 상태 */
    public InMemoryTileBoard() {
        initializeAllWhite();
    }

    /* DB 0 rows + checkpoint 0 bootstrap에서 canonical 전체를 기본색으로 재구성 */
    public synchronized void initializeAllWhite() {
        tiles.clear();
        for (int ty = 0; ty < BoardConstants.Z0_TILE_COUNT_PER_AXIS; ty++) {
            for (int tx = 0; tx < BoardConstants.Z0_TILE_COUNT_PER_AXIS; tx++) {
                TileKey key = new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
                tiles.put(key, TileState.allWhite());
            }
        }
    }

    /* canonical DB snapshot 전체 검증 뒤 기존 board를 한 번에 교체하는 initialized recovery 경계 */
    public synchronized void loadAll(List<TileStateSnapshot> snapshots) {

        if (snapshots.size() != BoardConstants.Z0_TILE_COUNT) {
            // partial snapshot을 ready 가능한 memory state로 보정하지 않음
            throw new IllegalStateException("DB tiles must be fully present or fully absent.");
        }

        // 검증 완료 전 기존 authoritative board를 변경하지 않는 임시 재구성 상태
        Map<TileKey, TileState> loadedTiles = new LinkedHashMap<>();
        for (TileStateSnapshot snapshot : snapshots) {
            loadedTiles.put(snapshot.key(), new TileState(snapshot.pixels(), snapshot.tileVersion()));
        }

        if (loadedTiles.size() != BoardConstants.Z0_TILE_COUNT) {
            // duplicate로 가려진 missing key를 정상 전체 snapshot으로 오인하지 않음
            throw new IllegalStateException("DB tiles contain duplicate or missing rows.");
        }

        for (int ty = 0; ty < BoardConstants.Z0_TILE_COUNT_PER_AXIS; ty++) {
            for (int tx = 0; tx < BoardConstants.Z0_TILE_COUNT_PER_AXIS; tx++) {
                TileKey key = new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
                if (!loadedTiles.containsKey(key)) {
                    // 범위 밖 extra key와 canonical missing 조합의 ready 전환 차단
                    throw new IllegalStateException("DB tiles must not miss any z=0 tile.");
                }
            }
        }

        tiles.clear();
        tiles.putAll(loadedTiles);
    }

    /* canonical board에서 tile 부재를 정상 조회 결과로 숨기지 않는 필수 조회 */
    public synchronized TileState getRequired(TileKey key) {
        TileState tileState = tiles.get(key);
        if (tileState == null) {
            // authoritative state에서 타일 부재를 조용히 넘기지 않기 위해 즉시 실패시킴
            throw new IllegalStateException("Required tile is missing from memory board.");
        }
        return tileState;
    }

    public synchronized boolean contains(TileKey key) {
        return tiles.containsKey(key);
    }

    public synchronized int size() {
        return tiles.size();
    }

    public synchronized Collection<TileState> allTiles() {
        return List.copyOf(tiles.values());
    }

    /* WAL fsync 완료 write와 검증된 recovery replay가 공유하는 pixel mutation·tileVersion 증가 경계 */
    public synchronized TileMutationResult applyPixel(int x, int y, int color) {
        validatePixelMutation(x, y, color);

        int tx = x / BoardConstants.TILE_SIZE;
        int ty = y / BoardConstants.TILE_SIZE;
        int lx = x % BoardConstants.TILE_SIZE;
        int ly = y % BoardConstants.TILE_SIZE;
        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
        TileState tileState = getRequired(key);
        byte[] pixels = tileState.pixels();

        // Java byte는 signed지만, 이 배열에서는 1 byte 팔레트 인덱스 저장 표현으로만 해석함
        pixels[(ly * BoardConstants.TILE_SIZE) + lx] = (byte) color;
        long nextTileVersion = tileState.tileVersion() + 1;
        tiles.put(key, new TileState(pixels, nextTileVersion));
        return new TileMutationResult(key, nextTileVersion);
    }

    /* checkpoint 이후 WAL replay에도 정상 write와 동일한 좌표·version 규칙 적용 */
    public synchronized void applyReplayRecord(int x, int y, int color) {
        applyPixel(x, y, color);
    }

    private void validatePixelMutation(int x, int y, int color) {
        if (x < 0 || x >= BoardConstants.BOARD_SIZE) {
            // 보드 밖 좌표를 허용하면 z=0 전체 타일 범위 불변식이 깨짐
            throw new IllegalArgumentException("Pixel x coordinate is out of board range.");
        }
        if (y < 0 || y >= BoardConstants.BOARD_SIZE) {
            // 보드 밖 좌표를 허용하면 z=0 전체 타일 범위 불변식이 깨짐
            throw new IllegalArgumentException("Pixel y coordinate is out of board range.");
        }
        if (color < 0 || color >= BoardConstants.PALETTE_SIZE) {
            // 팔레트 인덱스는 1 byte 저장 모델과 256색 고정 팔레트를 유지하기 위해 0~255만 허용함
            throw new IllegalArgumentException("Pixel color index is out of palette range.");
        }
    }
}
