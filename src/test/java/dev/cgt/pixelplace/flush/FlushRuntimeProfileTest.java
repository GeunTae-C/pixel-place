package dev.cgt.pixelplace.flush;

import dev.cgt.pixelplace.checkpoint.application.CheckpointFence;
import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.JpaCheckpointFence;
import dev.cgt.pixelplace.checkpoint.infra.WalCheckpointJpaRepository;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.flush.application.FlushDbStateProbe;
import dev.cgt.pixelplace.flush.application.FlushPersistenceService;
import dev.cgt.pixelplace.flush.application.FlushPlanCaptureService;
import dev.cgt.pixelplace.flush.application.FlushReconciliationService;
import dev.cgt.pixelplace.flush.application.FlushSingleFlightGuard;
import dev.cgt.pixelplace.flush.application.FlushTransactionExecutor;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.flush.application.FlushWalRetention;
import dev.cgt.pixelplace.wal.application.WalSegmentRetention;
import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.flush.infra.JpaFlushDbStateProbe;
import dev.cgt.pixelplace.flush.infra.ProgrammaticFlushTransactionExecutor;
import dev.cgt.pixelplace.pixel.application.PixelEventWriter;
import dev.cgt.pixelplace.pixel.infra.JpaPixelEventWriter;
import dev.cgt.pixelplace.pixel.infra.PixelEventJpaRepository;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.application.TileSnapshotWriter;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.infra.JpaTileMetadataReader;
import dev.cgt.pixelplace.tile.infra.JpaTileSnapshotWriter;
import dev.cgt.pixelplace.tile.infra.TileJpaRepository;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// 외부 호출 없이 11-B runtime bean의 default/stub 상호 배타 활성만 검증
class FlushRuntimeProfileTest {

    @Test
    void defaultProfileActivatesOneProductionBeanForEveryRuntimeFlushPort() {
        try (AnnotationConfigApplicationContext context = openContext()) {
            assertSingleBean(context, FlushWorker.class, FlushWorker.class);
            assertSingleBean(context, FlushWalRetention.class, FlushWalRetention.class);
            assertSingleBean(context, FlushPersistenceService.class, FlushPersistenceService.class);
            assertSingleBean(
                    context,
                    FlushTransactionExecutor.class,
                    ProgrammaticFlushTransactionExecutor.class
            );
            assertSingleBean(context, PendingAmbiguousFlushStore.class, PendingAmbiguousFlushStore.class);
            assertSingleBean(context, FlushDbStateProbe.class, JpaFlushDbStateProbe.class);
            assertSingleBean(
                    context,
                    FlushReconciliationService.class,
                    FlushReconciliationService.class
            );
            assertSingleBean(context, CheckpointFence.class, JpaCheckpointFence.class);
            assertSingleBean(context, PixelEventWriter.class, JpaPixelEventWriter.class);
            assertSingleBean(context, TileSnapshotWriter.class, JpaTileSnapshotWriter.class);
            assertSingleBean(context, FlushPlanCaptureService.class, FlushPlanCaptureService.class);
            assertSingleBean(context, FlushBoundaryCoordinator.class, FlushBoundaryCoordinator.class);
        }
    }

    @Test
    void stubProfileHasNoRuntimeFlushWorkerTransactionWriterFenceOrProbe() {
        try (AnnotationConfigApplicationContext context = openContext("stub")) {
            assertTrue(context.getBeansOfType(FlushWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(FlushWalRetention.class).isEmpty());
            assertTrue(context.getBeansOfType(FlushPersistenceService.class).isEmpty());
            assertTrue(context.getBeansOfType(FlushTransactionExecutor.class).isEmpty());
            assertTrue(context.getBeansOfType(PendingAmbiguousFlushStore.class).isEmpty());
            assertTrue(context.getBeansOfType(FlushDbStateProbe.class).isEmpty());
            assertTrue(context.getBeansOfType(FlushReconciliationService.class).isEmpty());
            assertTrue(context.getBeansOfType(CheckpointFence.class).isEmpty());
            assertTrue(context.getBeansOfType(PixelEventWriter.class).isEmpty());
            assertTrue(context.getBeansOfType(TileSnapshotWriter.class).isEmpty());
            assertTrue(context.getBeansOfType(FlushPlanCaptureService.class).isEmpty());
            assertSingleBean(context, FlushBoundaryCoordinator.class, FlushBoundaryCoordinator.class);
            assertSingleBean(context, FlushSingleFlightGuard.class, FlushSingleFlightGuard.class);
        }
    }

    private AnnotationConfigApplicationContext openContext(String... activeProfiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(activeProfiles);

        context.registerBean(
                WalCheckpointJpaRepository.class,
                () -> mock(WalCheckpointJpaRepository.class)
        );
        context.registerBean(TileJpaRepository.class, () -> mock(TileJpaRepository.class));
        context.registerBean(
                PixelEventJpaRepository.class,
                () -> mock(PixelEventJpaRepository.class)
        );
        context.registerBean(
                PlatformTransactionManager.class,
                () -> mock(PlatformTransactionManager.class)
        );
        context.registerBean(CheckpointReader.class, () -> mock(CheckpointReader.class));
        context.registerBean(WalReplaySource.class, () -> mock(WalReplaySource.class));
        context.registerBean(WalSegmentRetention.class, () -> mock(WalSegmentRetention.class));
        context.registerBean(DirtyTileTracker.class, () -> mock(DirtyTileTracker.class));
        context.registerBean(InMemoryTileBoard.class, () -> mock(InMemoryTileBoard.class));
        context.registerBean(ServiceReadiness.class, ServiceReadiness::new);

        context.register(
                dev.cgt.pixelplace.measurement.Measurements.class,
                CanonicalZ0TileKeys.class,
                DbBootstrapClassifier.class,
                FlushBoundaryCoordinator.class,
                FlushSingleFlightGuard.class,
                JpaTileMetadataReader.class,
                FlushPlanCaptureService.class,
                JpaCheckpointFence.class,
                JpaPixelEventWriter.class,
                JpaTileSnapshotWriter.class,
                FlushPersistenceService.class,
                ProgrammaticFlushTransactionExecutor.class,
                PendingAmbiguousFlushStore.class,
                JpaFlushDbStateProbe.class,
                FlushReconciliationService.class,
                FlushWorker.class,
                FlushWalRetention.class
        );
        context.refresh();
        return context;
    }

    private <T> void assertSingleBean(
            AnnotationConfigApplicationContext context,
            Class<T> portType,
            Class<? extends T> expectedImplementation
    ) {
        Map<String, T> beans = context.getBeansOfType(portType);

        assertEquals(1, beans.size());
        assertEquals(expectedImplementation, context.getBean(portType).getClass());
    }
}
