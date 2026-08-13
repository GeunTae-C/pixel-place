package dev.cgt.pixelplace.checkpoint.application;

/*
 * flush transaction과 reconciliation probe를 main checkpoint row로 직렬화하는 application port
 * startup recovery의 일반 CheckpointReader를 대체하지 않으며 transaction 내부 fencing만 담당
 */
public interface CheckpointFence {

    /* 현재 transaction에서 main row PESSIMISTIC_WRITE lock 획득 후 exact checkpoint 반환 */
    long lockMainCheckpoint();

    /* expected가 유지될 때만 target으로 전진하는 checkpoint 마지막 application-level write */
    void advanceMainCheckpoint(long expectedLastFlushedEventSeq, long flushTargetEventSeq);
}
