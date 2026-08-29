package dev.cgt.pixelplace.overview.scheduling;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.FlushRunResult;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.flush.scheduling.FlushScheduler;
import dev.cgt.pixelplace.flush.scheduling.FlushSchedulingConfiguration;
import dev.cgt.pixelplace.overview.application.OverviewRenderer;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverviewSchedulingRoutingTest {

    private final ApplicationContextRunner bootContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(
                    FlushSchedulingConfiguration.class,
                    BootRoutingFixtureConfiguration.class
            )
            .withPropertyValues("pixel-place.flush.fixed-delay=1h");

    @Test
    void registeredOverviewRunnableUsesDefaultSchedulerAndHonorsApplicationReadyGate() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "routing-test",
                    Map.of("pixel-place.flush.fixed-delay", "1h")
            ));
            context.register(RoutingFixtureConfiguration.class);
            context.refresh();

            RecordingTaskScheduler normal = context.getBean(
                    "taskScheduler",
                    RecordingTaskScheduler.class
            );
            RecordingTaskScheduler flush = context.getBean(
                    "flushTaskScheduler",
                    RecordingTaskScheduler.class
            );

            assertEquals(1, normal.fixedDelayCalls.size());
            assertEquals(1, flush.fixedDelayCalls.size());
            assertEquals(
                    Duration.ofMillis(BoardConstants.OVERVIEW_REFRESH_MILLIS),
                    normal.fixedDelayCalls.getFirst().delay()
            );
            assertEquals(Duration.ofHours(1), flush.fixedDelayCalls.getFirst().delay());
            assertTrue(normal.fixedDelayCalls.getFirst().taskDescription()
                    .contains("OverviewScheduler.runScheduledRefresh"));
            assertTrue(flush.fixedDelayCalls.getFirst().taskDescription()
                    .contains("FlushScheduler.runScheduledFlush"));
            assertFalse(normal == flush);

            OverviewService overviewService = context.getBean(OverviewService.class);
            Runnable periodicOverviewTask = normal.fixedDelayCalls.getFirst().task();
            periodicOverviewTask.run();
            verify(overviewService, never()).refresh();

            context.publishEvent(mock(ApplicationReadyEvent.class));
            assertEquals(1, normal.oneShotTasks.size());

            periodicOverviewTask.run();
            verify(overviewService, times(1)).refresh();
        }
    }

    @Test
    void bootDefaultAndFlushSchedulersHaveDistinctExecutorsAndOverviewFailureCannotBlockFlush() {
        bootContextRunner.run(context -> {
            assertTrue(context.getStartupFailure() == null);
            TaskScheduler normal = context.getBean("taskScheduler", TaskScheduler.class);
            TaskScheduler flush = context.getBean("flushTaskScheduler", TaskScheduler.class);
            assertNotSame(normal, flush);
            assertTrue(((AbstractBeanDefinition) context.getBeanFactory()
                    .getBeanDefinition("taskScheduler"))
                    .isDefaultCandidate());
            assertFalse(((AbstractBeanDefinition) context.getBeanFactory()
                    .getBeanDefinition("flushTaskScheduler"))
                    .isDefaultCandidate());

            CountDownLatch executed = new CountDownLatch(2);
            AtomicReference<Thread> normalThread = new AtomicReference<>();
            AtomicReference<Thread> flushThread = new AtomicReference<>();
            normal.schedule(() -> {
                normalThread.set(Thread.currentThread());
                executed.countDown();
            }, Instant.now());
            flush.schedule(() -> {
                flushThread.set(Thread.currentThread());
                executed.countDown();
            }, Instant.now());
            assertTrue(await(executed));
            assertNotSame(normalThread.get(), flushThread.get());

            OverviewScheduler overviewScheduler = context.getBean(OverviewScheduler.class);
            OverviewService overviewService = context.getBean(OverviewService.class);
            ServiceReadiness readiness = context.getBean(ServiceReadiness.class);
            OverviewRenderer renderer = context.getBean(OverviewRenderer.class);
            overviewScheduler.scheduleInitialRefresh();

            verify(renderer, timeout(5_000)).render();
            assertTrue(overviewService.currentPng().isEmpty());
            assertTrue(readiness.isReady());

            FlushScheduler flushScheduler = context.getBean(FlushScheduler.class);
            FlushWorker flushWorker = context.getBean(FlushWorker.class);
            flushScheduler.runScheduledFlush();
            verify(flushWorker).flushOnce();
        });
    }

    private boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Scheduler identity wait was interrupted.", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import({OverviewScheduler.class, FlushScheduler.class})
    static class RoutingFixtureConfiguration {

        @Bean
        OverviewService overviewService() {
            return mock(OverviewService.class);
        }

        @Bean
        FlushWorker flushWorker() {
            return mock(FlushWorker.class);
        }

        @Bean(name = "taskScheduler")
        RecordingTaskScheduler taskScheduler() {
            return new RecordingTaskScheduler();
        }

        @Bean(name = "flushTaskScheduler", defaultCandidate = false)
        RecordingTaskScheduler flushTaskScheduler() {
            return new RecordingTaskScheduler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OverviewScheduler.class, FlushScheduler.class})
    static class BootRoutingFixtureConfiguration {

        @Bean
        OverviewRenderer overviewRenderer() {
            OverviewRenderer renderer = mock(OverviewRenderer.class);
            when(renderer.render()).thenThrow(new IllegalStateException("overview failed"));
            return renderer;
        }

        @Bean
        ServiceReadiness serviceReadiness() {
            ServiceReadiness readiness = new ServiceReadiness();
            readiness.markReady();
            return readiness;
        }

        @Bean
        OverviewService overviewService(
                OverviewRenderer overviewRenderer,
                ServiceReadiness serviceReadiness
        ) {
            return new OverviewService(overviewRenderer, serviceReadiness);
        }

        @Bean
        FlushWorker flushWorker() {
            FlushWorker worker = mock(FlushWorker.class);
            when(worker.flushOnce()).thenReturn(FlushRunResult.NO_OP);
            return worker;
        }
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {

        private final List<FixedDelayCall> fixedDelayCalls = new ArrayList<>();
        private final List<Runnable> oneShotTasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            oneShotTasks.add(task);
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task,
                Instant startTime,
                Duration period
        ) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task,
                Instant startTime,
                Duration delay
        ) {
            fixedDelayCalls.add(new FixedDelayCall(task, task.toString(), delay));
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            fixedDelayCalls.add(new FixedDelayCall(task, task.toString(), delay));
            return mock(ScheduledFuture.class);
        }
    }

    private record FixedDelayCall(Runnable task, String taskDescription, Duration delay) {
    }
}
