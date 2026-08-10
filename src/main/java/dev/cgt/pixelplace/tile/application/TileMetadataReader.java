package dev.cgt.pixelplace.tile.application;

import dev.cgt.pixelplace.tile.domain.TileKey;

import java.util.List;

/* runtime bootstrap 판정이 tile BLOB 없이 DB 전체 row key만 읽는 application port */
public interface TileMetadataReader {

    /* z 필터 없이 모든 tiles row의 key 조회 */
    List<TileKey> readAllTileKeys();
}
