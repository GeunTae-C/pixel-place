package dev.cgt.pixelplace.tile.web;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.application.TileReadResult;
import dev.cgt.pixelplace.tile.application.TileReadService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// tile raw bytes HTTP 경계 검증
// controller는 gzip 표현과 헤더 설정만 담당, tile 조회는 service 경계에 위임
class TileControllerTest {

    private final TileReadService tileReadService = mock(TileReadService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TileController(tileReadService))
            .build();

    @Test
    // 성공 응답은 JSON이 아니라 gzip raw bytes와 X-Tile-Version 헤더 계약
    void getTileReturnsGzipRawBytesAndTileVersionHeader() throws Exception {
        byte[] rawBytes = new byte[BoardConstants.TILE_PIXEL_COUNT];
        rawBytes[0] = 17;
        when(tileReadService.readTile(BoardConstants.Z0_LEVEL, 3, 5))
                .thenReturn(new TileReadResult(rawBytes, 7L));

        MvcResult result = mockMvc.perform(get("/api/tiles/0/3/5"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andExpect(header().string("X-Tile-Version", "7"))
                .andReturn();

        byte[] decompressed = gunzip(result.getResponse().getContentAsByteArray());

        assertArrayEquals(rawBytes, decompressed);
        verify(tileReadService).readTile(BoardConstants.Z0_LEVEL, 3, 5);
    }

    @Test
    // z=1/downsample 미지원과 tile 좌표 오류는 client 요청 오류로 매핑
    void getTileConvertsInvalidRequestToBadRequest() throws Exception {
        when(tileReadService.readTile(1, 0, 0))
                .thenThrow(new IllegalArgumentException("Only z=0 tiles are supported in MVP."));
        when(tileReadService.readTile(BoardConstants.Z0_LEVEL, BoardConstants.Z0_TILE_COUNT_PER_AXIS, 0))
                .thenThrow(new IllegalArgumentException("Tile tx is out of range."));
        when(tileReadService.readTile(BoardConstants.Z0_LEVEL, 0, BoardConstants.Z0_TILE_COUNT_PER_AXIS))
                .thenThrow(new IllegalArgumentException("Tile ty is out of range."));

        mockMvc.perform(get("/api/tiles/1/0/0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tiles/0/32/0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tiles/0/0/32"))
                .andExpect(status().isBadRequest());

        verify(tileReadService).readTile(1, 0, 0);
        verify(tileReadService).readTile(BoardConstants.Z0_LEVEL, BoardConstants.Z0_TILE_COUNT_PER_AXIS, 0);
        verify(tileReadService).readTile(BoardConstants.Z0_LEVEL, 0, BoardConstants.Z0_TILE_COUNT_PER_AXIS);
    }

    @Test
    // 메모리 보드 불변식 위반은 400으로 숨기지 않음
    void getTileDoesNotConvertIllegalStateExceptionToBadRequest() {
        when(tileReadService.readTile(BoardConstants.Z0_LEVEL, 3, 5))
                .thenThrow(new IllegalStateException("Required tile is missing from memory board."));

        ServletException exception = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/api/tiles/0/3/5")));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        verify(tileReadService).readTile(BoardConstants.Z0_LEVEL, 3, 5);
    }

    private byte[] gunzip(byte[] gzipped) throws IOException {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return gzipInputStream.readAllBytes();
        }
    }
}
