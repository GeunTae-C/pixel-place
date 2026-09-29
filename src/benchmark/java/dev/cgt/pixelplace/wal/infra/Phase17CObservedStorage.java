package dev.cgt.pixelplace.wal.infra;

import dev.cgt.benchmark.Phase17CTrace;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.wal.application.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** 실제 storage 알고리즘을 상속하고 물리 channel/삭제 호출만 관측. 잠금·force·close 소유권 변경 없음 */
public class Phase17CObservedStorage extends SegmentedWalStorage {
    private final Phase17CTrace trace;
    private int generated;
    public Phase17CObservedStorage(WalProperties p, WalRecordParser parser, WalRecordJsonCodec codec, PixelMeasurement measurement, WalFileDurability durability, Phase17CTrace trace) {
        super(p, parser, codec, measurement, durability); this.trace = trace;
    }
    @Override FileChannel openFileChannel(Path path, StandardOpenOption... options) throws IOException {
        boolean create = Arrays.asList(options).contains(StandardOpenOption.CREATE_NEW);
        // newline 검사용 READ 채널을 writer 채택/종료로 집계하면 정상 scan이 중복 writer로 오인됨
        boolean writer = Arrays.asList(options).contains(StandardOpenOption.WRITE);
        String op = create ? "file.create" : writer ? "file.adopt" : "file.read"; long id = trace.begin(op, path.toString()); Throwable failure = null;
        try {
            var channel = super.openFileChannel(path, options);
            if (create && ++generated > 128) trace.incomplete();
            return new ObservedChannel(channel, path.toString(), writer ? "file.close" : "file.read.close", trace);
        } catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
        finally { trace.end(id, op, path.toString(), "completed", failure); }
    }
    @Override void forceFile(FileChannel channel, PixelMeasurement.Operation operation) throws IOException {
        String target = channel instanceof ObservedChannel c ? c.target : "unknown";
        long id = trace.begin(operation.name(), target); Throwable failure = null;
        try { super.forceFile(channel, operation); } catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
        finally { trace.end(id, operation.name(), target, "completed", failure); }
    }
    @Override void deleteFile(Path path) throws IOException {
        long id = trace.begin("file.delete", path.toString()); Throwable failure = null;
        try { super.deleteFile(path); } catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
        finally { trace.end(id, "file.delete", path.toString(), "completed", failure); }
    }
    /** 반환한 실제 channel의 close를 빠뜨리지 않고 관측. delegate 외 상태/잠금 취득 없음 */
    private static final class ObservedChannel extends FileChannel {
        private final FileChannel actual; private final String target, closeOperation; private final Phase17CTrace trace;
        ObservedChannel(FileChannel actual, String target, String closeOperation, Phase17CTrace trace) { this.actual = actual; this.target = target; this.closeOperation = closeOperation; this.trace = trace; }
        @Override protected void implCloseChannel() throws IOException {
            long id = trace.begin(closeOperation, target); Throwable failure = null;
            try { actual.close(); } catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
            finally { trace.end(id, closeOperation, target, "completed", failure); }
        }
        @Override public int read(ByteBuffer b) throws IOException { return actual.read(b); }
        @Override public long read(ByteBuffer[] b, int o, int n) throws IOException { return actual.read(b, o, n); }
        @Override public int write(ByteBuffer b) throws IOException { return actual.write(b); }
        @Override public long write(ByteBuffer[] b, int o, int n) throws IOException { return actual.write(b, o, n); }
        @Override public long position() throws IOException { return actual.position(); }
        @Override public FileChannel position(long n) throws IOException { actual.position(n); return this; }
        @Override public long size() throws IOException { return actual.size(); }
        @Override public FileChannel truncate(long n) throws IOException { actual.truncate(n); return this; }
        @Override public void force(boolean metadata) throws IOException { actual.force(metadata); }
        @Override public long transferTo(long p, long n, WritableByteChannel c) throws IOException { return actual.transferTo(p, n, c); }
        @Override public long transferFrom(ReadableByteChannel c, long p, long n) throws IOException { return actual.transferFrom(c, p, n); }
        @Override public int read(ByteBuffer b, long p) throws IOException { return actual.read(b, p); }
        @Override public int write(ByteBuffer b, long p) throws IOException { return actual.write(b, p); }
        @Override public MappedByteBuffer map(MapMode m, long p, long n) throws IOException { return actual.map(m, p, n); }
        @Override public FileLock lock(long p, long n, boolean s) throws IOException { return actual.lock(p, n, s); }
        @Override public FileLock tryLock(long p, long n, boolean s) throws IOException { return actual.tryLock(p, n, s); }
    }
}
