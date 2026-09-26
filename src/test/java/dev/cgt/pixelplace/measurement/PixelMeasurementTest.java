package dev.cgt.pixelplace.measurement;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.pixel.application.*;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.TileKey;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.cgt.pixelplace.measurement.PixelMeasurement.*;

/** 관측 오류가 command·lock·기존 예외 의미를 가리지 않고 phase 시작 귀속을 유지하는 계약 */
class PixelMeasurementTest {
    @Test void disabledTimersKeepCommandCaptureAndActualWalSafetyCounts() {
        var registry = new SimpleMeterRegistry(); var measure = new PixelMeasurement(false, registry);
        measure.commandEntered(); assertEquals(1, measure.activeCommands());
        measure.captured(7, 100, 2); assertEquals(2, measure.lastCapture().records());
        measure.walStarted(true, 2); measure.walForced(2, true); measure.walCompleted(2);
        measure.walForced(0, true); measure.walRotated(); measure.commandExited();
        var counts = measure.walCounts();
        assertEquals(0, measure.activeCommands()); assertEquals(2, counts.coveredRecords());
        assertEquals(1, counts.recordForces()); assertEquals(1, counts.emptyForces());
        assertEquals(1, counts.batchCalls()); assertEquals(2, counts.batchRecords());
        assertNull(counts.batchSizes()); assertTrue(registry.getMeters().isEmpty());
    }
    @Test void cycleStartIntervalSurvivesAnOlderCycleCompletingLater() {
        var measure = new PixelMeasurement(true, new SimpleMeterRegistry());
        var older = measure.begin(Operation.flush_cycle); measure.cycleStarted(older);
        var newer = measure.begin(Operation.flush_cycle); measure.cycleStarted(newer);
        measure.cycleCompleted(newer, Outcome.skipped); measure.cycleCompleted(older, Outcome.success);
        assertEquals(newer.startedNanos(), measure.lastCycle().startedNanos());
        assertEquals(older.startedNanos(), measure.lastCycle().previousStartedNanos());
        assertEquals(Outcome.skipped, measure.lastCycle().outcome());
    }
    @Test void overviewLoggingErrorRemainsPrimaryWhenMeasurementAlsoThrowsError() {
        var measure = new PixelMeasurement(true, new SimpleMeterRegistry());
        var timers = (Timer[][][]) ReflectionTestUtils.getField(measure, "timers");
        var observer = new AssertionError("observer"); var logging = new AssertionError("logging");
        Timer raw = mock(Timer.class); doThrow(observer).when(raw).record(anyLong(), any(TimeUnit.class));
        timers[Operation.overview.ordinal()][0][Outcome.failure.ordinal()] = raw;
        var ready = new ServiceReadiness(); ready.markReady();
        var renderer = mock(dev.cgt.pixelplace.overview.application.OverviewRenderer.class);
        when(renderer.render()).thenThrow(new IllegalStateException("render"));
        var service = new dev.cgt.pixelplace.overview.application.OverviewService(renderer, ready, measure);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(dev.cgt.pixelplace.overview.application.OverviewService.class);
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) { throw logging; }
        };
        appender.start(); logger.addAppender(appender);
        try {
            assertSame(logging, assertThrows(AssertionError.class, service::refresh));
            assertArrayEquals(new Throwable[]{observer}, logging.getSuppressed());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void scopeRetainsStartingPhaseAndCaptureUsesActualCountAcrossSequenceGaps() {
        var registry = new SimpleMeterRegistry(); var measure = new PixelMeasurement(true, registry);
        measure.phase(Phase.warmup); var scope = measure.begin(Operation.record_force);
        measure.phase(Phase.measurement); measure.end(scope, Outcome.success);
        assertEquals(1, timer(registry, Operation.record_force, Phase.warmup, Outcome.success).count());
        assertEquals(0, timer(registry, Operation.record_force, Phase.measurement, Outcome.success).count());
        measure.captured(10, 20, 2); var sample = measure.lastCapture();
        assertEquals(2, sample.records()); assertSame(sample, measure.lastCapture());
        assertEquals(10, sample.checkpoint()); assertEquals(20, sample.tail());
    }

    @Test void observerRuntimeFailurePreservesResultAndErrorIdentityWhileReleasingLock() {
        var measure = new PixelMeasurement(true, new SimpleMeterRegistry());
        var timers = (Timer[][][]) ReflectionTestUtils.getField(measure, "timers");
        Timer failing = mock(Timer.class); doThrow(new IllegalStateException("observer")).when(failing).record(anyLong(), any(TimeUnit.class));
        timers[Operation.write_held.ordinal()][0][Outcome.success.ordinal()] = failing;
        var coordinator = new FlushBoundaryCoordinator(measure);
        assertEquals("result", coordinator.coordinate(() -> "result")); assertEquals(1, measure.observerFailures());
        AssertionError original = new AssertionError("original"), observer = new AssertionError("observer");
        Timer raw = mock(Timer.class); doThrow(observer).when(raw).record(anyLong(), any(TimeUnit.class));
        timers[Operation.write_held.ordinal()][0][Outcome.failure.ordinal()] = raw;
        assertSame(original, assertThrows(AssertionError.class, () -> coordinator.coordinate(() -> { throw original; })));
        assertArrayEquals(new Throwable[]{observer}, original.getSuppressed());
        assertEquals("released", coordinator.coordinate(() -> "released"));
    }

    @Test void writeWaitAndHoldAreSeparatedDuringContention() throws Exception {
        var registry = new SimpleMeterRegistry(); var measure = new PixelMeasurement(true, registry);
        var coordinator = new FlushBoundaryCoordinator(measure);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> coordinator.coordinate(() -> { entered.countDown(); await(release); return null; }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> waiter = pool.submit(() -> coordinator.coordinate(() -> 1));
            Thread.sleep(40); release.countDown(); holder.get(5, TimeUnit.SECONDS); waiter.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertEquals(2, timer(registry, Operation.write_wait, Phase.prepare, Outcome.success).count());
        assertTrue(timer(registry, Operation.write_held, Phase.prepare, Outcome.success).totalTime(TimeUnit.MILLISECONDS) >= 30);
    }

    @Test void commandCountCoversRedisCoreAndPostprocessingWithSameFacadeAndFinally() {
        var registry = new SimpleMeterRegistry(); var measure = new PixelMeasurement(true, registry);
        var ready = new ServiceReadiness(); ready.markReady();
        var core = mock(PixelWriteService.class); var cooldown = mock(PixelCooldown.class); var broadcast = mock(PixelBroadcastService.class);
        var result = new PixelWriteResult(1, new TileKey(0, 0, 0), 1, 1, 2, 3);
        when(core.writePixel(7, 1, 2, 3)).thenReturn(result);
        doAnswer(call -> { assertEquals(1, measure.activeCommands()); Thread.sleep(10); return null; }).when(cooldown).checkWritable(7);
        doAnswer(call -> { assertEquals(1, measure.activeCommands()); throw new AssertionError("broadcast"); }).when(broadcast).broadcast(any());
        var service = new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(new FlushBoundaryCoordinator(measure), core, mock(DirtyTileTracker.class), ready, measure),
                broadcast,
                ready,
                new PixelUserWriteGate(),
                measure);
        assertThrows(AssertionError.class, () -> service.writePixel(7, 1, 2, 3));
        assertEquals(0, measure.activeCommands()); assertSame(measure, ReflectionTestUtils.getField(service, "measurement"));
        assertTrue(timer(registry, Operation.redis_check, Phase.prepare, Outcome.success).totalTime(TimeUnit.MILLISECONDS) >= 5);
        assertEquals(1, timer(registry, Operation.command, Phase.prepare, Outcome.failure).count());
        var offRegistry = new SimpleMeterRegistry(); var off = new PixelMeasurement(false, offRegistry);
        assertEquals("same", off.observe(Operation.command, () -> "same")); assertTrue(offRegistry.getMeters().isEmpty());
        assertNull(off.begin(Operation.command)); assertNull(off.lastCapture());
    }
    private static Timer timer(SimpleMeterRegistry r, Operation o, Phase p, Outcome result) {
        return r.get("pixel.place.operation").tags("operation", o.name(), "phase", p.name(), "outcome", result.name()).timer();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
