package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.pixel.support.PixelReceiverModel;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/* 이벤트 도착 순서 대신 snapshot/version 쌍과 픽셀별 journal을 쓰는 인계 계약 검증 */
class PixelReceiverModelTest {
    @Test
    void reverseVersionsKeepLatestSamePixelButAlsoApplyOlderDifferentPixelDespiteSeqGap() {
        var model = new PixelReceiverModel(); long epoch = model.connect();
        model.install(model.request(0, 0), new byte[65_536], 10);
        model.receive(epoch, event(1, 12, 12, 100));
        model.receive(epoch, event(1, 11, 11, 98));
        model.receive(epoch, event(2, 11, 11, 98));
        assertEquals(12, model.pixels(0, 0)[1]); assertEquals(11, model.pixels(0, 0)[2]);
        assertTrue(model.synchronizedTile(0, 0));
    }

    @Test
    void initialBufferExcludesSnapshotEventsAndRequeryReappliesAlreadyVisibleJournal() {
        var model = new PixelReceiverModel(); long epoch = model.connect(); var initial = model.request(0, 0);
        model.receive(epoch, event(1, 9, 9, 30)); model.receive(epoch, event(2, 11, 11, 50));
        assertFalse(model.synchronizedTile(0, 0));
        byte[] snapshot = new byte[65_536]; snapshot[1] = 10;
        assertTrue(model.install(initial, snapshot, 10));
        assertEquals(10, model.pixels(0, 0)[1]); assertEquals(11, model.pixels(0, 0)[2]);
        model.receive(epoch, event(3, 12, 12, 60));
        var requery = model.request(0, 0);
        model.receive(epoch, event(4, 13, 13, 70));
        assertTrue(model.install(requery, snapshot, 10));
        assertEquals(12, model.pixels(0, 0)[3]); assertEquals(13, model.pixels(0, 0)[4]);
        assertEquals(3, model.journalSize(0, 0));
    }

    @Test
    void oldGenerationOldEpochAndReleasedTileResponsesNeverRestoreStaleState() {
        var model = new PixelReceiverModel();
        assertThrows(IllegalStateException.class, () -> model.request(0, 0));
        long oldEpoch = model.connect(); var old = model.request(0, 0); var latest = model.request(0, 0);
        assertFalse(model.install(old, new byte[65_536], 999));
        assertTrue(model.install(latest, new byte[65_536], 10));
        model.disconnect(oldEpoch); assertFalse(model.synchronizedTile(0, 0));
        assertFalse(model.install(latest, new byte[65_536], 999));
        long current = model.connect(); var fresh = model.request(0, 0);
        model.receive(oldEpoch, event(1, 99, 99, 999));
        assertFalse(model.install(latest, new byte[65_536], 999));
        model.disconnect(oldEpoch); assertTrue(model.install(fresh, new byte[65_536], 0));
        assertTrue(model.synchronizedTile(0, 0)); assertEquals(0, model.pixels(0, 0)[1]);
        model.receive(current, event(1, 2, 2, 2)); model.release(0, 0);
        var resubscribe = model.request(0, 0);
        assertFalse(model.install(fresh, new byte[65_536], 999));
        assertTrue(model.install(resubscribe, new byte[65_536], 0)); assertEquals(0, model.journalSize(0, 0));
    }

    @Test
    void journalCoalescesPerPixelAndActiveTileBoundIsCanonicalBoard() {
        var model = new PixelReceiverModel(); long epoch = model.connect(); model.request(0, 0);
        for (int pixel = 0; pixel < 65_536; pixel++) {
            for (int version : new int[]{11, 12}) {
                model.receive(epoch, new PixelEventMessage("pixel", pixel % 256, pixel / 256, 1, version, version));
            }
        }
        assertEquals(65_536, model.journalSize(0, 0));
        for (int ty = 0; ty < 32; ty++) for (int tx = 0; tx < 32; tx++) model.request(tx, ty);
        assertEquals(1024, model.activeTiles());
        assertThrows(IllegalArgumentException.class, () -> model.request(32, 0));
        model.connect(); assertEquals(0, model.activeTiles());
    }

    @Test
    void messageFromOlderResultKeepsMutationVersionEvenAfterBoardAdvances() {
        var board = new InMemoryTileBoard(); var first = board.applyPixel(1, 2, 3);
        var result = new PixelWriteResult(10, first.key(), first.tileVersion(), 1, 2, 3);
        board.applyPixel(1, 2, 4);
        var message = PixelEventMessage.from(result);
        assertEquals(1, message.tileVersion()); assertEquals(3, message.color());
        assertEquals(2, board.getRequired(new TileKey(0, 0, 0)).tileVersion());
    }

    private static PixelEventMessage event(int x, int color, long version, long sequence) {
        return new PixelEventMessage("pixel", x, 0, color, sequence, version);
    }
}
