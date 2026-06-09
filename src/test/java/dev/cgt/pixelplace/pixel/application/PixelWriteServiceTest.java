package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// PixelWriteService는 write 성공 기준을 WAL fsync로 고정하므로 fake appender로 순서와 실패 불변식을 검증한다.
class PixelWriteServiceTest {

    @Test
    // write 승인 순서 전체가 직렬화되어야 WAL/eventSeq 순서와 메모리 반영 순서가 어긋나지 않는다.
    void writePixelMethodIsSynchronized() throws NoSuchMethodException {
        int modifiers = PixelWriteService.class
                .getDeclaredMethod("writePixel", long.class, int.class, int.class, int.class)
                .getModifiers();

        assertTrue(Modifier.isSynchronized(modifiers));
    }

    @Test
    // 정상 write는 WAL에 승인 이벤트를 먼저 남긴 뒤 같은 mutation 규칙으로 메모리 보드를 변경해야 한다.
    void successfulWriteAppendsWalRecordAndAppliesMemoryAfterFsync() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = new PixelWriteService(eventSeqManager, walAppender, board);

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
    // WAL append/fsync 시점에는 아직 메모리 authoritative state가 바뀌지 않아야 한다.
    void memoryIsDefaultColorAtWalAppendTime() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        TileKey tileKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        InspectingWalAppender walAppender = new InspectingWalAppender(board, tileKey, 0);
        PixelWriteService service = new PixelWriteService(eventSeqManager, walAppender, board);

        service.writePixel(7L, 768, 1280, 17);

        assertEquals((byte) 17, board.getRequired(tileKey).pixels()[0]);
        assertEquals(1, walAppender.callCount);
    }

    @Test
    // WAL fsync 실패는 write 승인 실패이므로 메모리 반영으로 넘어가면 안 된다.
    void walAppendFailureDoesNotApplyMemory() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        PixelWriteService service = new PixelWriteService(eventSeqManager, new FailingWalAppender(), board);

        assertThrows(IllegalStateException.class, () -> service.writePixel(7L, 768, 1280, 17));

        TileKey tileKey = new TileKey(BoardConstants.Z0_LEVEL, 3, 5);
        assertEquals(BoardConstants.DEFAULT_COLOR_INDEX, board.getRequired(tileKey).pixels()[0]);
        assertEquals(0L, board.getRequired(tileKey).tileVersion());
    }

    @Test
    // eventSeq는 전역 순서로 증가하고 tileVersion은 변경된 타일 안에서만 누적된다.
    void twoSuccessfulWritesOnSameTileIncrementEventSeqAndTileVersion() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = new PixelWriteService(eventSeqManager, walAppender, board);

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
    // 다른 타일 write는 기존 타일 버전을 움직이지 않고 새 타일의 버전만 증가시킨다.
    void writeOnDifferentTileIncrementsOnlyChangedTileVersion() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = new PixelWriteService(eventSeqManager, walAppender, board);

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
    // 잘못된 사용자는 승인 이벤트가 아니므로 eventSeq 발급과 WAL append 전에 실패해야 한다.
    void invalidUserIdFailsBeforeEventSeqAndWalAppend() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = new PixelWriteService(eventSeqManager, walAppender, board);

        assertThrows(IllegalArgumentException.class, () -> service.writePixel(0L, 768, 1280, 17));

        assertEquals(0, walAppender.records.size());
        assertEquals(0L, eventSeqManager.currentLastIssued());
        assertEquals(BoardConstants.DEFAULT_COLOR_INDEX, board.getRequired(new TileKey(BoardConstants.Z0_LEVEL, 3, 5)).pixels()[0]);
    }

    @Test
    // 좌표와 색상 검증은 WAL append 전에 끝나야 잘못된 이벤트가 replay 원본에 남지 않는다.
    void invalidCoordinatesAndColorFailBeforeEventSeqAndWalAppend() {
        assertInvalidWrite(7L, -1, 0, 0);
        assertInvalidWrite(7L, BoardConstants.BOARD_SIZE, 0, 0);
        assertInvalidWrite(7L, 0, -1, 0);
        assertInvalidWrite(7L, 0, BoardConstants.BOARD_SIZE, 0);
        assertInvalidWrite(7L, 0, 0, -1);
        assertInvalidWrite(7L, 0, 0, BoardConstants.PALETTE_SIZE);
    }

    @Test
    // 마지막 유효 좌표와 최대 유효 색상은 1 byte 팔레트 모델 안에서 정상 승인되어야 한다.
    void lastValidCoordinateAndMaxColorAreAccepted() {
        EventSeqManager eventSeqManager = new EventSeqManager();
        InMemoryTileBoard board = initializedBoard();
        RecordingWalAppender walAppender = new RecordingWalAppender();
        PixelWriteService service = new PixelWriteService(eventSeqManager, walAppender, board);

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
        PixelWriteService service = new PixelWriteService(eventSeqManager, walAppender, board);

        assertThrows(IllegalArgumentException.class, () -> service.writePixel(userId, x, y, color));

        assertEquals(0, walAppender.records.size());
        assertEquals(0L, eventSeqManager.currentLastIssued());
    }

    private InMemoryTileBoard initializedBoard() {
        InMemoryTileBoard board = new InMemoryTileBoard();
        board.initializeAllWhite();
        return board;
    }

    private static class RecordingWalAppender implements WalAppender {

        private final List<WalRecord> records = new ArrayList<>();

        @Override
        public void appendAndFsync(WalRecord record) {
            records.add(record);
        }
    }

    private static class FailingWalAppender implements WalAppender {

        @Override
        public void appendAndFsync(WalRecord record) {
            throw new IllegalStateException("WAL fsync failed.");
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
            // 이 시점의 색상이 기본값이면 WAL fsync 성공 전 memory apply 금지 순서가 지켜진 것이다.
            assertEquals(BoardConstants.DEFAULT_COLOR_INDEX, board.getRequired(tileKey).pixels()[pixelIndex]);
            callCount++;
        }
    }
}
