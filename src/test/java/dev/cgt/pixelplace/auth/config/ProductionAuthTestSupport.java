package dev.cgt.pixelplace.auth.config;

import dev.cgt.pixelplace.user.application.UserProvisioningService;
import dev.cgt.pixelplace.user.infra.UserJpaRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import java.time.*;
import static org.mockito.Mockito.mock;

/** 실제 YAML·Boot·production 인증 조립용 공통 입력. 외부 DB만 대체하며 비밀 없는 고정 시계 사용 */
public final class ProductionAuthTestSupport {
    public static final Instant NOW=Instant.parse("2026-09-11T00:00:00Z");
    public static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private ProductionAuthTestSupport() { }

    public static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Application.class)
                .withBean(UserJpaRepository.class, () -> mock(UserJpaRepository.class))
                .withBean(Clock.class, () -> CLOCK)
                .withPropertyValues("spring.config.import=", "KAKAO_CLIENT_ID=c-test-client", "KAKAO_CLIENT_SECRET=c-test-secret",
                        "pixel-place.auth.jwt-secret="+AuthConfigurationTest.key(32,'j'),
                        "pixel-place.auth.oauth-cookie-secret="+AuthConfigurationTest.key(32,'k'));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"})
    @Import({AuthConfiguration.class, AuthRuntimeConfiguration.class, AuthSecurityConfiguration.class, UserProvisioningService.class,
            dev.cgt.pixelplace.board.web.BoardController.class, dev.cgt.pixelplace.auth.web.HandoffTokenController.class})
    public static class Application { }
}
