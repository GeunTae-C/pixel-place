package dev.cgt.benchmark;

import java.util.*;

/** 고정 ordinal 표본을 tile/version별 정렬하여 한 번의 event replay로 대조. 최신 DB snapshot과의 오비교 방지 */
final class Phase18ReadReplay {
    record Sample(int tx, int ty, long version, String hash) { }
    static boolean sampled(long ordinal, long planned) {
        return planned <= 1000 || ordinal % ((planned + 999) / 1000) == 0;
    }
    static void verify(List<Sample> samples, List<Phase18Events.Event> events) throws Exception {
        Phase18Plan.require(samples.size() <= 1000, "read sample limit");
        var byTile = new TreeMap<Integer, List<Sample>>();
        for (Sample s : samples) {
            Phase18Plan.require(s.tx >= 0 && s.tx < 32 && s.ty >= 0 && s.ty < 32 && s.version >= 0, "read sample range");
            byTile.computeIfAbsent(s.ty * 32 + s.tx, k -> new ArrayList<>()).add(s);
        }
        var ordered = new ArrayList<>(events); ordered.sort(Comparator.comparingLong(Phase18Events.Event::eventSeq));
        var tileEvents = new HashMap<Integer, List<Phase18Events.Event>>();
        for (var e : ordered) tileEvents.computeIfAbsent((e.y()/256)*32+e.x()/256, k -> new ArrayList<>()).add(e);
        for (var tile : byTile.entrySet()) {
            tile.getValue().sort(Comparator.comparingLong(Sample::version));
            byte[] raw = dev.cgt.pixelplace.tile.domain.TileState.allWhite().pixels(); long version = 0; int index = 0;
            var history = tileEvents.getOrDefault(tile.getKey(), List.of());
            for (Sample sample : tile.getValue()) {
                while (version < sample.version && index < history.size()) {
                    var e = history.get(index++);
                    if (e.tileVersion() != version + 1) throw new IllegalStateException("Read replay event version gap");
                    raw[(e.y()%256)*256+e.x()%256] = (byte) e.color(); version++;
                }
                if (version != sample.version || !Phase18TileBody.hash(raw).equals(sample.hash)) throw new IllegalStateException("Read snapshot version/hash mismatch");
            }
        }
    }
}
