package dev.cgt.pixelplace.recovery.application;

import org.springframework.stereotype.Component;

/*
 * startup recovery 완료 여부와 runtime WAL/memory fatal 상태를 함께 표현하는 서비스 안전 상태
 * ready는 durable WAL tail과 memory authoritative state 일치가 보장되어 보호 대상 요청을 처리할 수 있는 상태
 */
@Component
public class ServiceReadiness {

    private boolean ready;

    /* 현재 보호 대상 요청과 core write를 처리할 수 있는지 확인 */
    public synchronized boolean isReady() {
        return ready;
    }

    /*
     * HTTP guard 통과 뒤에도 command/core 경계가 동일한 안전 상태를 재검사하기 위한 fail-fast API
     * not-ready는 요청 데이터 오류가 아니므로 전용 예외로만 표현
     */
    public synchronized void requireReady() {
        if (!ready) {
            throw new ServiceNotReadyException();
        }
    }

    /* startup recovery 전체 성공 뒤에만 보호 대상 요청 처리 허용 */
    public synchronized void markReady() {
        ready = true;
    }

    /* recovery 시작 또는 runtime fatal 발생 시 보호 API와 후속 core write 차단 */
    public synchronized void markNotReady() {
        ready = false;
    }
}
