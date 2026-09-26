package dev.cgt.pixelplace.pixel.application;

/* 사용자 gate 대기 실패이며 Redis TTL을 알 수 없으므로 cooldown 거부와 구분 */
public class PixelWriteBusyException extends RuntimeException {
    public static final String MESSAGE = "Pixel write is busy. Please retry later.";

    public PixelWriteBusyException() {
        super(MESSAGE);
    }
}
