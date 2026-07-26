package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 파일 WAL appender는 append + fsync 성공을 write 내구성 경계로 삼으므로 실제 임시 파일에 기록해 검증함
class FileWalAppenderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WalRecordParser parser = new WalRecordParser(objectMapper);

    @Test
    // active WAL이 없으면 append 시점에 파일을 만들고 승인 이벤트 한 줄을 기록해야 함
    void appendAndFsyncCreatesWalFileAndWritesOneLine() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.close();

        List<String> lines = Files.readAllLines(wal);
        assertEquals(1, lines.size());
        assertEquals(1L, parser.parseLine(lines.get(0), 1).eventSeq());
    }

    @Test
    // append는 직렬화되어야 하므로 여러 호출 결과가 eventSeq 순서대로 여러 줄에 남아야 함
    void appendAndFsyncWritesMultipleLinesInEventSeqOrder() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.appendAndFsync(record(2L));
        appender.appendAndFsync(record(3L));
        appender.close();

        List<String> lines = Files.readAllLines(wal);
        assertEquals(3, lines.size());
        assertEquals(1L, parser.parseLine(lines.get(0), 1).eventSeq());
        assertEquals(2L, parser.parseLine(lines.get(1), 2).eventSeq());
        assertEquals(3L, parser.parseLine(lines.get(2), 3).eventSeq());
    }

    @Test
    // appender가 기록한 줄은 기존 WalRecordParser가 다시 읽을 수 있어야 boot recovery와 호환됨
    void writtenLineCanBeParsedByWalRecordParser() throws IOException {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.close();

        WalRecord parsed = parser.parseLine(Files.readAllLines(wal).get(0), 1);
        assertEquals(768, parsed.x());
        assertEquals(1280, parsed.y());
        assertEquals(17, parsed.color());
    }

    @Test
    // 운영 환경에서 WAL 디렉터리가 아직 없을 수 있으므로 append 시점에 parent directory를 만듦
    void appendAndFsyncCreatesMissingParentDirectory() {
        Path wal = tempDir.resolve("missing").resolve("nested").resolve("active.wal");
        FileWalAppender appender = appender(wal);

        appender.appendAndFsync(record(1L));
        appender.close();

        assertTrue(Files.isRegularFile(wal));
    }

    @Test
    // activeFile 경로가 디렉터리면 JSON Lines append와 fsync 계약을 만족할 수 없어 실패해야 함
    void appendAndFsyncFailsWhenActiveFileIsDirectory() {
        FileWalAppender appender = appender(tempDir);

        assertThrows(IllegalStateException.class, () -> appender.appendAndFsync(record(1L)));
    }

    @Test
    // null 입력은 파일 접근 전 거부하되 I/O poison 원인으로 취급하지 않아 후속 정상 append 허용
    void nullRecordDoesNotAccessWalOrPoisonAppender() throws IOException {
        FileChannel channel = writableChannel();
        AtomicInteger openCount = new AtomicInteger();
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, openCount);

        assertThrows(NullPointerException.class, () -> appender.appendAndFsync(null));

        assertEquals(0, openCount.get());
        verifyNoInteractions(channel);

        appender.appendAndFsync(record(1L));

        assertEquals(1, openCount.get());
        verify(channel).write(any(ByteBuffer.class));
        verify(channel).force(true);
    }

    @Test
    // 기록된 WAL은 FileWalReplaySource가 다시 읽어 replay 대상 이벤트로 반환해야 함
    void writtenRecordsCanBeReadByFileWalReplaySource() {
        Path wal = tempDir.resolve("active.wal");
        FileWalAppender appender = appender(wal);
        appender.appendAndFsync(record(1L));
        appender.appendAndFsync(record(2L));
        appender.close();

        WalReplayBatch batch = replaySource(wal).readAfter(0L);

        assertEquals(2, batch.records().size());
        assertEquals(1L, batch.records().get(0).eventSeq());
        assertEquals(2L, batch.records().get(1).eventSeq());
        assertEquals(2L, batch.walLastEventSeq());
    }

    @Test
    void writeIOExceptionIsPropagated() throws IOException {
        FileChannel channel = writableChannel();
        IOException writeFailure = new IOException("write failed");
        when(channel.write(any(ByteBuffer.class))).thenThrow(writeFailure);
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> appender.appendAndFsync(record(1L))
        );

        assertSame(writeFailure, exception.getCause());
    }

    @Test
    void forceIOExceptionIsPropagated() throws IOException {
        FileChannel channel = writableChannel();
        IOException forceFailure = new IOException("force failed");
        doThrow(forceFailure).when(channel).force(true);
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> appender.appendAndFsync(record(1L))
        );

        assertSame(forceFailure, exception.getCause());
    }

    @Test
    // I/O 실패 후 partial tail 뒤에 새 JSON append 차단
    void ioFailurePoisonsAppenderAndNextAppendFailsImmediately() throws IOException {
        FileChannel channel = writableChannel();
        doThrow(new IOException("force failed")).when(channel).force(true);
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());

        assertThrows(IllegalStateException.class, () -> appender.appendAndFsync(record(1L)));

        IllegalStateException poisonedFailure = assertThrows(
                IllegalStateException.class,
                () -> appender.appendAndFsync(record(2L))
        );
        assertTrue(poisonedFailure.getMessage().contains("poisoned"));
    }

    @Test
    void poisonedAppenderDoesNotReopenWalChannel() throws IOException {
        FileChannel channel = writableChannel();
        doThrow(new IOException("force failed")).when(channel).force(true);
        AtomicInteger openCount = new AtomicInteger();
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, openCount);

        assertThrows(IllegalStateException.class, () -> appender.appendAndFsync(record(1L)));
        assertThrows(IllegalStateException.class, () -> appender.appendAndFsync(record(2L)));

        assertEquals(1, openCount.get());
    }

    @Test
    void firstIoFailureRemainsExceptionCause() throws IOException {
        FileChannel channel = writableChannel();
        IOException firstFailure = new IOException("first force failed");
        doThrow(firstFailure).when(channel).force(true);
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> appender.appendAndFsync(record(1L))
        );

        assertSame(firstFailure, exception.getCause());
    }

    @Test
    // close 실패가 최초 write/force 실패를 대체하지 않고 suppressed로 보존되는 경계
    void closeFailureKeepsFirstIoFailureAsCauseAndSuppressedDetail() throws IOException {
        FileChannel channel = writableChannel();
        IOException forceFailure = new IOException("force failed");
        IOException closeFailure = new IOException("close failed");
        doThrow(forceFailure).when(channel).force(true);
        doThrow(closeFailure).when(channel).close();
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> appender.appendAndFsync(record(1L))
        );

        assertSame(forceFailure, exception.getCause());
        assertEquals(1, forceFailure.getSuppressed().length);
        assertSame(closeFailure, forceFailure.getSuppressed()[0]);
    }

    @Test
    void poisonedAppendDoesNotAccessChannel() throws IOException {
        FileChannel channel = writableChannel();
        doThrow(new IOException("force failed")).when(channel).force(true);
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());
        assertThrows(IllegalStateException.class, () -> appender.appendAndFsync(record(1L)));
        clearInvocations(channel);

        assertThrows(IllegalStateException.class, () -> appender.appendAndFsync(record(2L)));

        verifyNoInteractions(channel);
    }

    @Test
    // 동일 channel의 write/force가 append monitor 안에서 직렬화되는지 검증
    void concurrentAppendsAreSerialized() throws Exception {
        FileChannel channel = writableChannel();
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        AtomicInteger writeCount = new AtomicInteger();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        when(channel.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
            int currentWrite = writeCount.incrementAndGet();
            if (currentWrite == 1) {
                firstWriteEntered.countDown();
                assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS));
            }
            return consume(invocation.getArgument(0));
        });
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> appender.appendAndFsync(record(1L)));
            assertTrue(firstWriteEntered.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondCallStarted.countDown();
                appender.appendAndFsync(record(2L));
            });
            assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedOnAppenderMonitor(secondThread.get());
            assertEquals(1, writeCount.get());

            releaseFirstWrite.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2, writeCount.get());
        } finally {
            releaseFirstWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    // 선행 thread poison 이후 monitor 대기 thread의 channel 진입 차단
    void waitingAppendFailsAtPoisonCheckWithoutStartingChannelWrite() throws Exception {
        FileChannel channel = writableChannel();
        IOException writeFailure = new IOException("write failed");
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        when(channel.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
            firstWriteEntered.countDown();
            assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS));
            throw writeFailure;
        });
        AtomicInteger openCount = new AtomicInteger();
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, openCount);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> appender.appendAndFsync(record(1L)));
            assertTrue(firstWriteEntered.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondCallStarted.countDown();
                appender.appendAndFsync(record(2L));
            });
            assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedOnAppenderMonitor(secondThread.get());
            verify(channel, times(1)).write(any(ByteBuffer.class));

            releaseFirstWrite.countDown();
            ExecutionException firstException = assertThrows(
                    ExecutionException.class,
                    () -> first.get(5, TimeUnit.SECONDS)
            );
            ExecutionException secondException = assertThrows(
                    ExecutionException.class,
                    () -> second.get(5, TimeUnit.SECONDS)
            );

            assertSame(writeFailure, firstException.getCause().getCause());
            assertTrue(secondException.getCause().getMessage().contains("poisoned"));
            verify(channel, times(1)).write(any(ByteBuffer.class));
            assertEquals(1, openCount.get());
        } finally {
            releaseFirstWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void newWalFirstAppendUsesForceTrue() throws IOException {
        FileChannel channel = writableChannel();
        FileWalAppender appender = controlledAppender(tempDir.resolve("new.wal"), channel, new AtomicInteger());

        appender.appendAndFsync(record(1L));

        verify(channel).force(true);
    }

    @Test
    void existingWalAppendUsesForceTrue() throws IOException {
        Path wal = tempDir.resolve("existing.wal");
        Files.writeString(wal, "");
        FileChannel channel = writableChannel();
        FileWalAppender appender = controlledAppender(wal, channel, new AtomicInteger());

        appender.appendAndFsync(record(1L));

        verify(channel).force(true);
    }

    @Test
    void secondAppendOnSameWalAlsoUsesForceTrue() throws IOException {
        FileChannel channel = writableChannel();
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());

        appender.appendAndFsync(record(1L));
        appender.appendAndFsync(record(2L));

        verify(channel, times(2)).force(true);
    }

    @Test
    void successfulAppendNeverUsesForceFalse() throws IOException {
        FileChannel channel = writableChannel();
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());

        appender.appendAndFsync(record(1L));

        verify(channel, never()).force(false);
    }

    @Test
    // force(true) 완료 전 append 성공 반환 금지
    void appendDoesNotReturnBeforeForceTrueCompletes() throws Exception {
        FileChannel channel = writableChannel();
        CountDownLatch forceEntered = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        doAnswer(invocation -> {
            forceEntered.countDown();
            assertTrue(releaseForce.await(5, TimeUnit.SECONDS));
            return null;
        }).when(channel).force(true);
        FileWalAppender appender = controlledAppender(tempDir.resolve("active.wal"), channel, new AtomicInteger());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> append = executor.submit(() -> appender.appendAndFsync(record(1L)));
            assertTrue(forceEntered.await(5, TimeUnit.SECONDS));
            assertFalse(append.isDone());

            releaseForce.countDown();
            append.get(5, TimeUnit.SECONDS);
        } finally {
            releaseForce.countDown();
            executor.shutdownNow();
        }
    }

    private FileWalAppender appender(Path activeFile) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(activeFile);
        return new FileWalAppender(properties, new WalRecordJsonCodec(objectMapper));
    }

    private FileWalAppender controlledAppender(
            Path activeFile,
            FileChannel controlledChannel,
            AtomicInteger openCount
    ) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(activeFile);

        return new FileWalAppender(properties, new WalRecordJsonCodec(objectMapper)) {
            @Override
            FileChannel openFileChannel(Path ignored, StandardOpenOption... options) {
                openCount.incrementAndGet();
                return controlledChannel;
            }
        };
    }

    private FileChannel writableChannel() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.size()).thenReturn(0L);
        when(channel.position(anyLong())).thenReturn(channel);
        when(channel.write(any(ByteBuffer.class))).thenAnswer(
                invocation -> consume(invocation.getArgument(0))
        );
        return channel;
    }

    private int consume(ByteBuffer buffer) {
        int remaining = buffer.remaining();
        buffer.position(buffer.limit());
        return remaining;
    }

    private void awaitBlockedOnAppenderMonitor(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (thread.getState() != Thread.State.BLOCKED) {
            throw new AssertionError("Second append did not block on FileWalAppender monitor.");
        }
    }

    private FileWalReplaySource replaySource(Path activeFile) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(activeFile);
        return new FileWalReplaySource(properties, parser);
    }

    private WalRecord record(long eventSeq) {
        return new WalRecord(
                eventSeq,
                7L,
                0,
                3,
                5,
                768,
                1280,
                17,
                LocalDateTime.of(2026, 4, 3, 6, 0, 0, 123_000_000)
        );
    }
}
