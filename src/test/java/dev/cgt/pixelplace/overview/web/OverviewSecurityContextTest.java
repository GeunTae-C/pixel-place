package dev.cgt.pixelplace.overview.web;

import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.web.ReadinessGuardInterceptor;
import dev.cgt.pixelplace.recovery.web.ReadinessWebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 13단계 allowlist 선행 구현 없이 현재 Boot Security filter와 readiness interceptor 우선순위 진단
 * application-level Overview 200 계약과 unauthenticated full filter 결과를 같은 의미로 오인하지 않음
 */
@WebMvcTest(OverviewController.class)
@Import({ReadinessGuardInterceptor.class, ReadinessWebConfig.class})
class OverviewSecurityContextTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OverviewService overviewService;

    @MockitoBean
    private ServiceReadiness serviceReadiness;

    @Test
    void unauthenticatedJsonRequestIsRejectedByCurrentBasicFilterBeforeReadiness() throws Exception {
        mockMvc.perform(get("/api/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Realm\""))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verifyNoInteractions(overviewService);
    }

    @Test
    void unauthenticatedHtmlRequestIsRedirectedByCurrentLoginFilter() throws Exception {
        mockMvc.perform(get("/api/overview").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        verifyNoInteractions(overviewService);
    }

    @Test
    @WithMockUser
    void authenticatedRequestReachesGlobalReadinessGuard() throws Exception {
        when(serviceReadiness.isReady()).thenReturn(false);

        mockMvc.perform(get("/api/overview"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Service is not ready."))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verifyNoInteractions(overviewService);
    }

    @Test
    @WithMockUser
    void authenticatedReadyRequestReachesOverviewController() throws Exception {
        byte[] png = {1, 2, 3};
        when(serviceReadiness.isReady()).thenReturn(true);
        when(overviewService.currentPng()).thenReturn(Optional.of(png));

        mockMvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
    }
}
