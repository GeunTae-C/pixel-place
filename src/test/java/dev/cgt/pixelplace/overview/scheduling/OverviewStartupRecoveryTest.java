package dev.cgt.pixelplace.overview.scheduling;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.common.constant.PaletteConstants;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.overview.application.OverviewRenderer;
import dev.cgt.pixelplace.overview.application.OverviewService;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryDbView;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryDbViewCaptureService;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryRunner;
import dev.cgt.pixelplace.recovery.application.StartupRecoveryService;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverviewStartupRecoveryTest {

    @Test
    void firstPublishedImageReflectsWalReplayAfterRecoveryRunnerCompletes() throws Exception {
        InMemoryTileBoard board = new InMemoryTileBoard();
        CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();
        ServiceReadiness readiness = new ServiceReadiness();
        EventSeqManager eventSeqManager = new EventSeqManager();
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        when(capture.capture()).thenReturn(new StartupRecoveryDbView(
                new CheckpointSnapshot(0L),
                TileLoadResult.allMissingResult()
        ));
        WalRecord replayed = new WalRecord(
                7L,
                1L,
                0,
                0,
                0,
                0,
                0,
                128,
                LocalDateTime.of(2026, 8, 28, 12, 0)
        );
        StartupRecoveryService recoveryService = new StartupRecoveryService(
                capture,
                new DbBootstrapClassifier(canonicalKeys),
                canonicalKeys,
                checkpoint -> new WalReplayBatch(List.of(replayed), replayed.eventSeq()),
                board,
                eventSeqManager,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        OverviewRenderer renderer = spy(new OverviewRenderer(board, canonicalKeys));
        OverviewService overviewService = new OverviewService(renderer, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OverviewScheduler overviewScheduler = new OverviewScheduler(overviewService, taskScheduler);

        overviewService.refresh();
        assertTrue(overviewService.currentPng().isEmpty());

        new StartupRecoveryRunner(recoveryService).run(mock(ApplicationArguments.class));
        assertTrue(readiness.isReady());
        assertEquals(7L, eventSeqManager.currentLastIssued());

        overviewScheduler.runScheduledRefresh();
        verify(renderer, never()).render();
        assertTrue(overviewService.currentPng().isEmpty());

        overviewScheduler.scheduleInitialRefresh();
        assertTrue(overviewService.currentPng().isEmpty());
        taskScheduler.oneShotTask.get().run();
        verify(renderer).render();

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(
                overviewService.currentPng().orElseThrow()
        ));
        assertNotNull(decoded);
        assertEquals(expectedRgb(128), decoded.getRGB(0, 0) & 0x00FF_FFFF);
    }

    @Test
    void recoveryFailureKeepsNotReadyAndAnySubmittedRefreshSkipsRenderer() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();
        ServiceReadiness readiness = new ServiceReadiness();
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        IllegalStateException recoveryFailure = new IllegalStateException("recovery failed");
        when(capture.capture()).thenThrow(recoveryFailure);
        StartupRecoveryService recoveryService = new StartupRecoveryService(
                capture,
                new DbBootstrapClassifier(canonicalKeys),
                canonicalKeys,
                checkpoint -> new WalReplayBatch(List.of(), checkpoint),
                board,
                new EventSeqManager(),
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        OverviewService overviewService = new OverviewService(renderer, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OverviewScheduler scheduler = new OverviewScheduler(overviewService, taskScheduler);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> new StartupRecoveryRunner(recoveryService).run(mock(ApplicationArguments.class))
        );
        assertSame(recoveryFailure, thrown);
        assertFalse(readiness.isReady());

        scheduler.scheduleInitialRefresh();
        taskScheduler.oneShotTask.get().run();

        verify(renderer, never()).render();
        assertTrue(overviewService.currentPng().isEmpty());
        assertFalse(readiness.isReady());
    }

    @Test
    void firstRefreshFailureKeepsRecoveredReadyStateAndPeriodicRetryPublishes() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        CanonicalZ0TileKeys canonicalKeys = new CanonicalZ0TileKeys();
        ServiceReadiness readiness = new ServiceReadiness();
        StartupRecoveryDbViewCaptureService capture = mock(StartupRecoveryDbViewCaptureService.class);
        when(capture.capture()).thenReturn(new StartupRecoveryDbView(
                new CheckpointSnapshot(0L),
                TileLoadResult.allMissingResult()
        ));
        StartupRecoveryService recoveryService = new StartupRecoveryService(
                capture,
                new DbBootstrapClassifier(canonicalKeys),
                canonicalKeys,
                checkpoint -> new WalReplayBatch(List.of(), checkpoint),
                board,
                new EventSeqManager(),
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        new StartupRecoveryRunner(recoveryService).run(mock(ApplicationArguments.class));

        byte[] recoveredPng = {1, 2, 3};
        OverviewRenderer renderer = mock(OverviewRenderer.class);
        when(renderer.render())
                .thenThrow(new IllegalStateException("first refresh failed"))
                .thenReturn(recoveredPng);
        OverviewService overviewService = new OverviewService(renderer, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled());
        RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
        OverviewScheduler scheduler = new OverviewScheduler(overviewService, taskScheduler);

        scheduler.scheduleInitialRefresh();
        taskScheduler.oneShotTask.get().run();

        assertTrue(overviewService.currentPng().isEmpty());
        assertTrue(readiness.isReady());

        scheduler.runScheduledRefresh();
        assertSame(recoveredPng, overviewService.currentPng().orElseThrow());
        assertTrue(readiness.isReady());
    }

    private int expectedRgb(int paletteIndex) {
        return Integer.parseInt(
                PaletteConstants.paletteHex().get(paletteIndex).substring(1),
                16
        );
    }

    private static final class RecordingTaskScheduler implements TaskScheduler {

        private final AtomicReference<Runnable> oneShotTask = new AtomicReference<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            oneShotTask.set(task);
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
            return mock(ScheduledFuture.class);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return mock(ScheduledFuture.class);
        }
    }
}
