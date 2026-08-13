package dev.cgt.pixelplace.flush.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlushTransactionResultTest {

    @Test
    void factoriesKeepCommittedAndFailureInvariantsSeparate() {
        RuntimeException failure = new RuntimeException("failure");

        FlushTransactionResult committed = FlushTransactionResult.committed();
        FlushTransactionResult rollback = FlushTransactionResult.definiteRollback(failure);
        FlushTransactionResult ambiguous = FlushTransactionResult.ambiguousCommit(failure);

        assertEquals(FlushTransactionOutcome.COMMITTED, committed.outcome());
        assertTrue(committed.failureCause().isEmpty());
        assertEquals(FlushTransactionOutcome.DEFINITE_ROLLBACK, rollback.outcome());
        assertSame(failure, rollback.failureCause().orElseThrow());
        assertEquals(FlushTransactionOutcome.AMBIGUOUS_COMMIT, ambiguous.outcome());
        assertSame(failure, ambiguous.failureCause().orElseThrow());
    }
}
