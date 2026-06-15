package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.common.constant.BoardConstants;
import dev.cgt.pixelplace.tile.domain.InMemoryTileBoard;
import dev.cgt.pixelplace.tile.domain.TileMutationResult;
import dev.cgt.pixelplace.wal.application.WalAppender;
import dev.cgt.pixelplace.wal.domain.WalRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/*
 * HTTP controller가 붙기 전 write path의 핵심 순서를 고정하는 application service
 * write 성공의 1차 내구성 기준은 DB가 아니라 WAL append + fsync 성공이며,
 * 메모리 authoritative state는 WAL fsync가 성공한 뒤에만 변경함
 */
@Service
public class PixelWriteService {

    private final EventSeqManager eventSeqManager;
    private final WalAppender walAppender;
    private final InMemoryTileBoard inMemoryTileBoard;

    public PixelWriteService(
            EventSeqManager eventSeqManager,
            WalAppender walAppender,
            InMemoryTileBoard inMemoryTileBoard
    ) {
        this.eventSeqManager = eventSeqManager;
        this.walAppender = walAppender;
        this.inMemoryTileBoard = inMemoryTileBoard;
    }

    /*
     * 승인된 픽셀 write 1건을 처리함
     * eventSeq 발급, WAL fsync, memory apply 순서가 서로 끼어들면 WAL 순서와 메모리 반영 순서가 달라질 수 있으므로
     * MVP에서는 service 메서드 전체를 직렬화해 승인 순서를 명확히 고정함
     */
    public synchronized PixelWriteResult writePixel(long userId, int x, int y, int color) {
        validateWriteRequest(userId, x, y, color);

        long eventSeq = eventSeqManager.allocate();
        WalRecord record = createWalRecord(eventSeq, userId, x, y, color);

        walAppender.appendAndFsync(record);

        TileMutationResult mutationResult = inMemoryTileBoard.applyPixel(x, y, color);
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
