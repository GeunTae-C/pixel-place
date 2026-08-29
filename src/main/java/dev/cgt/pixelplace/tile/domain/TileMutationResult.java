package dev.cgt.pixelplace.tile.domain;

/*
 * memory authoritative state 변경 직후의 TileKey와 tileVersion 전달 값
 * PixelWriteService가 command 결과와 dirty mark에 같은 변경 기준을 넘기기 위한 경계
 */
public record TileMutationResult(
        TileKey key,
        long tileVersion
) {
}
