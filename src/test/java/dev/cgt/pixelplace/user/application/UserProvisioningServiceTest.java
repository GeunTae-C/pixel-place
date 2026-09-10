package dev.cgt.pixelplace.user.application;

import dev.cgt.pixelplace.user.infra.UserEntity;
import dev.cgt.pixelplace.user.infra.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ID 검증·호출 순서·실패 전파 고정. 실제 repository transaction 경쟁은 MySQL 검증 책임 */
class UserProvisioningServiceTest {
    private final UserJpaRepository repository = mock(UserJpaRepository.class);
    private final UserProvisioningService service = new UserProvisioningService(repository);

    private UserEntity user(long id) {
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    @Test
    void existingKakaoIdReturnsInternalIdWithoutInsert() {
        UserEntity existing = user(7);
        when(repository.findByKakaoUserId(123)).thenReturn(Optional.of(existing));
        assertEquals(7, service.provision(123));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void newKakaoIdReturnsCommittedInsertResult() {
        when(repository.findByKakaoUserId(123)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            assertEquals(123, ((UserEntity) invocation.getArgument(0)).getKakaoUserId());
            return user(9);
        });
        assertEquals(9, service.provision(123));
        var order = inOrder(repository);
        order.verify(repository).findByKakaoUserId(123);
        order.verify(repository).saveAndFlush(any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void insertConflictRequiresSameKakaoRowOnNewRepositoryLookup() {
        var failure = new DataIntegrityViolationException("test unique conflict");
        UserEntity existing = user(19);
        when(repository.findByKakaoUserId(123)).thenReturn(Optional.empty(), Optional.of(existing));
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertEquals(19, service.provision(123));
        var order = inOrder(repository);
        order.verify(repository).findByKakaoUserId(123);
        order.verify(repository).saveAndFlush(any());
        order.verify(repository).findByKakaoUserId(123);
        order.verifyNoMoreInteractions();
    }

    @Test
    void unrelatedIntegrityFailureWithoutSameRowPreservesOriginalException() {
        var failure = new DataIntegrityViolationException("test not null failure");
        when(repository.findByKakaoUserId(123)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.provision(123)));
        verify(repository, times(2)).findByKakaoUserId(123);
        verify(repository).saveAndFlush(any());
    }

    @Test
    void invalidIdsAndAmbientTransactionsRejectBeforeRepositoryAccess() {
        for (long id : new long[]{0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> service.provision(id));
        }
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.provision(1));
        } finally {
            TransactionSynchronizationManager.clear();
        }
        verifyNoInteractions(repository);
    }

    @Test
    void invalidStoredInternalIdCannotBecomeSuccessfulPrincipal() {
        UserEntity invalid = user(-1);
        when(repository.findByKakaoUserId(123)).thenReturn(Optional.of(invalid));
        assertThrows(IllegalStateException.class, () -> service.provision(123));
    }
}
