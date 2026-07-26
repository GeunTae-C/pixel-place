package dev.cgt.pixelplace.wal.infra;

import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.application.WalRecordJsonCodec;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/*
 * active WAL 파일에 승인 이벤트를 append하고 fsync까지 수행하는 파일 기반 writer
 *
 * WAL은 승인 write의 1차 내구성 원본이며 모든 성공 append는 FileChannel.force(true) 완료를 경계로 삼음
 * 이 클래스가 실패를 던지면 이후 write path는 메모리 board 반영, Redis cooldown, broadcast 단계로 진행하면 안됨
 * write 또는 force의 I/O 결과가 불확실해지면 poison 상태로 전환하여 같은 프로세스의 추가 append를 차단함
 *
 * append는 하나의 FileChannel을 공유하므로 직렬화되어야 함
 * 여러 스레드가 동시에 파일 끝 위치를 계산하면 JSON Lines 순서가 섞이거나 partial line이 생길 수 있기 때문
 *
 * 현재 구현은 active WAL 파일 하나만 다룸
 * rotation/segment, group commit, partial line truncate 복구, parent directory fsync는 후속 작업 범위
 */
@Primary
@Component
public class FileWalAppender implements WalAppender {

    private final WalProperties walProperties;
    private final WalRecordJsonCodec walRecordJsonCodec;

    private FileChannel channel;
    private boolean poisoned;
    private IOException poisonCause;

    public FileWalAppender(WalProperties walProperties, WalRecordJsonCodec walRecordJsonCodec) {
        this.walProperties = walProperties;
        this.walRecordJsonCodec = walRecordJsonCodec;
    }

    /*
     * WalRecord 1건을 active WAL 끝에 append하고 force(true)까지 완료함
     * I/O 실패 뒤 partial tail 여부를 확정할 수 없으므로 poison 전환 후 재시작 recovery 전까지 추가 append 차단
     */
    @Override
    public synchronized void appendAndFsync(WalRecord record) {
        ensureNotPoisoned();
        Objects.requireNonNull(record, "record must not be null");

        byte[] line = walRecordJsonCodec.serializeLine(record);
        ByteBuffer buffer = ByteBuffer.wrap(line);

        try {
            FileChannel openChannel = channel();
            openChannel.position(openChannel.size());

            while (buffer.hasRemaining()) {
                openChannel.write(buffer);
            }

            // 파일 내용과 파일 metadata force 완료가 현재 durable WAL tail의 application-level 경계
            openChannel.force(true);
        } catch (IOException e) {
            poisoned = true;
            poisonCause = e;
            closeChannelAfterFailure(e);

            // 실패한 마지막 record의 존재 여부가 불명확하므로 자동 복구 없이 fail-stop 전환
            throw new IllegalStateException(
                    "Failed to append and fsync WAL record. WAL appender is poisoned and requires restart recovery. path="
                            + walProperties.getActiveFile(),
                    e
            );
        }
    }

    private void ensureNotPoisoned() {
        if (poisoned) {
            // partial WAL 뒤에 새 JSON을 이어 쓰면 active WAL 전체 복구 경계가 무너질 수 있음
            throw new IllegalStateException(
                    "WAL appender is poisoned. Restart and recovery are required.",
                    poisonCause
            );
        }
    }

    private void closeChannelAfterFailure(IOException originalFailure) {
        if (channel == null) {
            return;
        }

        try {
            channel.close();
        } catch (IOException closeFailure) {
            // close 실패가 최초 write/force 실패 원인을 덮지 않도록 suppressed로 보존
            originalFailure.addSuppressed(closeFailure);
        }
    }

    private FileChannel channel() throws IOException {
        if (channel != null && channel.isOpen()) {
            return channel;
        }

        Path activeFile = walProperties.getActiveFile();
        Path parent = activeFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            // parent directory fsync는 WAL 파일 생성 내구성을 더 강하게 만들지만, 이번 1차 구현 범위에서는 다루지 않음
        }

        if (Files.isDirectory(activeFile)) {
            // 디렉터리는 JSON Lines를 append/fsync할 수 있는 active WAL 파일이 아니므로 즉시 실패시킴
            throw new IllegalStateException("Active WAL path is a directory. path=" + activeFile);
        }

        channel = openFileChannel(
                activeFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ
        );
        return channel;
    }

    // 저수준 write/force/close 실패와 동시성 경계 검증을 위한 package-private test seam
    FileChannel openFileChannel(Path activeFile, StandardOpenOption... options) throws IOException {
        return FileChannel.open(activeFile, options);
    }

    /*
     * Spring 종료 시 열어 둔 FileChannel을 닫음
     * close 실패는 이미 appendAndFsync 단위에서 내구성을 보장한 뒤의 정리 실패이므로 종료 흐름을 막지 않음
     */
    @PreDestroy
    public synchronized void close() {
        if (channel == null) {
            return;
        }

        try {
            channel.close();
        } catch (IOException ignored) {
            // 종료 정리 실패는 다음 append 성공 여부와 무관하므로 여기서는 추가 복구를 시도하지 않음
        }
    }
}
