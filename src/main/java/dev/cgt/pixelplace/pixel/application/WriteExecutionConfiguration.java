package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** 선택한 executor의 단일 종료 owner. core→appender→storage 의존 순서로 executor 소진 선행 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WriteExecutionProperties.class)
public class WriteExecutionConfiguration {
    @Bean(destroyMethod = "close")
    public PixelWriteExecutor pixelWriteExecutor(WriteExecutionProperties properties,
            FlushBoundaryCoordinator coordinator, PixelWriteService core, DirtyTileTracker dirty,
            ServiceReadiness readiness, PixelMeasurement measurement, Environment environment) {
        properties.validate();
        if ("group".equals(properties.getMode())) {
            // stub은 startup 입력 대체용. 실제 group 내구성 실행으로 잘못 사용되지 않도록 명시 거부
            if (environment.acceptsProfiles(Profiles.of("stub")))
                throw new IllegalStateException("Group write execution is not supported with stub profile");
            return new GroupPixelWriteExecutor(coordinator, core, dirty, readiness, properties, measurement);
        }
        return new SinglePixelWriteExecutor(coordinator, core, dirty, readiness, measurement);
    }
}
