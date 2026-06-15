package dev.cgt.pixelplace.wal.domain;

import java.time.LocalDateTime;

/*
 * WAL에 기록되는 승인 이벤트의 단위
 * write 성공의 1차 내구성 기준이므로, recovery와 flush worker가 같은 eventSeq를 기준으로 재사용함
 *
 * 이 객체는 replay 전용이 아님
 * 앞으로:
 * 1. write path에서 WAL에 append할 이벤트 단위
 * 2. recovery에서 replay할 이벤트 단위
 * 3. flush worker에서 pixel_events에 저장할 이벤트 단위
 * 로 같이 쓰일 수 있음
 */
public record WalRecord(
				// 전체 서비스에서 단조 증가하는 전역 이벤트 순번. tileVersion과는 별개
				long eventSeq,

				long userId,

				// 타일 zoom level. MVP에서는 z=0 원본 타일만 사용함
				int z,

				// z 레벨 기준 타일  좌표. MVP z=0에서는 0..31 범위
				int tx,
				int ty,

				// 8192x8192 보드 기준 절대 픽셀 좌표.
				int x,
				int y,

				// 고정 256색 팔레트 인덱스. 유효 범위는 0..255
				int color,

				// 이벤트 생성 시각. 복구 순서는 createdAt이 아니라 eventSeq를 기준으로 함
				LocalDateTime createdAt
) {
}
