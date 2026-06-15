package dev.cgt.pixelplace.pixel.web;

import dev.cgt.pixelplace.pixel.application.PixelWriteResult;

/*
 * POST /api/pixels 성공 응답
 * eventSeq는 전역 이벤트 순서이고, tileVersion은 변경된 타일 버전이므로 서로 섞지 않음
 */
public record PixelWriteResponse(
        boolean accepted,
        long eventSeq,
        int x,
        int y,
        int color,
        long tileVersion
) {

    /*
     * application write 결과에서 HTTP 응답에 필요한 값만 노출함
     * tileKey는 내부 정합성 정보이므로 이번 1차 API 응답에는 포함하지 않음
     */
    public static PixelWriteResponse from(PixelWriteResult result) {
        return new PixelWriteResponse(
                true,
                result.eventSeq(),
                result.x(),
                result.y(),
                result.color(),
                result.tileVersion()
        );
    }
}
