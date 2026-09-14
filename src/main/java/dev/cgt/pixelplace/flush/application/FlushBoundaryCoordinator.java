package dev.cgt.pixelplace.flush.application;

import org.springframework.stereotype.Component;

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

    /*
     * callback 전체를 하나의 boundary에서 실행하고 성공·예외·Error 모든 경로에서 lock 해제
     * lock 자체를 외부에 노출하지 않아 획득 순서와 해제 책임 분산 방지
     */
    public <T> T coordinate(Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");

        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
