package dev.cgt.pixelplace.overview.web;

import dev.cgt.pixelplace.auth.config.ProductionAuthTestSupport;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** production Security 설정에서 공개 Overview·명시적 Bearer·readiness 순서 검증. PNG 공급만 대체 */
class OverviewSecurityContextTest {
    @TestConfiguration(proxyBeanMethods=false)
    @Import({OverviewController.class,ReadinessGuardInterceptor.class,ReadinessWebConfig.class})
    static class Fixture { }
    private org.springframework.boot.test.context.runner.WebApplicationContextRunner runner() {
        return ProductionAuthTestSupport.runner().withUserConfiguration(Fixture.class)
                .withBean(OverviewService.class,()->mock(OverviewService.class)).withBean(ServiceReadiness.class,()->mock(ServiceReadiness.class));
    }
    @Test void unauthenticatedJsonOverviewReachesReadinessWithoutBasicChallenge() {
        runner().run(c->{var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            mvc.perform(get("/api/overview").accept(MediaType.APPLICATION_JSON)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("Service is not ready.")).andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION));verifyNoInteractions(c.getBean(OverviewService.class));});
    }
    @Test void unauthenticatedHtmlOverviewUsesPublicReadinessPolicyWithoutLoginRedirect() {
        runner().run(c->{var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            mvc.perform(get("/api/overview").accept(MediaType.TEXT_HTML)).andExpect(status().isServiceUnavailable())
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION));verifyNoInteractions(c.getBean(OverviewService.class));});
    }
    @Test void explicitlyInvalidBearerIsRejectedBeforeReadinessOrOverview() {
        runner().run(c->{var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();
            mvc.perform(get("/api/overview").header(HttpHeaders.AUTHORIZATION,"Bearer invalid")).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,"Bearer"));
            verifyNoInteractions(c.getBean(OverviewService.class),c.getBean(ServiceReadiness.class));});
    }
    @Test void publicReadyRequestReachesActualOverviewController() {
        runner().run(c->{var mvc=MockMvcBuilders.webAppContextSetup(c).apply(springSecurity()).build();byte[] png={1,2,3};
            when(c.getBean(ServiceReadiness.class).isReady()).thenReturn(true);when(c.getBean(OverviewService.class).currentPng()).thenReturn(Optional.of(png));
            mvc.perform(get("/api/overview")).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(content().bytes(png));});
    }
}
