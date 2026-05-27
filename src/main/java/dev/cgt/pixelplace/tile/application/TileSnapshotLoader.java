package dev.cgt.pixelplace.tile.application;

// z=0 tiles 전체 로드 계약을 추상화한 포트다.
// recovery는 전체 존재 또는 전체 미존재만 허용하므로, 이 계약은 부분 로드를 정상으로 취급하면 안 된다.
public interface TileSnapshotLoader {

    TileLoadResult loadZ0Tiles();
}
