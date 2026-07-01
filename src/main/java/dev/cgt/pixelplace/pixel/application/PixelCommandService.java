package dev.cgt.pixelplace.pixel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/*
 * pixel write command orchestration service
 * cooldown 정책을 core write 앞뒤에 배치하고 WAL-first write 순서는 PixelWriteService에 보존
 */
@Service
public class PixelCommandService {

    private static final Logger log = LoggerFactory.getLogger(PixelCommandService.class);

    private final PixelCooldown pixelCooldown;
    private final PixelWriteService pixelWriteService;

    public PixelCommandService(PixelCooldown pixelCooldown, PixelWriteService pixelWriteService) {
        this.pixelCooldown = pixelCooldown;
        this.pixelWriteService = pixelWriteService;
    }

    /*
     * 사용자 cooldown 확인 후 승인 가능한 요청만 core write path로 전달
     * cooldown 시작은 WAL fsync와 memory apply 성공 이후에만 수행
     */
    public PixelWriteResult writePixel(long userId, int x, int y, int color) {
        validateUserId(userId);

        pixelCooldown.checkWritable(userId);

        PixelWriteResult result = pixelWriteService.writePixel(userId, x, y, color);

        try {
            pixelCooldown.startCooldown(userId);
        } catch (PixelCooldownUnavailableException exception) {
            /*
             * WAL fsync와 memory apply 이후 실패
             * core write rollback 책임 없음, 운영 경고만 남기는 후처리 실패
             */
            log.warn("Pixel cooldown set failed after successful write. userId={}", userId, exception);
        }

        return result;
    }

    private void validateUserId(long userId) {
        if (userId <= 0) {
            // 유효하지 않은 사용자 write는 cooldown check나 eventSeq 발급 전 차단
            throw new IllegalArgumentException("userId must be greater than zero. userId=" + userId);
        }
    }
}
