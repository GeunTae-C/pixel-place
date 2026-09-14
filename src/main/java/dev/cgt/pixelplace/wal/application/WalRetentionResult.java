package dev.cgt.pixelplace.wal.application;

import java.util.Objects;
import java.util.Optional;

/** 확인된 삭제 수와 비민감 지연 위치만 전달. 실패 syscall의 실제 삭제 여부를 단정하지 않음 */
public record WalRetentionResult(int deletedFiles, Optional<Delay> delay) {
    public WalRetentionResult {
        if (deletedFiles < 0) {
            // 확인된 성공 prefix만 표현 가능
            throw new IllegalArgumentException("Deleted file count must not be negative");
        }
        Objects.requireNonNull(delay, "delay must not be null");
    }

    public static WalRetentionResult completed(int deletedFiles) {
        return new WalRetentionResult(deletedFiles, Optional.empty());
    }

    public static WalRetentionResult delayed(int deletedFiles, long segmentNumber, DelayKind kind) {
        return new WalRetentionResult(deletedFiles, Optional.of(new Delay(segmentNumber, kind)));
    }

    /** 원시 예외·경로·payload 대신 관리 파일 번호와 syscall 실패 종류만 보존 */
    public record Delay(long segmentNumber, DelayKind kind) {
        public Delay {
            if (segmentNumber < 0) {
                throw new IllegalArgumentException("Delayed segment number must not be negative");
            }
            Objects.requireNonNull(kind, "delay kind must not be null");
        }
    }

    /** 개별 delete에서 재시도 가능한 실패만 표현 */
    public enum DelayKind { IO, SECURITY }
}
