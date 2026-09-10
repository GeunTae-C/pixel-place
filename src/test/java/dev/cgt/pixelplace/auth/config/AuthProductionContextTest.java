package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.user.application.UserProvisioningService;
import dev.cgt.pixelplace.user.infra.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import dev.cgt.pixelplace.auth.jwt.ServiceJwtTokens;
import dev.cgt.pixelplace.auth.oauth2.AuthorizationRequestCookieCodec;
import dev.cgt.pixelplace.auth.oauth2.CookieAuthorizationRequestRepository;
import dev.cgt.pixelplace.auth.oauth2.KakaoAuthorizationRequestResolver;
import dev.cgt.pixelplace.auth.oauth2.KakaoOAuth2UserService;
import dev.cgt.pixelplace.auth.oauth2.OAuthLoginSuccessHandler;
import dev.cgt.pixelplace.auth.web.HandoffTokenController;
import dev.cgt.pixelplace.auth.web.HandoffExchangeService;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 application YAML·Boot web 자동 설정에서 A/B1/B2의 미활성 인증 경계 검증. DB collaborator만 대체 */
class AuthProductionContextTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
    @Import({AuthConfiguration.class, UserProvisioningService.class})
    @ComponentScan(basePackages = {"dev.cgt.pixelplace.auth.jwt", "dev.cgt.pixelplace.auth.oauth2", "dev.cgt.pixelplace.auth.web"})
    static class WebApplication { }

    @Test
    void applicationYamlStartsWithoutKakaoRegistrationOrDecoderAndRetainsDefaultBasicAndForm() {
        new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(WebApplication.class)
                .withBean(UserJpaRepository.class, () -> mock(UserJpaRepository.class))
                .withPropertyValues("spring.config.import=", "spring.security.user.password=test-only-password",
                        "pixel-place.auth.jwt-secret=" + AuthConfigurationTest.key(32, 'j'),
                        "pixel-place.auth.oauth-cookie-secret=" + AuthConfigurationTest.key(32, 'k'))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(AuthProperties.class));
                    assertNotNull(context.getBean(UserProvisioningService.class));
                    assertTrue(context.getBeansOfType(ClientRegistrationRepository.class).isEmpty());
                    assertTrue(context.getBeansOfType(OAuth2AuthorizedClientService.class).isEmpty());
                    assertTrue(context.getBeansOfType(JwtDecoder.class).isEmpty());
                    assertTrue(context.getBeansOfType(ServiceJwtTokens.class).isEmpty());
                    assertTrue(context.getBeansOfType(AuthorizationRequestCookieCodec.class).isEmpty());
                    assertTrue(context.getBeansOfType(CookieAuthorizationRequestRepository.class).isEmpty());
                    assertTrue(context.getBeansOfType(KakaoAuthorizationRequestResolver.class).isEmpty());
                    assertTrue(context.getBeansOfType(KakaoOAuth2UserService.class).isEmpty());
                    assertTrue(context.getBeansOfType(OAuthLoginSuccessHandler.class).isEmpty());
                    assertTrue(context.getBeansOfType(HandoffTokenController.class).isEmpty());
                    assertTrue(context.getBeansOfType(HandoffExchangeService.class).isEmpty());
                    var filters = context.getBean(SecurityFilterChain.class).getFilters();
                    assertTrue(filters.stream().anyMatch(BasicAuthenticationFilter.class::isInstance));
                    assertTrue(filters.stream().anyMatch(UsernamePasswordAuthenticationFilter.class::isInstance));
                    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                    mvc.perform(get("/api/pixels").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
                    mvc.perform(get("/api/pixels").accept(MediaType.TEXT_HTML)).andExpect(status().isFound())
                            .andExpect(redirectedUrl("/login"));
                    // C 이전에는 인증을 통과해도 교환 route 자체가 등록되지 않은 상태
                    mvc.perform(post("/api/auth/token").with(user("test")).with(csrf())).andExpect(status().isNotFound());
                });
    }
}
