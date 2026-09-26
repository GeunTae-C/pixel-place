package dev.cgt.pixelplace.recovery.application;

import org.springframework.stereotype.Component;

/*
 * startup recovery 완료 여부와 runtime WAL/memory fatal 상태를 함께 표현하는 서비스 안전 상태
 * ready는 durable WAL tail과 memory authoritative state 일치가 보장되어 보호 대상 요청을 처리할 수 있는 상태
 */
@Component
public class ServiceReadiness {

    // temporary recovery 차단과 irreversible runtime fatal을 구분하는 process-local 안전 상태
    private State state = State.NOT_READY;

    /* 현재 보호 대상 요청과 core write를 처리할 수 있는지 확인 */
    public synchronized boolean isReady() {
        return state == State.READY;
    }

    /*
     * HTTP guard 통과 뒤에도 command/core 경계가 동일한 안전 상태를 재검사하기 위한 fail-fast API
     * not-ready는 요청 데이터 오류가 아니므로 전용 예외로만 표현
     */
    public synchronized void requireReady() {
        if (state != State.READY) {
            throw new ServiceNotReadyException();
        }
    }

    /* temporary/확정 write 실패의 기존 pending은 허용하고 pending 소유권 불명 fatal은 차단 */
    public synchronized void requireNotFatal() {
        if (state == State.FATAL_NOT_READY) {
            throw new ServiceNotReadyException();
        }
    }

    /* startup recovery 전체 성공 뒤에만 보호 대상 요청 처리 허용 */
    public synchronized void markReady() {
        if (state == State.NOT_READY || state == State.READY) {
            state = State.READY;
        }
    }

    /* recovery 시작 같은 temporary not-ready 전환, irreversible fatal 해제 책임은 갖지 않음 */
    public synchronized void markNotReady() {
        if (state == State.NOT_READY || state == State.READY) {
            state = State.NOT_READY;
        }
    }

    /*
     * pending 소유권을 확인할 수 없는 process-local 치명 상태의 단방향 설치
     * collaborator 호출 없는 synchronized 상태 변경 하나로 완료하여 실패 전파보다 먼저 fail-closed 보장
     */
    public synchronized void markFatalNotReady() {
        state = State.FATAL_NOT_READY;
    }

    /* write 불일치는 재시작 전 해제 금지. 이미 정상 capture된 pending의 exact reconciliation은 허용 */
    public synchronized void markWriteFailed() {
        if (state != State.FATAL_NOT_READY) state = State.WRITE_FAILED;
    }

    /* protected request와 pending reconciliation 허용 범위를 구분하는 내부 상태 */
    private enum State {
        /* startup recovery 중이거나 재진입 가능한 임시 차단 */
        NOT_READY,

        /* WAL durable tail과 memory authoritative state 일치 확인 완료 */
        READY,

        /* 새 write/capture 차단. pending 소유권 불명과 달리 기존 정상 plan의 결과 판정 가능 */
        WRITE_FAILED,

        /* pending exact identity 확인 불가 뒤 process restart 전 해제 금지 */
        FATAL_NOT_READY
    }
}
