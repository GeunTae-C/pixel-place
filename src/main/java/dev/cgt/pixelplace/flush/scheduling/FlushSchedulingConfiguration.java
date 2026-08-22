package dev.cgt.pixelplace.flush.scheduling;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

/* default runtime의 flush 전용 scheduler 활성화와 application-wide 기본 후보 격리 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!stub")
@EnableConfigurationProperties(FlushScheduleProperties.class)
public class FlushSchedulingConfiguration {

    @Bean
    ErrorHandler flushSchedulingErrorHandler() {
        return new FlushSchedulingErrorHandler();
    }

    /* named @Scheduled만 선택하는 단일-thread scheduler, 전역 기본 scheduler 책임은 갖지 않음 */
    @Bean(name = "flushTaskScheduler", defaultCandidate = false)
    ThreadPoolTaskScheduler flushTaskScheduler(
            @Qualifier("flushSchedulingErrorHandler") ErrorHandler errorHandler
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("pixel-place-flush-");
        scheduler.setErrorHandler(errorHandler);
        return scheduler;
    }
}
