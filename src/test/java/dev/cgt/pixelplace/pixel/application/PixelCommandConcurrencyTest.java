package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.cgt.pixelplace.pixel.application.PixelUserWriteGateTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/* command gate의 Redis/core/dirty 범위와 broadcast 이전 해제, 대기 실패의 무호출 검증 */
class PixelCommandConcurrencyTest {
    final PixelCooldown cooldown = mock(PixelCooldown.class);
    final PixelWriteService core = mock(PixelWriteService.class);
    final DirtyTileTracker dirty = mock(DirtyTileTracker.class);
    final PixelBroadcastService broadcast = mock(PixelBroadcastService.class);
    final ServiceReadiness readiness = new ServiceReadiness();
    final PixelWriteResult result = new PixelWriteResult(1, new TileKey(0, 0, 0), 1, 1, 2, 3);

    PixelCommandService command(PixelUserWriteGate gate) {
        readiness.markReady();
        when(core.writePixel(7, 1, 2, 3)).thenReturn(result);
        return new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()), core, dirty, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                broadcast,
                readiness,
                gate,
                dev.cgt.pixelplace.measurement.Measurements.disabled());
    }

    @Test
    void firstWriteStoresCooldownBeforeWaitingSameUserCanCheck() throws Exception {
        var gate = new PixelUserWriteGate(); var command = command(gate);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var active = new AtomicBoolean();
        doAnswer(call -> { if (active.get()) throw new PixelCooldownActiveException(1000); return null; }).when(cooldown).checkWritable(7);
        doAnswer(call -> { active.set(true); return null; }).when(cooldown).startCooldown(7);
        when(core.writePixel(7, 1, 2, 3)).thenAnswer(call -> { entered.countDown(); await(release); return result; });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> command.writePixel(7, 1, 2, 3)); await(entered);
            var second = pool.submit(() -> command.writePixel(7, 1, 2, 3)); awaitQueued(lock(gate, 7), 1);
            verify(cooldown, times(1)).checkWritable(7);
            release.countDown(); assertSame(result, first.get(5, TimeUnit.SECONDS));
            assertInstanceOf(PixelCooldownActiveException.class, assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause());
            verify(core, times(1)).writePixel(7, 1, 2, 3); verify(dirty, times(1)).markDirty(result.tileKey(), 1, 1);
            assertEquals(0, gate.entryCount());
        } finally { release.countDown(); }
    }

    @Test
    void busyAndInterruptedCommandNeverReachRedisOrWriteCollaborators() throws Exception {
        var gate = new PixelUserWriteGate(Duration.ofMillis(150)); var command = command(gate);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> gate.execute(7, () -> { entered.countDown(); await(release); return null; })); await(entered);
            assertThrows(PixelWriteBusyException.class, () -> command.writePixel(7, 1, 2, 3));
            Thread.currentThread().interrupt();
            try { assertThrows(PixelWriteBusyException.class, () -> command.writePixel(7, 1, 2, 3)); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
            verifyNoInteractions(cooldown, core, dirty, broadcast);
            release.countDown(); holder.get(5, TimeUnit.SECONDS); assertEquals(0, gate.entryCount());
        } finally { release.countDown(); }
    }

    @Test
    void readinessIsRecheckedAfterWaitingBeforeRedis() throws Exception {
        var gate = new PixelUserWriteGate(); var command = command(gate);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> gate.execute(7, () -> { entered.countDown(); await(release); return null; })); await(entered);
            var next = pool.submit(() -> command.writePixel(7, 1, 2, 3)); awaitQueued(lock(gate, 7), 1);
            readiness.markNotReady(); release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertInstanceOf(ServiceNotReadyException.class, assertThrows(ExecutionException.class, () -> next.get(5, TimeUnit.SECONDS)).getCause());
            verifyNoInteractions(cooldown, core, dirty, broadcast); assertEquals(0, gate.entryCount());
        } finally { release.countDown(); }
    }

    @Test
    void cooldownStorageFailureDoesNotCreateLocalCooldownAndGateIsReleasedBeforeBroadcast() {
        var gate = new PixelUserWriteGate(); var command = command(gate);
        doThrow(new PixelCooldownUnavailableException("redis", null)).when(cooldown).startCooldown(7);
        doAnswer(call -> { assertEquals(0, gate.entryCount()); return null; }).when(broadcast).broadcast(any());
        assertSame(result, command.writePixel(7, 1, 2, 3));
        assertSame(result, command.writePixel(7, 1, 2, 3));
        verify(core, times(2)).writePixel(7, 1, 2, 3); verify(broadcast, times(2)).broadcast(any());
    }

    @Test
    void coreAndDirtyFailureLeaveNoCooldownAndPermitNextRequestAccordingToReadiness() {
        var gate = new PixelUserWriteGate(); var command = command(gate);
        when(core.writePixel(7, 1, 2, 3)).thenThrow(new IllegalArgumentException("validation"));
        assertThrows(IllegalArgumentException.class, () -> command.writePixel(7, 1, 2, 3));
        assertEquals(0, gate.entryCount());
        doReturn(result).when(core).writePixel(7, 1, 2, 3);
        doThrow(new IllegalStateException("dirty")).when(dirty).markDirty(any(), anyLong(), anyLong());
        assertThrows(IllegalStateException.class, () -> command.writePixel(7, 1, 2, 3));
        assertEquals(0, gate.entryCount());
        verify(cooldown, never()).startCooldown(7); verifyNoInteractions(broadcast);
        doNothing().when(dirty).markDirty(any(), anyLong(), anyLong());
        assertSame(result, command.writePixel(7, 1, 2, 3));
    }

    @Test
    void broadcastRawErrorPreservesCompletedWriteAndGateRelease() {
        var gate = new PixelUserWriteGate(); var command = command(gate); var error = new AssertionError("broadcast");
        doThrow(error).when(broadcast).broadcast(any());
        assertSame(error, assertThrows(AssertionError.class, () -> command.writePixel(7, 1, 2, 3)));
        assertEquals(0, gate.entryCount());
        verify(dirty).markDirty(result.tileKey(), 1, 1); verify(cooldown).startCooldown(7);
    }

    @Test
    void springSingleCommandUsesSingletonUserGateForRuntimeEntry() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            // 단건 core mock의 사용자 gate 경계 검증. 실제 default group은 Config Data/worker fixture에서 검증
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "single-fixture",java.util.Map.of("pixel-place.write.mode","single")));
            readiness.markReady(); when(core.writePixel(7, 1, 2, 3)).thenReturn(result);
            context.registerBean(PixelCooldown.class, () -> cooldown);
            context.registerBean(FlushBoundaryCoordinator.class, () -> new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()));
            context.registerBean(PixelWriteService.class, () -> core);
            context.registerBean(DirtyTileTracker.class, () -> dirty);
            context.registerBean(PixelBroadcastService.class, () -> broadcast);
            context.registerBean(ServiceReadiness.class, () -> readiness);
            context.register(PixelCommandService.class, WriteExecutionConfiguration.class, PixelUserWriteGate.class, dev.cgt.pixelplace.measurement.Measurements.class); context.refresh();
            var command = context.getBean(PixelCommandService.class); var gate = context.getBean(PixelUserWriteGate.class);
            assertInstanceOf(SinglePixelWriteExecutor.class,context.getBean(PixelWriteExecutor.class));
            assertSame(gate, org.springframework.test.util.ReflectionTestUtils.getField(command, "userWriteGate"));
            assertSame(result, command.writePixel(7, 1, 2, 3)); assertEquals(0, gate.entryCount());
        }
    }
}
