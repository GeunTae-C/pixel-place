package dev.cgt.pixelplace.tile.domain;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.TileStateSnapshot;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 메모리에 올라와 있는 전체 z=0 타일 보드
// 실시간 authoritative state는 DB가 아니라 이 메모리 타일 보드다.
// 부팅이 끝난 뒤에는 z=0 타일 1024개가 항상 존재해야 이후 read/write/replay가 타일 부재를 정상 흐름으로 오해하지 않는다.
@Component
public class InMemoryTileBoard {

    private final Map<TileKey, TileState> tiles = new LinkedHashMap<>();

    // 전체 타일을 흰색으로 채운다.
    // z=0 전체 1024 타일이 존재
    // 빈 맵 상태가 아님
    public InMemoryTileBoard() {
        initializeAllWhite();
    }

    // DB tiles 전체가 없는 경우에도 z=0 전체 타일을 all-white로 즉시 메모리에 채워 넣는 pre-init 경로다.
    // Z0_TILE_COUNT_PER_AXIS == BOARD_SIZE(8192) / TILE_SIZE(256) = 32
    public synchronized void initializeAllWhite() {
        tiles.clear();
        for (int ty = 0; ty < BoardConstants.Z0_TILE_COUNT_PER_AXIS; ty++) {
            for (int tx = 0; tx < BoardConstants.Z0_TILE_COUNT_PER_AXIS; tx++) {
                TileKey key = new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
                tiles.put(key, TileState.allWhite());
            }
        }
    }

    // DB의 z=0 전체 타일 snapshot을 메모리에 적재.
    // DB tiles는 전체 존재 또는 전체 미존재만 허용한다.
    // 일부만 로드된 상태를 받아들이면 recovery가 부분 복구를 성공으로 오해하므로 여기서 실패시킨다.
    public synchronized void loadAll(List<TileStateSnapshot> snapshots) {

        // 검증 1: 개수(1024) 확인.
        if (snapshots.size() != BoardConstants.Z0_TILE_COUNT) {
            // recovery에서는 조용한 partial load를 허용하지 않는다.
            throw new IllegalStateException("DB tiles must be fully present or fully absent.");
        }

        // DB snapshot 목록은 순차 리스트이므로, 이후 전체 타일 좌표 검증과 적재를 위해 TileKey 기준 Map으로 변환한다.
        // 이 Map은 최종 보드 상태가 아니라, 메모리 보드 재구성을 위한 임시 적재 데이터다.
        Map<TileKey, TileState> loadedTiles = new LinkedHashMap<>();
        for (TileStateSnapshot snapshot : snapshots) {
            loadedTiles.put(snapshot.key(), new TileState(snapshot.pixels(), snapshot.tileVersion()));
        }

        // 검증 2: 중복/누락 확인
        // 중복된 키가 있거나, 결과적으로 1024개가 안 되면 실패.
        if (loadedTiles.size() != BoardConstants.Z0_TILE_COUNT) {
            // duplicate나 missing을 정상화하지 않고 즉시 실패시켜 잘못된 복구를 막는다.
            throw new IllegalStateException("DB tiles contain duplicate or missing rows.");
        }

        for (int ty = 0; ty < BoardConstants.Z0_TILE_COUNT_PER_AXIS; ty++) {
            for (int tx = 0; tx < BoardConstants.Z0_TILE_COUNT_PER_AXIS; tx++) {
                TileKey key = new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
                // 검증 3: 모든 좌표 존재 확인
                // (0,0)부터 (31,31)까지 z=0 전체 타일이 진짜 다 있는지 다시 확인.
                if (!loadedTiles.containsKey(key)) {
                    // z=0 전체 타일이 메모리에 존재해야 한다는 불변식을 recovery 시점에 강제한다.
                    throw new IllegalStateException("DB tiles must not miss any z=0 tile.");
                }
            }
        }

        tiles.clear();
        tiles.putAll(loadedTiles);
    }


    // 특정 타일 get
    public synchronized TileState getRequired(TileKey key) {
        TileState tileState = tiles.get(key);
        if (tileState == null) {
            // authoritative state에서 타일 부재를 조용히 넘기지 않기 위해 즉시 실패시킨다.
            throw new IllegalStateException("Required tile is missing from memory board.");
        }
        return tileState;
    }

    // 타일 존재 여부
    public synchronized boolean contains(TileKey key) {
        return tiles.containsKey(key);
    }

    // 현재 타일 수
    public synchronized int size() {
        return tiles.size();
    }

    // 전체 타일 목록 반환
    public synchronized Collection<TileState> allTiles() {
        return List.copyOf(tiles.values());
    }

    // lastFlushedEventSeq 이후 WAL replay를 메모리 authoritative state에 반영하기 위한 최소 메서드다.
    // WAL replay 이벤트 1건을 실제 메모리 보드에 적용
    public synchronized void applyReplayRecord(int x, int y, int color) {
        int tx = x / BoardConstants.TILE_SIZE;
        int ty = y / BoardConstants.TILE_SIZE;
        int lx = x % BoardConstants.TILE_SIZE;
        int ly = y % BoardConstants.TILE_SIZE;
        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
        TileState tileState = getRequired(key);
        byte[] pixels = tileState.pixels();
        pixels[(ly * BoardConstants.TILE_SIZE) + lx] = (byte) color;
        tiles.put(key, new TileState(pixels, tileState.tileVersion() + 1));
    }
}
