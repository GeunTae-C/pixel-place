package dev.cgt.pixelplace.flush.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/* 이전 invocation 종료 뒤 간격을 정하는 유일한 자동 flush trigger 설정과 생성 시점 불변식 보유 */
@ConfigurationProperties(prefix = "pixel-place.flush")
public record FlushScheduleProperties(Duration fixedDelay) {

    public FlushScheduleProperties {
        Objects.requireNonNull(fixedDelay, "fixedDelay must not be null");
        if (fixedDelay.isZero() || fixedDelay.isNegative()) {
            throw new IllegalArgumentException("fixedDelay must be positive");
        }

        try {
            fixedDelay.toMillis();
        } catch (ArithmeticException overflow) {
            // Spring scheduling의 millisecond 변환 경계 밖 설정은 기동 전에 거부
            throw new IllegalArgumentException("fixedDelay exceeds millisecond range", overflow);
        }
    }
}
