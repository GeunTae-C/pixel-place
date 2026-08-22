package dev.cgt.pixelplace.recovery.application;

import dev.cgt.pixelplace.checkpoint.domain.CheckpointSnapshot;
import dev.cgt.pixelplace.tile.application.TileLoadResult;

import java.util.Objects;

/* 단일 recovery capture transaction에서 함께 관측한 checkpoint와 tiles immutable DB view */
public record StartupRecoveryDbView(
        CheckpointSnapshot checkpoint,
        TileLoadResult tileLoadResult
) {

    public StartupRecoveryDbView {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null.");
        Objects.requireNonNull(tileLoadResult, "tileLoadResult must not be null.");
    }
}
