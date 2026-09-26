package dev.cgt.pixelplace.pixel.web;

import dev.cgt.pixelplace.pixel.application.PixelCommandService;
import dev.cgt.pixelplace.pixel.application.PixelCooldownActiveException;
import dev.cgt.pixelplace.pixel.application.PixelCooldownUnavailableException;
import dev.cgt.pixelplace.pixel.application.PixelWriteResult;
import dev.cgt.pixelplace.pixel.application.PixelWriteBusyException;
import dev.cgt.pixelplace.pixel.application.PixelWriteUnknownException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/*
 * POST /api/pixels HTTP 진입점
 * Access decoder가 검증한 내부 users.id만 application write path에 전달
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
     * 인증된 subject를 한 번 변환한 뒤 기존 body 검증과 command service 호출 경계 유지
     * 잘못된 요청은 service로 넘기기 전 필수 필드 누락을 먼저 차단해 WAL append로 진행되지 않게 함
     */
    @PostMapping
    public ResponseEntity<PixelWriteResponse> writePixel(
            Authentication authentication,
            @RequestBody PixelWriteRequest request
    ) {
        long userId = Long.parseLong(authentication.getName());
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

    /* 처리 불확실성 전달. 미기록·재시도 가능성 또는 cooldown TTL을 만들어내지 않음 */
    @ExceptionHandler(PixelWriteUnknownException.class)
    public ResponseEntity<Map<String, String>> handleWriteUnknown(PixelWriteUnknownException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", ex.getMessage()));
    }

    /* gate/queue 대기 실패는 Redis TTL과 무관한 과부하이므로 503 반환 */
    @ExceptionHandler(PixelWriteBusyException.class)
    public ResponseEntity<Map<String, String>> handleWriteBusy(PixelWriteBusyException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", ex.getMessage()));
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
