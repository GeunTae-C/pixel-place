package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
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

/** 최초/기존/전환 후보는 force와 이름 S가 끝나기 전 active로 게시 불가. 실패 시 candidate 소유권 검증 */
class SegmentedWalCreationDurabilityTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(strings={"first","existing","rotation"})
    void directorySyncFailureClosesUnpublishedCandidateAndPoisonsEveryLaterOperation(String boundary) throws Exception {
        Path base=root.resolve("wal");var durability=spy(new TestWalFileDurability());
        if(boundary.equals("existing")) records(base,2);
        try(var storage=new ControlledStorage(base,1,durability)) {
            if(boundary.equals("rotation")) storage.appendAndFsync(record(2));
            var failure=new IOException("S after file force");
            doAnswer(call->{call.callRealMethod();throw failure;}).when(durability).syncDirectory(any());
            var result=assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(5)));
            assertSame(failure,result.getCause());
            for(var channel:storage.writers) verify(channel,atLeastOnce()).close();
            if(boundary.equals("first")) assertEquals(0,Files.size(base));
            else assertArrayEquals(CODEC.serializeLine(record(2)),Files.readAllBytes(base));
            if(boundary.equals("rotation")) assertEquals(0,Files.size(storage.segmentPath(1)));
            clearInvocations(durability);
            assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(9)));
            assertThrows(IllegalStateException.class,()->storage.readAfter(0));
            assertThrows(IllegalStateException.class,()->storage.deleteCommittedPrefix(2));
            assertThrows(IllegalStateException.class,()->storage.prepareForRecovery(null));
            verifyNoInteractions(durability);
        }
    }

    @ParameterizedTest @ValueSource(strings={"io","runtime","error","same"})
    void candidateCloseFailurePreservesSyncCauseAndErrorPriority(String kind) throws Exception {
        var durability=spy(new TestWalFileDurability());
        Throwable sync=kind.equals("same")?new AssertionError("same"):new IOException("S");
        Throwable close=switch(kind){case "io"->new IOException("close");case "runtime"->new IllegalStateException("close");case "same"->sync;default->new AssertionError("close");};
        doThrow(sync).when(durability).syncDirectory(any());
        try(var storage=new ControlledStorage(root.resolve("wal"),1000,durability)) {
            storage.setup=(channel,index)->doThrow(close).when(channel).close();
            Throwable actual=assertThrows(Throwable.class,()->storage.appendAndFsync(record(2)));
            Throwable expected=sync instanceof Error||!(close instanceof Error)?sync:close;
            if(expected instanceof IOException) actual=actual.getCause();
            assertSame(expected,actual);assertEquals(sync==close?0:1,actual.getSuppressed().length);
            assertEquals(0,Files.size(root.resolve("wal")));
        }
    }

    @Test void firstAndExistingAdoptionForcesAreSeparateFromNewRecordCoverage() throws Exception {
        var durability=new TestWalFileDurability();Path base=root.resolve("wal");
        try(var storage=new ControlledStorage(base,10000,durability)) {
            storage.appendAndFsync(record(2));
            assertEquals(List.of(PixelMeasurement.Operation.empty_force,PixelMeasurement.Operation.record_force),storage.forceKinds);
            durability.events.clear();storage.appendAndFsync(record(5));assertTrue(durability.events.isEmpty());
        }
        try(var reopened=new ControlledStorage(base,10000,new TestWalFileDurability())) {
            var batch=reopened.readAfter(0);reopened.prepareForRecovery(batch);reopened.appendAndFsync(record(9));
            assertEquals(List.of(PixelMeasurement.Operation.adoption_force,PixelMeasurement.Operation.record_force),reopened.forceKinds);
        }
    }
}
