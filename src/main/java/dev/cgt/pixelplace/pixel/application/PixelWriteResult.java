package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

/*
 * 승인된 pixel write 결과를 이후 HTTP 응답, WebSocket broadcast, cache invalidation 연결 단계에 전달하는 값 객체
 * eventSeq는 전역 이벤트 순서이고, tileVersion은 변경된 타일의 버전이므로 서로 섞지 않음
 */
public record PixelWriteResult(
        long eventSeq,
        TileKey tileKey,
        long tileVersion,
        int x,
        int y,
        int color
) {
}
