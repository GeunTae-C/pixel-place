package dev.cgt.pixelplace.tile.domain;

// memory board, WAL affected set, dirty tracking, DB composite key가 공유하는 tile 식별 값
public record TileKey(int z, int tx, int ty) {
}
