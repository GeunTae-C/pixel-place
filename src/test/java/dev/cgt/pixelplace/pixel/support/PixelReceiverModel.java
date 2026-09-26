package dev.cgt.pixelplace.pixel.support;

import dev.cgt.pixelplace.pixel.application.PixelEventMessage;
import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.HashMap;
import java.util.Map;

/*
 * 19단계에 인계할 test-only 수신 모델. production 프론트 구현을 대체하지 않음
 * snapshot 기준 B와 픽셀별 최신 journal로 역순 이벤트·늦은 GET의 덮어쓰기 방지
 */
public final class PixelReceiverModel {
    private final Map<TileKey, View> tiles = new HashMap<>();
    private long epoch;
    private long generation;
    private boolean connected;

    public long connect() {
        tiles.clear(); connected = true; return ++epoch;
    }

    public void disconnect(long eventEpoch) {
        if (eventEpoch == epoch) connected = false;
    }

    public Request request(int tx, int ty) {
        if (!connected) throw new IllegalStateException("Connection and receive buffer must exist first");
        if (tx < 0 || tx >= 32 || ty < 0 || ty >= 32) throw new IllegalArgumentException("tile range");
        var key = new TileKey(0, tx, ty);
        var view = tiles.computeIfAbsent(key, ignored -> new View());
        view.generation = ++generation;
        return new Request(epoch, key, generation);
    }

    public void release(int tx, int ty) { tiles.remove(new TileKey(0, tx, ty)); }

    public void receive(long eventEpoch, PixelEventMessage event) {
        if (!connected || eventEpoch != epoch) return;
        if (event.x() < 0 || event.x() >= 8192 || event.y() < 0 || event.y() >= 8192
                || event.color() < 0 || event.color() > 255 || event.tileVersion() <= 0) {
            throw new IllegalArgumentException("event range");
        }
        View view = tiles.get(new TileKey(0, event.x() / 256, event.y() / 256));
        if (view == null || event.tileVersion() <= view.baseVersion) return;
        int pixel = (event.y() % 256) * 256 + event.x() % 256;
        PixelEventMessage previous = view.journal.get(pixel);
        if (previous != null && previous.tileVersion() >= event.tileVersion()) return;
        // 이벤트 개수 대신 픽셀별 최신 값만 보존하므로 타일당 최대 65,536개
        view.journal.put(pixel, event);
        if (view.pixels != null) view.pixels[pixel] = (byte) event.color();
    }

    public boolean install(Request request, byte[] pixels, long version) {
        if (!connected || request.epoch() != epoch) return false;
        View view = tiles.get(request.key());
        if (view == null || view.generation != request.generation()) return false;
        if (pixels.length != 65_536 || version < 0) throw new IllegalArgumentException("snapshot shape");
        view.pixels = pixels.clone(); view.baseVersion = version;
        view.journal.entrySet().removeIf(entry -> entry.getValue().tileVersion() <= version);
        view.journal.forEach((pixel, event) -> view.pixels[pixel] = (byte) event.color());
        return true;
    }

    public boolean synchronizedTile(int tx, int ty) {
        View view = tiles.get(new TileKey(0, tx, ty));
        return connected && view != null && view.pixels != null;
    }

    public byte[] pixels(int tx, int ty) { return tiles.get(new TileKey(0, tx, ty)).pixels.clone(); }
    public int journalSize(int tx, int ty) { return tiles.get(new TileKey(0, tx, ty)).journal.size(); }
    public int activeTiles() { return tiles.size(); }

    /* 요청 generation은 tile 해제 후 재구독에서도 재사용하지 않는 응답 소유권 */
    public record Request(long epoch, TileKey key, long generation) { }

    private static final class View {
        private long generation;
        private long baseVersion = -1;
        private byte[] pixels;
        private final Map<Integer, PixelEventMessage> journal = new HashMap<>();
    }
}
