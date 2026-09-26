package dev.cgt.pixelplace.flush.application;

import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.wal.application.WalRetentionResult;
import dev.cgt.pixelplace.wal.application.WalSegmentRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 같은 flush single-flight 안에서 확정 commit 경계만 보관하는 WAL 정리 orchestration
 * DB checkpoint cache·sequence seed가 아니며 새 프로세스에서는 삭제 허가 없이 시작
 */
@Service
@Profile("!stub")
public class FlushWalRetention {
    private static final Logger log = LoggerFactory.getLogger(FlushWalRetention.class);
    private final FlushBoundaryCoordinator coordinator;
    private final PixelMeasurement measurement;
    private final ServiceReadiness readiness;
    private final PendingAmbiguousFlushStore pendingStore;
    private final WalSegmentRetention retention;
    // worker single-flight가 갱신·재시도를 직렬화. 0은 아직 확정 경계가 없음
    private long confirmedCheckpoint;

    public FlushWalRetention(FlushBoundaryCoordinator coordinator, ServiceReadiness readiness,
                             PendingAmbiguousFlushStore pendingStore, WalSegmentRetention retention, PixelMeasurement measurement) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.readiness = Objects.requireNonNull(readiness);
        this.pendingStore = Objects.requireNonNull(pendingStore);
        this.retention = Objects.requireNonNull(retention);
        this.measurement = measurement;
    }

    /** executor commit 또는 exact pending clear 성공 뒤에만 호출. 지연 전 허가를 먼저 보관 */
    public void onCommitConfirmed(long checkpoint) {
        if (checkpoint <= 0 || checkpoint < confirmedCheckpoint) {
            // 감소·잘못된 확정값은 내부 계약 위반이며 기존 commit을 rollback으로 바꾸지 않음
            throw fatal(new IllegalArgumentException("Confirmed retention checkpoint must be positive and nondecreasing"));
        }
        confirmedCheckpoint = checkpoint;
        retryIfEligible();
    }

    /** 같은 프로세스의 정상 no-op 재시도. readiness와 pending 확인은 coordinator 안에서 수행 */
    public void retryIfEligible() {
        if (confirmedCheckpoint == 0) return;
        WalRetentionResult result;
        try {
            result = coordinator.retention(() -> {
                if (!readiness.isReady()) return null;
                var pending = Objects.requireNonNull(pendingStore.current(), "Retention pending lookup returned null");
                if (pending.isPresent()) return null;
                return Objects.requireNonNull(measurement.observe(PixelMeasurement.Operation.retention,
                        () -> retention.deleteCommittedPrefix(confirmedCheckpoint)), "Retention returned null");
            });
        } catch (RuntimeException | Error failure) {
            // 파일 검사·pending 확인 실패를 executor catch 밖에서 fail-closed 처리
            throw fatal(failure);
        }
        if (result != null && result.delay().isPresent()) {
            try {
                warnDelayed(result.delay().orElseThrow());
            } catch (RuntimeException loggingFailure) {
                // 단순 지연 경고 실패로 확정 DB outcome·dirty 소유권을 오염시키지 않음
            }
            // logging Error는 위 RuntimeException 경계에서 삼키지 않고 원래 instance로 전파
        }
    }

    // 비민감 경고의 좁은 실패 주입 경계. 원시 syscall 예외·파일 내용은 전달하지 않음
    void warnDelayed(WalRetentionResult.Delay delay) {
        log.warn("WAL retention delayed. segment={}, kind={}", delay.segmentNumber(), delay.kind());
    }

    private RuntimeException fatal(Throwable first) {
        try {
            readiness.markFatalNotReady();
        } catch (RuntimeException | Error later) {
            Throwable primary = first instanceof Error || !(later instanceof Error) ? first : later;
            Throwable secondary = primary == first ? later : first;
            if (primary != secondary && java.util.Arrays.stream(primary.getSuppressed()).noneMatch(item -> item == secondary)) {
                primary.addSuppressed(secondary);
            }
            first = primary;
        }
        if (first instanceof Error error) throw error;
        return (RuntimeException) first;
    }
}
