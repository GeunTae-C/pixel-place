package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 불확실한 파일 결과의 fail-stop과 최초 실패·close Error identity 보존 검증 */
class SegmentedWalFailureTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings={"legacy-open","existing-open","old-close","next-open","empty-force","partial-write","record-force","scan-read","scan-close","active-attributes"})
    void fileFailurePoisonsAndBlocksEveryLaterFileAccess(String point) throws Exception {
        Path base=directory.resolve("wal");
        IOException failure=new IOException(point);
        ControlledStorage storage=spy(new ControlledStorage(base,1));
        if(point.equals("existing-open")) records(base,2);
        if(point.equals("legacy-open")) doThrow(failure).when(storage).openFileChannel(base,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
        if(point.equals("existing-open")) doThrow(failure).when(storage).openFileChannel(base,StandardOpenOption.READ,StandardOpenOption.WRITE);
        Path next=storage.segmentPath(1);
        if(point.equals("next-open")) doThrow(failure).when(storage).openFileChannel(next,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
        storage.setup=(channel,index)-> {
            if(point.equals("old-close") && index==0) doThrow(failure).when(channel).close();
            if(point.equals("empty-force") && index==1 || point.equals("record-force") && index==0) doThrow(failure).when(channel).force(true);
            if(point.equals("partial-write") && index==0) doAnswer(invocation->{
                ByteBuffer buffer=invocation.getArgument(0);
                int limit=buffer.limit(); buffer.limit(buffer.position()+Math.max(1,buffer.remaining()/2));
                invocation.callRealMethod(); buffer.limit(limit); throw failure;
            }).when(channel).write(any(ByteBuffer.class));
        };
        try(storage) {
            boolean later=Arrays.asList("old-close","next-open","empty-force","active-attributes").contains(point);
            if(later) storage.appendAndFsync(record(2));
            if(point.equals("active-attributes")) doThrow(failure).when(storage).readAttributes(base);
            if(point.startsWith("scan-")) {
                records(base,2);
                BufferedReader reader=mock(BufferedReader.class);
                if(point.equals("scan-read")) when(reader.readLine()).thenThrow(failure);
                else doThrow(failure).when(reader).close();
                doReturn(reader).when(storage).openReader(base);
            }
            RuntimeException thrown=assertThrows(RuntimeException.class,()-> {
                if(point.startsWith("scan-")) storage.readAfter(0);
                else storage.appendAndFsync(record(9));
            });
            assertSame(failure,thrown.getCause());
            for(var writer:storage.writers) verify(writer,atLeastOnce()).close();
            assertBlockedWithoutIo(storage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"io/io","runtime/io","io/error","runtime/error","error/io","error/runtime","error/error","error/same"})
    void cleanupPreservesRequiredPrimaryIdentityWithoutSelfSuppression(String kinds) throws Exception {
        String[] parts=kinds.split("/");
        Throwable first=problem(parts[0],"first");
        Throwable later=parts[1].equals("same")?first:problem(parts[1],"close");
        try(var storage=new ControlledStorage(directory.resolve("wal"),1000)) {
            storage.setup=(channel,index)-> {
                doThrow(first).when(channel).write(any(ByteBuffer.class));
                doThrow(later).when(channel).close();
            };
            Throwable thrown=assertThrows(Throwable.class,()->storage.appendAndFsync(record(2)));
            Throwable primary=first instanceof Error || !(later instanceof Error)?first:later;
            Throwable actual=thrown instanceof IllegalStateException && primary instanceof IOException?thrown.getCause():thrown;
            assertSame(primary,actual);
            if(first==later) assertEquals(0,primary.getSuppressed().length);
            else assertArrayEquals(new Throwable[]{primary==first?later:first},primary.getSuppressed());
            assertThrows(IllegalStateException.class,()->storage.readAfter(0));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void nullAndSerializationFailureRejectBeforeIoWithoutPoison(boolean alreadyOpen) throws Exception {
        WalRecordJsonCodec codec=mock(WalRecordJsonCodec.class);
        when(codec.serializeLine(any())).thenAnswer(inv->CODEC.serializeLine(inv.getArgument(0)));
        var bad=record(5);
        doThrow(new IllegalArgumentException("serialization")).when(codec).serializeLine(bad);
        try(var storage=spy(new SegmentedWalStorage(properties(directory.resolve("wal"),1000),PARSER,codec))) {
            if(alreadyOpen) storage.appendAndFsync(record(2));
            clearInvocations(storage);
            assertThrows(NullPointerException.class,()->storage.appendAndFsync(null));
            assertThrows(IllegalArgumentException.class,()->storage.appendAndFsync(bad));
            verifyNoIo(storage);
            storage.appendAndFsync(record(9));
            assertEquals(9,storage.readAfter(0).walLastEventSeq());
        }
    }

    @Test
    void nonpositiveDuplicateAndReverseInputsDoNotChangeBytesOrPoison() throws Exception {
        try(var storage=storage(directory.resolve("wal"),1)) {
            assertThrows(IllegalArgumentException.class,()->storage.appendAndFsync(record(0)));
            assertThrows(IllegalArgumentException.class,()->storage.appendAndFsync(record(-1)));
            assertFalse(Files.exists(directory.resolve("wal")));
            records(storage.segmentPath(0),5);
            byte[] before=Files.readAllBytes(storage.segmentPath(0));
            for(long seq:new long[]{5,2}) assertThrows(IllegalArgumentException.class,()->storage.appendAndFsync(record(seq)));
            assertArrayEquals(before,Files.readAllBytes(storage.segmentPath(0)));
            storage.appendAndFsync(record(9));
            for(long seq:new long[]{9,5}) assertThrows(IllegalArgumentException.class,()->storage.appendAndFsync(record(seq)));
            storage.appendAndFsync(record(14));
            assertEquals(14,storage.readAfter(0).walLastEventSeq());
        }
    }

    @Test
    void nextNameCollisionIsPreservedAndNoOtherNumberIsTried() throws Exception {
        try(var storage=storage(directory.resolve("wal"),1)) {
            storage.appendAndFsync(record(2));
            Path collision=storage.segmentPath(1);
            Files.writeString(collision,"preserved");
            assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(5)));
            assertEquals("preserved",Files.readString(collision));
            assertFalse(Files.exists(storage.segmentPath(2)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void positiveShortWritesFinishAndZeroWriteFailsPromptly(boolean zero) throws Exception {
        try(var storage=new ControlledStorage(directory.resolve("wal"),1000)) {
            AtomicInteger writes=new AtomicInteger();
            storage.setup=(channel,index)->doAnswer(inv->{
                writes.incrementAndGet();
                if(zero) return 0;
                ByteBuffer buffer=inv.getArgument(0); int limit=buffer.limit();
                buffer.limit(Math.min(limit,buffer.position()+3));
                Object count=inv.callRealMethod(); buffer.limit(limit); return count;
            }).when(channel).write(any(ByteBuffer.class));
            if(zero) {
                assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(2)));
                assertEquals(1,writes.get());
            } else {
                storage.appendAndFsync(record(2));
                assertTrue(writes.get()>1);
                assertEquals(record(2),storage.readAfter(0).records().getFirst());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"size","missing","link"})
    void adoptedActiveMutationIsNeverSilentlyRecreatedOrAccepted(String change) throws Exception {
        Path base=directory.resolve("wal");
        try(var storage=spy(storage(base,1000))) {
            storage.appendAndFsync(record(2));
            if(change.equals("size")) Files.writeString(base,"changed-size");
            if(change.equals("missing")) doThrow(new NoSuchFileException("test active")).when(storage).readAttributes(base);
            if(change.equals("link")) {
                var attributes=mock(java.nio.file.attribute.BasicFileAttributes.class);
                when(attributes.isSymbolicLink()).thenReturn(true);
                doReturn(attributes).when(storage).readAttributes(base);
            }
            assertThrows(IllegalStateException.class,()->storage.readAfter(0));
            assertBlockedWithoutIo(storage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"unopened","opened","close-io","close-error"})
    void closeIsTerminalAndIdempotentEvenWhenCleanupFails(String mode) throws Exception {
        ControlledStorage storage=spy(new ControlledStorage(directory.resolve("wal"),1000));
        if(!mode.equals("unopened")) storage.appendAndFsync(record(2));
        Error error=new AssertionError("close");
        if(mode.equals("close-io")) doThrow(new IOException("close")).when(storage.writers.getFirst()).close();
        if(mode.equals("close-error")) doThrow(error).when(storage.writers.getFirst()).close();
        if(mode.equals("close-error")) assertSame(error,assertThrows(AssertionError.class,storage::close));
        else assertDoesNotThrow(storage::close);
        clearInvocations(storage);
        storage.close();
        assertBlockedWithoutIo(storage);
        for(var writer:storage.writers) verify(writer,times(1)).close();
    }

    @Test
    void completeRecordAfterFailedForceCanBeRecoveredByFreshStorage() throws Exception {
        Path base=directory.resolve("wal");
        try(var first=new ControlledStorage(base,1)) {
            first.setup=(channel,index)->doThrow(new IOException("force")).when(channel).force(true);
            assertThrows(IllegalStateException.class,()->first.appendAndFsync(record(2)));
        }
        try(var fresh=storage(base,1)) {
            assertEquals(2,fresh.readAfter(0).walLastEventSeq());
            fresh.appendAndFsync(record(5));
            assertEquals(5,fresh.readAfter(0).walLastEventSeq());
        }
    }

    private Throwable problem(String kind,String text) {
        return switch(kind) {case "io"->new IOException(text);case "runtime"->new IllegalArgumentException(text);default->new AssertionError(text);};
    }
    private void assertBlockedWithoutIo(SegmentedWalStorage storage) throws Exception {
        clearInvocations(storage);
        assertThrows(IllegalStateException.class,()->storage.appendAndFsync(record(21)));
        assertThrows(IllegalStateException.class,()->storage.readAfter(0));
        verifyNoIo(storage);
    }
    private void verifyNoIo(SegmentedWalStorage storage) throws Exception {
        verify(storage,never()).openDirectory(any()); verify(storage,never()).readAttributes(any());
        verify(storage,never()).openReader(any()); verify(storage,never()).createDirectories(any());
        verify(storage,never()).openFileChannel(any(),any(StandardOpenOption[].class));
    }
}
