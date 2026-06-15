package dev.cgt.pixelplace.pixel.web;

/*
 * POST /api/pixels 요청 body
 * Integer를 사용해 JSON 필드 누락이 primitive 기본값 0으로 조용히 처리되지 않게 함
 */
public record PixelWriteRequest(
        Integer x,
        Integer y,
        Integer color
) {

    /*
     * x는 필수 좌표이므로 누락된 요청은 service의 eventSeq 발급과 WAL append 전에 거부함
     */
    public int requiredX() {
        if (x == null) {
            // 필수 좌표가 없으면 어떤 픽셀 write인지 확정할 수 없으므로 잘못된 요청으로 실패 처리함
            throw new IllegalArgumentException("x is required.");
        }
        return x;
    }

    /*
     * y는 필수 좌표이므로 누락된 요청은 service의 eventSeq 발급과 WAL append 전에 거부함
     */
    public int requiredY() {
        if (y == null) {
            // 필수 좌표가 없으면 어떤 픽셀 write인지 확정할 수 없으므로 잘못된 요청으로 실패 처리함
            throw new IllegalArgumentException("y is required.");
        }
        return y;
    }

    /*
     * color는 필수 팔레트 인덱스이며, 범위 검증은 PixelWriteService의 기존 write 검증에 맡김
     */
    public int requiredColor() {
        if (color == null) {
            // 색상 값 없이 승인하면 WAL replay가 재현할 최종 상태가 없으므로 append 전에 실패 처리함
            throw new IllegalArgumentException("color is required.");
        }
        return color;
    }
}
