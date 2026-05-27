package dev.cgt.pixelplace.tile.domain;

// 타일 식별자를 값 객체로 분리해 맵 키와 전체 타일 검증에서 같은 기준을 사용한다.
public record TileKey(int z, int tx, int ty) {
}
