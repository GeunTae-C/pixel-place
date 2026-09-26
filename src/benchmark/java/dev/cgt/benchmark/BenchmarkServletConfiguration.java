package dev.cgt.benchmark;

import jakarta.servlet.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** benchmark에서만 등록하는 실제 servlet 진입/종료 관측. production scan·security chain 변경 없음 */
@Configuration(proxyBeanMethods = false)
class BenchmarkServletConfiguration {
    static final class Activity implements Filter {
        final AtomicInteger active = new AtomicInteger();
        final AtomicLong started = new AtomicLong(), completed = new AtomicLong();
        @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            active.incrementAndGet(); started.incrementAndGet();
            try { chain.doFilter(request, response); }
            finally { completed.incrementAndGet(); active.decrementAndGet(); }
        }
    }
    @Bean Activity benchmarkActivity() { return new Activity(); }
    @Bean FilterRegistrationBean<Activity> benchmarkPixelActivity(Activity activity) {
        var registration = new FilterRegistrationBean<>(activity);
        registration.addUrlPatterns("/api/pixels"); registration.setOrder(Integer.MIN_VALUE); registration.setAsyncSupported(false);
        return registration;
    }
}
