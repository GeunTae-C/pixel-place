package dev.cgt.pixelplace.measurement;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 직접 생성 fixture와 제한 Spring context가 동일한 비활성 facade를 명시적으로 주입하는 테스트 지원 */
@TestConfiguration(proxyBeanMethods = false)
public class Measurements {
    private static final PixelMeasurement DISABLED = new PixelMeasurement(false, new SimpleMeterRegistry());
    public static PixelMeasurement disabled() { return DISABLED; }
    @Bean public PixelMeasurement pixelMeasurement() { return disabled(); }
}
