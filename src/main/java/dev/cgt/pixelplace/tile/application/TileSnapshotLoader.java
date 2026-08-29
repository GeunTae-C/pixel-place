package dev.cgt.pixelplace.tile.application;

/* 전체 DB key와 z=0 snapshot을 보정 없이 전달하며 bootstrap 최종 판정 책임은 갖지 않는 recovery port */
public interface TileSnapshotLoader {

    /* 전체 DB key metadata와 z=0 bytes를 보정 없이 같은 recovery view에 전달 */
    TileLoadResult loadZ0Tiles();
}
