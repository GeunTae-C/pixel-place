package dev.cgt.pixelplace.pixel.web;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.pixel.application.PixelCommandService;
import dev.cgt.pixelplace.pixel.application.PixelCooldownActiveException;
import dev.cgt.pixelplace.pixel.application.PixelCooldownUnavailableException;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.tile.domain.TileKey;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// pixel write HTTP 경계 검증
// controller는 임시 X-User-Id 파싱과 응답 변환만 담당, write 처리는 service 경계에 위임
class PixelControllerTest {

    private final PixelCommandService pixelCommandService = mock(PixelCommandService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PixelController(pixelCommandService))
            .build();

    @Test
    // 승인된 write 결과를 HTTP 응답 DTO로 변환하는 기본 계약
    void writePixelReturnsAcceptedResponse() throws Exception {
        when(pixelCommandService.writePixel(7L, 768, 1280, 17))
                .thenReturn(new PixelWriteResult(
                        1L,
                        new TileKey(BoardConstants.Z0_LEVEL, 3, 5),
                        1L,
                        768,
                        1280,
                        17
                ));

        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "y": 1280,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.eventSeq").value(1))
                .andExpect(jsonPath("$.x").value(768))
                .andExpect(jsonPath("$.y").value(1280))
                .andExpect(jsonPath("$.color").value(17))
                .andExpect(jsonPath("$.tileVersion").value(1));

        verify(pixelCommandService).writePixel(7L, 768, 1280, 17);
    }

    @Test
    // 임시 사용자 식별 header 없이는 write path 진입 금지
    void writePixelWithoutUserIdHeaderReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/pixels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "y": 1280,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(pixelCommandService);
    }

    @Test
    // 필수 x 좌표 누락 시 service 호출 금지
    void writePixelWithoutXReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "y": 1280,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("x is required.")));

        verifyNoInteractions(pixelCommandService);
    }

    @Test
    // 필수 y 좌표 누락 시 service 호출 금지
    void writePixelWithoutYReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("y is required.")));

        verifyNoInteractions(pixelCommandService);
    }

    @Test
    // 필수 color 누락 시 service 호출 금지
    void writePixelWithoutColorReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "y": 1280
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("color is required.")));

        verifyNoInteractions(pixelCommandService);
    }

    @Test
    // 요청 검증 계열 service 실패는 client 오류로 매핑
    void writePixelConvertsServiceIllegalArgumentExceptionToBadRequest() throws Exception {
        when(pixelCommandService.writePixel(7L, -1, 1280, 17))
                .thenThrow(new IllegalArgumentException("x coordinate is out of board range. x=-1"));

        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": -1,
                                  "y": 1280,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("x coordinate is out of board range")));

        verify(pixelCommandService).writePixel(7L, -1, 1280, 17);
    }

    @Test
    // cooldown 활성 상태는 write path 진입 전 429로 고정
    void writePixelReturnsTooManyRequestsWhenCooldownActive() throws Exception {
        when(pixelCommandService.writePixel(7L, 768, 1280, 17))
                .thenThrow(new PixelCooldownActiveException(123_000L));

        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "y": 1280,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Pixel write cooldown is active."))
                .andExpect(jsonPath("$.remainingMillis").value(123_000));

        verify(pixelCommandService).writePixel(7L, 768, 1280, 17);
    }

    @Test
    // cooldown check 실패는 승인 여부 불명확 상태이므로 503으로 고정
    void writePixelReturnsServiceUnavailableWhenCooldownUnavailable() throws Exception {
        when(pixelCommandService.writePixel(7L, 768, 1280, 17))
                .thenThrow(new PixelCooldownUnavailableException(
                        "Pixel cooldown check failed.",
                        new RuntimeException("redis down")
                ));

        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "y": 1280,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Pixel cooldown check failed."));

        verify(pixelCommandService).writePixel(7L, 768, 1280, 17);
    }

    @Test
    // WAL fsync 실패 같은 서버 불변식 실패는 400으로 숨기지 않음
    void writePixelDoesNotConvertIllegalStateExceptionToBadRequest() {
        when(pixelCommandService.writePixel(7L, 768, 1280, 17))
                .thenThrow(new IllegalStateException("WAL fsync failed."));

        ServletException exception = assertThrows(ServletException.class, () ->
                mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "y": 1280,
                                  "color": 17
                                }
                                """)));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        verify(pixelCommandService).writePixel(7L, 768, 1280, 17);
    }

    @Test
    // 잘못된 사용자 header는 write 승인 경로로 넘기지 않음
    void writePixelWithNonNumericUserIdHeaderReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/pixels")
                        .header("X-User-Id", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "x": 768,
                                  "y": 1280,
                                  "color": 17
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(pixelCommandService);
    }
}
