package dev.cgt.pixelplace.overview;

import dev.cgt.pixelplace.checkpoint.application.CheckpointReader;
import dev.cgt.pixelplace.flush.application.FlushPlan;
import dev.cgt.pixelplace.flush.application.FlushPlanCaptureService;
import dev.cgt.pixelplace.flush.application.FlushReconciliationService;
import dev.cgt.pixelplace.flush.application.FlushRunResult;
import dev.cgt.pixelplace.flush.application.FlushSingleFlightGuard;
import dev.cgt.pixelplace.flush.application.FlushTransactionExecutor;
import dev.cgt.pixelplace.flush.application.FlushWorker;
import dev.cgt.pixelplace.flush.application.PendingAmbiguousFlushStore;
import dev.cgt.pixelplace.overview.application.OverviewRenderer;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.overview.scheduling.OverviewScheduler;
import dev.cgt.pixelplace.overview.web.OverviewController;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryService;
import dev.cgt.pixelplace.tile.application.DirtyTile;
import dev.cgt.pixelplace.tile.application.SynchronizedDirtyTileTracker;
import dev.cgt.pixelplace.tile.application.TileReadService;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.tile.web.TileController;
import dev.cgt.pixelplace.wal.application.WalAppender;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/* Overview dependency graph가 write/persistence/recovery 상태 변경 경계와 분리되는지 고정 */
class OverviewIsolationTest {

    private static final Set<String> FORBIDDEN_DEPENDENCY_FRAGMENTS = Set.of(
            ".checkpoint.",
            ".flush.",
            ".pixel.",
            ".wal.",
            "DirtyTileTracker",
            "PendingAmbiguousFlush",
            "EventSeqManager",
            "Repository",
            "Redis"
    );

    @Test
    void overviewProductionGraphHasNoPersistenceWriteFlushOrRecoveryMutationDependency() {
        List<Class<?>> overviewTypes = List.of(
                OverviewRenderer.class,
                OverviewService.class,
                OverviewScheduler.class,
                OverviewController.class
        );

        List<String> dependencyNames = overviewTypes.stream()
                .flatMap(this::dependencyTypes)
                .map(Class::getName)
                .toList();

        for (String dependencyName : dependencyNames) {
            assertTrue(
                    FORBIDDEN_DEPENDENCY_FRAGMENTS.stream()
                            .noneMatch(dependencyName::contains),
                    () -> "Forbidden Overview dependency: " + dependencyName
            );
        }
        assertFalse(OverviewScheduler.class.isAnnotationPresent(EnableScheduling.class));
    }

    @Test
    void startupRecoveryDoesNotAcquireOverviewDependency() {
        boolean hasOverviewDependency = Arrays.stream(StartupRecoveryService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(type -> type.getPackageName().contains(".overview"));

        assertFalse(hasOverviewDependency);
    }

    @Test
    void overviewFailureLeavesWriteTileReadFlushAndPersistenceStateIndependent() throws Exception {
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        when(renderer.render()).thenThrow(new IllegalStateException("overview failed"));
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        OverviewService overviewService = new OverviewService(renderer, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());
        EventSeqManager eventSeqManager = new EventSeqManager();
        eventSeqManager.initializeLastIssued(41L);
        InMemoryTileBoard board = new InMemoryTileBoard();
        TileKey trackedKey = new TileKey(0, 1, 1);
        SynchronizedDirtyTileTracker dirtyTileTracker = new SynchronizedDirtyTileTracker();
        dirtyTileTracker.markDirty(trackedKey, 7L, 3L);
        PendingAmbiguousFlushStore pendingStore = new PendingAmbiguousFlushStore();
        CheckpointReader checkpointReader = mock(CheckpointReader.class);

        overviewService.refresh();

        assertTrue(overviewService.currentPng().isEmpty());
        assertTrue(readiness.isReady());
        assertEquals(41L, eventSeqManager.currentLastIssued());
        assertTrue(pendingStore.current().isEmpty());
        assertEquals(
                List.of(new DirtyTile(trackedKey, 7L, 3L)),
                dirtyTileTracker.drainDirtyTiles()
        );
        verifyNoInteractions(checkpointReader);

        WalAppender walAppender = mock(WalAppender.class);
        PixelWriteService pixelWriteService = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        PixelWriteResult writeResult = pixelWriteService.writePixel(5L, 0, 0, 9);

        assertEquals(42L, writeResult.eventSeq());
        assertEquals(42L, eventSeqManager.currentLastIssued());
        verify(walAppender).appendAndFsync(any());

        ResponseEntity<byte[]> tileResponse = new TileController(new TileReadService(board))
                .getTile(0, 0, 0);
        assertEquals(HttpStatus.OK, tileResponse.getStatusCode());
        byte[] rawTile;
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(tileResponse.getBody())
        )) {
            rawTile = gzip.readAllBytes();
        }
        assertEquals(9, Byte.toUnsignedInt(rawTile[0]));

        FlushPlan plan = mock(FlushPlan.class);
        when(plan.noOp()).thenReturn(true);
        FlushPlanCaptureService capture = mock(FlushPlanCaptureService.class);
        when(capture.capturePlan()).thenReturn(plan);
        FlushTransactionExecutor executor = mock(FlushTransactionExecutor.class);
        FlushReconciliationService reconciliation = mock(FlushReconciliationService.class);
        FlushWorker worker = new FlushWorker(
                new FlushSingleFlightGuard(),
                readiness,
                capture,
                executor,
                pendingStore,
                reconciliation,
                dirtyTileTracker,
                mock(dev.cgt.pixelplace.flush.application.FlushWalRetention.class)
        , dev.cgt.pixelplace.measurement.Measurements.disabled());

        assertEquals(FlushRunResult.NO_OP, worker.flushOnce());
        verify(capture).capturePlan();
        verifyNoInteractions(executor, reconciliation, checkpointReader);
        assertTrue(pendingStore.current().isEmpty());
        assertTrue(dirtyTileTracker.drainDirtyTiles().isEmpty());
    }

    private Stream<Class<?>> dependencyTypes(Class<?> type) {
        Stream<Class<?>> fields = Arrays.stream(type.getDeclaredFields()).map(Field::getType);
        Stream<Class<?>> constructors = Arrays.stream(type.getDeclaredConstructors())
                .flatMap(this::parameterTypes);
        return Stream.concat(fields, constructors);
    }

    private Stream<Class<?>> parameterTypes(Constructor<?> constructor) {
        return Arrays.stream(constructor.getParameterTypes());
    }
}
