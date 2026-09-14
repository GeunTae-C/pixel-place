package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.application.WalReplayBatch;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 하나의 WAL 파일군의 상태·파일 작업 직렬화 소유자
 * 번호는 파일 순서만 표현하며 eventSeq·DB checkpoint를 대신하지 않음
 * 생성 시 I/O 없이 경로·크기를 고정하고 실제 작업의 실패는 같은 instance의 재시도 차단
 */
@Component
public class SegmentedWalStorage implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SegmentedWalStorage.class);
    private final Path basePath;
    private final long maxSegmentBytes;
    private final WalRecordParser parser;
    private final WalRecordJsonCodec codec;
    private boolean poisoned;
    private boolean closed;
    private FileChannel channel;
    private SegmentFile active;
    private long durableTail;

    public SegmentedWalStorage(WalProperties properties, WalRecordParser parser, WalRecordJsonCodec codec) {
        properties.validate();
        this.basePath = properties.normalizedBasePath();
        this.maxSegmentBytes = properties.getMaxSegmentBytes();
        this.parser = Objects.requireNonNull(parser);
        this.codec = Objects.requireNonNull(codec);
    }

    // namespace 밖 항목은 attributes조차 읽지 않으며 canonical 연속 suffix만 채택
    synchronized List<SegmentFile> inspectFiles() {
        requireUsable();
        try {
            validateActive();
            return inspect();
        } catch (IOException | RuntimeException exception) {
            throw fail(exception);
        } catch (Error error) {
            throw fail(error);
        }
    }

    /** 모든 파일 검증·자원 종료 뒤 checkpoint 초과 record와 실제 전체 tail 반환 */
    public synchronized WalReplayBatch readAfter(long checkpoint) {
        requireUsable();
        if (checkpoint < 0) {
            // 호출 입력 오류는 파일 접근·고장 전환 전에 거부
            throw new IllegalArgumentException("WAL checkpoint must not be negative");
        }
        try {
            validateActive();
            return scan(checkpoint).batch();
        } catch (IOException | RuntimeException exception) {
            throw fail(exception);
        } catch (Error error) {
            throw fail(error);
        }
    }

    /** record 전체 write와 force(true) 성공 이후에만 확인한 size·tail 전진 */
    public synchronized void appendAndFsync(WalRecord record) {
        requireUsable();
        // 정상 개방 상태에서도 순수 입력 실패로 파일 접근하거나 고장 전환하지 않음
        Objects.requireNonNull(record, "record must not be null");
        byte[] bytes = codec.serializeLine(record);
        if (record.eventSeq() <= 0) throw new IllegalArgumentException("WAL eventSeq must be positive");

        ScanResult initial = null;
        if (channel == null) {
            try {
                // scan-only 성공은 writer 초기화가 아님. 실제 첫 기록 직전에 전체 재검증
                initial = scan(0);
            } catch (IOException | RuntimeException | Error failure) {
                throw fail(failure);
            }
        }
        long previousTail = initial == null ? durableTail : initial.batch().walLastEventSeq();
        if (record.eventSeq() <= previousTail) {
            // 잘못된 호출은 정상 파일군을 고장 상태로 만들거나 writer를 열지 않음
            throw new IllegalArgumentException("WAL append eventSeq must exceed durable tail");
        }
        try {
            if (initial != null) adoptWriter(initial);
            validateActive();
            long size = active.size();
            if (size > 0 && (size >= maxSegmentBytes || bytes.length > maxSegmentBytes - size)) {
                rotate();
            }
            channel.position(active.size());
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) {
                    // 진행 없는 write를 재시도하면 lock을 쥔 채 무한 대기할 수 있음
                    throw new IOException("WAL write made no progress");
                }
            }
            channel.force(true);
            active = new SegmentFile(active.number(), active.path(), Math.addExact(active.size(), bytes.length));
            durableTail = record.eventSeq();
        } catch (IOException | RuntimeException | Error failure) {
            throw fail(failure);
        }
    }

    private void adoptWriter(ScanResult initial) throws IOException {
        if (initial.segments().isEmpty()) {
            createDirectories(basePath.getParent());
            // 기존 경로 충돌은 실패. 번호를 우회하거나 기존 파일을 덮어쓰지 않음
            channel = openFileChannel(basePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
            active = new SegmentFile(0, basePath, 0);
        } else {
            SegmentFile candidate = initial.segments().getLast().file();
            channel = openFileChannel(candidate.path(), StandardOpenOption.READ, StandardOpenOption.WRITE);
            active = candidate;
        }
        durableTail = initial.batch().walLastEventSeq();
    }

    private void rotate() throws IOException {
        if (active.number() == Long.MAX_VALUE) {
            // 다음 이름이 없으므로 old close·새 파일 생성 이전에 terminal 실패
            throw new IllegalStateException("WAL segment number exhausted");
        }
        long next = active.number() + 1;
        Path nextPath = segmentPath(next);
        channel.close();
        channel = null;
        channel = openFileChannel(nextPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        // active 게시 전 candidate도 소유하여 empty force 실패 시 반드시 close 시도
        channel.force(true);
        active = new SegmentFile(next, nextPath, 0);
    }

    private void validateActive() throws IOException {
        if (channel == null) return;
        BasicFileAttributes attributes = readAttributes(active.path());
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.size() != active.size() || channel.size() != active.size()) {
            // 열린 handle만 검사하면 unlink된 파일에 계속 쓰거나 외부 크기 변조를 놓칠 수 있음
            throw new IllegalStateException("Adopted WAL active changed. segment=" + active.number());
        }
    }

    /** 단일 Spring 종료 owner. 종료를 먼저 고정해 반복 종료·대기 호출의 재open 차단 */
    @Override
    @PreDestroy
    public synchronized void close() {
        if (closed) return;
        closed = true;
        FileChannel owned = channel;
        channel = null;
        if (owned != null) {
            try {
                owned.close();
            } catch (IOException closeFailure) {
                // 성공 append는 이미 fsync 완료. 비민감 진단만 남기고 종료 유지
                log.warn("WAL storage close failed. kind=IO");
            }
        }
    }

    private ScanResult scan(long checkpoint) throws IOException {
        List<SegmentFile> files = inspect();
        List<SegmentFacts> facts = new ArrayList<>();
        List<WalRecord> records = new ArrayList<>();
        long tail = 0;
        for (SegmentFile file : files) {
            validateNewline(file);
            final long previousTail = tail;
            BufferedReader reader = openReader(file.path());
            SegmentFacts read = using(reader, () -> readRecords(reader, file, checkpoint, previousTail, records));
            facts.add(read);
            if (read.lastEventSeq() > 0) tail = read.lastEventSeq();
        }
        return new ScanResult(List.copyOf(facts), new WalReplayBatch(records, tail));
    }

    private SegmentFacts readRecords(BufferedReader reader, SegmentFile file, long checkpoint,
                                     long previousTail, List<WalRecord> records) throws IOException {
        long tail = previousTail;
        long first = 0;
        long lineNumber = 0;
        String line;
        while (true) {
            try {
                line = reader.readLine();
            } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
                // decoder 예외에는 파일 위치가 없으므로 원인 보존과 별개로 segment·읽기 위치 명시
                throw new IOException("Invalid UTF-8 WAL input. segment=" + file.number()
                        + ", nextLineNumber=" + (lineNumber + 1), invalidUtf8);
            }
            if (line == null) break;
            lineNumber++;
            WalRecord record;
            try {
                record = parser.parseLine(line, lineNumber);
            } catch (RuntimeException invalidRecord) {
                // Jackson의 원문 포함 cause/suppressed를 끊고 위치·분류만 외부로 전달
                throw new IllegalArgumentException("Invalid WAL record. segment=" + file.number()
                        + ", lineNumber=" + lineNumber + ", kind=RECORD_VALIDATION");
            }
            if (record.eventSeq() <= tail) {
                throw new IllegalStateException("WAL eventSeq is not strictly increasing. segment="
                        + file.number() + ", lineNumber=" + lineNumber);
            }
            if (first == 0) first = record.eventSeq();
            tail = record.eventSeq();
            if (tail > checkpoint) records.add(record);
        }
        return new SegmentFacts(file, first, first == 0 ? 0 : tail);
    }

    private void validateNewline(SegmentFile file) throws IOException {
        if (file.size() == 0) return;
        FileChannel temporary = openFileChannel(file.path(), StandardOpenOption.READ);
        using(temporary, () -> {
            ByteBuffer last = ByteBuffer.allocate(1);
            temporary.position(file.size() - 1);
            if (temporary.read(last) != 1 || last.array()[0] != (byte)'\n') {
                // 완전한 JSON도 마지막 newline 없이는 durable record 경계로 채택 금지
                throw new IllegalStateException("WAL is not newline-terminated. segment=" + file.number());
            }
            return null;
        });
    }

    private List<SegmentFile> inspect() throws IOException {
        DirectoryStream<Path> entries;
        try {
            entries = openDirectory(basePath.getParent());
        } catch (NoSuchFileException absentParent) {
            // 명시적 parent 부재만 최초 empty 입력으로 허용. 접근 불가와 구분
            return List.of();
        }
        List<SegmentFile> files = new ArrayList<>();
        String baseName = basePath.getFileName().toString();
        String prefix = baseName + ".seg-";
        using(entries, () -> {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.equals(baseName) && !name.startsWith(prefix)) {
                    continue;
                }
                long number = name.equals(baseName) ? 0 : parseNumber(name, prefix);
                BasicFileAttributes attributes = readAttributes(entry);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    // 관리 이름의 link·directory를 다른 파일로 따라가거나 무시하면 안 됨
                    throw new IllegalStateException("Non-regular WAL segment. segment=" + number);
                }
                files.add(new SegmentFile(number, entry, attributes.size()));
            }
            return null;
        });
        files.sort(Comparator.comparingLong(SegmentFile::number));
        for (int i = 0; i < files.size(); i++) {
            SegmentFile file = files.get(i);
            if (i > 0 && file.number() - files.get(i - 1).number() != 1) {
                // 양의 정렬 번호의 차이로 overflow 없이 중간 유실·별칭 중복 검출
                throw new IllegalStateException("Non-contiguous WAL segments. segment=" + file.number());
            }
            if (file.size() == 0 && (i < files.size() - 1 || files.size() == 1 && file.number() != 0)) {
                throw new IllegalStateException("Empty closed or isolated numbered WAL segment. segment=" + file.number());
            }
        }
        return List.copyOf(files);
    }

    private long parseNumber(String name, String prefix) {
        String suffix = name.substring(prefix.length());
        if (!suffix.matches("[0-9]{19}")) {
            throw new IllegalStateException("Non-canonical WAL segment name");
        }
        long number;
        try {
            number = Long.parseLong(suffix);
        } catch (NumberFormatException invalidNumber) {
            throw new IllegalStateException("WAL segment number exceeds range");
        }
        if (number <= 0 || !segmentPath(number).getFileName().toString().equals(name)) {
            throw new IllegalStateException("Non-canonical WAL segment number");
        }
        return number;
    }

    Path segmentPath(long number) {
        return number == 0 ? basePath : basePath.resolveSibling(
                basePath.getFileName() + ".seg-" + String.format(Locale.ROOT, "%019d", number));
    }

    private void requireUsable() {
        if (closed) throw new IllegalStateException("WAL storage is closed");
        if (poisoned) {
            throw new IllegalStateException("WAL storage is poisoned; restart recovery required");
        }
    }

    private RuntimeException fail(Throwable failure) {
        poisoned = true;
        FileChannel owned = channel;
        channel = null;
        if (owned != null) {
            try {
                owned.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                failure = combine(failure, closeFailure);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("WAL storage I/O failed", failure);
    }

    // try-with-resources의 suppressed 규칙과 달리 후속 close Error를 우선해야 하는 WAL 경계
    private static <T> T using(Closeable resource, IoSupplier<T> action) throws IOException {
        T result = null;
        Throwable failure = null;
        try {
            result = action.get();
        } catch (IOException | RuntimeException | Error problem) {
            failure = problem;
        }
        try {
            resource.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
            failure = combine(failure, closeFailure);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof IOException io) throw io;
        return result;
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        Throwable primary = first instanceof Error || !(next instanceof Error) ? first : next;
        Throwable secondary = primary == first ? next : first;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == secondary) return primary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws IOException; }

    // 실제 filesystem 연산 경계를 유지하는 package-private 실패 주입 seam
    DirectoryStream<Path> openDirectory(Path parent) throws IOException {
        return Files.newDirectoryStream(parent);
    }

    BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    FileChannel openFileChannel(Path path, StandardOpenOption... options) throws IOException {
        return FileChannel.open(path, options);
    }

    BufferedReader openReader(Path path) throws IOException {
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }

    void createDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
    }

    record SegmentFile(long number, Path path, long size) { }
    private record SegmentFacts(SegmentFile file, long firstEventSeq, long lastEventSeq) { }
    private record ScanResult(List<SegmentFacts> segments, WalReplayBatch batch) { }
}
