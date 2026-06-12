package dev.cgt.pixelplace.pixel.web;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
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

class PixelControllerTest {

    private final PixelWriteService pixelWriteService = mock(PixelWriteService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PixelController(pixelWriteService))
            .build();

    @Test
    void writePixelReturnsAcceptedResponse() throws Exception {
        when(pixelWriteService.writePixel(7L, 768, 1280, 17))
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

        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
    }

    @Test
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

        verifyNoInteractions(pixelWriteService);
    }

    @Test
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

        verifyNoInteractions(pixelWriteService);
    }

    @Test
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

        verifyNoInteractions(pixelWriteService);
    }

    @Test
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

        verifyNoInteractions(pixelWriteService);
    }

    @Test
    void writePixelConvertsServiceIllegalArgumentExceptionToBadRequest() throws Exception {
        when(pixelWriteService.writePixel(7L, -1, 1280, 17))
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

        verify(pixelWriteService).writePixel(7L, -1, 1280, 17);
    }

    @Test
    void writePixelDoesNotConvertIllegalStateExceptionToBadRequest() {
        when(pixelWriteService.writePixel(7L, 768, 1280, 17))
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
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
    }

    @Test
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

        verifyNoInteractions(pixelWriteService);
    }
}
