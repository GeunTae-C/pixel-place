package dev.cgt.pixelplace.flush.application;

import org.springframework.stereotype.Component;
import dev.cgt.pixelplace.measurement.PixelMeasurement;
import static dev.cgt.pixelplace.measurement.PixelMeasurement.Operation.*;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/*
 * write core+dirty mark, WAL scan+snapshot capture, 확정 뒤 WAL retention의 JVM boundary 직렬화 담당
 * DB I/O나 flush single-flight 책임은 갖지 않으며 기본 non-fair lock만 사용
 */
@Component
public class FlushBoundaryCoordinator {

    private final ReentrantLock lock = new ReentrantLock();
    private final PixelMeasurement measurement;

    public FlushBoundaryCoordinator(PixelMeasurement measurement) { this.measurement = measurement; }

    /*
     * callback 전체를 하나의 boundary에서 실행하고 성공·예외·Error 모든 경로에서 lock 해제
     * lock 자체를 외부에 노출하지 않아 획득 순서와 해제 책임 분산 방지
     */
    public <T> T coordinate(Supplier<T> action) {
        return coordinate(write_wait, write_held, action);
    }

    public <T> T capture(Supplier<T> action) { return coordinate(capture_wait, capture_held, action); }
    public <T> T retention(Supplier<T> action) { return coordinate(retention_wait, retention_held, action); }

    private <T> T coordinate(PixelMeasurement.Operation waiting, PixelMeasurement.Operation held, Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        var wait = measurement.begin(waiting);
        lock.lock();
        try {
            // 계측 raw Error도 바깥 finally를 거쳐 획득한 lock 반환
            measurement.end(wait, PixelMeasurement.Outcome.success);
            return measurement.observe(held, action);
        } finally {
            lock.unlock();
        }
    }
}
