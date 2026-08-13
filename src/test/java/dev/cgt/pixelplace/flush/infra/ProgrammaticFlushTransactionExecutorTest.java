package dev.cgt.pixelplace.flush.infra;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.flush.application.DbBootstrapState;
import dev.cgt.pixelplace.flush.application.FlushPersistenceService;
import dev.cgt.pixelplace.flush.application.FlushPlan;
import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;
import dev.cgt.pixelplace.flush.application.FlushTransactionOutcome;
import dev.cgt.pixelplace.flush.application.FlushTransactionResult;
import dev.cgt.pixelplace.tile.domain.TileKey;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProgrammaticFlushTransactionExecutorTest {

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final FlushPersistenceService persistenceService = mock(FlushPersistenceService.class);
    private final ProgrammaticFlushTransactionExecutor executor = new ProgrammaticFlushTransactionExecutor(
            transactionManager,
            persistenceService
    );
    private final FlushPlan plan = plan();

    @BeforeEach
    void stubTransactionStart() {
        when(transactionManager.getTransaction(any())).thenReturn(status);
    }

    @Test
    void startsNamedRequiresNewWritableDefaultIsolationTransaction() {
        executor.execute(plan);

        ArgumentCaptor<TransactionDefinition> captor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(captor.capture());
        TransactionDefinition definition = captor.getValue();
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.getIsolationLevel());
        assertEquals(false, definition.isReadOnly());
        assertEquals(ProgrammaticFlushTransactionExecutor.TRANSACTION_NAME, definition.getName());
    }

    @Test
    void bodySuccessAndNormalCommitReturnsCommitted() {
        FlushTransactionResult result = executor.execute(plan);

        assertEquals(FlushTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(true, result.failureCause().isEmpty());
        verify(persistenceService).persist(plan);
        verify(transactionManager).commit(status);
        verify(transactionManager, never()).rollback(status);
    }

    @Test
    void transactionStartRuntimeFailureIsDefiniteWithoutBodyCommitOrRollback() {
        RuntimeException failure = new RuntimeException("start failed");
        when(transactionManager.getTransaction(any())).thenThrow(failure);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.DEFINITE_ROLLBACK, failure);
        verifyNoInteractions(persistenceService);
        verify(transactionManager, never()).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void transactionStartErrorIsDefiniteAndPreservesSameInstance() {
        AssertionError failure = new AssertionError("start fatal");
        when(transactionManager.getTransaction(any())).thenThrow(failure);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.DEFINITE_ROLLBACK, failure);
    }

    @Test
    void bodyRuntimeFailureAndSuccessfulRollbackIsDefiniteWithoutCommit() {
        RuntimeException failure = new RuntimeException("body failed");
        doThrow(failure).when(persistenceService).persist(plan);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.DEFINITE_ROLLBACK, failure);
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(status);
    }

    @Test
    void bodyErrorAndSuccessfulRollbackIsDefiniteWithOriginalError() {
        AssertionError failure = new AssertionError("body fatal");
        doThrow(failure).when(persistenceService).persist(plan);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.DEFINITE_ROLLBACK, failure);
        verify(transactionManager).rollback(status);
    }

    @Test
    void bodyFailureAndRollbackFailureIsAmbiguousWithSuppressedRollback() {
        RuntimeException bodyFailure = new RuntimeException("body failed");
        RuntimeException rollbackFailure = new RuntimeException("rollback failed");
        doThrow(bodyFailure).when(persistenceService).persist(plan);
        doThrow(rollbackFailure).when(transactionManager).rollback(status);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.AMBIGUOUS_COMMIT, bodyFailure);
        assertEquals(1, bodyFailure.getSuppressed().length);
        assertSame(rollbackFailure, bodyFailure.getSuppressed()[0]);
        verify(transactionManager, never()).commit(status);
    }

    @Test
    void bodyRuntimeFailureAndRollbackErrorUsesSameRollbackErrorWithBodySuppressed() {
        RuntimeException bodyFailure = new RuntimeException("body failed");
        AssertionError rollbackError = new AssertionError("rollback fatal");
        doThrow(bodyFailure).when(persistenceService).persist(plan);
        doThrow(rollbackError).when(transactionManager).rollback(status);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.AMBIGUOUS_COMMIT, rollbackError);
        assertEquals(1, rollbackError.getSuppressed().length);
        assertSame(bodyFailure, rollbackError.getSuppressed()[0]);
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(status);
    }

    @Test
    void bodyErrorAndRollbackErrorIsAmbiguousWithBodyErrorPrimary() {
        AssertionError bodyFailure = new AssertionError("body fatal");
        AssertionError rollbackFailure = new AssertionError("rollback fatal");
        doThrow(bodyFailure).when(persistenceService).persist(plan);
        doThrow(rollbackFailure).when(transactionManager).rollback(status);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.AMBIGUOUS_COMMIT, bodyFailure);
        assertEquals(1, bodyFailure.getSuppressed().length);
        assertSame(rollbackFailure, bodyFailure.getSuppressed()[0]);
    }

    @Test
    void sameBodyAndRollbackErrorIsNotSelfSuppressed() {
        AssertionError sameFailure = new AssertionError("same body and rollback fatal");
        doThrow(sameFailure).when(persistenceService).persist(plan);
        doThrow(sameFailure).when(transactionManager).rollback(status);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.AMBIGUOUS_COMMIT, sameFailure);
        assertEquals(0, sameFailure.getSuppressed().length);
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(status);
    }

    @Test
    void commitRuntimeFailureIsAmbiguousAndNeverCallsRollback() {
        RuntimeException failure = new RuntimeException("commit failed");
        doThrow(failure).when(transactionManager).commit(status);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.AMBIGUOUS_COMMIT, failure);
        verify(transactionManager, never()).rollback(status);
    }

    @Test
    void commitErrorIsAmbiguousAndNeverCallsRollback() {
        AssertionError failure = new AssertionError("commit fatal");
        doThrow(failure).when(transactionManager).commit(status);

        FlushTransactionResult result = executor.execute(plan);

        assertOutcomeWithCause(result, FlushTransactionOutcome.AMBIGUOUS_COMMIT, failure);
        verify(transactionManager, never()).rollback(status);
    }

    @Test
    void nullAndNoOpPlansAreRejectedBeforeTransactionStart() {
        assertThrows(NullPointerException.class, () -> executor.execute(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(FlushPlan.noOp(0L, DbBootstrapState.INITIALIZED))
        );

        verifyNoInteractions(transactionManager, persistenceService);
    }

    private void assertOutcomeWithCause(
            FlushTransactionResult result,
            FlushTransactionOutcome outcome,
            Throwable cause
    ) {
        assertEquals(outcome, result.outcome());
        assertSame(cause, result.failureCause().orElseThrow());
    }

    private FlushPlan plan() {
        TileKey key = new TileKey(0, 0, 0);
        return FlushPlan.nonNoOp(
                0L,
                1L,
                List.of(new WalRecord(
                        1L,
                        7L,
                        0,
                        0,
                        0,
                        0,
                        0,
                        17,
                        LocalDateTime.of(2026, 4, 3, 6, 0)
                )),
                List.of(new FlushTileSnapshot(
                        key,
                        new byte[BoardConstants.TILE_PIXEL_COUNT],
                        1L
                )),
                List.of(),
                DbBootstrapState.INITIALIZED
        );
    }
}
