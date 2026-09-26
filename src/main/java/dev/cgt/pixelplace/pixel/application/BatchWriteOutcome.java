package dev.cgt.pixelplace.pixel.application;

/** 요청별 선택 결과. UNKNOWN은 WAL 미기록 보장이 아니며 HTTP 매핑은 후속 consumer 책임 */
public record BatchWriteOutcome(State state, PixelWriteResult result, Throwable failure) {
    public enum State { PENDING, SUCCEEDED, FAILED, UNKNOWN }
}
