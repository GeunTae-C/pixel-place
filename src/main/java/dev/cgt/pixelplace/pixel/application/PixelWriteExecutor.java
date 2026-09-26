package dev.cgt.pixelplace.pixel.application;

/** command가 core와 dirty 완료를 한 실행 전략으로 호출하는 경계. 후처리는 caller 소유 */
public interface PixelWriteExecutor extends AutoCloseable {
    /** memory와 dirty까지 성공한 결과만 반환. 실패 응답은 WAL 미기록 보장이 아님 */
    PixelWriteResult execute(long userId, int x, int y, int color);

    /** 종료 중인 등록 요청도 실제 core/dirty 반환까지 포함하는 immutable 관측값 */
    ExecutionSnapshot snapshot();

    /** 신규 접수 중단과 등록 요청 소진. 공유 storage 자체의 종료 소유권은 갖지 않음 */
    @Override
    void close();

}
