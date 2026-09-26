package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.FlushBoundaryCoordinator;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.WalAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/*
 * PixelCommandService cooldown orchestration 검증
 * Redis 정책은 core write 바깥에 두고 WAL-first 순서는 PixelWriteService에 보존
 */
class PixelCommandServiceTest {

    private final PixelCooldown pixelCooldown = mock(PixelCooldown.class);
    private final FlushBoundaryCoordinator flushBoundaryCoordinator = new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled());
    private final PixelWriteService pixelWriteService = mock(PixelWriteService.class);
    private final DirtyTileTracker dirtyTileTracker = mock(DirtyTileTracker.class);
    private final PixelBroadcastService pixelBroadcastService = mock(PixelBroadcastService.class);
    private final ServiceReadiness serviceReadiness = readyReadiness();
    private final PixelCommandService service = new PixelCommandService(pixelCooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(flushBoundaryCoordinator, pixelWriteService, dirtyTileTracker, serviceReadiness, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                pixelBroadcastService,
                serviceReadiness,
                new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),
                dev.cgt.pixelplace.measurement.Measurements.disabled());

    @Test
    // cooldown check -> core write -> dirty mark -> cooldown start -> broadcast 순서 고정
    void writePixelMarksDirtyAndBroadcastsAfterCooldownStartWhenCoreWriteSucceeds() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        InOrder inOrder = inOrder(pixelCooldown, pixelWriteService, dirtyTileTracker, pixelBroadcastService);
        inOrder.verify(pixelCooldown).checkWritable(7L);
        inOrder.verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        inOrder.verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        inOrder.verify(pixelCooldown).startCooldown(7L);
        ArgumentCaptor<PixelEventMessage> messageCaptor = ArgumentCaptor.forClass(PixelEventMessage.class);
        inOrder.verify(pixelBroadcastService).broadcast(messageCaptor.capture());

        PixelEventMessage message = messageCaptor.getValue();
        assertAll(
                () -> assertEquals("pixel", message.type()),
                () -> assertEquals(result.x(), message.x()),
                () -> assertEquals(result.y(), message.y()),
                () -> assertEquals(result.color(), message.color()),
                () -> assertEquals(result.eventSeq(), message.eventSeq())
        );
    }

    @Test
    // dirty mark 인자는 실패 재등록과 추가 snapshot 병합용 최신 eventSeq/tileVersion 관측값 모두 필요
    void writePixelPassesTileKeyEventSeqAndTileVersionToDirtyTracker() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);

        service.writePixel(7L, 768, 1280, 17);

        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
    }

    @Test
    // 이미 not-ready인 command는 userId 검증과 Redis를 포함한 모든 write orchestration 진입 금지
    void writePixelRejectsNotReadyBeforeValidationAndAllCollaborators() {
        serviceReadiness.markNotReady();

        ServiceNotReadyException exception = assertThrows(
                ServiceNotReadyException.class,
                () -> service.writePixel(0L, 768, 1280, 17)
        );

        assertEquals(ServiceNotReadyException.MESSAGE, exception.getMessage());
        verifyNoInteractions(pixelCooldown, pixelWriteService, dirtyTileTracker, pixelBroadcastService);
    }

    @Test
    // command 검사 직후 fatal 전환과 경쟁한 요청은 core 내부 재검사에서 eventSeq/WAL/memory 이전 차단
    void writePixelIsBlockedByCoreRecheckWhenReadinessChangesAfterCommandCheck() {
        ServiceReadiness readiness = readyReadiness();
        PixelCooldown cooldown = mock(PixelCooldown.class);
        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        WalAppender walAppender = mock(WalAppender.class);
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        PixelWriteService realWriteService = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        PixelBroadcastService broadcaster = mock(PixelBroadcastService.class);
        PixelCommandService commandService = new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()), realWriteService, tracker, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                broadcaster,
                readiness,
                new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),
                dev.cgt.pixelplace.measurement.Measurements.disabled());
        doAnswer(invocation -> {
            readiness.markNotReady();
            return null;
        }).when(cooldown).checkWritable(7L);

        assertThrows(
                ServiceNotReadyException.class,
                () -> commandService.writePixel(7L, 768, 1280, 17)
        );

        verify(cooldown).checkWritable(7L);
        verify(cooldown, never()).startCooldown(7L);
        verifyNoInteractions(eventSeqManager, walAppender, board, tracker, broadcaster);
    }

    @Test
    // cooldown 활성 상태면 eventSeq 발급/WAL append 경로 진입 금지
    void writePixelDoesNotCallWriteServiceWhenCooldownActive() {
        doThrow(new PixelCooldownActiveException(1000L))
                .when(pixelCooldown).checkWritable(7L);

        assertThrows(PixelCooldownActiveException.class, () -> service.writePixel(7L, 768, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verifyNoInteractions(pixelWriteService);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // cooldown check 실패면 승인 여부 판단 불가로 core write 진입 금지
    void writePixelDoesNotCallWriteServiceWhenCooldownCheckFails() {
        doThrow(new PixelCooldownUnavailableException("Pixel cooldown check failed.", new RuntimeException("redis down")))
                .when(pixelCooldown).checkWritable(7L);

        assertThrows(PixelCooldownUnavailableException.class, () -> service.writePixel(7L, 768, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verifyNoInteractions(pixelWriteService);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // Redis 검사 뒤 executor 접수 전 좌표 거부. core 재검증과 별개의 admission 계약
    void writePixelRejectsInvalidCoordinatesAfterRedisBeforeExecutorAdmission() {
        assertThrows(IllegalArgumentException.class, () -> service.writePixel(7L, -1, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verifyNoInteractions(pixelWriteService);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // WAL fsync 등 core 실패는 cooldown 시작 금지
    void writePixelDoesNotStartCooldownWhenCoreWriteFailsWithIllegalStateException() {
        when(pixelWriteService.writePixel(7L, 768, 1280, 17))
                .thenThrow(new IllegalStateException("WAL fsync failed."));

        assertThrows(IllegalStateException.class, () -> service.writePixel(7L, 768, 1280, 17));

        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verifyNoInteractions(dirtyTileTracker);
        verifyNoInteractions(pixelBroadcastService);
        verifyNoMoreInteractions(pixelCooldown);
    }

    @Test
    // dirty mark 실패는 flush 정합성 후처리 실패이므로 cooldown/broadcast 진행 금지
    void writePixelPropagatesIllegalStateExceptionWhenDirtyMarkFails() {
        PixelWriteResult result = result();
        RuntimeException dirtyFailure = new IllegalArgumentException("dirty invalid");
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(dirtyFailure)
                .when(dirtyTileTracker)
                .markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.writePixel(7L, 768, 1280, 17)
        );

        assertAll(
                () -> assertEquals(
                        "Dirty tile mark failed after successful write. eventSeq=1",
                        exception.getMessage()
                ),
                () -> assertSame(dirtyFailure, exception.getCause()),
                () -> assertInstanceOf(IllegalArgumentException.class, exception.getCause())
        );
        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        verify(pixelCooldown, never()).startCooldown(7L);
        verifyNoInteractions(pixelBroadcastService);
    }

    @Test
    // dirty mark 성공 후 cooldown set 실패는 이미 성공한 WAL+memory write 결과를 깨지 않고 broadcast는 계속 시도
    void writePixelBroadcastsAndReturnsResultWhenCooldownStartFailsAfterSuccessfulWrite() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(new PixelCooldownUnavailableException("Pixel cooldown set failed.", new RuntimeException("redis down")))
                .when(pixelCooldown).startCooldown(7L);

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        verify(pixelCooldown).startCooldown(7L);
        verify(pixelBroadcastService).broadcast(any(PixelEventMessage.class));
    }

    @Test
    // dirty mark 성공 후 broadcast 실패는 이미 성공한 WAL+memory write 결과를 깨지 않음
    void writePixelReturnsResultWhenBroadcastFailsAfterSuccessfulWrite() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(new RuntimeException("websocket down"))
                .when(pixelBroadcastService).broadcast(any(PixelEventMessage.class));

        PixelWriteResult actual = service.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        verify(pixelCooldown).checkWritable(7L);
        verify(pixelWriteService).writePixel(7L, 768, 1280, 17);
        verify(dirtyTileTracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        verify(pixelCooldown).startCooldown(7L);
        verify(pixelBroadcastService).broadcast(any(PixelEventMessage.class));
    }

    @Test
    // userId 기본 검증 실패는 cooldown 저장소와 write service 모두 호출 금지
    void writePixelRejectsNonPositiveUserIdBeforeCooldownCheck() {
        assertThrows(IllegalArgumentException.class, () -> service.writePixel(0L, 768, 1280, 17));

        verifyNoInteractions(pixelCooldown, pixelWriteService, dirtyTileTracker, pixelBroadcastService);
    }

    @Test
    // Redis 사전 확인과 성공 후처리는 boundary 밖, core write와 dirty mark만 같은 callback 내부
    void writeAndDirtyMarkShareBoundaryWhileCooldownAndBroadcastStayOutside() {
        AtomicBoolean insideBoundary = new AtomicBoolean();
        FlushBoundaryCoordinator trackingCoordinator = new FlushBoundaryCoordinator(dev.cgt.pixelplace.measurement.Measurements.disabled()) {
            @Override
            public <T> T coordinate(Supplier<T> action) {
                assertFalse(insideBoundary.get());
                insideBoundary.set(true);
                try {
                    return super.coordinate(action);
                } finally {
                    insideBoundary.set(false);
                }
            }
        };
        PixelCooldown cooldown = mock(PixelCooldown.class);
        PixelWriteService writeService = mock(PixelWriteService.class);
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        PixelBroadcastService broadcaster = mock(PixelBroadcastService.class);
        PixelWriteResult result = result();
        doAnswer(invocation -> {
            assertFalse(insideBoundary.get());
            return null;
        }).when(cooldown).checkWritable(7L);
        when(writeService.writePixel(7L, 768, 1280, 17)).thenAnswer(invocation -> {
            assertTrue(insideBoundary.get());
            return result;
        });
        doAnswer(invocation -> {
            assertTrue(insideBoundary.get());
            return null;
        }).when(tracker).markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());
        doAnswer(invocation -> {
            assertFalse(insideBoundary.get());
            return null;
        }).when(cooldown).startCooldown(7L);
        doAnswer(invocation -> {
            assertFalse(insideBoundary.get());
            return null;
        }).when(broadcaster).broadcast(any(PixelEventMessage.class));
        PixelCommandService commandService = new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(trackingCoordinator, writeService, tracker, readyReadiness(), dev.cgt.pixelplace.measurement.Measurements.disabled()),
                broadcaster,
                readyReadiness(),
                new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),
                dev.cgt.pixelplace.measurement.Measurements.disabled());

        PixelWriteResult actual = commandService.writePixel(7L, 768, 1280, 17);

        assertSame(result, actual);
        assertFalse(insideBoundary.get());
    }

    @Test
    // dirty mark 실패도 callback 밖으로 전파되면서 coordinator lock은 반드시 해제
    void dirtyFailureReleasesCoordinatorAndSkipsSuccessPostProcessing() {
        PixelWriteResult result = result();
        when(pixelWriteService.writePixel(7L, 768, 1280, 17)).thenReturn(result);
        doThrow(new IllegalStateException("dirty failed"))
                .when(dirtyTileTracker)
                .markDirty(result.tileKey(), result.eventSeq(), result.tileVersion());

        assertThrows(IllegalStateException.class, () -> service.writePixel(7L, 768, 1280, 17));

        assertEquals("released", flushBoundaryCoordinator.coordinate(() -> "released"));
        verify(pixelCooldown, never()).startCooldown(7L);
        verifyNoInteractions(pixelBroadcastService);
    }

    @Test
    // coordinator 대기 중 fatal 전환된 요청은 callback 진입 뒤 core monitor 재검사에서 차단
    void writeWaitingAtBoundaryIsRejectedByCoreReadinessRecheck() throws Exception {
        BlockingCoordinator blockingCoordinator = new BlockingCoordinator();
        ServiceReadiness readiness = readyReadiness();
        PixelCooldown cooldown = mock(PixelCooldown.class);
        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        WalAppender walAppender = mock(WalAppender.class);
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        PixelWriteService realWriteService = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        , dev.cgt.pixelplace.measurement.Measurements.disabled());
        DirtyTileTracker tracker = mock(DirtyTileTracker.class);
        PixelBroadcastService broadcaster = mock(PixelBroadcastService.class);
        PixelCommandService commandService = new PixelCommandService(cooldown,
                new dev.cgt.pixelplace.pixel.application.SinglePixelWriteExecutor(blockingCoordinator, realWriteService, tracker, readiness, dev.cgt.pixelplace.measurement.Measurements.disabled()),
                broadcaster,
                readiness,
                new dev.cgt.pixelplace.pixel.application.PixelUserWriteGate(),
                dev.cgt.pixelplace.measurement.Measurements.disabled());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PixelWriteResult> future = executor.submit(
                    () -> commandService.writePixel(7L, 768, 1280, 17)
            );
            assertTrue(blockingCoordinator.awaitEntered());

            readiness.markNotReady();
            blockingCoordinator.release();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS)
            );
            assertInstanceOf(ServiceNotReadyException.class, failure.getCause());
            verify(cooldown).checkWritable(7L);
            verify(cooldown, never()).startCooldown(7L);
            verifyNoInteractions(eventSeqManager, walAppender, board, tracker, broadcaster);
        } finally {
            blockingCoordinator.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private PixelWriteResult result() {
        return new PixelWriteResult(
                1L,
                new TileKey(BoardConstants.Z0_LEVEL, 3, 5),
                1L,
                768,
                1280,
                17
        );
    }

    private static ServiceReadiness readyReadiness() {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        return readiness;
    }

    private static final class BlockingCoordinator extends FlushBoundaryCoordinator {
        private BlockingCoordinator() { super(dev.cgt.pixelplace.measurement.Measurements.disabled()); }

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public <T> T coordinate(Supplier<T> action) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Boundary release timed out.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Boundary wait was interrupted.", exception);
            }
            return super.coordinate(action);
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }
}
