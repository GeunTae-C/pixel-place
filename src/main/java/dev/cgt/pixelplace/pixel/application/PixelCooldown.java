package dev.cgt.pixelplace.pixel.application;

/*
 * pixel write cooldown 정책 application port
 * Redis 구현 세부사항을 command orchestration 경계 밖에 두기 위한 추상화
 */
public interface PixelCooldown {

    /*
     * 사용자 write 가능 여부 확인
     * cooldown 활성 상태면 core write path 진입 전 예외로 차단
     */
    void checkWritable(long userId);

    /*
     * 성공한 write 이후 cooldown 시작
     * validation, WAL, memory 실패 경로에서 호출되면 안 되는 후처리
     */
    void startCooldown(long userId);
}
