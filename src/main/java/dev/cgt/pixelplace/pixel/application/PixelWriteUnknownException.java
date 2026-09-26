package dev.cgt.pixelplace.pixel.application;

/** 종료 유예 초과로 결과 미확정. WAL 미기록 또는 자동 재시도 가능성을 뜻하지 않음 */
public final class PixelWriteUnknownException extends RuntimeException {
    public PixelWriteUnknownException() { super("Pixel write outcome is unknown."); }
}
