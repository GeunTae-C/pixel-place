package dev.cgt.pixelplace.checkpoint.domain;

// recovery 시작 기준점인 lastFlushedEventSeq를 담는 값 객체
// lastFlushedEventSeq는 DB가 pixel_events와 tiles에 모두 반영 완료한 마지막 eventSeq
public record CheckpointSnapshot(long lastFlushedEventSeq) {
}
