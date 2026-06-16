package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.domain.TileState;
import org.springframework.stereotype.Service;

/*
 * z=0 tile raw bytes 조회 use case
 * 실시간 authoritative tile 상태는 DB가 아니라 InMemoryTileBoard에서 읽음
 * HTTP 계층이 메모리 보드 구현에 직접 의존하지 않도록 좌표 검증과 조회 책임을 분리
 */
@Service
public class TileReadService {

    private final InMemoryTileBoard board;

    public TileReadService(InMemoryTileBoard board) {
        this.board = board;
    }

    /*
     * 요청된 tile 좌표를 검증하고, 현재 메모리 authoritative state의 raw bytes와 tileVersion을 반환
     * tileVersion은 per-tile version이며 eventSeq, WAL seq, checkpoint seq와 섞으면 안 됨
     */
    public TileReadResult readTile(int z, int tx, int ty) {
        validateTileRequest(z, tx, ty);

        TileKey key = new TileKey(BoardConstants.Z0_LEVEL, tx, ty);
        TileState tileState = board.getRequired(key);
        return new TileReadResult(tileState.pixels(), tileState.tileVersion());
    }

    private void validateTileRequest(int z, int tx, int ty) {
        if (z != BoardConstants.Z0_LEVEL) {
            // MVP는 원본 z=0 타일만 authoritative state로 보유하므로 downsample z 요청은 명확히 거절
            throw new IllegalArgumentException("Only z=0 tiles are supported in MVP.");
        }
        if (tx < 0 || tx >= BoardConstants.Z0_TILE_COUNT_PER_AXIS) {
            // 정상 z=0 타일 범위를 벗어나면 pre-init된 메모리 보드 불변식 밖의 요청
            throw new IllegalArgumentException("Tile tx is out of range.");
        }
        if (ty < 0 || ty >= BoardConstants.Z0_TILE_COUNT_PER_AXIS) {
            // 정상 z=0 타일 범위를 벗어나면 pre-init된 메모리 보드 불변식 밖의 요청
            throw new IllegalArgumentException("Tile ty is out of range.");
        }
    }
}
