package dev.cgt.pixelplace.flush.application;

/* flush plan 한 건의 새 physical transaction lifecycle과 결과 분류를 추상화하는 application port */
public interface FlushTransactionExecutor {

    /* commit/rollback 확인 결과와 원래 failure cause를 함께 반환하는 transaction 경계 */
    FlushTransactionResult execute(FlushPlan plan);
}
