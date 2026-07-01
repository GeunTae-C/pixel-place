package dev.cgt.pixelplace.pixel.web;

import dev.cgt.pixelplace.pixel.application.PixelCommandService;
import dev.cgt.pixelplace.pixel.application.PixelCooldownActiveException;
import dev.cgt.pixelplace.pixel.application.PixelCooldownUnavailableException;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import org.springframework.http.HttpStatus;
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
 * WAL 기록, eventSeq 발급, 메모리 타일 반영 순서는 application service 불변식이므로 controller는 직접 처리하지 않음
 */
@RestController
@RequestMapping("/api/pixels")
public class PixelController {

    private final PixelCommandService pixelCommandService;

    public PixelController(PixelCommandService pixelCommandService) {
        this.pixelCommandService = pixelCommandService;
    }

    /*
     * 픽셀 write 요청을 HTTP body와 임시 userId header에서 읽어 command service에 위임
     * 잘못된 요청은 service로 넘기기 전 필수 필드 누락을 먼저 차단해 WAL append로 진행되지 않게 함
     */
    @PostMapping
    public ResponseEntity<PixelWriteResponse> writePixel(
            @RequestHeader("X-User-Id") long userId,
            @RequestBody PixelWriteRequest request
    ) {
        PixelWriteResult result = pixelCommandService.writePixel(
                userId,
                request.requiredX(),
                request.requiredY(),
                request.requiredColor()
        );

        return ResponseEntity.ok(PixelWriteResponse.from(result));
    }

    /*
     * cooldown 활성 상태
     * 승인된 write가 아니므로 eventSeq/WAL/memory path 진입 전 429 응답
     */
    @ExceptionHandler(PixelCooldownActiveException.class)
    public ResponseEntity<Map<String, Object>> handleCooldownActive(PixelCooldownActiveException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "message", ex.getMessage(),
                        "remainingMillis", ex.remainingMillis()
                ));
    }

    /*
     * cooldown 저장소 check 실패
     * 승인 가능 여부가 불명확하므로 write 진행 없이 503 응답
     */
    @ExceptionHandler(PixelCooldownUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleCooldownUnavailable(PixelCooldownUnavailableException ex) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", ex.getMessage()));
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
