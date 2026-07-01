package dev.cgt.pixelplace.pixel.application;

/*
 * 사용자 cooldown 잔여 상태
 * HTTP layer에서 429 Too Many Requests로 변환되는 거절 신호
 */
public class PixelCooldownActiveException extends RuntimeException {

    private final long remainingMillis;

    public PixelCooldownActiveException(long remainingMillis) {
        super("Pixel write cooldown is active.");
        this.remainingMillis = remainingMillis;
    }

    public long remainingMillis() {
        return remainingMillis;
    }
}
