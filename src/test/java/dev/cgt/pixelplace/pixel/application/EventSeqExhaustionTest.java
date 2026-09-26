package dev.cgt.pixelplace.pixel.application;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 마지막 한 번호의 동시 발급 및 overflow 없는 고갈 거부 */
class EventSeqExhaustionTest {
    @Test void exhaustionNeverChangesMaximumAndConcurrentAllocationHasOneWinner() throws Exception {
        var seq=new EventSeqManager();seq.initializeLastIssued(Long.MAX_VALUE-1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);var wins=new java.util.concurrent.atomic.AtomicInteger();
            Callable<Void> attempt=()->{start.await();try{assertEquals(Long.MAX_VALUE,seq.allocate());wins.incrementAndGet();}catch(IllegalStateException expected){assertEquals(Long.MAX_VALUE,seq.currentLastIssued());}return null;};
            var a=threads.submit(attempt);var b=threads.submit(attempt);a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
            assertEquals(1,wins.get());
        }
        assertThrows(IllegalStateException.class,seq::allocate);assertEquals(Long.MAX_VALUE,seq.currentLastIssued());
        assertThrows(IllegalStateException.class,()->seq.requireCapacity(1));
    }
}
