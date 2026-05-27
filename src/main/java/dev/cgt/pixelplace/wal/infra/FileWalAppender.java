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
 * active WAL 파일에 승인 이벤트를 append하고 fsync까지 수행하는 파일 기반 writer다.
 *
 * WAL은 성공한 write의 1차 내구성 원본이므로 append + fsync가 끝나기 전에는 write 성공으로 볼 수 없다.
 * 이 클래스가 실패를 던지면 이후 write path는 메모리 board 반영, Redis cooldown, broadcast 단계로 진행하면 안 된다.
 *
 * append는 하나의 FileChannel을 공유하므로 직렬화되어야 한다.
 * 여러 스레드가 동시에 파일 끝 위치를 계산하면 JSON Lines 순서가 섞이거나 partial line이 생길 수 있기 때문이다.
 *
 * 현재 구현은 active WAL 파일 하나만 다룬다.
 * rotation/segment, group commit, partial line truncate 복구, parent directory fsync는 후속 작업 범위다.
 */
@Primary
@Component
public class FileWalAppender implements WalAppender {

    private final WalProperties walProperties;
    private final WalRecordJsonCodec walRecordJsonCodec;

    private FileChannel channel;
    private boolean firstAppendRequiresMetadataForce;

    public FileWalAppender(WalProperties walProperties, WalRecordJsonCodec walRecordJsonCodec) {
        this.walProperties = walProperties;
        this.walRecordJsonCodec = walRecordJsonCodec;
    }

    /*
     * WalRecord 1건을 active WAL 끝에 append하고 fsync까지 완료한다.
     * fsync 실패는 WAL 실패이며 eventSeq gap은 허용되지만 해당 write는 성공으로 처리하면 안 된다.
     */
    @Override
    public synchronized void appendAndFsync(WalRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        byte[] line = walRecordJsonCodec.serializeLine(record);
        ByteBuffer buffer = ByteBuffer.wrap(line);

        try {
            FileChannel openChannel = channel();
            openChannel.position(openChannel.size());

            while (buffer.hasRemaining()) {
                openChannel.write(buffer);
            }

            boolean metadata = shouldForceMetadata();
            openChannel.force(metadata);
            markForceSucceeded(metadata);
        } catch (IOException e) {
            /*
             * write 중 IOException이 발생하면 파일 끝에 partial line이 남을 수 있다.
             * 현재 정책은 boot recovery에서 잘못된 JSON Lines를 실패로 감지하는 것이며,
             * 여기서 truncate 복구는 구현하지 않는다.
             */
            throw new IllegalStateException("Failed to append and fsync WAL record. path=" + walProperties.getActiveFile(), e);
        }
    }

    /*
     * 새 WAL 파일의 첫 append는 파일 생성 메타데이터까지 내구화해야 하므로 force(true)가 필요하다.
     * 기존 파일에 이어 쓰는 일반 append는 파일 내용 내구화가 목적이라 force(false)를 사용한다.
     */
    boolean shouldForceMetadata() {
        return firstAppendRequiresMetadataForce;
    }

    boolean shouldForceMetadataAfterOpen(boolean newFile) {
        return newFile;
    }

    private void markForceSucceeded(boolean metadata) {
        if (metadata) {
            firstAppendRequiresMetadataForce = false;
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
            // parent directory fsync는 WAL 파일 생성 내구성을 더 강하게 만들지만, 이번 1차 구현 범위에서는 다루지 않는다.
        }

        if (Files.isDirectory(activeFile)) {
            // 디렉터리는 JSON Lines를 append/fsync할 수 있는 active WAL 파일이 아니므로 즉시 실패시킨다.
            throw new IllegalStateException("Active WAL path is a directory. path=" + activeFile);
        }

        boolean newFile = Files.notExists(activeFile);
        channel = FileChannel.open(
                activeFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ
        );
        firstAppendRequiresMetadataForce = shouldForceMetadataAfterOpen(newFile);
        return channel;
    }

    /*
     * Spring 종료 시 열어 둔 FileChannel을 닫는다.
     * close 실패는 이미 appendAndFsync 단위에서 내구성을 보장한 뒤의 정리 실패이므로 종료 흐름을 막지 않는다.
     */
    @PreDestroy
    public synchronized void close() {
        if (channel == null) {
            return;
        }

        try {
            channel.close();
        } catch (IOException ignored) {
            // 종료 정리 실패는 다음 append 성공 여부와 무관하므로 여기서는 추가 복구를 시도하지 않는다.
        }
    }
}
