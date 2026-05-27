package dev.cgt.pixelplace.pixel.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// EventSeqManager가 recovery seed를 마지막 발급 완료값으로 취급하는지 검증한다.
class EventSeqManagerTest {

    @Test
    // seed 0은 아직 발급된 이벤트가 없는 상태이므로 첫 allocate 결과는 1이어야 한다.
    void allocateReturnsOneAfterZeroSeed() {
        EventSeqManager manager = new EventSeqManager();

        manager.initializeLastIssued(0L);

        assertEquals(1L, manager.allocate());
        assertEquals(1L, manager.currentLastIssued());
    }

    @Test
    // seed 15는 15번까지 이미 발급/반영된 상태이므로 다음 발급값은 16이어야 한다.
    void allocateReturnsSeedPlusOneAfterNonZeroSeed() {
        EventSeqManager manager = new EventSeqManager();

        manager.initializeLastIssued(15L);

        assertEquals(16L, manager.allocate());
        assertEquals(16L, manager.currentLastIssued());
    }

    @Test
    // eventSeq는 정합성 판단 기준이므로 여러 번 발급해도 빈 번호 없이 1씩 증가해야 한다.
    void allocateIncrementsByOne() {
        EventSeqManager manager = new EventSeqManager();

        manager.initializeLastIssued(0L);

        assertEquals(1L, manager.allocate());
        assertEquals(2L, manager.allocate());
        assertEquals(3L, manager.allocate());
        assertEquals(3L, manager.currentLastIssued());
    }

    @Test
    // 음수 seed는 recovery 기준점 자체가 잘못된 상태이므로 조용히 보정하지 않고 실패시킨다.
    void rejectNegativeSeed() {
        EventSeqManager manager = new EventSeqManager();

        assertThrows(IllegalArgumentException.class, () -> manager.initializeLastIssued(-1L));
    }
}
