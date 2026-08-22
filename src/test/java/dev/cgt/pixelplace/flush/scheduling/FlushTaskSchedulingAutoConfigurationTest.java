package dev.cgt.pixelplace.flush.scheduling;

import dev.cgt.pixelplace.flush.application.FlushWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FlushTaskSchedulingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(
                    FlushSchedulingConfiguration.class,
                    BootWiringFixtureConfiguration.class
            )
            .withPropertyValues("pixel-place.flush.fixed-delay=1h");

    @Test
    void bootCreatesNormalDefaultSchedulerBesideNonDefaultFlushScheduler() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            Map<String, TaskScheduler> schedulers = context.getBeansOfType(TaskScheduler.class);
            assertEquals(2, schedulers.size());
            assertTrue(schedulers.containsKey("taskScheduler"));
            assertTrue(schedulers.containsKey("flushTaskScheduler"));

            TaskScheduler normal = schedulers.get("taskScheduler");
            TaskScheduler flush = schedulers.get("flushTaskScheduler");
            assertNotNull(normal);
            assertNotNull(flush);
            assertNotSame(normal, flush);

            BootCandidateProbe probe = context.getBean(BootCandidateProbe.class);
            assertSame(normal, probe.plainScheduler());
            assertSame(flush, probe.namedFlushScheduler());

            assertTrue(((AbstractBeanDefinition) context.getBeanFactory()
                    .getBeanDefinition("taskScheduler"))
                    .isDefaultCandidate());
            assertFalse(((AbstractBeanDefinition) context.getBeanFactory()
                    .getBeanDefinition("flushTaskScheduler"))
                    .isDefaultCandidate());

            Method scheduledMethod = FlushScheduler.class.getDeclaredMethod("runScheduledFlush");
            assertEquals(
                    "flushTaskScheduler",
                    scheduledMethod.getAnnotation(Scheduled.class).scheduler()
            );
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(FlushScheduler.class)
    static class BootWiringFixtureConfiguration {

        @Bean
        FlushWorker flushWorker() {
            return mock(FlushWorker.class);
        }

        @Bean
        BootCandidateProbe bootCandidateProbe(
                TaskScheduler taskScheduler,
                @Qualifier("flushTaskScheduler") TaskScheduler flushTaskScheduler
        ) {
            return new BootCandidateProbe(taskScheduler, flushTaskScheduler);
        }
    }

    private record BootCandidateProbe(
            TaskScheduler plainScheduler,
            TaskScheduler namedFlushScheduler
    ) {
    }
}
