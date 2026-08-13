package dev.cgt.pixelplace.flush.application;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FlushReconciliationServiceTest {

    private final FlushDbStateProbe probe = mock(FlushDbStateProbe.class);
    private final FlushReconciliationService service = new FlushReconciliationService(probe);

    @Test
    void bootstrapTargetAndInitializedConfirmsCommit() {
        assertDecision(
                pending(0L, 10L, DbBootstrapState.BOOTSTRAP_PENDING),
                new FlushDbState(10L, DbBootstrapState.INITIALIZED),
                FlushReconciliationDecision.COMMIT_CONFIRMED
        );
    }

    @Test
    void bootstrapExpectedAndPendingConfirmsRollback() {
        assertDecision(
                pending(0L, 10L, DbBootstrapState.BOOTSTRAP_PENDING),
                new FlushDbState(0L, DbBootstrapState.BOOTSTRAP_PENDING),
                FlushReconciliationDecision.ROLLBACK_CONFIRMED
        );
    }

    @Test
    void initializedTargetAndInitializedConfirmsCommit() {
        assertDecision(
                pending(3L, 10L, DbBootstrapState.INITIALIZED),
                new FlushDbState(10L, DbBootstrapState.INITIALIZED),
                FlushReconciliationDecision.COMMIT_CONFIRMED
        );
    }

    @Test
    void initializedExpectedAndInitializedConfirmsRollback() {
        assertDecision(
                pending(3L, 10L, DbBootstrapState.INITIALIZED),
                new FlushDbState(3L, DbBootstrapState.INITIALIZED),
                FlushReconciliationDecision.ROLLBACK_CONFIRMED
        );
    }

    @Test
    void greaterBetweenLowerAndModeMismatchStatesAreUnresolved() {
        PendingAmbiguousFlush initialized = pending(3L, 10L, DbBootstrapState.INITIALIZED);
        assertUnresolved(initialized, new FlushDbState(11L, DbBootstrapState.INITIALIZED));
        assertUnresolved(initialized, new FlushDbState(7L, DbBootstrapState.INITIALIZED));
        assertUnresolved(initialized, new FlushDbState(2L, DbBootstrapState.INITIALIZED));
        assertUnresolved(initialized, new FlushDbState(10L, DbBootstrapState.BOOTSTRAP_PENDING));
        assertUnresolved(initialized, new FlushDbState(3L, DbBootstrapState.INCONSISTENT));

        PendingAmbiguousFlush bootstrap = pending(0L, 10L, DbBootstrapState.BOOTSTRAP_PENDING);
        assertUnresolved(bootstrap, new FlushDbState(10L, DbBootstrapState.BOOTSTRAP_PENDING));
        assertUnresolved(bootstrap, new FlushDbState(0L, DbBootstrapState.INITIALIZED));
    }

    @Test
    void probeFailureIsPropagatedWithoutReplacement() {
        PendingAmbiguousFlush pending = pending(3L, 10L, DbBootstrapState.INITIALIZED);
        IllegalStateException failure = new IllegalStateException("probe failed");
        when(probe.probe()).thenThrow(failure);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> service.reconcile(pending)
        );

        assertSame(failure, actual);
    }

    @Test
    void reconciliationHasNoWriterTransactionExecutorOrPlanDependency() {
        List<String> fieldTypes = Arrays.stream(FlushReconciliationService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();

        assertEquals(List.of(FlushDbStateProbe.class.getName()), fieldTypes);
        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("Writer")));
        assertTrue(fieldTypes.stream().noneMatch(type -> type.contains("FlushPlan")));
    }

    private void assertDecision(
            PendingAmbiguousFlush pending,
            FlushDbState observed,
            FlushReconciliationDecision expected
    ) {
        when(probe.probe()).thenReturn(observed);

        assertEquals(expected, service.reconcile(pending));

        verify(probe).probe();
        verifyNoMoreInteractions(probe);
    }

    private void assertUnresolved(PendingAmbiguousFlush pending, FlushDbState observed) {
        org.mockito.Mockito.reset(probe);
        when(probe.probe()).thenReturn(observed);

        UnresolvedFlushCommitException failure = assertThrows(
                UnresolvedFlushCommitException.class,
                () -> service.reconcile(pending)
        );

        assertTrue(failure.getMessage().contains("observedCheckpoint=" + observed.lastFlushedEventSeq()));
        assertTrue(failure.getMessage().contains("observedState=" + observed.bootstrapState()));
    }

    private PendingAmbiguousFlush pending(long expected, long target, DbBootstrapState state) {
        return new PendingAmbiguousFlush(expected, target, state, List.of());
    }
}
