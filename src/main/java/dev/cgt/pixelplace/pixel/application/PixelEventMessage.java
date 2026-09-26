package dev.cgt.pixelplace.pixel.application;

/*
 * WebSocket 단건 pixel event message
 * eventSeq는 전역 write 순서이며 tileVersion과 섞으면 안 됨
 */
public record PixelEventMessage(
        String type,
        int x,
        int y,
        int color,
        long eventSeq,
        long tileVersion
) {

    /* 해당 mutation 직후의 version 전달. broadcast 시점의 board 재조회 금지 */
    public static PixelEventMessage from(PixelWriteResult result) {
        return new PixelEventMessage(
                "pixel",
                result.x(),
                result.y(),
                result.color(),
                result.eventSeq(),
                result.tileVersion()
        );
    }
}
