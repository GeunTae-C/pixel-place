package dev.cgt.pixelplace.pixel.application;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import static dev.cgt.pixelplace.measurement.PixelMeasurement.Operation.*;
import dev.cgt.pixelplace.recovery.application.ServiceReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/*
 * pixel write command orchestration과 HTTP accepted 전 후처리 경계 담당
 * 현재 HTTP accepted는 core write와 dirty mark 성공까지이며 DB flush 완료를 요구하지 않음
 * cooldown start와 broadcast 실패는 완료 write를 취소하지 않으며 WAL-first 순서는 PixelWriteService 책임
 */
@Service
public class PixelCommandService {

    private static final Logger log = LoggerFactory.getLogger(PixelCommandService.class);

    private final PixelCooldown pixelCooldown;
    private final PixelMeasurement measurement;
    private final PixelUserWriteGate userWriteGate;
    private final PixelWriteExecutor writeExecutor;
    private final PixelBroadcastService pixelBroadcastService;
    private final ServiceReadiness serviceReadiness;

    public PixelCommandService(
            PixelCooldown pixelCooldown,
            PixelWriteExecutor writeExecutor,
            PixelBroadcastService pixelBroadcastService,
            ServiceReadiness serviceReadiness,
            PixelUserWriteGate userWriteGate,
            PixelMeasurement measurement
    ) {
        this.pixelCooldown = pixelCooldown;
        this.writeExecutor = writeExecutor;
        this.pixelBroadcastService = pixelBroadcastService;
        this.serviceReadiness = serviceReadiness;
        this.userWriteGate = userWriteGate;
        this.measurement = measurement;
    }

    /*
     * 이미 not-ready인 요청을 application validation과 Redis 접근 전에 차단한 뒤 승인 가능한 요청만 core write로 전달
     * 현재 HTTP accepted는 core write와 dirty mark 성공까지 요구하며 cooldown/broadcast는 완료 write의 후처리
     */
    public PixelWriteResult writePixel(long userId, int x, int y, int color) {
        measurement.commandEntered();
        try {
            return measurement.observe(command, () -> executeCommand(userId, x, y, color));
        } finally {
            // 후처리·예외·raw Error까지 command 점유 수명을 닫아 drain 관측의 조기 완료 방지
            measurement.commandExited();
        }
    }

    private PixelWriteResult executeCommand(long userId, int x, int y, int color) {
        serviceReadiness.requireReady();

        validateUserId(userId);

        PixelWriteResult result = userWriteGate.execute(userId, () -> writeUnderUserGate(userId, x, y, color));

        // 느린 session의 fan-out이 동일 사용자 gate를 점유하지 않게 후처리 경계 분리
        try {
            measurement.observe(broadcast, () -> pixelBroadcastService.broadcast(PixelEventMessage.from(result)));
        } catch (RuntimeException exception) {
            // 완료 write는 전파 실패로 취소하지 않으며 클라이언트 재동기화 대상
            warnAfterWrite("Pixel WebSocket broadcast failed after successful write. eventSeq={}", result.eventSeq(), exception);
        }

        return result;
    }

    private PixelWriteResult writeUnderUserGate(long userId, int x, int y, int color) {
        // gate 대기 중 fatal 전환 가능하므로 Redis 접근 전 readiness 재검사
        serviceReadiness.requireReady();
        measurement.observe(redis_check, () -> pixelCooldown.checkWritable(userId));

        PixelWriteService.validateWriteRequest(userId, x, y, color);
        PixelWriteResult result = writeExecutor.execute(userId, x, y, color);

        try {
            measurement.observe(redis_start, () -> pixelCooldown.startCooldown(userId));
        } catch (PixelCooldownUnavailableException exception) {
            /*
             * WAL fsync와 memory apply 이후 실패
             * core write rollback 책임 없음, 운영 경고만 남기는 후처리 실패
             */
            warnAfterWrite("Pixel cooldown set failed after successful write. userId={}", userId, exception);
        }

        return result;
    }

    private void warnAfterWrite(String message, long id, RuntimeException failure) {
        try {
            log.warn(message, id, failure);
        } catch (RuntimeException ignored) {
            // 통상적인 logging 실패도 완료 write의 성공 의미를 바꾸면 안 됨. raw Error는 전파
        }
    }

    private void validateUserId(long userId) {
        if (userId <= 0) {
            // 유효하지 않은 사용자 write는 cooldown check나 eventSeq 발급 전 차단
            throw new IllegalArgumentException("userId must be greater than zero. userId=" + userId);
        }
    }
}
