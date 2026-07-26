package dev.cgt.pixelplace.recovery.web;

import dev.cgt.pixelplace.board.web.BoardController;
import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.pixel.application.PixelCommandService;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.web.PixelController;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.TileReadResult;
import dev.cgt.pixelplace.tile.application.TileReadService;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.web.TileController;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * startup recovery와 runtime fatal readiness가 HTTP 경계에서 같은 503 계약으로 처리되는지 검증
 * DB, WAL, Redis, WebSocket 없이 interceptor 선차단과 service 내부 재검사 변환 경계 고정
 */
class ReadinessGuardInterceptorTest {

    private ServiceReadiness serviceReadiness;
    private PixelCommandService pixelCommandService;
    private TileReadService tileReadService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        serviceReadiness = new ServiceReadiness();
        pixelCommandService = mock(PixelCommandService.class);
        tileReadService = mock(TileReadService.class);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BoardController(),
                        new PixelController(pixelCommandService),
                        new TileController(tileReadService)
                )
                .addInterceptors(new ReadinessGuardInterceptor(serviceReadiness))
                .setControllerAdvice(new ServiceNotReadyExceptionHandler())
                .build();
    }

    @Test
    // recovery 전 board metadata 노출 금지
    void getBoardReturnsServiceUnavailableWhenNotReady() throws Exception {
        mockMvc.perform(get("/api/board"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(new MediaType(
                        MediaType.APPLICATION_JSON,
                        StandardCharsets.UTF_8
                )))
                .andExpect(content().string("{\"message\":\"Service is not ready.\"}"));
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

        verifyNoInteractions(pixelCommandService);
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
        when(pixelCommandService.writePixel(7L, 1, 2, 3))
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

        verify(pixelCommandService).writePixel(7L, 1, 2, 3);
    }

    @Test
    // interceptor 통과 뒤 fatal 전환을 감지한 service 전용 예외도 기존 guard와 같은 503/body/content-type 사용
    void serviceNotReadyAfterInterceptorPassReturnsSameServiceUnavailableContract() throws Exception {
        serviceReadiness.markReady();
        when(pixelCommandService.writePixel(7L, 1, 2, 3))
                .thenThrow(new ServiceNotReadyException());

        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"x":1,"y":2,"color":3}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(new MediaType(
                        MediaType.APPLICATION_JSON,
                        StandardCharsets.UTF_8
                )))
                .andExpect(content().string("{\"message\":\"Service is not ready.\"}"));

        verify(pixelCommandService).writePixel(7L, 1, 2, 3);
    }

    @Test
    // 최초 fatal 요청의 일반 내부 실패까지 readiness 503으로 바꾸면 최초 원인 의미가 사라짐
    void serviceIllegalStateExceptionIsNotConvertedToReadiness503() {
        serviceReadiness.markReady();
        IllegalStateException fatalFailure = new IllegalStateException("WAL fsync failed.");
        when(pixelCommandService.writePixel(7L, 1, 2, 3)).thenThrow(fatalFailure);

        ServletException exception = assertThrows(ServletException.class, () ->
                mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"x":1,"y":2,"color":3}
                                """)));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertSame(fatalFailure, exception.getCause());
        verify(pixelCommandService).writePixel(7L, 1, 2, 3);
    }

    @Test
    void serviceReadinessDefaultsToFalse() {
        assertFalse(new ServiceReadiness().isReady());
    }
}
