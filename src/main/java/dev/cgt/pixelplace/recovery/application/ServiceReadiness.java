package dev.cgt.pixelplace.recovery.application;

import org.springframework.stereotype.Component;

// recovery 전/후 상태를 명시적으로 분리하기 위한 객체다.
// ready 전환은 부팅 완료 시점을 코드에서 드러내는 최소 신호다.
@Component
public class ServiceReadiness {

    private boolean ready;

    public synchronized boolean isReady() {
        return ready;
    }

    public synchronized void markReady() {
        ready = true;
    }

    public synchronized void markNotReady() {
        ready = false;
    }
}
