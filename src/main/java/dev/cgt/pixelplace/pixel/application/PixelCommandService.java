package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import dev.cgt.pixelplace.tile.application.DirtyTileTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/*
 * pixel write command orchestration과 HTTP accepted 전 후처리 경계 담당
 * 현재 HTTP accepted는 core write와 dirty mark 성공까지이며 DB flush 완료를 요구하지 않음
 * cooldown start 실패와 broadcast 실패는 이미 완료된 write를 취소하지 않으며, WAL-first 순서는 PixelWriteService에서 보존된다
 */
@Service
public class PixelCommandService {

    private static final Logger log = LoggerFactory.getLogger(PixelCommandService.class);

    private final PixelCooldown pixelCooldown;
    private final PixelWriteService pixelWriteService;
    private final DirtyTileTracker dirtyTileTracker;
    private final PixelBroadcastService pixelBroadcastService;
    private final ServiceReadiness serviceReadiness;

    public PixelCommandService(
            PixelCooldown pixelCooldown,
            PixelWriteService pixelWriteService,
            DirtyTileTracker dirtyTileTracker,
            PixelBroadcastService pixelBroadcastService,
            ServiceReadiness serviceReadiness
    ) {
        this.pixelCooldown = pixelCooldown;
        this.pixelWriteService = pixelWriteService;
        this.dirtyTileTracker = dirtyTileTracker;
        this.pixelBroadcastService = pixelBroadcastService;
        this.serviceReadiness = serviceReadiness;
    }

    /*
     * 이미 not-ready인 요청을 application validation과 Redis 접근 전에 차단한 뒤 승인 가능한 요청만 core write로 전달
     * 현재 HTTP accepted는 core write와 dirty mark 성공까지 요구하며 cooldown/broadcast는 완료 write의 후처리
     */
    public PixelWriteResult writePixel(long userId, int x, int y, int color) {
        serviceReadiness.requireReady();

        validateUserId(userId);

        pixelCooldown.checkWritable(userId);

        PixelWriteResult result = pixelWriteService.writePixel(userId, x, y, color);

        markDirty(result);

        try {
            pixelCooldown.startCooldown(userId);
        } catch (PixelCooldownUnavailableException exception) {
            /*
             * WAL fsync와 memory apply 이후 실패
             * core write rollback 책임 없음, 운영 경고만 남기는 후처리 실패
             */
            log.warn("Pixel cooldown set failed after successful write. userId={}", userId, exception);
        }

        try {
            pixelBroadcastService.broadcast(PixelEventMessage.from(result));
        } catch (RuntimeException exception) {
            /*
             * write 성공 이후 전파 실패
             * WAL/memory/cooldown rollback 사유 아님, 클라이언트 재동기화 대상으로 남김
             */
            log.warn("Pixel WebSocket broadcast failed after successful write. eventSeq={}", result.eventSeq(), exception);
        }

        return result;
    }

    private void markDirty(PixelWriteResult result) {
        try {
            dirtyTileTracker.markDirty(
                    result.tileKey(),
                    result.eventSeq(),
                    result.tileVersion()
            );
        } catch (RuntimeException exception) {
            /*
             * WAL fsync와 memory apply 이후 보조 dirty 추적 실패
             * flush source of truth는 WAL이지만 현재 HTTP accepted 계약에는 dirty mark 성공도 필요
             */
            throw new IllegalStateException(
                    "Dirty tile mark failed after successful write. eventSeq=" + result.eventSeq(),
                    exception
            );
        }
    }

    private void validateUserId(long userId) {
        if (userId <= 0) {
            // 유효하지 않은 사용자 write는 cooldown check나 eventSeq 발급 전 차단
            throw new IllegalArgumentException("userId must be greater than zero. userId=" + userId);
        }
    }
}
