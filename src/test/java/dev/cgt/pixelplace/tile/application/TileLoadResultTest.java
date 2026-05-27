package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TileLoadResult는 recovery 분기 입력이므로 allMissing 플래그와 snapshot 목록의 모순을 생성 시점에 차단해야 한다.
class TileLoadResultTest {

    @Test
    // DB z=0 타일이 전부 없는 최초 부팅 경로는 snapshot 없이 allMissing으로만 표현한다.
    void allMissingResultHasNoSnapshots() {
        TileLoadResult result = TileLoadResult.allMissingResult();

        assertTrue(result.allMissing());
        assertTrue(result.snapshots().isEmpty());
    }

    @Test
    // fullyLoaded는 로드된 snapshot을 그대로 recovery 입력으로 전달하는 경로다.
    void fullyLoadedKeepsSnapshots() {
        TileStateSnapshot snapshot = snapshot(0);

        TileLoadResult result = TileLoadResult.fullyLoaded(List.of(snapshot));

        assertFalse(result.allMissing());
        assertEquals(List.of(snapshot), result.snapshots());
    }

    @Test
    // allMissing과 snapshot 데이터가 동시에 있으면 recovery가 최초 부팅과 DB 로드를 잘못 판단할 수 있다.
    void rejectAllMissingWithSnapshots() {
        List<TileStateSnapshot> snapshots = List.of(snapshot(0));

        assertThrows(IllegalArgumentException.class, () -> new TileLoadResult(true, snapshots));
    }

    @Test
    // loaded 상태의 빈 snapshot은 allMissing과 의미가 충돌하므로 별도 상태로 허용하지 않는다.
    void rejectLoadedWithoutSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new TileLoadResult(false, List.of()));
    }

    @Test
    // snapshot 목록이 null이면 recovery 입력 자체가 불완전하므로 생성 시점에 실패시킨다.
    void rejectNullSnapshots() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> new TileLoadResult(true, null));

        assertTrue(exception instanceof NullPointerException || exception instanceof IllegalArgumentException);
    }

    @Test
    // 외부에서 원본 리스트를 바꿔도 recovery가 들고 있는 결과는 변하지 않아야 한다.
    void snapshotsAreDefensivelyCopied() {
        TileStateSnapshot first = snapshot(0);
        TileStateSnapshot second = snapshot(1);
        List<TileStateSnapshot> original = new ArrayList<>();
        original.add(first);

        TileLoadResult result = TileLoadResult.fullyLoaded(original);
        original.add(second);

        assertEquals(List.of(first), result.snapshots());
    }

    @Test
    // 생성된 결과의 snapshot 목록은 recovery 도중 외부 코드가 직접 수정할 수 없어야 한다.
    void snapshotsAreUnmodifiable() {
        TileLoadResult result = TileLoadResult.fullyLoaded(List.of(snapshot(0)));

        assertThrows(UnsupportedOperationException.class, () -> result.snapshots().add(snapshot(1)));
        assertThrows(UnsupportedOperationException.class, () -> result.snapshots().remove(0));
    }

    private TileStateSnapshot snapshot(int tx) {
        return new TileStateSnapshot(new TileKey(0, tx, 0), new byte[256 * 256], 0L);
    }
}
