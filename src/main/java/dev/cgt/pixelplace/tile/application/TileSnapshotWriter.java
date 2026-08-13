package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.flush.application.FlushTileSnapshot;

import java.util.List;

/* immutable flush plan에 capture된 tile bytes와 version만 DB 후행 snapshot으로 저장하는 port */
public interface TileSnapshotWriter {

    /* plan이 정한 bootstrap 전체 또는 initialized target snapshot의 일괄 저장 경계 */
    void writeAll(List<FlushTileSnapshot> tileSnapshots);
}
