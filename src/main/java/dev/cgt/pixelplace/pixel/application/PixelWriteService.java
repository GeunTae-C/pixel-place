package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileMutationResult;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/*
 * write core의 validation, eventSeq, WAL, memory apply 순서를 고정하는 application service
 * WAL append + fsync는 1차 내구성 경계이며 core write 완료에는 memory authoritative state 반영도 필요
 * WAL 또는 memory fatal 뒤에는 not-ready 전환으로 같은 프로세스의 후속 core write 차단
 */
@Service
public class PixelWriteService {

    private final EventSeqManager eventSeqManager;
    private final PixelMeasurement measurement;
    private final WalAppender walAppender;
    private final InMemoryTileBoard inMemoryTileBoard;
    private final ServiceReadiness serviceReadiness;

    public PixelWriteService(
            EventSeqManager eventSeqManager,
            WalAppender walAppender,
            InMemoryTileBoard inMemoryTileBoard,
            ServiceReadiness serviceReadiness,
            PixelMeasurement measurement
    ) {
        this.eventSeqManager = eventSeqManager;
        this.walAppender = walAppender;
        this.inMemoryTileBoard = inMemoryTileBoard;
        this.serviceReadiness = serviceReadiness;
        this.measurement = measurement;
    }

    /*
     * 승인 가능한 픽셀 write 1건의 WAL 1차 내구성과 memory 반영 처리
     * eventSeq 발급, WAL fsync, memory apply 순서가 서로 끼어들면 WAL 순서와 메모리 반영 순서가 달라질 수 있으므로
     * MVP에서는 service 메서드 전체를 직렬화해 승인 순서를 명확히 고정함
     */
    public synchronized PixelWriteResult writePixel(long userId, int x, int y, int color) {
        try {
            return measurement.observe(PixelMeasurement.Operation.core_inside, () -> writeWithinMonitor(userId, x, y, color));
        } catch (Error failure) {
            serviceReadiness.markWriteFailed();
            throw failure;
        }
    }

    private PixelWriteResult writeWithinMonitor(long userId, int x, int y, int color) {
        // monitor 대기 중 앞선 write가 fatal 전환했으면 validation이나 eventSeq 발급 전 차단
        serviceReadiness.requireReady();

        validateWriteRequest(userId, x, y, color);

        long eventSeq;
        WalRecord record;
        try {
            eventSeq = eventSeqManager.allocate();
            record = createWalRecord(eventSeq, userId, x, y, color);
        } catch (RuntimeException failure) {
            serviceReadiness.markWriteFailed();
            throw new IllegalStateException("Write preparation failed; recovery required", failure);
        }

        try {
            walAppender.appendAndFsync(record);
        } catch (RuntimeException exception) {
            // WAL 결과가 불확실한 최초 요청은 내부 실패로 전파하고 후속 요청만 not-ready로 차단
            serviceReadiness.markWriteFailed();
            throw new IllegalStateException(
                    "WAL append/fsync failed. Service was marked not ready and requires recovery. eventSeq="
                            + eventSeq,
                    exception
            );
        }

        TileMutationResult mutationResult;
        try {
            mutationResult = inMemoryTileBoard.applyPixel(x, y, color);
        } catch (RuntimeException exception) {
            // durable WAL record와 memory 상태 불일치에서는 새 write와 새 flush plan을 허용할 수 없음
            serviceReadiness.markWriteFailed();
            throw new IllegalStateException(
                    "WAL fsync succeeded but memory apply failed. "
                            + "Service was marked not ready and requires recovery. eventSeq=" + eventSeq,
                    exception
            );
        }

        return new PixelWriteResult(
                eventSeq,
                mutationResult.key(),
                mutationResult.tileVersion(),
                x,
                y,
                color
        );
    }

    /** caller가 coordinator를 보유한 채 호출. 단건과 같은 monitor·sequence·storage 사용 */
    public synchronized BatchWriteResult writeBatch(BatchWriteGuard batch, Consumer<PixelWriteResult> dirty) {
        return measurement.observe(PixelMeasurement.Operation.batch_core_inside, () -> writeBatchWithinMonitor(batch, dirty));
    }

    private BatchWriteResult writeBatchWithinMonitor(BatchWriteGuard batch, Consumer<PixelWriteResult> dirty) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(dirty, "dirty callback");
        serviceReadiness.requireReady();
        if (!batch.begin()) return new BatchWriteResult(batch.snapshot(), null);
        try {
            List<PixelWriteRequest> requests = batch.requests();
            for (PixelWriteRequest request : requests)
                validateWriteRequest(request.userId(), request.x(), request.y(), request.color());
            eventSeqManager.requireCapacity(requests.size());
            List<WalRecord> records = new ArrayList<>(requests.size());
            for (PixelWriteRequest request : requests)
                records.add(createWalRecord(eventSeqManager.allocate(), request.userId(), request.x(), request.y(), request.color()));
            if (batch.isAborted()) return new BatchWriteResult(batch.snapshot(), null);
            // storage는 전체 JSON line 준비 뒤에만 I/O 시작. 모든 파일 force 전 memory/dirty 금지
            walAppender.appendBatchAndFsync(List.copyOf(records));
            for (int i = 0; i < records.size(); i++) {
                WalRecord record = records.get(i);
                BatchWriteOutcome outcome = batch.complete(i, () -> applyBatchRecord(record, dirty));
                if (outcome.failure() instanceof Error failure) throw failure;
                if (outcome.state() == BatchWriteOutcome.State.UNKNOWN) {
                    // durable WAL에 대응하는 memory를 abort로 건너뛰었으므로 boundary 반환 전 새 capture 차단
                    serviceReadiness.markWriteFailed();
                    return new BatchWriteResult(batch.snapshot(), outcome.failure());
                }
                if (outcome.failure() instanceof MemoryApplyFailure) {
                    serviceReadiness.markWriteFailed();
                    batch.failRemaining(outcome.failure());
                    return new BatchWriteResult(batch.snapshot(), outcome.failure());
                }
            }
            return new BatchWriteResult(batch.snapshot(), null);
        } catch (RuntimeException failure) {
            // 준비·seq·append 실패 뒤 prefix terminal은 보존하고 미확정 요청만 실패 선택
            serviceReadiness.markWriteFailed();
            var wrapped = new IllegalStateException("Batch preparation/WAL failed; recovery required", failure);
            batch.failRemaining(wrapped);
            return new BatchWriteResult(batch.snapshot(), wrapped);
        } catch (Error failure) {
            serviceReadiness.markWriteFailed();
            batch.failRemaining(failure);
            throw failure;
        }
    }

    private BatchWriteOutcome applyBatchRecord(WalRecord record, Consumer<PixelWriteResult> dirty) {
        PixelWriteResult result;
        try {
            TileMutationResult mutation = inMemoryTileBoard.applyPixel(record.x(), record.y(), record.color());
            result = new PixelWriteResult(record.eventSeq(), mutation.key(), mutation.tileVersion(), record.x(), record.y(), record.color());
        } catch (RuntimeException failure) {
            throw new MemoryApplyFailure(failure);
        }
        try {
            dirty.accept(result);
        } catch (RuntimeException failure) {
            // 이 요청만 500. WAL과 memory가 일치하므로 suffix 처리는 계속
            return new BatchWriteOutcome(BatchWriteOutcome.State.FAILED, result,
                    new IllegalStateException("Dirty tile mark failed after successful write. eventSeq=" + result.eventSeq(), failure));
        } catch (Error failure) {
            // raw Error도 이미 반영한 core 결과와 함께 terminal에 보존한 뒤 같은 instance로 재전파
            return new BatchWriteOutcome(BatchWriteOutcome.State.FAILED, result, failure);
        }
        return new BatchWriteOutcome(BatchWriteOutcome.State.SUCCEEDED, result, null);
    }

    /** dirty 실패와 구분하여 suffix memory를 차단하는 core 내부 원인 표식 */
    private static final class MemoryApplyFailure extends IllegalStateException {
        MemoryApplyFailure(RuntimeException cause) { super("WAL fsync succeeded but batch memory apply failed; recovery required", cause); }
    }

    static void validateWriteRequest(long userId, int x, int y, int color) {
        if (userId <= 0) {
            // 유효하지 않은 사용자 write는 승인 이벤트가 아니므로 eventSeq 발급이나 WAL 기록으로 진행하면 안됨
            throw new IllegalArgumentException("userId must be greater than zero. userId=" + userId);
        }
        if (x < 0 || x >= BoardConstants.BOARD_SIZE) {
            // WAL에 보드 밖 좌표가 기록되면 replay가 같은 잘못된 변경을 반복하므로 append 전에 차단함
            throw new IllegalArgumentException("x coordinate is out of board range. x=" + x);
        }
        if (y < 0 || y >= BoardConstants.BOARD_SIZE) {
            // WAL에 보드 밖 좌표가 기록되면 replay가 같은 잘못된 변경을 반복하므로 append 전에 차단함
            throw new IllegalArgumentException("y coordinate is out of board range. y=" + y);
        }
        if (color < 0 || color >= BoardConstants.PALETTE_SIZE) {
            // 256색 팔레트 인덱스 범위 밖 값은 1 byte 저장 모델과 복구 규칙을 깨므로 append 전에 차단함
            throw new IllegalArgumentException("color index is out of palette range. color=" + color);
        }
    }

    private WalRecord createWalRecord(long eventSeq, long userId, int x, int y, int color) {
        int tx = x / BoardConstants.TILE_SIZE;
        int ty = y / BoardConstants.TILE_SIZE;
        return new WalRecord(
                eventSeq,
                userId,
                BoardConstants.Z0_LEVEL,
                tx,
                ty,
                x,
                y,
                color,
                LocalDateTime.now()
        );
    }
}
