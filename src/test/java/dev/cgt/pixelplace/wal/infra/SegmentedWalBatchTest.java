package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 파일 batch의 force·물리 크기·durable prefix 및 순수 입력 실패 구분 */
class SegmentedWalBatchTest {
    @TempDir Path directory;

    @Test void threeRecordsUseOneRecordForceWhileSinglesUseThree() throws Exception {
        try(var batch=new ControlledStorage(directory.resolve("batch"),10000);
            var single=new ControlledStorage(directory.resolve("single"),10000)) {
            var records=List.of(record(2),record(5),record(9));
            batch.appendBatchAndFsync(records);
            for(var record:records) single.appendAndFsync(record);
            // 최초 empty force를 별도로 포함하고 record force의 batch/single 차이는 유지
            verify(batch.writers.getFirst(),times(2)).force(true);
            verify(single.writers.getFirst(),times(4)).force(true);
            assertEquals(1,batch.forceKinds.stream().filter(k->k==dev.cgt.pixelplace.measurement.PixelMeasurement.Operation.record_force).count());
            assertEquals(3,single.forceKinds.stream().filter(k->k==dev.cgt.pixelplace.measurement.PixelMeasurement.Operation.record_force).count());
            assertEquals(records,batch.readAfter(0).records());assertEquals(9,batch.readAfter(0).walLastEventSeq());
            assertArrayEquals(Files.readAllBytes(directory.resolve("single")),Files.readAllBytes(directory.resolve("batch")));
        }
    }

    @ParameterizedTest @ValueSource(strings={"exact","one-byte-over","oversize","many","existing-forced","empty-active"})
    void rotationForcesEachRecordBearingFileBeforeCloseAndSeparatesEmptyForce(String mode) throws Exception {
        int length=CODEC.serializeLine(record(2)).length;
        long max=switch(mode){case "exact","existing-forced","empty-active"->2L*length;case "one-byte-over"->2L*length-1;default->1;};
        Path base=directory.resolve("wal");
        if(mode.equals("empty-active")){records(base,1);Files.createFile(base.resolveSibling("wal.seg-0000000000000000001"));}
        try(var storage=new ControlledStorage(base,max)) {
            var records=List.of(record(2),record(5),record(9));
            if(mode.equals("existing-forced"))storage.appendAndFsync(record(1));
            storage.appendBatchAndFsync(records);
            var actual=storage.readAfter(0).records();
            assertEquals(records,actual.subList(actual.size()-3,actual.size()));
            assertEquals(9,storage.readAfter(0).walLastEventSeq());
            for(int i=0;i<storage.writers.size();i++) {
                var writer=storage.writers.get(i);
                int forces=2+(mode.equals("existing-forced")&&i==0?1:0);
                verify(writer,times(forces)).force(true);
                if(i<storage.writers.size()-1){var order=inOrder(writer);order.verify(writer,atLeastOnce()).force(true);order.verify(writer).close();}
            }
            for(var file:storage.inspectFiles()) {
                byte[] bytes=Files.readAllBytes(file.path());assertEquals('\n',bytes[bytes.length-1]);
                if(!mode.equals("oversize")&&!mode.equals("many"))assertTrue(bytes.length<=max);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"zero","negative","partial","first-force","later-force","empty-force","old-close","open","active"})
    void partialBatchFileFailurePoisonsWithoutRetryOrLosingPrefix(String point) throws Exception {
        Path base=directory.resolve("wal");var failure=new IOException(point);
        try(var storage=spy(new ControlledStorage(base,1))) {
            storage.setup=(channel,index)->{
                if(index==0 && (point.equals("zero")||point.equals("negative"))) doReturn(point.equals("zero")?0:-1).when(channel).write(any(ByteBuffer.class));
                if(index==1&&point.equals("partial"))doAnswer(call->{var buffer=(ByteBuffer)call.getArgument(0);buffer.limit(buffer.position()+3);call.callRealMethod();throw failure;}).when(channel).write(any(ByteBuffer.class));
                if(index==0&&point.equals("first-force"))doCallRealMethod().doThrow(failure).when(channel).force(true);
                if(index==1&&point.equals("later-force"))doCallRealMethod().doThrow(failure).when(channel).force(true);
                if(index==1&&point.equals("empty-force"))doThrow(failure).when(channel).force(true);
                if(index==0&&point.equals("old-close"))doThrow(failure).when(channel).close();
            };
            Path next=storage.segmentPath(1);
            if(point.equals("open"))doThrow(failure).when(storage).openFileChannel(next,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
            if(point.equals("active"))doThrow(failure).when(storage).readAttributes(base);
            assertThrows(RuntimeException.class,()->storage.appendBatchAndFsync(List.of(record(2),record(5),record(9))));
            if(List.of("partial","later-force","empty-force","old-close","open").contains(point))assertArrayEquals(CODEC.serializeLine(record(2)),Files.readAllBytes(base));
            clearInvocations(storage);
            assertThrows(IllegalStateException.class,()->storage.appendBatchAndFsync(List.of(record(21))));
            assertThrows(IllegalStateException.class,()->storage.readAfter(0));
            assertThrows(IllegalStateException.class,()->storage.deleteCommittedPrefix(1));
            verify(storage,never()).openFileChannel(any(),any(StandardOpenOption[].class));verify(storage,never()).openDirectory(any());verify(storage,never()).readAttributes(any());
        }
    }

    @Test void wholeBatchValidationAndSerializationHappenBeforeAnyFileIoWithoutPoison() throws Exception {
        var codec=spy(CODEC);doThrow(new IllegalArgumentException("serialize")).when(codec).serializeLine(record(9));
        Path path=directory.resolve("wal");
        try(var storage=spy(new SegmentedWalStorage(properties(path,1000),PARSER,codec,dev.cgt.pixelplace.measurement.Measurements.disabled(), new dev.cgt.pixelplace.wal.infra.TestWalFileDurability()))) {
            assertThrows(NullPointerException.class,()->storage.appendBatchAndFsync(null));
            assertThrows(IllegalArgumentException.class,()->storage.appendBatchAndFsync(List.of()));
            assertThrows(NullPointerException.class,()->storage.appendBatchAndFsync(Arrays.asList(record(2),null)));
            for(var records:List.of(List.of(record(2),record(0)),List.of(record(2),record(2)),List.of(record(5),record(2)),List.of(record(2),record(9))))
                assertThrows(IllegalArgumentException.class,()->storage.appendBatchAndFsync(records));
            verify(storage,never()).openDirectory(any());verify(storage,never()).openFileChannel(any(),any(StandardOpenOption[].class));assertFalse(Files.exists(path));
            storage.appendBatchAndFsync(List.of(record(2),record(5)));assertEquals(5,storage.readAfter(0).walLastEventSeq());
        }
    }

    @Test void shortWritesFinishAllLinesBeforeOneForce() throws Exception {
        try(var storage=new ControlledStorage(directory.resolve("wal"),10000)) {
            storage.setup=(channel,index)->doAnswer(call->{var b=(ByteBuffer)call.getArgument(0);int limit=b.limit();b.limit(Math.min(limit,b.position()+3));var count=call.callRealMethod();b.limit(limit);return count;}).when(channel).write(any(ByteBuffer.class));
            storage.appendBatchAndFsync(List.of(record(2),record(5),record(9)));
            verify(storage.writers.getFirst(),times(2)).force(true);assertEquals(3,storage.readAfter(0).records().size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"runtime/error","error/runtime","error/error","error/same","error/duplicate"})
    void failureAfterDurablePrefixPreservesPrimaryIdentityAndSuppressedOrder(String mode) throws Exception {
        Throwable first=mode.startsWith("runtime")?new IllegalArgumentException("write"):new AssertionError("write");
        Throwable later=mode.endsWith("same")?first:mode.endsWith("runtime")?new IllegalStateException("close"):new AssertionError("close");
        Throwable prior=new IllegalStateException("prior");first.addSuppressed(prior);
        if(mode.endsWith("duplicate"))first.addSuppressed(later);
        try(var storage=new ControlledStorage(directory.resolve("wal"),1)) {
            storage.setup=(channel,index)->{if(index==1){doThrow(first).when(channel).write(any(ByteBuffer.class));doThrow(later).when(channel).close();}};
            Throwable actual=assertThrows(Throwable.class,()->storage.appendBatchAndFsync(List.of(record(2),record(5),record(9))));
            Throwable primary=first instanceof Error?first:later;assertSame(primary,actual);
            if(primary==first)assertArrayEquals(later==first?new Throwable[]{prior}:new Throwable[]{prior,later},actual.getSuppressed());
            else assertArrayEquals(new Throwable[]{first},actual.getSuppressed());
            assertArrayEquals(CODEC.serializeLine(record(2)),Files.readAllBytes(directory.resolve("wal")));
            assertThrows(IllegalStateException.class,()->storage.appendBatchAndFsync(List.of(record(21))));
        }
    }
}
