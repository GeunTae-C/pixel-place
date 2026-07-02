package dev.cgt.pixelplace.pixel.application;

/*
 * pixel write 성공 이후 변경 이벤트 전파 port
 * WebSocket 구현 세부사항을 command orchestration 바깥에 두기 위한 경계
 */
public interface PixelBroadcastService {

    /*
     * 이미 성공한 write 결과를 클라이언트에게 전파
     * 구현체 실패는 core write rollback 사유가 아님
     */
    void broadcast(PixelEventMessage message);
}
