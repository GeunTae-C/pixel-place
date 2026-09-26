package dev.cgt.pixelplace.pixel.application;

/** caller가 넘긴 batch 순서를 보존하는 immutable 입력. core 진입 뒤 전체 재검증 */
public record PixelWriteRequest(long userId, int x, int y, int color) { }
