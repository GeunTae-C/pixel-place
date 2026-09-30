package dev.cgt.benchmark;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 18 앱에서만 등록하는 write/read servlet 소유 관측. client timeout과 실제 gzip/handler 종료를 분리 */
@Configuration(proxyBeanMethods = false)
class Phase18Activity {
    record State(String kind, long started, long completed) { }
    static final class Tracker implements Filter {
        private final Map<String, State> requests = new LinkedHashMap<>();
        private final int maximum;
        private String delayed;
        private CountDownLatch entered, release;
        Tracker(int maximum) { this.maximum = maximum; }
        synchronized void delay(String id, CountDownLatch entered, CountDownLatch release) {
            this.delayed = id; this.entered = entered; this.release = release;
        }
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            var http = (HttpServletRequest) request; String id = http.getHeader("X-Phase18-Request");
            String kind = http.getRequestURI().startsWith("/api/tiles/") ? "read" : "write";
            CountDownLatch wait = null;
            synchronized (this) {
                if (id == null || id.length() > 180 || requests.size() >= maximum || requests.containsKey(id))
                    throw new ServletException("Missing/duplicate/unbounded fixture request identity");
                requests.put(id, new State(kind, System.nanoTime(), 0));
                if (id.equals(delayed)) { entered.countDown(); wait = release; }
            }
            try {
                // 소진 음성 fixture 전용 latch. production 시계·업무 결과 변경 없음
                if (wait != null && !wait.await(30, TimeUnit.SECONDS)) throw new ServletException("Read fixture release deadline");
                chain.doFilter(request, response);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new ServletException(failure); }
            finally { synchronized (this) { State before = requests.get(id); requests.put(id, new State(kind, before.started, System.nanoTime())); } }
        }
        synchronized boolean idle(Set<String> expected) {
            return requests.keySet().equals(expected) && requests.values().stream().allMatch(s -> s.completed != 0);
        }
        synchronized Map<String, State> snapshot() { return Map.copyOf(requests); }
    }
    @Bean FilterRegistrationBean<Tracker> phase18Servlets(Tracker tracker) {
        var registration = new FilterRegistrationBean<>(tracker);
        registration.addUrlPatterns("/api/pixels", "/api/tiles/*"); registration.setOrder(Integer.MIN_VALUE + 1);
        registration.setAsyncSupported(false); return registration;
    }
}
