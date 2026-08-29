package dev.cgt.pixelplace.checkpoint.application;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;

/*
 * startup recovery와 runtime plan capture가 저장소 구현 없이 main checkpoint를 읽는 port
 * 조회값은 pixel_events와 tiles가 함께 flush 완료된 DB 복구 경계
 */
public interface CheckpointReader {

    /* 누락을 checkpoint 0으로 보정하지 않는 main recovery 기준 조회 */
    CheckpointSnapshot readMainCheckpoint();
}
