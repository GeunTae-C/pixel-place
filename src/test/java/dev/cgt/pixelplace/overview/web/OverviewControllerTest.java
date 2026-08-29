package dev.cgt.pixelplace.overview.web;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.web.ReadinessGuardInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OverviewControllerTest {

    @Test
    void readyImageReturnsPngWithNoCacheAndExactPublishedBody() throws Exception {
        OverviewService service = mock(OverviewService.class);
        byte[] png = validPng();
        when(service.currentPng()).thenReturn(Optional.of(png));
        MockMvc mockMvc = standalone(service);

        byte[] responseBody = mockMvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertArrayEquals(png, responseBody);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(responseBody));
        assertNotNull(decoded);
        assertEquals(BoardConstants.OVERVIEW_SIZE, decoded.getWidth());
        assertEquals(BoardConstants.OVERVIEW_SIZE, decoded.getHeight());
    }

    @Test
    void readyWithoutImageReturnsJson503EvenWhenClientAcceptsOnlyPng() throws Exception {
        OverviewService service = mock(OverviewService.class);
        when(service.currentPng()).thenReturn(Optional.empty());
        MockMvc mockMvc = standalone(service);

        mockMvc.perform(get("/api/overview").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message")
                        .value(OverviewController.IMAGE_NOT_AVAILABLE_MESSAGE));
    }

    @Test
    void endpointLocalNoImageDoesNotChangeGlobalReadiness() throws Exception {
        OverviewService service = mock(OverviewService.class);
        when(service.currentPng()).thenReturn(Optional.empty());
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        MockMvc mockMvc = guarded(service, readiness);

        mockMvc.perform(get("/api/overview"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value(OverviewController.IMAGE_NOT_AVAILABLE_MESSAGE));

        assertTrue(readiness.isReady());
    }

    @Test
    void globalNotReadyBlocksExistingImageBeforeControllerWithDistinctMessage() throws Exception {
        OverviewService service = mock(OverviewService.class);
        ServiceReadiness readiness = new ServiceReadiness();
        MockMvc mockMvc = guarded(service, readiness);

        mockMvc.perform(get("/api/overview"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Service is not ready."));

        verifyNoInteractions(service);
    }

    @Test
    void controllerDeclaresExactlyOneGetMappingForOverviewPath() {
        RequestMapping classMapping = OverviewController.class.getAnnotation(RequestMapping.class);
        long getMappings = Arrays.stream(OverviewController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .count();

        assertArrayEquals(new String[]{"/api/overview"}, classMapping.value());
        assertEquals(1L, getMappings);
    }

    private MockMvc standalone(OverviewService service) {
        return MockMvcBuilders.standaloneSetup(new OverviewController(service)).build();
    }

    private MockMvc guarded(OverviewService service, ServiceReadiness readiness) {
        return MockMvcBuilders.standaloneSetup(new OverviewController(service))
                .addInterceptors(new ReadinessGuardInterceptor(readiness))
                .build();
    }

    private byte[] validPng() {
        BufferedImage image = new BufferedImage(
                BoardConstants.OVERVIEW_SIZE,
                BoardConstants.OVERVIEW_SIZE,
                BufferedImage.TYPE_INT_RGB
        );
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError("Failed to create controller test PNG.", exception);
        }
    }
}
