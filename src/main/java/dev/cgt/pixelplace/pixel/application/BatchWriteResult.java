package dev.cgt.pixelplace.pixel.application;

import java.util.List;

/** 입력 순서의 terminal 결과와 batch 중단 원인. 외부 future 게시 책임 없음 */
public record BatchWriteResult(List<BatchWriteOutcome> outcomes, Throwable fatalFailure) {
    public BatchWriteResult { outcomes = List.copyOf(outcomes); }
}
