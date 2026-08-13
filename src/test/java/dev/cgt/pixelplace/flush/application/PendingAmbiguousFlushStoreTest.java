package dev.cgt.pixelplace.flush.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingAmbiguousFlushStoreTest {

    private final PendingAmbiguousFlushStore store = new PendingAmbiguousFlushStore();

    @Test
    void firstPendingInstallsAndExistingPendingCannotBeOverwritten() {
        PendingAmbiguousFlush first = pending();
        PendingAmbiguousFlush second = pending();

        store.installIfAbsent(first);

        assertSame(first, store.current().orElseThrow());
        assertThrows(IllegalStateException.class, () -> store.installIfAbsent(second));
        assertSame(first, store.current().orElseThrow());
    }

    @Test
    void differentInstanceCannotClearButExactSameInstanceCan() {
        PendingAmbiguousFlush installed = pending();
        store.installIfAbsent(installed);

        assertThrows(IllegalStateException.class, () -> store.clearIfSame(pending()));
        assertSame(installed, store.current().orElseThrow());

        store.clearIfSame(installed);
        assertTrue(store.current().isEmpty());
    }

    @Test
    void nullInstallAndClearAreRejected() {
        assertThrows(NullPointerException.class, () -> store.installIfAbsent(null));
        assertThrows(NullPointerException.class, () -> store.clearIfSame(null));
    }

    private PendingAmbiguousFlush pending() {
        return new PendingAmbiguousFlush(0L, 1L, DbBootstrapState.INITIALIZED, List.of());
    }
}
