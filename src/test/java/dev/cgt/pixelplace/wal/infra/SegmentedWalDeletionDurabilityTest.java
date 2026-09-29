package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRetentionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static dev.cgt.pixelplace.wal.infra.WalFileDurability.NameState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** T17-09/10: 실제 삭제 전후 오류와 Q/S 판정. count는 확정된 이름 삭제에만 부여 */
class SegmentedWalDeletionDurabilityTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void deleteBeforeOrAfterIoOrSecurityNeedsSuccessfulSyncForDelay(boolean removed, boolean security, boolean syncFails) throws Exception {
        var durability = spy(new TestWalFileDurability());
        try (var storage = spy(new ControlledStorage(directory.resolve("wal"), 1, durability))) {
            storage.appendBatchAndFsync(List.of(record(2), record(5), record(9), record(14)));
            Path failed = storage.segmentPath(1), next = storage.segmentPath(2);
            Throwable deletion = security ? new SecurityException("delete") : new IOException("delete");
            doAnswer(call -> { if (removed) call.callRealMethod(); throw deletion; }).when(storage).deleteFile(failed);
            var sync = new IOException("S");
            var count = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                call.callRealMethod();
                if (count.incrementAndGet() == 2 && syncFails) throw sync;
                return null;
            }).when(durability).syncDirectory(any());
            durability.events.clear();
            if (syncFails) {
                Throwable thrown = assertThrows(Throwable.class, () -> storage.deleteCommittedPrefix(9));
                assertSame(deletion, security ? thrown : thrown.getCause());
                assertTrue(List.of(deletion.getSuppressed()).contains(sync));
                assertEquals(List.of("Q", "S", "Q", "Q", "S"), durability.events);
                assertThrows(IllegalStateException.class, () -> storage.readAfter(0));
            } else {
                assertEquals(WalRetentionResult.delayed(1, 1, security ? WalRetentionResult.DelayKind.SECURITY : WalRetentionResult.DelayKind.IO),
                        storage.deleteCommittedPrefix(9));
                assertEquals(List.of("Q", "S", "Q", "Q", "S", "Q"), durability.events);
                doCallRealMethod().when(storage).deleteFile(failed);
                assertEquals(WalRetentionResult.completed(removed ? 1 : 2), storage.deleteCommittedPrefix(9));
            }
            if (syncFails) verify(storage, never()).deleteFile(next);
            assertEquals(!removed && syncFails, Files.exists(failed));
        }
    }

    @ParameterizedTest
    @CsvSource({"PRESENT,PRESENT", "PRESENT,ABSENT", "ABSENT,PRESENT", "UNKNOWN,ABSENT", "ABSENT,UNKNOWN"})
    void normalDeleteWithUncertainOrTransitioningNameFailsAfterSafeParentSync(String first, String last) throws Exception {
        var durability = spy(new TestWalFileDurability());
        try (var storage = spy(new ControlledStorage(directory.resolve("wal"), 1, durability))) {
            storage.appendBatchAndFsync(List.of(record(2), record(5), record(9)));
            Path target = storage.segmentPath(0), next = storage.segmentPath(1);
            doReturn(new WalFileDurability.NameObservation(WalFileDurability.NameState.valueOf(first),true,null),
                    new WalFileDurability.NameObservation(WalFileDurability.NameState.valueOf(last),true,null))
                    .when(durability).queryName(eq(target), any());
            clearInvocations(durability);
            assertThrows(RuntimeException.class, () -> storage.deleteCommittedPrefix(5));
            verify(durability).syncDirectory(any());
            verify(storage, never()).deleteFile(next);
            verifyPoisonedWithoutIo(storage, durability);
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void queryFailurePreservesCauseAndOnlySafeParentAttemptsSync(boolean parentSafe, boolean syncError) throws Exception {
        var durability = spy(new TestWalFileDurability());
        try (var storage = spy(new ControlledStorage(directory.resolve("wal"), 1, durability))) {
            storage.appendBatchAndFsync(List.of(record(2), record(5)));
            Path target = storage.segmentPath(0);
            IOException query = new IOException("Q");
            AssertionError error = new AssertionError("S");
            doReturn(new WalFileDurability.NameObservation(UNKNOWN,parentSafe,query)).when(durability).queryName(eq(target),any());
            if (syncError) doThrow(error).when(durability).syncDirectory(any());
            clearInvocations(durability);
            Throwable thrown = assertThrows(Throwable.class, () -> storage.deleteCommittedPrefix(5));
            if (parentSafe && syncError) {
                assertSame(error,thrown); assertArrayEquals(new Throwable[]{query}, error.getSuppressed());
            } else assertSame(query,thrown.getCause());
            verify(durability,times(parentSafe ? 1 : 0)).syncDirectory(any());
            verifyPoisonedWithoutIo(storage,durability);
        }
    }

    @Test void firstRetentionWithNoEligibleFilePreparesButDoesNotQueryOrDeleteSync() throws Exception {
        var durability = spy(new TestWalFileDurability());
        try (var storage = new ControlledStorage(directory.resolve("wal"),1,durability)) {
            records(storage.segmentPath(0),2);
            assertEquals(WalRetentionResult.completed(0),storage.deleteCommittedPrefix(2));
            assertEquals(List.of("prepare"),durability.events);
            verify(durability,never()).queryName(any(),any());
            verify(durability,never()).syncDirectory(any());
        }
    }

    private void verifyPoisonedWithoutIo(ControlledStorage storage, TestWalFileDurability durability) throws IOException {
        clearInvocations(storage,durability);
        assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(14)));
        assertThrows(IllegalStateException.class,()->storage.readAfter(0));
        assertThrows(IllegalStateException.class,()->storage.deleteCommittedPrefix(5));
        assertThrows(IllegalStateException.class,()->storage.prepareForRecovery(null));
        verifyNoInteractions(durability);
        verify(storage,never()).openDirectory(any());
        verify(storage,never()).deleteFile(any());
    }
}
