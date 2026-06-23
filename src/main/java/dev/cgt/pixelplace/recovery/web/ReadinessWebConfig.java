package dev.cgt.pixelplace.recovery.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/*
 * recovery readiness guard를 현재 구현된 HTTP API 경계에만 연결하는 MVC 설정
 * actuator, error endpoint, 정적 리소스는 startup recovery 차단 대상에서 제외
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
