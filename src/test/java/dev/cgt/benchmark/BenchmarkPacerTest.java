package dev.cgt.benchmark;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** 발생기 deadline·긴 대기 양보·짧은 대기 정밀도·취소 경계. 실제 처리량은 별도 실행에서 검증 */
class BenchmarkPacerTest {
    @Test void representativeIntervalNeverParksAndDoesNotReturnBeforeDeadline() throws Exception {
        var clock=new AtomicLong();
        BenchmarkPacer.await(9_523_809,clock::get,n->fail("Short interval must not park"),()->clock.addAndGet(100_000));
        assertTrue(clock.get()>=9_523_809 && clock.get()<9_623_809);
    }
    @Test void longLowLoadWaitYieldsCpuBeforeFiniteSpinWindow() throws Exception {
        var clock=new AtomicLong();var parked=new AtomicLong();
        BenchmarkPacer.await(1_000_000_000,clock::get,n->{parked.addAndGet(n);clock.addAndGet(n);},()->clock.addAndGet(1_000_000));
        assertEquals(980_000_000,parked.get());assertEquals(1_000_000_000,clock.get());
    }
    @Test void overdueArrivalReturnsWithoutInventingAdditionalWait() throws Exception {
        BenchmarkPacer.await(10,()->11,n->fail("Already due"),()->fail("Already due"));
    }
    @Test void interruptAfterOversleptParkPreventsNextSendAndClearsFlag() {
        var clock=new AtomicLong();
        try {
            assertThrows(InterruptedException.class,()->BenchmarkPacer.await(100_000_000,clock::get,
                n->{clock.set(110_000_000);Thread.currentThread().interrupt();},()->fail("No spin after interrupt")));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void monotonicDeadlineDifferenceSurvivesSignedNanoTimeWrap() throws Exception {
        var clock=new AtomicLong(Long.MAX_VALUE-10);
        BenchmarkPacer.await(Long.MIN_VALUE+9,clock::get,n->fail("Short interval"),clock::incrementAndGet);
        assertEquals(Long.MIN_VALUE+9,clock.get());
    }
}
