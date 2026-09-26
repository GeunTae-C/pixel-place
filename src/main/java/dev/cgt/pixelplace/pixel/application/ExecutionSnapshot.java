package dev.cgt.pixelplace.pixel.application;

/** 계측 off에서도 유지되는 실제 수명. terminal 선택과 게시 완료, worker 반환을 별도로 관측 */
public record ExecutionSnapshot(int queued, int claimedUnsettled, int workerInFlight,
                                int outstanding, int unpublishedTerminal, State state,
                                int activeCount, boolean workerAlive) {
    public enum State { RUNNING, STOPPING, STOPPED, FAILED }
    public boolean accepting() { return state == State.RUNNING; }
    public boolean closed() { return (state == State.STOPPED || state == State.FAILED)
            && activeCount == 0 && !workerAlive && unpublishedTerminal == 0
            && queued == 0 && claimedUnsettled == 0 && outstanding == 0 && workerInFlight == 0; }
}
