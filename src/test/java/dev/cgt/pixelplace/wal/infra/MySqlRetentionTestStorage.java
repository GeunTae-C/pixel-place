package dev.cgt.pixelplace.wal.infra;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** 실제 MySQL 통합 fixture의 임시 WAL에서 delete syscall만 관측·실패 주입. scan/append/정리는 production 구현 사용 */
public final class MySqlRetentionTestStorage extends SegmentedWalStorage {
    private boolean deletionFails;
    private int deleteAttempts;
    private Runnable beforeDelete = () -> { };
    private boolean recordForceFails;

    public MySqlRetentionTestStorage(Path temporaryRoot, Path walPath) {
        this(temporaryRoot, walPath, new TestWalFileDurability());
    }

    public MySqlRetentionTestStorage(Path temporaryRoot, Path walPath, WalFileDurability durability) {
        super(WalStorageTestSupport.properties(requireTemporaryPath(temporaryRoot, walPath), 1L),
                WalStorageTestSupport.PARSER, WalStorageTestSupport.CODEC, dev.cgt.pixelplace.measurement.Measurements.disabled(), durability);
    }

    /** 완성 line이 실제 기록된 뒤 record force에만 실패 주입 */
    public void failRecordForce() { recordForceFails = true; }

    @Override void forceFile(java.nio.channels.FileChannel writer,
                             dev.cgt.pixelplace.measurement.PixelMeasurement.Operation kind) throws IOException {
        if (recordForceFails && kind == dev.cgt.pixelplace.measurement.PixelMeasurement.Operation.record_force) {
            throw new IOException("Test-only complete record force failure");
        }
        super.forceFile(writer, kind);
    }

    /** 테스트가 소유한 임시 경로 밖에서는 실패 주입 거부 */
    private static Path requireTemporaryPath(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root.toAbsolutePath().normalize()) || normalized.equals(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("WAL fault fixture requires a child of its temporary directory");
        }
        return normalized;
    }

    public void failDeletion(boolean enabled) {
        deletionFails = enabled;
    }

    public void beforeDelete(Runnable observation) {
        beforeDelete = Objects.requireNonNull(observation);
    }

    public int deleteAttempts() {
        return deleteAttempts;
    }

    @Override
    void deleteFile(Path path) throws IOException {
        deleteAttempts++;
        beforeDelete.run();
        if (deletionFails) {
            // DB commit 이후 파일 하나의 삭제 지연만 주입, storage 오류 분류는 그대로 유지
            throw new IOException("Test-only WAL deletion failure");
        }
        super.deleteFile(path);
    }
}
