package dev.cgt.pixelplace.flush.scheduling;

import dev.cgt.pixelplace.flush.application.FlushWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.util.ErrorHandler;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FlushSchedulingContractTest {

    @Test
    void scheduledMethodUsesNamedFixedDelayAndSameInitialDelayOnly() {
        Method[] scheduledMethods = Arrays.stream(FlushScheduler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .toArray(Method[]::new);

        assertEquals(1, scheduledMethods.length);
        Method method = scheduledMethods[0];
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertEquals("runScheduledFlush", method.getName());
        assertEquals(0, method.getParameterCount());
        assertEquals(void.class, method.getReturnType());
        assertEquals("flushTaskScheduler", scheduled.scheduler());
        assertEquals("${pixel-place.flush.fixed-delay}", scheduled.fixedDelayString());
        assertEquals("${pixel-place.flush.fixed-delay}", scheduled.initialDelayString());
        assertEquals(-1L, scheduled.fixedRate());
        assertEquals("", scheduled.fixedRateString());
        assertEquals("", scheduled.cron());
    }

    @Test
    void schedulerAndConfigurationAreDefaultRuntimeOnlyAndSchedulingIsNotGlobalApplicationPolicy() {
        assertEquals("!stub", profileValue(FlushScheduler.class));
        assertEquals("!stub", profileValue(FlushSchedulingConfiguration.class));
        assertNotNull(FlushSchedulingConfiguration.class.getAnnotation(EnableScheduling.class));
        EnableConfigurationProperties properties = FlushSchedulingConfiguration.class
                .getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(properties);
        assertTrue(Arrays.asList(properties.value()).contains(FlushScheduleProperties.class));
        assertFalse(SchedulingConfigurer.class.isAssignableFrom(FlushSchedulingConfiguration.class));
        assertNull(dev.cgt.pixelplace.PixelPlaceApplication.class.getAnnotation(EnableScheduling.class));
    }

    @Test
    void flushSchedulerBeanIsNamedNonDefaultCandidateAndNotPrimary() throws Exception {
        Method beanMethod = FlushSchedulingConfiguration.class.getDeclaredMethod(
                "flushTaskScheduler",
                ErrorHandler.class
        );
        Bean bean = beanMethod.getAnnotation(Bean.class);

        assertNotNull(bean);
        assertEquals("flushTaskScheduler", bean.name()[0]);
        assertTrue(bean.autowireCandidate());
        assertFalse(bean.defaultCandidate());
        assertFalse(beanMethod.isAnnotationPresent(Primary.class));
        assertEquals(ThreadPoolTaskScheduler.class, beanMethod.getReturnType());
    }

    @Test
    void namedQualifierSelectsFlushSchedulerWhilePlainInjectionSelectsNormalDefaultCandidate() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "flush-test",
                    Map.of("pixel-place.flush.fixed-delay", "1s")
            ));
            context.register(FlushSchedulingConfiguration.class, CandidateFixtureConfiguration.class);
            context.refresh();

            CandidateProbe probe = context.getBean(CandidateProbe.class);
            ThreadPoolTaskScheduler flush = context.getBean(
                    "flushTaskScheduler",
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler normal = context.getBean(
                    "taskScheduler",
                    ThreadPoolTaskScheduler.class
            );

            assertSame(normal, probe.plainScheduler());
            assertSame(flush, probe.namedFlushScheduler());
            assertFalse(((AbstractBeanDefinition) context.getBeanFactory()
                    .getBeanDefinition("flushTaskScheduler"))
                    .isDefaultCandidate());
            assertTrue(((AbstractBeanDefinition) context.getBeanFactory()
                    .getBeanDefinition("taskScheduler"))
                    .isDefaultCandidate());
            assertEquals(1, flush.getScheduledThreadPoolExecutor().getCorePoolSize());
            assertEquals(1, normal.getScheduledThreadPoolExecutor().getCorePoolSize());
            assertTrue(context.getBean("flushSchedulingErrorHandler")
                    instanceof FlushSchedulingErrorHandler);
        }
    }

    @Test
    void stubProfileCreatesNeitherFlushSchedulerNorSchedulingInfrastructure() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("stub");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "flush-test",
                    Map.of("pixel-place.flush.fixed-delay", "1s")
            ));
            context.registerBean(FlushWorker.class, () -> mock(FlushWorker.class));
            context.register(FlushSchedulingConfiguration.class, FlushScheduler.class);
            context.refresh();

            assertTrue(context.getBeansOfType(FlushScheduler.class).isEmpty());
            assertFalse(context.containsBean("flushTaskScheduler"));
            assertFalse(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME
            ));
        }
    }

    private String profileValue(Class<?> type) {
        Profile profile = type.getAnnotation(Profile.class);
        assertNotNull(profile);
        assertEquals(1, profile.value().length);
        return profile.value()[0];
    }

    @Configuration(proxyBeanMethods = false)
    static class CandidateFixtureConfiguration {

        @Bean(name = "taskScheduler")
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix("test-default-");
            return scheduler;
        }

        @Bean
        CandidateProbe candidateProbe(
                TaskScheduler taskScheduler,
                @Qualifier("flushTaskScheduler") TaskScheduler flushTaskScheduler
        ) {
            return new CandidateProbe(taskScheduler, flushTaskScheduler);
        }
    }

    private record CandidateProbe(
            TaskScheduler plainScheduler,
            TaskScheduler namedFlushScheduler
    ) {
    }
}
