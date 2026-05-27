package dev.cgt.pixelplace.checkpoint.application;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;

// recovery가 저장소 구현 세부사항을 모르도록 분리한 포트다.
public interface CheckpointReader {

    CheckpointSnapshot readMainCheckpoint();
}
