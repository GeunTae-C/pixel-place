package dev.cgt.pixelplace.overview.scheduling;

import dev.cgt.pixelplace.overview.application.OverviewRenderer;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.overview.web.OverviewController;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverviewProfileTest {

    @Test
    void stubKeepsManualOverviewBeansButHasNoAutomaticTriggerOrSchedulerInfrastructure() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("stub");
            context.register(
                    dev.cgt.pixelplace.measurement.Measurements.class,
                    InMemoryTileBoard.class,
                    CanonicalZ0TileKeys.class,
                    ServiceReadiness.class,
                    OverviewRenderer.class,
                    OverviewService.class,
                    OverviewController.class,
                    OverviewScheduler.class
            );
            context.refresh();

            assertEquals(1, context.getBeansOfType(OverviewRenderer.class).size());
            assertEquals(1, context.getBeansOfType(OverviewService.class).size());
            assertEquals(1, context.getBeansOfType(OverviewController.class).size());
            assertTrue(context.getBeansOfType(OverviewScheduler.class).isEmpty());
            assertFalse(context.containsBean("taskScheduler"));
            assertFalse(context.containsBean("flushTaskScheduler"));
            assertFalse(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME
            ));
        }
    }
}
