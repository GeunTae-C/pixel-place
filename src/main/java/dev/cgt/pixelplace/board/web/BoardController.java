package dev.cgt.pixelplace.board.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * board 초기 렌더링에 필요한 고정 메타데이터를 제공하는 read-only HTTP 진입점
 * 실시간 authoritative tile 상태, WAL, write path와 분리해 클라이언트 부팅 정보만 노출
 * 실제 tile raw bytes 조회는 이후 GET /api/tiles/0/{tx}/{ty}에서 별도로 구현
 */
@RestController
@RequestMapping("/api/board")
public class BoardController {

    /*
     * 클라이언트가 보드 크기와 palette를 알고 첫 화면을 준비하도록 현재 서버의 고정 설정을 반환
     * request body와 application-level 사용자 식별 정보 사용 없음
     * 실제 unauthenticated 접근 가능 여부는 현재 Spring Security filter chain에 따름
     * tile 데이터나 overview 이미지는 포함하지 않음
     */
    @GetMapping
    public ResponseEntity<BoardInfoResponse> getBoardInfo() {
        return ResponseEntity.ok(BoardInfoResponse.current());
    }
}
