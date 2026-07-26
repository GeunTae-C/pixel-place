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

### 현재 단계
- 현재 application-level 사용자 식별은 임시 `X-User-Id` header를 사용한다.
- 이는 실제 인증이 아니며, 최종 카카오 OAuth2 + 서비스 JWT 구현 전의 개발용 식별 방식이다.
- 현재 Spring Security filter의 실제 동작은 configuration과 실행 결과를 별도로 확인한다.

### 최종 로그인 진입점
- 사용자 로그인은 **카카오 OAuth2 Authorization Code 방식만 사용**한다.
- 자체 아이디/비밀번호 회원가입·로그인은 별도 설계 없이 추가하지 않는다.

### 최종 서비스 인증 흐름
1. Spring Security OAuth2 Client로 카카오 로그인을 처리한다.
2. 카카오 userinfo에서 `kakaoUserId`를 확인한다.
3. `users` 테이블에서 해당 카카오 계정과 매핑된 내부 사용자를 조회하거나 신규 생성한다.
4. pixel-place 서비스용 Access JWT와 Refresh Token을 발급한다.
5. Access JWT의 `sub`에는 내부 `users.id`를 문자열로 저장한다.
6. `POST /api/pixels`에서 서비스 Access JWT를 검증한다.
7. JWT principal에서 내부 `userId`를 획득한다.
8. 내부 `userId`로 Redis cooldown을 확인한다.
9. 승인된 픽셀 변경과 WAL record에 내부 `userId`를 사용한다.
10. flush 시 `pixel_events.user_id`에 내부 `users.id`를 저장한다.

### 토큰 책임 구분
- 카카오 OAuth access token: 카카오 로그인과 userinfo 확인에 사용한다.
- 서비스 Access JWT: pixel-place 보호 API 인증에 사용한다.
- 서비스 Refresh Token: 카카오 재로그인 없이 서비스 Access JWT를 재발급하는 데 사용한다.
- Refresh Token의 저장, 만료, rotation, 재사용 탐지, 로그아웃·폐기 정책은 13단계에서 확정한다.

### 금지
- Access JWT `sub`에 `kakaoUserId`를 저장하여 내부 `users.id`와 혼용하지 않는다.
- request body의 `userId`를 인증 정보로 신뢰하지 않는다.
- 카카오 OAuth token을 pixel-place API의 장기 인증 토큰으로 그대로 사용하지 않는다.

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
      "paletteSize": 256,
      "palette": ["#FFFFFF", "#000000", "..."],
      "overviewRefreshSeconds": 10
    }

### 6.2 전체 보기(Overview)
- `GET /api/overview`

#### 역할
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
- 타일 1개 원시 크기: `256 × 256 = 65,536 bytes`

#### 전송 방식
- **gzip/deflate 압축**
- 캐시 헤더 적용

#### 권장 헤더
- `ETag`
- `Cache-Control`
- `X-Tile-Version`

### 타일 버전 정책
- 각 타일은 `tileVersion`을 가진다.
- 해당 타일 내부 픽셀이 변경될 때 `tileVersion`을 증가시킨다.
- 클라이언트는 mismatch 판단 시 해당 타일만 재요청한다.

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
- 인증 확인
- 쿨다운 확인(Redis)
- 저장
- 이벤트 로그 추가
- WebSocket diff broadcast

#### 응답 권장 예시
    {
      "accepted": true,
      "cooldownRemainingMs": 0,
      "eventSeq": 12345,
      "tileVersion": 991
    }

### 6.5 실시간 업데이트(읽기)
- **WebSocket endpoint:** `/ws`

### 방식
- **순수 WebSocket(JSON)**
- STOMP 미사용

### 최소 이벤트 구조 예시
    {
      "type": "pixel",
      "x": 100,
      "y": 200,
      "color": 17,
      "eventSeq": 12345
    }

### 배치 전송 예시
    {
      "type": "pixels",
      "events": [
        { "x": 100, "y": 200, "color": 17, "eventSeq": 12345 },
        { "x": 101, "y": 200, "color": 22, "eventSeq": 12346 }
      ]
    }

### 이벤트 순서 정책
- WebSocket diff 이벤트에는 단조 증가하는 **`eventSeq`** 를 포함한다.
- 클라이언트는 sequence gap이 감지되면 관련 타일을 재동기화한다.

### 선택 최적화
- **50ms 단위 배치 전송** 가능

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
- (선택) refresh token 저장
- (선택) 타일 캐시 / 버전 관리

### WAL
- 승인된 픽셀 write의 **1차 내구성 저장소**
- 포맷은 **JSON Lines**
- 요청마다 `append + fsync`
- 성공 응답의 의미는 **DB 반영 완료가 아니라 WAL append + fsync 성공**

### MySQL + JPA
- 사용자(카카오 계정 매핑)
- DB 후행 타일 상태 저장소(`tiles`)
- 승인 이벤트 영속 로그(`pixel_events`)
- DB flush 완료 지점 저장(`wal_checkpoint`)

### 현재 구조에서 DB의 역할
- 실시간 authoritative state는 DB가 아니라 **메모리 타일 상태**
- DB는 **WAL 뒤를 따라가는 후행 저장소 + 복구 시작점**
- 서버 재시작 시:
  1. DB `tiles` 전체 로드
  2. `wal_checkpoint.last_flushed_event_seq` 확인
  3. DB 반영 완료 마지막 eventSeq 이후 WAL replay
  4. 메모리 상태 복구

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

### MVP 정책
- **비로그인 사용자 요청 금지**
- **로그인 사용자만 픽셀 변경 가능**
- **사용자당 180초 쿨다운**

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

## 12) MVP 구현 순서

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
- 서비스 Access JWT + Refresh Token 발급
- Access JWT `sub`에 내부 `users.id` 저장
- JWT principal의 내부 userId로 cooldown/WAL/pixel_events 연결

### Phase 7. 고도화
- 스냅샷 / 리플레이
- 메트릭 수집
- 부하 테스트
- 필요 시 `z=1`, `z=2` 다운샘플 타일 추가

---

## 13) 백엔드 내부 우선순위

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

## 14) 현재 확정된 선택 사항

- 보드 크기: **8192 × 8192**
- 타일 크기: **256 × 256**
- 총 타일 수: **1024**
- 로딩 방식: **뷰포트 기반 타일 로딩**
- MVP 타일 레벨: **`z=0` 원본만 구현**
- 전체 보기: **별도 overview 모드 제공**
- overview 갱신 주기: **10초**
- 팔레트: **256색 고정 팔레트**
- 픽셀 저장 표현: **1 byte 팔레트 인덱스**
- 타일 응답 포맷: **raw bytes + gzip**
- 실시간 방식: **순수 WebSocket(JSON)**
- 실시간 반영 전략: **타일 재요청이 아니라 diff 반영**
- 이벤트 정합성: **eventSeq 사용**
- 타일 정합성: **tileVersion 사용**
- 로그인 방향: **카카오 OAuth2 + JWT(후속)**
- 레이트리밋: **로그인 사용자만, 사용자당 180초 쿨다운**
- 이벤트 로그: **append-only 저장**
- 구현 방식: **vertical slice**
- 다운샘플 타일(`z=1`, `z=2`): **후속 최적화**

---

## 15) 고정 결론(한 줄)
- **8192×8192 보드, 256×256 타일, 뷰포트 기반 z=0 원본 타일 로딩, 별도 overview 모드, 256색 팔레트 인덱스 저장, raw bytes 타일 API, POST 픽셀 쓰기 API, 순수 WebSocket diff, 사용자당 180초 쿨다운, append-only 이벤트 로그, 카카오 OAuth2 + JWT(후속)**

## 16) DDL

### 현재 기준 DB 역할 요약
- `users`: 카카오 로그인 사용자 식별용 최소 저장소
- `tiles`: DB 후행 타일 상태 저장소
- `pixel_events`: 승인 이벤트 append-only 영속 로그
- `wal_checkpoint`: 마지막 DB flush 완료 지점 저장
  - `last_flushed_event_seq`는 `pixel_events`와 `tiles`에 모두 반영 완료된 마지막 `eventSeq`다.
  - 이 값 이하의 WAL 이벤트는 boot recovery 시 replay하지 않는다.
  - 이 값은 active WAL 파일의 마지막 `eventSeq`인 `walLastEventSeq`와 다르다.
