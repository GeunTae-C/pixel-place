package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.auth.oauth2.KakaoAuthorizationRequestResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 YAML과 Boot 등록 해석 검증. DB만 제외하고 카카오 외부 통신 없이 callback 고정성 확인 */
class KakaoRegistrationTest {
    static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RegistrationApplication.class).withPropertyValues(
                        "spring.config.import=", "KAKAO_CLIENT_ID=c-test-client", "KAKAO_CLIENT_SECRET=c-test-secret",
                        "pixel-place.auth.jwt-secret=" + AuthConfigurationTest.key(32, 'j'),
                        "pixel-place.auth.oauth-cookie-secret=" + AuthConfigurationTest.key(32, 'k'));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"})
    @Import(AuthConfiguration.class)
    static class RegistrationApplication {
        @Bean KakaoAuthorizationRequestResolver resolver(ClientRegistrationRepository registrations, OriginPolicy origins) {
            return new KakaoAuthorizationRequestResolver(registrations, origins);
        }
    }

    @Test void productionYamlProvidesCompleteScopeFreeRegistrationAndFixedRedirect() {
        runner().run(c -> {
            assertNull(c.getStartupFailure());
            var registrations=c.getBean(ClientRegistrationRepository.class);
            var r=registrations.findByRegistrationId("kakao");
            assertNotNull(r);
            assertNull(registrations.findByRegistrationId("other"));
            assertEquals(ClientAuthenticationMethod.CLIENT_SECRET_POST,r.getClientAuthenticationMethod());
            assertEquals(AuthorizationGrantType.AUTHORIZATION_CODE,r.getAuthorizationGrantType());
            assertTrue(r.getScopes() == null || r.getScopes().isEmpty());
            assertEquals("https://kauth.kakao.com/oauth/authorize",r.getProviderDetails().getAuthorizationUri());
            assertEquals("https://kauth.kakao.com/oauth/token",r.getProviderDetails().getTokenUri());
            assertEquals("https://kapi.kakao.com/v2/user/me",r.getProviderDetails().getUserInfoEndpoint().getUri());
            assertEquals("id",r.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
            assertEquals(AuthenticationMethod.HEADER,r.getProviderDetails().getUserInfoEndpoint().getAuthenticationMethod());
            assertEquals(c.getBean(OriginPolicy.class).providerCallbackUri().toString(),r.getRedirectUri());
            assertNull(c.getEnvironment().getProperty("app.oauth2.kakao.redirect-uri"));
            var request=new MockHttpServletRequest("GET",KakaoAuthorizationRequestResolver.START_PATH);
            request.setServletPath(KakaoAuthorizationRequestResolver.START_PATH);
            request.addHeader("Host","attacker.example"); request.addHeader("Forwarded","host=attacker.example;proto=https");
            var auth=c.getBean(KakaoAuthorizationRequestResolver.class).resolve(request);
            assertEquals(r.getRedirectUri(),auth.getRedirectUri());
            assertEquals("S256",auth.getAdditionalParameters().get("code_challenge_method"));
        });
    }

    @Test void separatelyOverriddenRegistrationCallbackRejectsStartup() {
        runner().withPropertyValues("spring.security.oauth2.client.registration.kakao.redirect-uri=http://localhost:8080/other")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }
}
