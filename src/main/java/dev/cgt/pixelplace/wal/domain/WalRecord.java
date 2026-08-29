package dev.cgt.pixelplace.wal.domain;

import java.time.LocalDateTime;

/*
 * WAL에 기록되는 승인 이벤트의 단위
 * write 성공의 1차 내구성 기준이며 다음 경계에서 동일한 eventSeq 계약으로 사용함
 *
 * 1. write path에서 WAL에 append할 이벤트 단위
 * 2. recovery에서 replay할 이벤트 단위
 * 3. flush worker에서 pixel_events에 저장할 이벤트 단위
 */
public record WalRecord(
        // 전체 서비스의 전역 승인 순서, per-tile tileVersion과 별개
				long eventSeq,

        // 현재 application-level 승인 사용자 식별자
				long userId,

        // authoritative 원본 tile level, 현재 MVP는 z=0만 허용
				int z,

        // z level 기준 tile 좌표, 현재 canonical z=0 범위는 0..31
				int tx,
				int ty,

        // 8192x8192 board 기준 절대 pixel 좌표
				int x,
				int y,

        // 고정 256색 palette index, 유효 범위 0..255
				int color,

        // 승인 시각 기록값, recovery·checkpoint 순서는 eventSeq만 사용
				LocalDateTime createdAt
) {
}
