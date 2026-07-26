package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.recovery.application.ServiceNotReadyException;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// WAL 1차 내구성, memory core 완료, runtime fatal 차단 순서를 fake 협력자로 검증
class PixelWriteServiceTest {

    @Test
    // write 승인 순서 전체가 직렬화되어야 WAL/eventSeq 순서와 메모리 반영 순서가 어긋나지 않음
    void writePixelMethodIsSynchronized() throws NoSuchMethodException {
        int modifiers = PixelWriteService.class
                .getDeclaredMethod("writePixel", long.class, int.class, int.class, int.class)
                .getModifiers();

        assertTrue(Modifier.isSynchronized(modifiers));
    }

    @Test
    // 정상 write는 WAL에 승인 이벤트를 먼저 남긴 뒤 같은 mutation 규칙으로 메모리 보드를 변경해야 함
    void successfulWriteAppendsWalRecordAndAppliesMemoryAfterFsync() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = readyService(eventSeqManager, walAppender, board);

        PixelWriteResult result = service.writePixel(7L, 768, 1280, 17);

        assertEquals(1, walAppender.records.size());
        WalRecord record = walAppender.records.get(0);
        assertEquals(1L, record.eventSeq());
        assertEquals(7L, record.userId());
        assertEquals(BoardConstants.Z0_LEVEL, record.z());
        assertEquals(3, record.tx());
        assertEquals(5, record.ty());
        assertEquals(768, record.x());
        assertEquals(1280, record.y());
        assertEquals(17, record.color());
        assertNotNull(record.createdAt());

        TileKey tileKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        assertEquals((byte) 17, board.getRequired(tileKey).pixels()[0]);
        assertEquals(1L, board.getRequired(tileKey).tileVersion());
        assertEquals(1L, result.eventSeq());
        assertEquals(tileKey, result.tileKey());
        assertEquals(1L, result.tileVersion());
        assertEquals(768, result.x());
        assertEquals(1280, result.y());
        assertEquals(17, result.color());
    }

    @Test
    // WAL append/fsync 시점에는 아직 메모리 authoritative state가 바뀌지 않아야 함
    void memoryIsDefaultColorAtWalAppendTime() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        TileKey tileKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        InspectingWalAppender walAppender = new InspectingWalAppender(board, tileKey, 0);
        PixelWriteService service = readyService(eventSeqManager, walAppender, board);

        service.writePixel(7L, 768, 1280, 17);

        assertEquals((byte) 17, board.getRequired(tileKey).pixels()[0]);
        assertEquals(1, walAppender.callCount);
    }

    @Test
    // WAL 실패 요청은 원인을 보존하고 fatal 전환하며 memory apply로 진행 금지
    void walAppendFailureMarksNotReadyPropagatesCauseAndDoesNotApplyMemory() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        RuntimeException walFailure = new IllegalStateException("WAL fsync failed.");
        WalAppender walAppender = record -> {
            throw walFailure;
        };
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        ServiceReadiness readiness = readyReadiness();
        PixelWriteService service = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.writePixel(7L, 768, 1280, 17)
        );

        assertSame(walFailure, exception.getCause());
        assertFalse(readiness.isReady());
        verifyNoInteractions(board);
    }

    @Test
    // durable WAL 이후 memory 실패는 WAL을 지우거나 성공을 반환하지 않고 재시작 복구가 필요한 fatal 상태로 전환
    void memoryApplyFailureMarksNotReadyAndPropagatesCause() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        RuntimeException memoryFailure = new IllegalStateException("memory apply failed");
        when(board.applyPixel(768, 1280, 17)).thenThrow(memoryFailure);
        ServiceReadiness readiness = readyReadiness();
        PixelWriteService service = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.writePixel(7L, 768, 1280, 17)
        );

        assertSame(memoryFailure, exception.getCause());
        assertFalse(readiness.isReady());
        assertEquals(1, walAppender.records.size());
        verify(board).applyPixel(768, 1280, 17);
    }

    @Test
    void successfulWriteKeepsServiceReady() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        ServiceReadiness readiness = readyReadiness();
        PixelWriteService service = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        );

        service.writePixel(7L, 768, 1280, 17);

        assertTrue(readiness.isReady());
    }

    @Test
    // not-ready는 요청 데이터 오류가 아니며 core validation과 모든 collaborator보다 먼저 전용 예외로 차단
    void notReadyFailsBeforeValidationEventSeqWalAndMemory() {
        ServiceReadiness readiness = new ServiceReadiness();
        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        WalAppender walAppender = mock(WalAppender.class);
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        PixelWriteService service = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        );

        ServiceNotReadyException exception = assertThrows(
                ServiceNotReadyException.class,
                () -> service.writePixel(0L, 768, 1280, 17)
        );

        assertEquals(ServiceNotReadyException.MESSAGE, exception.getMessage());
        verifyNoInteractions(eventSeqManager, walAppender, board);
    }

    @Test
    // 선행 fatal 동안 monitor에서 대기한 write도 재검사에서 차단되고 collaborator 접근은 첫 요청 1회로 제한
    void waitingWriteIsRejectedAfterFirstWriteMarksNotReady() throws Exception {
        ServiceReadiness readiness = readyReadiness();
        EventSeqManager eventSeqManager = mock(EventSeqManager.class);
        when(eventSeqManager.allocate()).thenReturn(1L);
        CountDownLatch firstWalEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWal = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        AtomicInteger walCallCount = new AtomicInteger();
        RuntimeException walFailure = new IllegalStateException("WAL fsync failed.");
        WalAppender walAppender = record -> {
            walCallCount.incrementAndGet();
            firstWalEntered.countDown();
            try {
                if (!releaseFirstWal.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out while holding the write monitor.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding the write monitor.", exception);
            }
            throw walFailure;
        };
        InMemoryTileBoard board = mock(InMemoryTileBoard.class);
        PixelWriteService service = new PixelWriteService(
                eventSeqManager,
                walAppender,
                board,
                readiness
        );
        AtomicReference<Throwable> firstThrown = new AtomicReference<>();
        AtomicReference<Throwable> secondThrown = new AtomicReference<>();
        Thread first = new Thread(
                () -> captureFailure(firstThrown, () -> service.writePixel(7L, 768, 1280, 17)),
                "pixel-write-first-fatal"
        );
        Thread second = new Thread(
                () -> {
                    secondCallStarted.countDown();
                    captureFailure(secondThrown, () -> service.writePixel(0L, 768, 1280, 17));
                },
                "pixel-write-waiting"
        );

        try {
            first.start();
            assertTrue(firstWalEntered.await(5, TimeUnit.SECONDS));

            second.start();
            assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedOnWriteMonitor(second);
            assertEquals(1, walCallCount.get());

            releaseFirstWal.countDown();
            join(first);
            join(second);
        } finally {
            releaseFirstWal.countDown();
        }

        IllegalStateException firstException = assertInstanceOf(
                IllegalStateException.class,
                firstThrown.get()
        );
        assertSame(walFailure, firstException.getCause());
        assertInstanceOf(ServiceNotReadyException.class, secondThrown.get());
        assertFalse(readiness.isReady());
        assertEquals(1, walCallCount.get());
        verify(eventSeqManager, times(1)).allocate();
        verifyNoInteractions(board);
    }

    @Test
    // eventSeq는 전역 순서로 증가하고 tileVersion은 변경된 타일 안에서만 누적됨
    void twoSuccessfulWritesOnSameTileIncrementEventSeqAndTileVersion() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = readyService(eventSeqManager, walAppender, board);

        PixelWriteResult first = service.writePixel(7L, 768, 1280, 17);
        PixelWriteResult second = service.writePixel(7L, 769, 1280, 18);

        assertEquals(1L, first.eventSeq());
        assertEquals(1L, first.tileVersion());
        assertEquals(2L, second.eventSeq());
        assertEquals(2L, second.tileVersion());
        assertEquals(2, walAppender.records.size());
        assertEquals(2L, walAppender.records.get(1).eventSeq());
    }

    @Test
    // 다른 타일 write는 기존 타일 버전을 움직이지 않고 새 타일의 버전만 증가시킴
    void writeOnDifferentTileIncrementsOnlyChangedTileVersion() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = readyService(eventSeqManager, walAppender, board);

        PixelWriteResult first = service.writePixel(7L, 768, 1280, 17);
        PixelWriteResult second = service.writePixel(7L, 1024, 1280, 18);

        TileKey firstKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        TileKey secondKey = new TileKey(BoardConstants.Z0_LEVEL, 4, 5);
        assertEquals(firstKey, first.tileKey());
        assertEquals(secondKey, second.tileKey());
        assertEquals(1L, board.getRequired(firstKey).tileVersion());
        assertEquals(1L, board.getRequired(secondKey).tileVersion());
        assertEquals(1L, second.tileVersion());
    }

    @Test
    // 잘못된 사용자는 승인 이벤트가 아니므로 eventSeq 발급과 WAL append 전에 실패해야 함
    void invalidUserIdFailsBeforeEventSeqAndWalAppend() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = readyService(eventSeqManager, walAppender, board);

        assertThrows(IllegalArgumentException.class, () -> service.writePixel(0L, 768, 1280, 17));

        assertEquals(0, walAppender.records.size());
        assertEquals(0L, eventSeqManager.currentLastIssued());
        assertEquals(BoardConstants.DEFAULT_COLOR_INDEX, board.getRequired(new TileKey(BoardConstants.Z0_LEVEL, 3, 5)).pixels()[0]);
    }

    @Test
    // 좌표와 색상 검증은 WAL append 전에 끝나야 잘못된 이벤트가 replay 원본에 남지 않음
    void invalidCoordinatesAndColorFailBeforeEventSeqAndWalAppend() {
        assertInvalidWrite(7L, -1, 0, 0);
        assertInvalidWrite(7L, BoardConstants.BOARD_SIZE, 0, 0);
        assertInvalidWrite(7L, 0, -1, 0);
        assertInvalidWrite(7L, 0, BoardConstants.BOARD_SIZE, 0);
        assertInvalidWrite(7L, 0, 0, -1);
        assertInvalidWrite(7L, 0, 0, BoardConstants.PALETTE_SIZE);
    }

    @Test
    // 마지막 유효 좌표와 최대 유효 색상은 1 byte 팔레트 모델 안에서 정상 승인되어야 함
    void lastValidCoordinateAndMaxColorAreAccepted() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = readyService(eventSeqManager, walAppender, board);

        PixelWriteResult result = service.writePixel(
                7L,
                BoardConstants.BOARD_SIZE - 1,
                BoardConstants.BOARD_SIZE - 1,
                BoardConstants.PALETTE_SIZE - 1
        );

        TileKey tileKey = new TileKey(BoardConstants.Z0_LEVEL, 31, 31);
        byte[] pixels = board.getRequired(tileKey).pixels();
        assertEquals(tileKey, result.tileKey());
        assertEquals((byte) (BoardConstants.PALETTE_SIZE - 1), pixels[BoardConstants.TILE_PIXEL_COUNT - 1]);
        assertEquals(1L, result.tileVersion());
    }

    private void assertInvalidWrite(long userId, int x, int y, int color) {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = readyService(eventSeqManager, walAppender, board);

        assertThrows(IllegalArgumentException.class, () -> service.writePixel(userId, x, y, color));

        assertEquals(0, walAppender.records.size());
        assertEquals(0L, eventSeqManager.currentLastIssued());
    }

    private InMemoryTileBoard initializedBoard() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        board.initializeAllWhite();
        return board;
    }

    private PixelWriteService readyService(
            EventSeqManager eventSeqManager,
            WalAppender walAppender,
            InMemoryTileBoard board
    ) {
        return new PixelWriteService(eventSeqManager, walAppender, board, readyReadiness());
    }

    private ServiceReadiness readyReadiness() {
        ServiceReadiness readiness = new ServiceReadiness();
        readiness.markReady();
        return readiness;
    }

    private void captureFailure(AtomicReference<Throwable> target, Runnable action) {
        try {
            action.run();
            fail("Expected write to fail.");
        } catch (Throwable throwable) {
            target.set(throwable);
        }
    }

    private void awaitBlockedOnWriteMonitor(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (thread.getState() != Thread.State.BLOCKED) {
            fail("Waiting write did not block on PixelWriteService monitor.");
        }
    }

    private void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive());
    }

    private static class RecordingWalAppender implements WalAppender {

        private final List<WalRecord> records = new ArrayList<>();

        @Override
        public void appendAndFsync(WalRecord record) {
            records.add(record);
        }
    }

    private static class InspectingWalAppender implements WalAppender {

        private final InMemoryTileBoard board;
        private final TileKey tileKey;
        private final int pixelIndex;
        private int callCount;

        private InspectingWalAppender(InMemoryTileBoard board, TileKey tileKey, int pixelIndex) {
            this.board = board;
            this.tileKey = tileKey;
            this.pixelIndex = pixelIndex;
        }

        @Override
        public void appendAndFsync(WalRecord record) {
            // 이 시점의 색상이 기본값이면 WAL fsync 성공 전 memory apply 금지 순서가 지켜진 것
            assertEquals(BoardConstants.DEFAULT_COLOR_INDEX, board.getRequired(tileKey).pixels()[pixelIndex]);
            callCount++;
        }
    }
}
