package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileMutationResult;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/*
 * write core의 validation, eventSeq, WAL, memory apply 순서를 고정하는 application service
 * WAL append + fsync는 1차 내구성 경계이며 core write 완료에는 memory authoritative state 반영도 필요
 * WAL 또는 memory fatal 뒤에는 not-ready 전환으로 같은 프로세스의 후속 core write 차단
 */
@Service
public class PixelWriteService {

    private final EventSeqManager eventSeqManager;
    private final WalAppender walAppender;
    private final InMemoryTileBoard inMemoryTileBoard;
    private final ServiceReadiness serviceReadiness;

    public PixelWriteService(
            EventSeqManager eventSeqManager,
            WalAppender walAppender,
            InMemoryTileBoard inMemoryTileBoard,
            ServiceReadiness serviceReadiness
    ) {
        this.eventSeqManager = eventSeqManager;
        this.walAppender = walAppender;
        this.inMemoryTileBoard = inMemoryTileBoard;
        this.serviceReadiness = serviceReadiness;
    }

    /*
     * 승인 가능한 픽셀 write 1건의 WAL 1차 내구성과 memory 반영 처리
     * eventSeq 발급, WAL fsync, memory apply 순서가 서로 끼어들면 WAL 순서와 메모리 반영 순서가 달라질 수 있으므로
     * MVP에서는 service 메서드 전체를 직렬화해 승인 순서를 명확히 고정함
     */
    public synchronized PixelWriteResult writePixel(long userId, int x, int y, int color) {
        // monitor 대기 중 앞선 write가 fatal 전환했으면 validation이나 eventSeq 발급 전 차단
        serviceReadiness.requireReady();

        validateWriteRequest(userId, x, y, color);

        long eventSeq = eventSeqManager.allocate();
        WalRecord record = createWalRecord(eventSeq, userId, x, y, color);

        try {
            walAppender.appendAndFsync(record);
        } catch (RuntimeException exception) {
            // WAL 결과가 불확실한 최초 요청은 내부 실패로 전파하고 후속 요청만 not-ready로 차단
            serviceReadiness.markNotReady();
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
            serviceReadiness.markNotReady();
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

    private void validateWriteRequest(long userId, int x, int y, int color) {
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
