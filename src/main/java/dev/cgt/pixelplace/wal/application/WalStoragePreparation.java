package dev.cgt.pixelplace.wal.application;

/** startup에서 검증한 batch의 파일 내용을 memory/seed/READY 전에 내구화하는 경계 */
public interface WalStoragePreparation {
    void prepareForRecovery(WalReplayBatch validatedBatch);
}
