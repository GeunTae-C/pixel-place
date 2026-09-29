package dev.cgt.pixelplace.recovery;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.JpaCheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.StubCheckpointReader;
import dev.cgt.pixelplace.checkpoint.infra.WalCheckpointJpaRepository;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.flush.application.FlushPlanCaptureService;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryDbViewCaptureService;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryService;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.application.TileMetadataReader;
import dev.cgt.pixelplace.tile.application.TileSnapshotLoader;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.infra.JpaTileMetadataReader;
import dev.cgt.pixelplace.tile.infra.JpaTileSnapshotLoader;
import dev.cgt.pixelplace.tile.infra.StubTileSnapshotLoader;
import dev.cgt.pixelplace.tile.infra.TileJpaRepository;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplaySource;
import dev.cgt.pixelplace.wal.infra.FileWalReplaySource;
import dev.cgt.pixelplace.wal.infra.StubWalReplaySource;
import dev.cgt.pixelplace.wal.infra.WalProperties;
import dev.cgt.pixelplace.wal.infra.SegmentedWalStorage;
import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// 외부 DB/WAL 호출 없이 startup recovery 입력 adapter의 profile별 bean 유일성 검증
class RecoveryAdapterProfileTest {

    @Test
    void defaultProfileSelectsExactlyOneRealAdapterForEachRecoveryPort() {
        try (AnnotationConfigApplicationContext context = openContext()) {
            assertSingleBean(context, CheckpointReader.class, JpaCheckpointReader.class);
            assertSingleBean(context, TileSnapshotLoader.class, JpaTileSnapshotLoader.class);
            assertSingleBean(context, WalReplaySource.class, FileWalReplaySource.class);
            assertSingleBean(context, dev.cgt.pixelplace.wal.application.WalStoragePreparation.class, dev.cgt.pixelplace.wal.infra.FileWalStoragePreparation.class);
            assertSame(context.getBean(SegmentedWalStorage.class),field(context.getBean(dev.cgt.pixelplace.wal.infra.FileWalStoragePreparation.class),"storage"));
            assertCommonCaptureAndRecoveryWiring(context);
        }
    }

    @Test
    void stubProfileSelectsExactlyOneStubAdapterForEachRecoveryPort() {
        try (AnnotationConfigApplicationContext context = openContext("stub")) {
            assertSingleBean(context, CheckpointReader.class, StubCheckpointReader.class);
            assertSingleBean(context, TileSnapshotLoader.class, StubTileSnapshotLoader.class);
            assertSingleBean(context, WalReplaySource.class, StubWalReplaySource.class);
            assertSingleBean(context, dev.cgt.pixelplace.wal.application.WalStoragePreparation.class, dev.cgt.pixelplace.wal.infra.StubWalStoragePreparation.class);
            assertCommonCaptureAndRecoveryWiring(context);
            var durability=context.getBean(dev.cgt.pixelplace.wal.infra.WindowsWalFileDurability.class);
            Object bridge=field(durability,"bridge");
            assertTrue(field(bridge,"loaded")==null);
            context.getBean(StartupRecoveryService.class).recover();
            assertTrue(context.getBean(ServiceReadiness.class).isReady());
            assertTrue(field(bridge,"loaded")==null);
            assertTrue(context.getBeansOfType(StartupRecoveryDbViewCaptureService.class).values().stream()
                    .noneMatch(bean -> bean.getClass().getSimpleName().contains("Stub")));
        }
    }

    @Test
    // runtime flush 입력은 실제 DB/WAL 조합만 허용하므로 default profile에서만 활성
    void defaultProfileActivatesRuntimeFlushBeansAndSharedCoordinator() {
        try (AnnotationConfigApplicationContext context = openContext()) {
            assertSingleBean(context, TileMetadataReader.class, JpaTileMetadataReader.class);
            assertSingleBean(context, FlushPlanCaptureService.class, FlushPlanCaptureService.class);
            assertSingleBean(context, FlushBoundaryCoordinator.class, FlushBoundaryCoordinator.class);
        }
    }

    @Test
    // stub은 startup recovery 입력만 대체하며 runtime flush용 stub bean은 만들지 않음
    void stubProfileDisablesRuntimeFlushBeansButKeepsSharedCoordinator() {
        try (AnnotationConfigApplicationContext context = openContext("stub")) {
            assertTrue(context.getBeansOfType(TileMetadataReader.class).isEmpty());
            assertTrue(context.getBeansOfType(FlushPlanCaptureService.class).isEmpty());
            assertSingleBean(context, FlushBoundaryCoordinator.class, FlushBoundaryCoordinator.class);
        }
    }

    private AnnotationConfigApplicationContext openContext(String... activeProfiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(activeProfiles);

        // real adapter 생성 의존성만 제공하며 repository/WAL 메서드는 호출하지 않음
        context.registerBean(
                WalCheckpointJpaRepository.class,
                () -> mock(WalCheckpointJpaRepository.class)
        );
        context.registerBean(TileJpaRepository.class, () -> mock(TileJpaRepository.class));
        context.registerBean(WalProperties.class, WalProperties::new);
        context.registerBean(WalRecordParser.class, () -> mock(WalRecordParser.class));
        context.registerBean(WalRecordJsonCodec.class, () -> mock(WalRecordJsonCodec.class));
        context.registerBean(DirtyTileTracker.class, () -> mock(DirtyTileTracker.class));
        context.registerBean(InMemoryTileBoard.class, () -> mock(InMemoryTileBoard.class));
        context.registerBean(EventSeqManager.class, EventSeqManager::new);
        context.registerBean(ServiceReadiness.class, ServiceReadiness::new);

        context.register(
                dev.cgt.pixelplace.measurement.Measurements.class,
                JpaCheckpointReader.class,
                StubCheckpointReader.class,
                JpaTileSnapshotLoader.class,
                StubTileSnapshotLoader.class,
                FileWalReplaySource.class,
                SegmentedWalStorage.class,
                dev.cgt.pixelplace.wal.infra.WindowsWalFileDurability.class,
                dev.cgt.pixelplace.wal.infra.FileWalStoragePreparation.class,
                dev.cgt.pixelplace.wal.infra.StubWalStoragePreparation.class,
                StubWalReplaySource.class,
                CanonicalZ0TileKeys.class,
                DbBootstrapClassifier.class,
                StartupRecoveryDbViewCaptureService.class,
                StartupRecoveryService.class,
                FlushBoundaryCoordinator.class,
                JpaTileMetadataReader.class,
                FlushPlanCaptureService.class
        );
        context.refresh();
        return context;
    }

    private void assertCommonCaptureAndRecoveryWiring(AnnotationConfigApplicationContext context) {
        assertSingleBean(
                context,
                StartupRecoveryDbViewCaptureService.class,
                StartupRecoveryDbViewCaptureService.class
        );
        assertSingleBean(context, StartupRecoveryService.class, StartupRecoveryService.class);
        assertSingleBean(context, DbBootstrapClassifier.class, DbBootstrapClassifier.class);

        StartupRecoveryDbViewCaptureService capture =
                context.getBean(StartupRecoveryDbViewCaptureService.class);
        assertSame(context.getBean(CheckpointReader.class), field(capture, "checkpointReader"));
        assertSame(context.getBean(TileSnapshotLoader.class), field(capture, "tileSnapshotLoader"));

        StartupRecoveryService recovery = context.getBean(StartupRecoveryService.class);
        assertSame(capture, field(recovery, "dbViewCaptureService"));
        assertSame(context.getBean(DbBootstrapClassifier.class), field(recovery, "dbBootstrapClassifier"));
    }

    private Object field(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to inspect recovery wiring field: " + fieldName, exception);
        }
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
