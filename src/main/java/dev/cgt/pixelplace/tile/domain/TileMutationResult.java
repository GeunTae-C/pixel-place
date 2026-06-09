package dev.cgt.pixelplace.tile.domain;

/*
 * 메모리 타일 변경 결과를 write/replay 이후 단계에 전달하는 값 객체다.
 * 어떤 타일이 바뀌었는지와 변경 후 tileVersion을 함께 반환해,
 * 이후 응답 생성, dirty tile tracking, cache invalidation, broadcast, flush worker가 같은 기준을 사용할 수 있게 한다.
 */
public record TileMutationResult(
        TileKey key,
        long tileVersion
) {
}
