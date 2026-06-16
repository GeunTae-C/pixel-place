package dev.cgt.pixelplace.board.web;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.common.constant.PaletteConstants;

import java.util.List;

/*
 * GET /api/board 응답 DTO
 * 클라이언트가 board 크기, tile 크기, z=0 MVP 범위, palette 정보를 초기 렌더링 전에 알 수 있게 함
 * tile raw data는 이 응답에 싣지 않아 read metadata와 tile payload의 책임을 분리
 */
public record BoardInfoResponse(
        int boardSize,
        int tileSize,
        int z,
        int tileCountX,
        int tileCountY,
        int paletteSize,
        List<String> palette,
        int overviewRefreshSeconds
) {

    private static final int OVERVIEW_REFRESH_SECONDS = 10;

    public BoardInfoResponse {
        palette = List.copyOf(palette);
    }

    /*
     * 현재 MVP에서 지원하는 z=0 보드 메타데이터를 BoardConstants 기준으로 만듦
     * geometry 숫자를 응답 생성부에 흩뿌리지 않아 이후 read API들이 같은 불변식을 공유하게 함
     */
    public static BoardInfoResponse current() {
        return new BoardInfoResponse(
                BoardConstants.BOARD_SIZE,
                BoardConstants.TILE_SIZE,
                BoardConstants.Z0_LEVEL,
                BoardConstants.Z0_TILE_COUNT_PER_AXIS,
                BoardConstants.Z0_TILE_COUNT_PER_AXIS,
                BoardConstants.PALETTE_SIZE,
                PaletteConstants.paletteHex(),
                OVERVIEW_REFRESH_SECONDS
        );
    }
}
