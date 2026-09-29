package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.flush.application.DbBootstrapClassifier;
import dev.cgt.pixelplace.measurement.Measurements;
import dev.cgt.pixelplace.pixel.application.EventSeqManager;
import dev.cgt.pixelplace.recovery.application.*;
import dev.cgt.pixelplace.tile.application.TileLoadResult;
import dev.cgt.pixelplace.tile.domain.CanonicalZ0TileKeys;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 검증 batch의 일회 대응·전체 R/마지막 S·READY 이전 실패를 실제 파일 storage와 startup으로 검증 */
class SegmentedWalPreparationTest {
    @TempDir Path root;

    private SegmentedWalStorage storage(Path base, TestWalFileDurability durability) {
        return spy(new SegmentedWalStorage(properties(base,1),PARSER,CODEC,Measurements.disabled(),durability));
    }

    @ParameterizedTest @ValueSource(longs={0,5,9})
    void everyRemainingFileIncludingCheckpointPrefixAndEmptyTailIsSynced(long checkpoint) throws Exception {
        var durability=new TestWalFileDurability(); Path base=root.resolve("wal");
        try(var storage=storage(base,durability)) {
            records(base,2,5); records(storage.segmentPath(1),9); Files.createFile(storage.segmentPath(2));
            byte[] before=Files.readAllBytes(base);
            var batch=storage.readAfter(checkpoint);
            clearInvocations(storage);
            storage.prepareForRecovery(batch);
            assertEquals(List.of("prepare","R:wal","R:wal.seg-0000000000000000001","R:wal.seg-0000000000000000002","S"),durability.events);
            verify(storage,never()).openFileChannel(any(),any(StandardOpenOption[].class));
            assertArrayEquals(before,Files.readAllBytes(base));
            durability.events.clear(); clearInvocations(storage);
            assertThrows(IllegalArgumentException.class,()->storage.prepareForRecovery(batch));
            assertTrue(durability.events.isEmpty()); verify(storage,never()).openDirectory(any());
        }
    }

    @ParameterizedTest @ValueSource(strings={"manual","other-storage","older-scan","after-append"})
    void unrelatedOrStaleBatchRejectsBeforeIoWithoutPoison(String kind) throws Exception {
        var durability=new TestWalFileDurability(); Path base=root.resolve("wal"); records(base,2);
        try(var storage=storage(base,durability);var other=storage(base,new TestWalFileDurability())) {
            var first=storage.readAfter(0); WalReplayBatch invalid=first;
            if(kind.equals("manual")) invalid=new WalReplayBatch(first.records(),first.walLastEventSeq());
            if(kind.equals("other-storage")) invalid=other.readAfter(0);
            if(kind.equals("older-scan")) storage.readAfter(0);
            if(kind.equals("after-append")) storage.appendAndFsync(record(5));
            var rejected=invalid; durability.events.clear(); clearInvocations(storage);
            assertThrows(IllegalArgumentException.class,()->storage.prepareForRecovery(rejected));
            assertTrue(durability.events.isEmpty()); verify(storage,never()).openDirectory(any());
            var current=storage.readAfter(0); storage.prepareForRecovery(current);
        }
    }

    @ParameterizedTest @ValueSource(strings={"size","replacement","added","removed"})
    void fileSetOrIdentityChangeAfterScanPoisonsBeforeRecoverySync(String change) throws Exception {
        var durability=spy(new TestWalFileDurability()); Path base=root.resolve("wal"); records(base,2);
        try(var storage=storage(base,durability)) {
            var batch=storage.readAfter(0);
            if(change.equals("size")) Files.writeString(base,"x",StandardOpenOption.APPEND);
            if(change.equals("replacement")) doReturn("different identity").when(durability).fileIdentity(base);
            if(change.equals("added")) records(storage.segmentPath(1),5);
            if(change.equals("removed")) Files.delete(base);
            assertThrows(IllegalStateException.class,()->storage.prepareForRecovery(batch));
            verify(durability,never()).syncRecoveredFile(any());
            assertThrows(IllegalStateException.class,()->storage.readAfter(0));
        }
    }

    @ParameterizedTest @ValueSource(strings={"prepare","first-R","second-R","last-S"})
    void recoveryFailureBlocksLaterFilesMemorySeedReadyAndEveryLaterStorageIo(String point) throws Exception {
        var durability=spy(new TestWalFileDurability()); Path base=root.resolve("wal");
        try(var storage=storage(base,durability)) {
            records(base,2); records(storage.segmentPath(1),5); Files.createFile(storage.segmentPath(2));
            byte[] before=Files.readAllBytes(base);
            var failure=new IOException(point);
            if(point.equals("prepare")) doThrow(failure).when(durability).prepareDirectory(any());
            if(point.equals("first-R")) doThrow(failure).when(durability).syncRecoveredFile(base);
            Path second=storage.segmentPath(1);
            if(point.equals("second-R")) doThrow(failure).when(durability).syncRecoveredFile(second);
            if(point.equals("last-S")) doThrow(failure).when(durability).syncDirectory(any());
            var board=mock(InMemoryTileBoard.class);var seq=mock(EventSeqManager.class);var ready=new ServiceReadiness();
            RuntimeException actual=assertThrows(RuntimeException.class,()->recovery(storage,board,seq,ready).recover());
            assertSame(failure,actual.getCause()); verifyNoInteractions(board,seq);assertFalse(ready.isReady());
            if(!point.equals("last-S")) verify(durability,never()).syncRecoveredFile(storage.segmentPath(2));
            if(point.equals("first-R")||point.equals("second-R")) verify(durability,never()).syncDirectory(any());
            assertArrayEquals(before,Files.readAllBytes(base));
            clearInvocations(storage,durability);
            assertThrows(IllegalStateException.class,()->storage.readAfter(0));
            assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(9)));
            assertThrows(IllegalStateException.class,()->storage.deleteCommittedPrefix(2));
            assertThrows(IllegalStateException.class,()->storage.prepareForRecovery(new WalReplayBatch(List.of(),0)));
            verifyNoInteractions(durability); verify(storage,never()).openDirectory(any());
        }
    }

    @Test void fullRecoverySyncPrecedesMemorySeedAndReadyWithoutAppendWriter() throws Exception {
        var durability=spy(new TestWalFileDurability()); Path base=root.resolve("wal"); records(base,2);
        try(var storage=storage(base,durability)) {
            var board=mock(InMemoryTileBoard.class);var seq=mock(EventSeqManager.class);var ready=spy(new ServiceReadiness());
            recovery(storage,board,seq,ready).recover();
            var order=inOrder(durability,board,seq,ready);
            order.verify(ready).markNotReady();order.verify(durability).prepareDirectory(root);
            order.verify(durability).syncRecoveredFile(base); order.verify(durability).syncDirectory(any());
            order.verify(board).initializeAllWhite();order.verify(board).applyReplayRecord(0,0,17);
            order.verify(seq).initializeLastIssued(2);order.verify(ready).markReady();
            verify(storage,never()).openFileChannel(any(),eq(StandardOpenOption.READ),eq(StandardOpenOption.WRITE));
        }
    }

    @Test void priorDirectAppendPreparationDoesNotSkipRecoveryFilesOrFinalSync() throws Exception {
        var durability=new TestWalFileDurability();Path base=root.resolve("wal");
        try(var storage=storage(base,durability)) {
            storage.appendAndFsync(record(2));var batch=storage.readAfter(2);durability.events.clear();
            storage.prepareForRecovery(batch);
            assertEquals(List.of("R:wal","S"),durability.events);
        }
    }

    @Test void absentFileSetPreparesParentsWithoutCreatingWalAndCachedPreparationStillSyncs() throws Exception {
        var durability=new TestWalFileDurability(); Path base=root.resolve("missing/wal");
        try(var storage=storage(base,durability)) {
            var first=storage.readAfter(0);storage.prepareForRecovery(first);
            assertEquals(List.of("prepare"),durability.events);assertFalse(Files.exists(base));
            var again=storage.readAfter(0);durability.events.clear();storage.prepareForRecovery(again);
            assertEquals(List.of("S"),durability.events);assertFalse(Files.exists(base));
        }
    }

    private StartupRecoveryService recovery(SegmentedWalStorage storage,InMemoryTileBoard board,EventSeqManager seq,ServiceReadiness ready) {
        var capture=new StartupRecoveryDbViewCaptureService(()->new CheckpointSnapshot(0),TileLoadResult::allMissingResult);
        var keys=new CanonicalZ0TileKeys();
        return new StartupRecoveryService(capture,new DbBootstrapClassifier(keys),keys,new FileWalReplaySource(storage),
                board,seq,ready,Measurements.disabled(),new FileWalStoragePreparation(storage));
    }
}
