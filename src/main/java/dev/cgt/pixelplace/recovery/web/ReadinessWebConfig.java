package dev.cgt.pixelplace.recovery.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/*
 * startup recovery와 runtime fatal 상태를 차단하는 readiness guard의 MVC 연결 설정
 * 현재 구현된 보호 API만 연결하며 actuator, error endpoint, 정적 리소스로 범위를 확장하지 않음
 */
@Configuration
public class ReadinessWebConfig implements WebMvcConfigurer {

    private final ReadinessGuardInterceptor readinessGuardInterceptor;

    public ReadinessWebConfig(ReadinessGuardInterceptor readinessGuardInterceptor) {
        this.readinessGuardInterceptor = readinessGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(readinessGuardInterceptor)
                .addPathPatterns(
                        "/api/pixels",
                        "/api/board",
                        "/api/tiles/**"
                );
    }
}
