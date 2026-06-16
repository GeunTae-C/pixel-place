package dev.cgt.pixelplace.board.web;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// board metadata HTTP 경계 검증
// tile payload, DB, WAL 없이 초기 렌더링 설정만 내려주는 계약 고정
class BoardControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new BoardController())
            .build();

    @Test
    // 클라이언트 부팅에 필요한 고정 board 설정과 palette 노출 계약
    void getBoardInfoReturnsBoardMetadata() throws Exception {
        mockMvc.perform(get("/api/board"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.boardSize").value(BoardConstants.BOARD_SIZE))
                .andExpect(jsonPath("$.tileSize").value(BoardConstants.TILE_SIZE))
                .andExpect(jsonPath("$.z").value(BoardConstants.Z0_LEVEL))
                .andExpect(jsonPath("$.tileCountX").value(BoardConstants.Z0_TILE_COUNT_PER_AXIS))
                .andExpect(jsonPath("$.tileCountY").value(BoardConstants.Z0_TILE_COUNT_PER_AXIS))
                .andExpect(jsonPath("$.paletteSize").value(BoardConstants.PALETTE_SIZE))
                .andExpect(jsonPath("$.palette.length()").value(BoardConstants.PALETTE_SIZE))
                .andExpect(jsonPath("$.palette[15]").value("#FFFFFF"))
                .andExpect(jsonPath("$.overviewRefreshSeconds").value(10));
    }

    @Test
    // metadata API가 tile raw bytes 책임을 갖지 않도록 응답 필드 제한
    void getBoardInfoDoesNotReturnTileRawData() throws Exception {
        mockMvc.perform(get("/api/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiles").doesNotExist())
                .andExpect(jsonPath("$.tileData").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
