package dev.cgt.pixelplace.recovery.web;

import dev.cgt.pixelplace.board.web.BoardController;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
import dev.cgt.pixelplace.pixel.web.PixelController;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.TileReadResult;
import dev.cgt.pixelplace.tile.application.TileReadService;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.web.TileController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * startup recovery readiness가 controller/service HTTP 경계보다 먼저 적용되는지 검증
 * DB, WAL, Redis, WebSocket 없이 공통 차단과 정상 통과 계약만 고정
 */
class ReadinessGuardInterceptorTest {

    private ServiceReadiness serviceReadiness;
    private PixelWriteService pixelWriteService;
    private TileReadService tileReadService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        serviceReadiness = new ServiceReadiness();
        pixelWriteService = mock(PixelWriteService.class);
        tileReadService = mock(TileReadService.class);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BoardController(),
                        new PixelController(pixelWriteService),
                        new TileController(tileReadService)
                )
                .addInterceptors(new ReadinessGuardInterceptor(serviceReadiness))
                .build();
    }

    @Test
    // recovery 전 board metadata 노출 금지
    void getBoardReturnsServiceUnavailableWhenNotReady() throws Exception {
        mockMvc.perform(get("/api/board"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Service is not ready."));
    }

    @Test
    // recovery 전 불완전한 memory tile 조회와 service 호출 금지
    void getTileReturnsServiceUnavailableWithoutServiceCallWhenNotReady() throws Exception {
        mockMvc.perform(get("/api/tiles/0/0/0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Service is not ready."));

        verifyNoInteractions(tileReadService);
    }

    @Test
    // recovery 중 write path 진입 시 WAL과 replay 순서 충돌 방지를 위한 service 호출 금지
    void postPixelReturnsServiceUnavailableWithoutServiceCallWhenNotReady() throws Exception {
        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"x":1,"y":2,"color":3}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Service is not ready."));

        verifyNoInteractions(pixelWriteService);
    }

    @Test
    void getBoardPassesToControllerWhenReady() throws Exception {
        serviceReadiness.markReady();

        mockMvc.perform(get("/api/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardSize").value(BoardConstants.BOARD_SIZE));
    }

    @Test
    void getTilePassesToControllerAndServiceWhenReady() throws Exception {
        serviceReadiness.markReady();
        byte[] rawBytes = new byte[BoardConstants.TILE_PIXEL_COUNT];
        when(tileReadService.readTile(0, 0, 0))
                .thenReturn(new TileReadResult(rawBytes, 0L));

        mockMvc.perform(get("/api/tiles/0/0/0"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andExpect(header().string("X-Tile-Version", "0"));

        verify(tileReadService).readTile(0, 0, 0);
    }

    @Test
    void postPixelPassesToControllerAndServiceWhenReady() throws Exception {
        serviceReadiness.markReady();
        when(pixelWriteService.writePixel(7L, 1, 2, 3))
                .thenReturn(new PixelWriteResult(
                        1L,
                        new TileKey(BoardConstants.Z0_LEVEL, 0, 0),
                        1L,
                        1,
                        2,
                        3
                ));

        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"x":1,"y":2,"color":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        verify(pixelWriteService).writePixel(7L, 1, 2, 3);
    }

    @Test
    void serviceReadinessDefaultsToFalse() {
        assertFalse(new ServiceReadiness().isReady());
    }
}
