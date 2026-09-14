package dev.cgt.pixelplace.wal.infra;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import static dev.cgt.pixelplace.wal.infra.WalStorageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 전환 중단으로 남긴 파일군을 새 storage가 검증. JVM 종료·전원 장애 실험과 구분 */
class SegmentedWalRestartTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings={"before-create","before-empty-force","after-empty-force","before-first-byte","partial-line","after-record-force"})
    void freshStorageJudgesActualDiskStateAtEachTransitionInterruption(String point) throws Exception {
        Path base=directory.resolve("wal");
        Path next;
        try(var first=spy(new ControlledStorage(base,1))) {
            first.appendAndFsync(record(2));
            next=first.segmentPath(1);
            IOException interruption=new IOException("transition interruption");
            if(point.equals("before-create")) doThrow(interruption).when(first).openFileChannel(next,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
            first.setup=(channel,index)-> {
                if(index!=1)return;
                var forceCount=new java.util.concurrent.atomic.AtomicInteger();
                doAnswer(inv->{
                    int call=forceCount.incrementAndGet();
                    if(point.equals("before-empty-force")&&call==1)throw interruption;
                    inv.callRealMethod();
                    if(point.equals("after-empty-force")&&call==1 || point.equals("after-record-force")&&call==2)throw interruption;
                    return null;
                }).when(channel).force(true);
                if(point.equals("before-first-byte")) doThrow(interruption).when(channel).write(any(ByteBuffer.class));
                if(point.equals("partial-line")) doAnswer(inv->{
                    ByteBuffer buffer=inv.getArgument(0);buffer.limit(buffer.position()+3);
                    inv.callRealMethod();throw interruption;
                }).when(channel).write(any(ByteBuffer.class));
            };
            assertThrows(IllegalStateException.class,()->first.appendAndFsync(record(5)));
        }
        byte[] leftover=Files.exists(next)?Files.readAllBytes(next):null;
        try(var fresh=storage(base,1)) {
            if(point.equals("partial-line")) {
                assertThrows(IllegalStateException.class,()->fresh.readAfter(0));
                assertArrayEquals(leftover,Files.readAllBytes(next));
            } else {
                assertEquals(point.equals("after-record-force")?5:2,fresh.readAfter(0).walLastEventSeq());
                fresh.appendAndFsync(record(9));
                assertEquals(9,fresh.readAfter(0).walLastEventSeq());
                assertEquals(point.equals("after-record-force")?3:2,fresh.inspectFiles().size());
            }
        }
    }
}
