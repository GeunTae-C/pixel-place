package dev.cgt.pixelplace.flush.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlushSchedulePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void oneSecondPropertyBindsToDuration() {
        contextRunner
                .withPropertyValues("pixel-place.flush.fixed-delay=1s")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(
                            Duration.ofSeconds(1),
                            context.getBean(FlushScheduleProperties.class).fixedDelay()
                    );
                });
    }

    @Test
    void missingMalformedZeroAndNegativePropertiesFailContextStartup() {
        assertBindingFailure();
        assertBindingFailure("pixel-place.flush.fixed-delay=not-a-duration");
        assertBindingFailure("pixel-place.flush.fixed-delay=0s");
        assertBindingFailure("pixel-place.flush.fixed-delay=-1s");
    }

    private void assertBindingFailure(String... propertyValues) {
        contextRunner
                .withPropertyValues(propertyValues)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlushScheduleProperties.class)
    static class BindingConfiguration {
    }
}
