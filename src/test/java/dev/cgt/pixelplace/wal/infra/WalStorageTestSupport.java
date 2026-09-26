package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.application.WalRecordParser;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.mockito.Mockito.spy;

/** 임시 WAL의 실제 codec·고정밀 시각 fixture. runtime 원본 경로 접근 책임 없음 */
final class WalStorageTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final WalRecordJsonCodec CODEC = new WalRecordJsonCodec(MAPPER);
    static final WalRecordParser PARSER = new WalRecordParser(MAPPER);

    static WalRecord record(long seq) {
        return new WalRecord(seq, 7, 0, 0, 0, 0, 0, 17,
                LocalDateTime.of(2026, 9, 14, 1, 2, 3, 123456789));
    }

    static WalProperties properties(Path path, long maximum) {
        WalProperties properties = new WalProperties();
        properties.setActiveFile(path);
        properties.setMaxSegmentBytes(maximum);
        return properties;
    }

    static SegmentedWalStorage storage(Path path, long maximum) {
        return new SegmentedWalStorage(properties(path, maximum), PARSER, CODEC, dev.cgt.pixelplace.measurement.Measurements.disabled());
    }

    static void records(Path path, long... seqs) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        for(long seq : seqs) bytes.write(CODEC.serializeLine(record(seq)));
        Files.write(path, bytes.toByteArray());
    }

    /** 실제 임시 file channel에만 실패 주입. 소유권과 실제 bytes는 production 경로 사용 */
    static class ControlledStorage extends SegmentedWalStorage {
        final List<FileChannel> writers = new ArrayList<>();
        private final List<FileChannel> physicalWriters = new ArrayList<>();
        ChannelSetup setup = (channel, index) -> { };

        ControlledStorage(Path path, long maximum) { super(properties(path, maximum), PARSER, CODEC, dev.cgt.pixelplace.measurement.Measurements.disabled()); }

        @Override
        FileChannel openFileChannel(Path path, StandardOpenOption... options) throws IOException {
            FileChannel real = super.openFileChannel(path, options);
            if (!Arrays.asList(options).contains(StandardOpenOption.WRITE)) return real;
            physicalWriters.add(real);
            FileChannel wrapped = spy(real);
            writers.add(wrapped);
            setup.apply(wrapped, writers.size()-1);
            return wrapped;
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                // close 실패 대역이 실제 OS handle 정리를 막아도 fixture 종료 시 원래 channel 해제
                for (FileChannel physical : physicalWriters) {
                    try { physical.close(); }
                    catch (IOException failure) { throw new AssertionError("Fixture channel cleanup failed", failure); }
                }
            }
        }
    }

    @FunctionalInterface
    interface ChannelSetup { void apply(FileChannel channel, int index) throws IOException; }
}
