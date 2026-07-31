# pixel-place-specs (r/place 오마주) — 포트폴리오 스펙 v3

## 0) 목표

- 웹에서 **픽셀 보드**를 보여주고(줌/패닝), 사용자가 픽셀을 찍으면 **실시간으로 반영**되는 r/place류 서비스를 구현한다.
- 신입 포트폴리오 관점에서 **대용량/트래픽 설계 요소**를 드러낸다.
    - 타일링
    - 뷰포트 기반 로딩
    - 실시간 diff 전송
    - 레이트리밋
    - 캐시
    - 스냅샷/리플레이 확장 가능성

---

## 1) 보드 / 타일 / 뷰포트 결정

### 보드 크기
- **Board Size:** `8192 × 8192 = 67,108,864`

### 타일 크기
- **Tile Size:** `256 × 256`
- 타일 1개 픽셀 수: `256 × 256 = 65,536`

### 타일 개수
- 한 변 타일 수: `8192 / 256 = 32`
- 총 타일 수: `32 × 32 = 1024`

### 로딩 방식
- **Viewport(줌/패닝) 기반 타일 로딩**
- 클라이언트는 현재 화면에 보이는 영역과 겹치는 타일만 요청한다.
- 픽셀 변경은 타일 전체 재다운로드가 아니라 **diff 이벤트**로 반영한다.

---

## 2) 줌 / 전체 보기 정책

### 기본 정책
- MVP에서는 **`z=0` 원본 타일만 구현**한다.
- 일반 뷰포트는 현재 화면에 필요한 `z=0` 타일만 로딩한다.

### 다운샘플 타일 정책
- `z=1`, `z=2` 다운샘플 타일은 **MVP 범위에서 제외**한다.
- 성능 최적화가 실제로 필요해질 경우 후속 단계에서 추가한다.

### 전체 보기(Overview) 모드
- 일반 줌과 별개로 **전체 보기 버튼**을 제공한다.
- 전체 보기 모드는 보드 전체를 축약한 **overview 이미지**를 보여준다.
- overview는 **탐색용(read-only)** 으로 사용한다.
- 사용자가 overview에서 특정 영역을 클릭하면:
    - 메인 뷰포트를 해당 좌표로 이동
    - 자동으로 작업 뷰로 복귀

### Overview 응답 방식
- `GET /api/overview`
- 응답은 보드 전체를 축약한 이미지
- 권장 크기: `1024 × 1024` 또는 `2048 × 2048`
- MVP에서는 **단일 overview 이미지 1장 제공**

### Overview 갱신 정책
- overview는 실시간 정밀 동기화 대상이 아니라 **탐색용 이미지**다.
- 최신 보드 상태와 **최대 10초 이내 차이**가 있을 수 있다.
- 서버는 dirty 상태를 기준으로 **10초 주기**로 overview를 재생성한다.

---

## 3) 색상 / 팔레트 정책

### 팔레트 방식
- 보드는 **256색 고정 팔레트**를 사용한다.
- 픽셀 데이터는 실제 RGB를 직접 저장하지 않고, **팔레트 인덱스(0~255)** 를 저장한다.

### 저장 방식
- 픽셀 1개 = **1 byte**
- 값 의미:
    - `0` → 팔레트의 0번 색
    - `1` → 팔레트의 1번 색
    - ...
    - `255` → 팔레트의 255번 색

### 장점
- 저장 구조가 단순하다.
- 타일 1개 크기가 명확하다.
- raw bytes 전송 구조와 잘 맞는다.
- 압축 효율 설명이 쉽다.

### 팔레트 전달 방식
- 초기 로딩 시 클라이언트가 색상표를 알아야 하므로, 팔레트 정보는 **`GET /api/board` 응답에 포함**한다.
- 외부 API 응답에서는 색상값을 `#RRGGBB` 형식 문자열 배열로 전달한다.

---

## 4) 인증 / 로그인 방향

### 현재 application-level 사용자 식별
- `POST /api/pixels`는 임시 `X-User-Id` header에서 `userId`를 읽는다.
- `X-User-Id`는 실제 인증 수단이 아니라 개발 단계의 application-level 식별값이다.
- request body는 `x`, `y`, `color`만 가지며 `userId`를 받지 않는다.

### 현재 HTTP security filter 상태
- Spring Security, OAuth2 Client, OAuth2 Resource Server starter가 의존성에 포함되어 있다.
- 현재 명시적인 `SecurityFilterChain`과 `spring.security` 설정은 없다.
- 따라서 HTTP 및 WebSocket handshake 접근은 Spring Security auto-configuration의 기본 filter chain 영향을 받는다.
- controller 단위 `standaloneSetup` 테스트는 filter chain을 포함하지 않으므로 공개 접근의 근거가 아니다.
- `BoardController` 제한 `@WebMvcTest`에서 unauthenticated JSON 요청은 `401`과 HTTP Basic challenge, HTML 요청은 `/login`으로 `302` redirect가 관측되었고, mock 인증 요청은 readiness `503`에 도달했다.
- 위 진단은 Board MVC slice의 기본 Security filter와 readiness 우선순위만 확인한다. 전체 application context, Tile/Pixel endpoint, 실제 `/ws` handshake 결과는 검증하지 않았다.

### 최종 로그인 진입점과 인증 흐름
- 사용자 로그인은 **카카오 OAuth2 Authorization Code 방식만 사용**한다.
- 자체 아이디/비밀번호 회원가입·로그인은 추가하지 않는다.
1. 카카오 userinfo에서 `kakaoUserId`를 확인한다.
2. `users` 테이블에서 대응하는 내부 사용자를 조회하거나 생성한다.
3. pixel-place 서비스용 **Access JWT 하나만** 발급한다.
4. Access JWT의 `sub`에는 내부 `users.id`를 문자열로 저장한다.
5. 보호 API는 `Authorization: Bearer <access-jwt>`를 독립적으로 검증한다.
6. JWT principal의 내부 `userId`를 Redis cooldown, WAL, `pixel_events.user_id`에 사용한다.

### 최종 stateless 정책과 토큰 책임
- 명시적 `SecurityFilterChain`은 13단계에서 구현하며 서버 측 인증 session을 사용하지 않는다.
- 서비스 Refresh Token은 발급·저장하지 않고, Access JWT 만료 시 카카오 OAuth2 로그인을 다시 수행한다.
- 카카오 OAuth access token은 카카오 userinfo 확인에만 사용한다.
- 서비스 Access JWT만 pixel-place 보호 API 인증에 사용한다.
- Access JWT `sub`에 `kakaoUserId`를 저장하거나 request body의 `userId`를 신뢰하지 않는다.
- Access JWT 전달·클라이언트 보관 방식과 WebSocket 인증 정책은 13단계에서 함께 확정한다.

---

## 5) 백엔드(Spring) 종속성 — 확정안

### Spring Initializr 선택
- Spring Web
- WebSocket
- Validation
- Spring Security
- OAuth2 Client
- OAuth2 Resource Server
- Spring Data Redis
- Spring Data JPA
- MySQL Driver
- Actuator
- Lombok(선택)
- DevTools(개발용)

---

## 6) API 설계 — 확정안

### 6.1 보드 메타
- `GET /api/board`

#### 응답 예시
    {
      "boardSize": 8192,
      "tileSize": 256,
      "z": 0,
      "tileCountX": 32,
      "tileCountY": 32,
      "paletteSize": 256,
      "palette": ["#000000", "#800000", "#008000", "..."],
      "overviewRefreshSeconds": 10
    }

#### 현재 구현
- 위 8개 필드를 `BoardInfoResponse`가 반환한다.
- 배열 순서는 production의 실제 palette index 순서다. 예시는 축약 표기이며 실제 응답은 `paletteSize == palette.size() == 256`을 만족한다.
- `palette[15] == "#FFFFFF"`이며 빈 보드의 기본 색상 index는 `15`다.
- controller 자체는 request body나 application-level 사용자 식별값을 사용하지 않는다.
- 제한 MVC security 진단에서는 unauthenticated JSON `401`, HTML `/login` `302`가 관측되었다. 전체 application context의 실제 접근 결과로 확대 해석하지 않는다.

### 6.2 전체 보기(Overview)
- `GET /api/overview`

#### 현재 구현
- controller/service가 아직 없어 현재 호출 가능한 API가 아니다.

#### 최종 MVP 목표
- 보드 전체를 축약한 overview 이미지 반환
- 탐색용 read-only
- 최신 상태와 수 초 차이가 있을 수 있음

### 6.3 타일 로딩(핵심)
- `GET /api/tiles/{z}/{tx}/{ty}`

#### MVP 제약
- MVP에서는 `z=0`만 지원

#### 파라미터
- `z`: 줌 레벨
- `tx`, `ty`: 타일 좌표

#### 응답
- 타일 픽셀 데이터 **raw bytes**
- 포맷: **1 byte/pixel**
- 압축 전 또는 압축 해제 후 크기: `256 × 256 = 65,536 bytes`
- HTTP wire body는 gzip 결과이므로 타일 내용에 따라 길이가 달라진다.

#### 현재 응답 헤더
- `Content-Type: application/octet-stream`
- `Content-Encoding: gzip`
- `X-Tile-Version`: 현재 `InMemoryTileBoard`의 실시간 tileVersion

#### 후속 cache 목표
- `ETag`
- `If-None-Match`
- `Cache-Control`
- `304 Not Modified`

### 타일 버전 정책
- 각 타일은 `tileVersion`을 가진다.
- 해당 타일 내부 픽셀이 변경될 때 `tileVersion`을 증가시킨다.
- 클라이언트는 mismatch 판단 시 해당 타일만 재요청한다.
- `X-Tile-Version`은 실시간 메모리 버전이고, DB `tiles.tile_version`은 마지막 flush snapshot 버전이므로 항상 같지는 않다.
- `tileVersion`은 tile별 버전이고 `eventSeq`는 전체 write의 전역 순서다.

> 타일 포맷은 PNG가 아니라 **raw bytes + gzip** 으로 확정한다.  
> 이유는 256색 팔레트 구조와 잘 맞고, 서버 인코딩 비용 없이 단순하게 설명 가능하기 때문이다.

### 6.4 픽셀 찍기(쓰기)
- `POST /api/pixels`

#### 요청 예시
    {
      "x": 100,
      "y": 200,
      "color": 17
    }

#### 처리
- 입력 검증
    - 좌표 범위 확인
    - 색상 인덱스 범위 확인
- 임시 `X-User-Id` application-level 사용자 식별 확인
- 쿨다운 확인(Redis)
- WAL append + fsync
- 메모리 타일 반영과 dirty mark
- WebSocket diff broadcast

#### 현재 성공 응답 예시
    {
      "accepted": true,
      "eventSeq": 12345,
      "x": 100,
      "y": 200,
      "color": 17,
      "tileVersion": 991
    }

#### 현재 성공 의미
- WAL append + fsync 성공은 write의 1차 내구성 경계다.
- core write 완료는 WAL append + fsync와 memory apply가 모두 성공한 상태다.
- HTTP `200` 성공 응답은 core write와 현재 command 계약상 dirty mark까지 성공한 상태다.
- DB flush 완료는 HTTP 성공 조건이 아니다.
- core write 뒤 cooldown start 또는 WebSocket broadcast가 실패해도 완료 write를 취소하지 않는다.

### 6.5 실시간 업데이트(읽기)
- **WebSocket endpoint:** `/ws`

### 방식
- **순수 WebSocket(JSON)**
- STOMP 미사용

### 현재 단건 server payload
    {
      "type": "pixel",
      "x": 100,
      "y": 200,
      "color": 17,
      "eventSeq": 12345
    }

### 후속 배치 전송 목표 예시
    {
      "type": "pixels",
      "events": [
        { "x": 100, "y": 200, "color": 17, "eventSeq": 12345 },
        { "x": 101, "y": 200, "color": 22, "eventSeq": 12346 }
      ]
    }

### 현재 security/readiness 경계
- WebSocket handler/config 자체에는 application-level 인증 로직이 없다.
- 실제 `/ws` handshake 접근 제한은 현재 Spring Security filter chain에 따른다.
- 현재 제한 security 진단에는 WebSocket config/handler가 포함되지 않아 실제 handshake 응답은 미검증이다.
- MVC `ReadinessGuardInterceptor`는 `/ws` handshake에 직접 적용되지 않는다.
- not-ready 전환 시 기존 WebSocket session을 강제로 종료하지 않는다.
- 별도의 readiness handshake guard와 최종 WebSocket 인증 정책은 현재 미구현이며, 인증 정책은 13단계에서 확정한다.

### 이벤트 순서 정책
- WebSocket diff 이벤트에는 단조 증가하는 **`eventSeq`** 를 포함한다.
- 클라이언트는 sequence gap이 감지되면 관련 타일을 재동기화한다.

### 후속 보강
- 50ms 단위 batch broadcast
- broadcast 순서 보장과 session별 send 직렬화

> 순수 WebSocket(JSON)을 선택한 이유는 메시지 크기가 작고 구현이 단순하기 때문이다.  
> 이후 규모가 커지면 메시지 압축 또는 바이너리 프레임을 후속 최적화로 고려할 수 있다.

---

## 7) 실시간 반영 전략

### 초기 로딩 / 뷰포트 이동
- 클라이언트는 현재 화면에 필요한 타일만 `GET /api/tiles/0/{tx}/{ty}` 로 요청한다.

### 픽셀 변경 반영
- 서버는 WebSocket으로 **변경 픽셀 diff만 전송**한다.
- 클라이언트는 canvas에서 해당 픽셀만 즉시 수정한다.

### 타일 재요청이 필요한 경우
- 처음 접속
- 뷰포트 이동
- WebSocket 재연결 후 정합성 확인 필요 시
- version mismatch
- sequence gap 감지 시
- 주기적 재동기화 필요 시

> 픽셀 1개 변경 시 타일 전체를 다시 받지 않는다.  
> 타일은 기준 상태(base state), WebSocket diff는 실시간 변화(stream) 역할로 분리한다.

---

## 8) 타일 인덱싱 규칙

### 원본(z=0) 기준
- `tx = x / 256`
- `ty = y / 256`

### 타일 내부 좌표
- `lx = x % 256`
- `ly = y % 256`

---

## 9) 저장 / 운영 정책

### Redis
- 사용자 쿨다운 전용
- 키 형식: `cooldown:user:{userId}`
- 승인 성공 시에만 TTL `180초` 설정
- 서비스 Refresh Token은 발급하거나 저장하지 않음

### 타일 버전과 HTTP cache
- 현재: memory `tileVersion` 증가, Pixel 응답 `tileVersion`, Tile 응답 `X-Tile-Version`
- 후속: `ETag`, `If-None-Match`, `Cache-Control`, `304 Not Modified` 조건부 HTTP cache

### WAL
- 승인된 픽셀 write의 **1차 내구성 저장소**
- 포맷은 **JSON Lines**
- 요청마다 `append + fsync`
- WAL append + fsync 성공은 write의 **1차 내구성 경계**
- core write 완료는 WAL append + fsync와 memory apply가 모두 성공한 상태
- 현재 HTTP `200` 성공 응답은 core write와 command 계약상 dirty mark까지 성공한 상태
- DB flush 완료는 HTTP 성공 조건이 아님

### MySQL + JPA
- `tiles`: 현재 schema/entity/recovery snapshot load가 존재하지만 snapshot write는 11단계 구현 범위
- `pixel_events`: 현재 DDL 계약은 존재하지만 WAL record의 DB insert는 11단계 구현 범위
- `wal_checkpoint`: 현재 recovery read 구조가 존재하지만 flush checkpoint update는 11단계 구현 범위
- 11단계에서 `pixel_events` insert, `tiles` snapshot write, `wal_checkpoint` update를 하나의 DB transaction으로 구현
- `users`와 카카오 계정 매핑은 후속 인증 목표

### 현재 구조에서 DB의 역할
- 실시간 authoritative state는 DB가 아니라 **메모리 타일 상태**
- DB는 **WAL 뒤를 따라가는 후행 저장소 + 복구 시작점**
- 서버 재시작 시:
  1. `wal_checkpoint.last_flushed_event_seq` 확인
  2. DB `tiles` 전체 로드 또는 all-white pre-init
  3. active WAL을 끝까지 읽어 `walLastEventSeq` 확인
  4. `lastFlushedEventSeq` 이후 WAL record replay
  5. `lastIssuedEventSeq = max(lastFlushedEventSeq, walLastEventSeq)` 초기화
  6. 복구 완료 후 ready 전환

### 픽셀 이벤트 로그 정책
- 픽셀 이벤트 로그는 **append-only 방식**으로 저장한다.
- 현재 구조에서 `event_seq`는 DB가 생성하지 않고 서버가 직접 발급한다.
- DB flush 시 `pixel_events.event_seq`에 그대로 적재한다.

### 관측
- Actuator
- 후속 메트릭 예시:
  - WebSocket 연결 수
  - 픽셀 업데이트 수 / 초
  - 레이트리밋 히트율

---

## 10) 레이트리밋 / 쿨다운 정책

### 최종 MVP endpoint 정책
- `POST /api/pixels`: 인증된 사용자만 허용하고 사용자당 180초 쿨다운 적용
- Board, Tile, Overview 조회: 최종 `permitAll`
- 현재 Overview API는 미구현
- WebSocket 인증과 handshake 정책: 후속 단계에서 별도 확정
- 현재 Pixel write: 실제 인증 대신 임시 `X-User-Id` 식별값 사용

### 현재 결론
- MVP에서는 **사용자 기준 쿨다운만 우선 적용**
- IP 기반 제한은 필수로 넣지 않는다.
- 필요 시 후속으로 보조 방어선으로 추가한다.

---

## 11) 구현 전략(백/프론트 진행 방식)

### 권장 방식
- **백엔드 코어 먼저**
- 프론트 최소 연결
- 다시 백엔드 기능 확장
- 다시 프론트 연결

즉, **vertical slice 방식**으로 진행한다.

### 권장하지 않는 방식
- 백엔드를 전부 다 만든 뒤 프론트를 나중에 한 번에 붙이기
- 처음부터 백/프론트를 여러 기능 단위로 동시에 크게 벌리기

### 이유
- 각 단계마다 동작 결과를 확인할 수 있다.
- 원인 분리가 쉽다.
- 중간에 멈춰도 데모 가능한 결과물이 남는다.

---

## 12) 목표 MVP 구현 순서

> 아래 항목은 완료 현황이 아니라 목표 순서다. 현재 구현 여부는 API별 현재 구현 설명과 `pixel-place-details.md`의 통합 계약 매트릭스를 따른다.

### Phase 1. 백엔드 최소 코어
- Spring Boot 프로젝트 생성
- 패키지 구조 정리
- application.yml 설정
- MySQL / Redis 연결
- 공통 예외 처리 / 응답 포맷 정리
- `GET /api/board`
- `GET /api/tiles/0/{tx}/{ty}`
- `GET /api/overview`

### Phase 2. 프론트 최소 뷰어
- HTML/CSS/JS 기반 canvas 화면
- viewport(줌/패닝) 구현
- 현재 보이는 타일 요청 / 렌더링
- 타일 캐시 적용
- overview 버튼 연결

### Phase 3. 픽셀 쓰기
- `POST /api/pixels`
- 좌표 / 색상 검증
- 저장 처리
- append-only 이벤트 로그 저장
- 프론트 클릭 → 픽셀 배치 요청 → 성공 시 반영

### Phase 4. 실시간 반영
- 순수 WebSocket(`/ws`) 연결
- diff broadcast 구현
- 클라이언트에서 diff 수신 후 해당 픽셀만 canvas 반영

### Phase 5. 운영 요소 추가
- Redis 쿨다운 / 레이트리밋
- version mismatch 처리
- sequence gap 처리
- 캐시 정책 보강

### Phase 6. 인증 추가
- 카카오 OAuth2 Authorization Code 로그인
- `kakaoUserId`와 내부 `users.id` 매핑
- 짧은 수명의 서비스 Access JWT만 발급
- Access JWT `sub`에 내부 `users.id` 저장
- JWT principal의 내부 userId로 cooldown/WAL/pixel_events 연결
- 서비스 Refresh Token 없이 만료 시 카카오 OAuth2 재로그인

### Phase 7. 고도화
- 스냅샷 / 리플레이
- 메트릭 수집
- 부하 테스트
- 필요 시 `z=1`, `z=2` 다운샘플 타일 추가

---

## 13) 후속 목표를 포함한 백엔드 내부 우선순위

1. `BoardMetaService`
2. `TileReadService`
3. `OverviewService`
4. `PixelWriteService`
5. `PixelEventLogService`
6. `WebSocketBroadcastService`
7. `RateLimitService`
8. `AuthService`
9. `SnapshotService`

---

## 14) 설계상 확정된 선택 사항

- 보드 크기: **8192 × 8192**
- 타일 크기: **256 × 256**
- 총 타일 수: **1024**
- 로딩 방식: **뷰포트 기반 타일 로딩**
- MVP 타일 레벨: **`z=0` 원본만 구현**
- 전체 보기 목표: **별도 overview 모드 제공**(현재 API 미구현)
- overview 갱신 주기: **10초**
- 팔레트: **256색 고정 팔레트**
- 픽셀 저장 표현: **1 byte 팔레트 인덱스**
- 타일 응답 포맷: **raw bytes + gzip**
- 실시간 방식: **순수 WebSocket(JSON)**
- 실시간 반영 전략: **타일 재요청이 아니라 diff 반영**
- 이벤트 정합성: **eventSeq 사용**
- 타일 정합성: **tileVersion 사용**
- 로그인 방향: **카카오 OAuth2 + stateless Access JWT only(후속)**
- 레이트리밋: **로그인 사용자만, 사용자당 180초 쿨다운**
- 이벤트 로그: **append-only 저장**
- 구현 방식: **vertical slice**
- 다운샘플 타일(`z=1`, `z=2`): **후속 최적화**

---

## 15) 고정 결론(한 줄)
- **8192×8192 보드, 256×256 타일, 뷰포트 기반 z=0 원본 타일 로딩, 별도 overview 목표, 256색 팔레트 인덱스 저장, raw bytes 타일 API, POST 픽셀 쓰기 API, 순수 WebSocket diff, 사용자당 180초 쿨다운, append-only 이벤트 로그, 카카오 OAuth2 + stateless Access JWT only(후속)**

## 16) DDL

### 현재 기준 DB 역할 요약
- `tiles`: DB 후행 타일 상태 저장소
- `pixel_events`: 승인 이벤트 append-only 영속 로그
- `wal_checkpoint`: 마지막 DB flush 완료 지점 저장
  - `last_flushed_event_seq`는 `pixel_events`와 `tiles`에 모두 반영 완료된 마지막 `eventSeq`다.
  - 이 값 이하의 WAL 이벤트는 boot recovery 시 replay하지 않는다.
  - 이 값은 active WAL 파일의 마지막 `eventSeq`인 `walLastEventSeq`와 다르다.

### 현재 SQL 계약
- 현재 `pixel_place.sql`에는 위 3개 테이블만 있으며 `users` 테이블과 FK는 없다.
- `pixel_events.event_seq`는 `WalRecord.eventSeq`를 저장하는 non-auto-increment primary key다.
- `pixel_events.user_id`는 현재 임시 `X-User-Id` 값이고 일반 컬럼이다.
- `pixel_events.created_at`은 DB flush 시각이 아니라 WAL record의 원래 생성 시각이다.
- `tiles.tile_version`은 마지막 DB flush snapshot의 버전이다.

### 후속 인증 목표
- `users` 테이블에서 `kakaoUserId`와 내부 `users.id`를 매핑한다.
- Access JWT `sub`와 `pixel_events.user_id`에는 내부 `users.id`를 사용하고 FK를 검토한다.
