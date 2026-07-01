package dev.cgt.pixelplace.pixel.application;

/*
 * cooldown 저장소 접근 실패
 * write 전 check 실패는 승인 여부를 판단할 수 없으므로 HTTP layer에서 503으로 변환
 */
public class PixelCooldownUnavailableException extends RuntimeException {

    public PixelCooldownUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
