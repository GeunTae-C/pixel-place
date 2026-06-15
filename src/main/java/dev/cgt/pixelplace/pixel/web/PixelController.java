package dev.cgt.pixelplace.pixel.web;

import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.application.PixelWriteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/*
 * POST /api/pixels HTTP 진입점
 * 아직 JWT 인증 단계가 아니므로 X-User-Id를 임시 사용자 식별자로 받아 application write path에 넘김
 * WAL 기록, eventSeq 발급, 메모리 타일 반영 순서는 PixelWriteService의 불변식이므로 controller는 직접 처리하지 않음
 */
@RestController
@RequestMapping("/api/pixels")
public class PixelController {

    private final PixelWriteService pixelWriteService;

    public PixelController(PixelWriteService pixelWriteService) {
        this.pixelWriteService = pixelWriteService;
    }

    /*
     * 픽셀 write 요청을 HTTP body와 임시 userId header에서 읽어 PixelWriteService에 위임함
     * 잘못된 요청은 service로 넘기기 전 필수 필드 누락을 먼저 차단해 WAL append로 진행되지 않게 함
     */
    @PostMapping
    public ResponseEntity<PixelWriteResponse> writePixel(
            @RequestHeader("X-User-Id") long userId,
            @RequestBody PixelWriteRequest request
    ) {
        PixelWriteResult result = pixelWriteService.writePixel(
                userId,
                request.requiredX(),
                request.requiredY(),
                request.requiredColor()
        );

        return ResponseEntity.ok(PixelWriteResponse.from(result));
    }

    /*
     * 요청 필드 누락, 좌표/색상/userId 검증 실패는 승인된 write가 아니므로 HTTP 400으로 돌려줌
     * WAL fsync 실패 같은 IllegalStateException은 여기서 잡지 않아 서버 내부 실패로 남김
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
