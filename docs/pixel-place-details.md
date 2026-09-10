# pixel-place-details — MVP API 상세 스펙 확정본

## 0) 공통 원칙

### 현재 application-level 사용자 식별
- POST /api/pixels는 필수 X-User-Id header를 long으로 변환해 userId로 사용한다.
- X-User-Id는 인증 수단이 아니라 개발 단계의 임시 사용자 식별자다.
- request body에는 userId가 없으며, 현재 body 필드는 x, y, color뿐이다.

### 현재 HTTP security filter 상태
- build.gradle에는 Spring Security starter, OAuth2 Client, OAuth2 Resource Server dependency가 있다.
- production source에는 명시적 SecurityFilterChain, HttpSecurity, @EnableWebSecurity, authorizeHttpRequests 설정이 없고 application.yml에도 spring.security 설정이 없다.
- 정적 구성상 Spring Boot security auto-configuration이 적용될 수 있으며, 기본 chain은 모든 요청 인증, form login, HTTP Basic을 구성한다. 이것은 후속 최종 API 정책이 아니라 현재 dependency와 auto-configuration의 결과다.
- BoardController와 OverviewController 제한 slice에서 실제 filter chain을 포함해 확인한 결과는 다음과 같다.
  - unauthenticated JSON 요청: 401, Location 없음, WWW-Authenticate: Basic realm="Realm"
  - unauthenticated HTML 요청: 302, Location: /login, WWW-Authenticate 없음
  - @WithMockUser 요청: security filter를 통과한 뒤 readiness guard가 503을 반환하며 Location과 WWW-Authenticate는 모두 없음
- 위 실행 결과는 BoardController와 OverviewController 제한 slice의 filter/readiness 우선순위만 증명한다. 전체 application, Tile, Pixel, 실제 /ws handshake 응답으로 일반화하지 않는다.
- 전체 application의 unauthenticated 응답과 실제 /ws handshake 접근 결과는 아직 filter-chain 포함 실행으로 검증하지 않았다.
- Spring Security filter는 MVC interceptor보다 앞선 servlet filter 계층에 있다. 인증 단계에서 응답이 끝나면 readiness interceptor나 X-User-Id binding까지 도달하지 않으며, Board와 Overview 제한 slice에서 이 우선순위를 확인했다.

### 최종 인증 목표
- 로그인 진입점은 카카오 OAuth2 Authorization Code만 제공하고 자체 아이디/비밀번호 로그인을 추가하지 않는다.
- kakaoUserId를 내부 users.id와 매핑하고, pixel-place 서비스 Access JWT 하나만 발급한다.
- Access JWT sub에는 내부 users.id를 문자열로 저장한다.
- 보호 API는 Authorization: Bearer 요청의 JWT principal에서 userId를 얻는다.
- 서버 측 인증 session, 서비스 Refresh Token, token refresh/revoke/denylist를 사용하지 않는다.
- Access JWT 만료 시 카카오 OAuth2 로그인을 다시 수행한다.
- 최종 명시적 SecurityFilterChain의 endpoint 정책은 Board/Tile/Overview permitAll, Pixel authenticated로 구현한다.
- 최종 WebSocket 인증과 handshake 제한은 13단계에서 별도로 확정한다.

### 현재와 후속 에러 포맷
- 현재 production 응답은 controller, readiness interceptor, 전역 readiness handler, Spring MVC/Boot 기본 처리에 따라 구조가 서로 다르다.
- 현재 구현에 없는 timestamp/status/error/code/path 공통 필드를 실제 응답처럼 작성하지 않는다.
- 후속 목표는 공통 ErrorResponse, 공통 errorCode, @RestControllerAdvice 기반 통합 JSON 오류 응답이다.

---

## 1) 후속 공통 에러 코드 초안

아래 code는 현재 controller 응답에 공통 적용된 값이 아니라 후속 통합 오류 체계의 후보다.

### 공통
- `INVALID_REQUEST`
- `INTERNAL_SERVER_ERROR`

### 인증
- `UNAUTHORIZED`
- `FORBIDDEN`

### 픽셀 쓰기
- `INVALID_COORDINATE`
- `INVALID_COLOR`
- `COOLDOWN_ACTIVE`
- `PIXEL_WRITE_FAILED`

### 타일 조회
- `INVALID_TILE_COORDINATE`
- `UNSUPPORTED_Z_LEVEL`

---

## 2) 통합 API 계약 매트릭스

현재 production code와 제한 security 진단을 같은 기준으로 대조한 결과다. Board/Overview의 security 관측값을 전체 application, Tile, Pixel 또는 실제 WebSocket handshake 결과로 확대하지 않는다.

| 기능/API | protocol | HTTP method | 실제 route pattern | 현재 지원 범위 | request path variable | request query | request header | request body 또는 client message | 성공 status 또는 handshake 결과 | 실패 status | response body 또는 server payload | response header | Content-Type | Content-Encoding | application-level 사용자 식별 | 현재 security filter 상태 | readiness 적용 여부 | 현재 production 구현 여부 | 문서상 최종 목표 | 현재 구현과 최종 목표의 차이 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Board | HTTP | GET | `/api/board` | 고정 z=0 보드 메타 | 없음 | 없음 | 없음 | 없음 | `200` | readiness `503`; 내부 `500` 계열; 제한 slice unauth JSON `401`, HTML `302` | 8개 `BoardInfoResponse` 필드 JSON | 커스텀 response header 없음 | `application/json` | 없음 | 없음 | 명시적 chain 없음; Board 제한 slice에서 Basic `401`/login `302` 확인 | MVC guard 적용 | 구현 | 최종 `permitAll` | 현재 기본 Security 관측과 최종 공개 계약 불일치; 전체 app 결과 미검증 |
| Tile | HTTP | GET | `/api/tiles/{z}/{tx}/{ty}` | `z=0`, `tx/ty=0..31`; `/api/tiles/0/{tx}/{ty}`는 구체적 호출 | `z`, `tx`, `ty` | 없음 | 없음 | 없음 | `200` | 범위 `400`; readiness `503`; 내부 `500` 계열 | 성공: gzip palette index bytes; 400 및 readiness 503: `message` JSON; 내부 500 body: Spring 기본 처리; Security 오류 body: 실제 Tile endpoint 미검증 | 성공: `X-Tile-Version`; 오류 응답 header는 경로별 상이하며 Security 응답은 실제 Tile endpoint 미검증 | 성공: `application/octet-stream` | 성공: `gzip` | 없음 | 명시적 chain 없음; 실제 endpoint unauth 응답 미검증 | `/api/tiles/**` MVC guard 적용 | 구현 | 최종 `permitAll`; 조건부 cache | 현재 z=0만 지원; ETag/If-None-Match/Cache-Control/304 미구현 |
| Overview | HTTP | GET | `/api/overview` | 인메모리 z=0 보드의 고정 2048×2048 PNG | 없음 | 없음 | 없음 | 없음 | `200` | no-image/readiness `503`; 내부 `500` 계열; 제한 slice unauth JSON `401`, HTML `302` | 성공: 완전한 PNG bytes; no-image/readiness: `message` JSON | 성공: `Cache-Control: no-cache` | 성공: `image/png` | 없음 | 없음 | 명시적 chain 없음; Overview 제한 slice에서 Basic `401`/login `302` 확인 | `/api/overview` MVC guard 적용 | 구현 | 최종 `permitAll` | 현재 기본 Security와 최종 공개 계약 불일치; 전체 app 결과 미검증 |
| Pixel | HTTP | POST | `/api/pixels` | `x/y=0..8191`, `color=0..255`, `userId>0` | 없음 | 없음 | `X-User-Id`, JSON content type | `x`, `y`, `color` JSON | `200` | validation/binding `400`; cooldown `429`; readiness/cooldown check `503`; 최초 fatal/내부 오류 `500` 계열; Security 응답 미검증 | 성공 `accepted`, `eventSeq`, `x`, `y`, `color`, `tileVersion`; 오류 형식은 경로별 상이 | 커스텀 response header 없음 | `application/json` | 없음 | 임시 `X-User-Id`; 실제 인증 아님 | 명시적 chain 없음; Pixel filter/binding 우선순위 미검증 | MVC guard + command/core 재검사 | 구현 | 카카오 OAuth2 + Bearer Access JWT principal, authenticated | X-User-Id 교체와 공통 오류 형식 필요 |
| WebSocket | WebSocket(JSON) | GET (HTTP Upgrade handshake) | `/ws` | server→client 단건 pixel diff | 해당 없음 | 해당 없음 | 표준 Upgrade handshake header; application 인증 header 계약 없음 | client message 계약 없음 | 실제 handshake 결과 미검증 | 실제 handshake 실패 status 미검증 | 현재 `{type,x,y,color,eventSeq}` 단건 JSON | 실제 handshake response header 미검증 | 해당 없음 | 해당 없음 | 없음 | 명시적 chain 없음; 제한 진단에 config/handler 미포함 | MVC readiness interceptor 미적용; 기존 session 강제 종료 없음 | 단건 broadcast 구현 | 최종 인증/handshake 확정; batch/order/send 직렬화 | 실제 handshake와 최종 인증 미확정; 후속 보강 미구현 |

### API별 상세 계약

---

### A. `GET /api/board`

#### 역할
- 초기 설정값 조회

#### application-level 식별과 security
- controller 자체는 request body나 application-level 사용자 식별값을 사용하지 않는다.
- Board 제한 MVC 진단에서 unauthenticated JSON은 `401` + HTTP Basic challenge, HTML은 `/login` `302`가 관측되었다.
- 위 결과는 전체 application context의 공개 접근 계약이 아니라 현재 제한 slice의 기본 filter 동작이다.

#### 요청
- Query 없음
- Body 없음

#### 응답 상태코드
- `200 OK`: security filter와 readiness guard를 통과한 정상 요청
- `503 Service Unavailable`: readiness guard 차단
- 현재 제한 security 진단 결과: unauthenticated JSON `401`, HTML `302`
- 예상하지 못한 내부 실패: Spring Boot 기본 `500` 계열 처리

#### 응답 예시
```json
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
```

#### 응답 필드
- `boardSize`: 보드 한 변 길이
- `tileSize`: 타일 한 변 길이
- `z`: 현재 MVP 원본 타일 level인 `0`
- `tileCountX`, `tileCountY`: 각 축의 z=0 타일 수인 `32`
- `paletteSize`: 팔레트 색상 수
- `palette`: 실제 palette index 순서의 색상 배열. 예시는 축약이며 실제 응답은 256개이고, `palette[15] == "#FFFFFF"`이며 빈 보드의 기본 색상 index는 `15`
- `overviewRefreshSeconds`: `ApplicationReadyEvent`의 최초 요청과 별개인 nominal fixed-delay 갱신 간격. 최대 stale 시간 보장이 아님

#### 확정 메모
- 현재 값이 고정에 가깝더라도 **팔레트 전달 때문에 유지**한다.
- palette 배열 순서는 production의 실제 palette index 순서이며, 실제 응답은 `paletteSize == palette.size() == 256`을 만족한다.

---

### B. `GET /api/tiles/{z}/{tx}/{ty}`

#### 역할
- 원본 타일 1개 조회

#### application-level 식별과 security
- controller 자체는 application-level 사용자 식별값을 사용하지 않는다.
- 현재 제한 security 진단에는 `TileController`가 포함되지 않아 실제 unauthenticated 응답은 미검증이다.

#### route와 현재 지원 범위
- controller route pattern은 `GET /api/tiles/{z}/{tx}/{ty}`다.
- 현재 지원 값은 `z=0`뿐이며 `GET /api/tiles/0/{tx}/{ty}`는 유효한 구체적 호출 형태다.
- MVP 기준 타일 좌표 범위:
    - `tx: 0 ~ 31`
    - `ty: 0 ~ 31`

#### 요청 예시
```http
GET /api/tiles/0/3/5
```

#### 응답 상태코드
- `200 OK`: 정상 조회
- `400 Bad Request`: `z != 0` 또는 `tx`/`ty` 범위 오류, body는 `{"message":"<실제 예외 메시지>"}`
- `503 Service Unavailable`: readiness guard 차단, body는 `message` JSON
- `500` 계열: 정상 범위 타일 누락, gzip 실패 등 내부 오류, body는 Spring 기본 처리
- Security 오류 body: 현재 실제 Tile endpoint의 filter 포함 실행 결과가 없어 미검증

#### 성공 응답
- `Content-Type: application/octet-stream`
- `Content-Encoding: gzip`
- 압축 해제 후 body: `256 * 256 = 65,536 bytes`
- HTTP wire body: gzip 결과이므로 내용에 따라 가변 길이
- 각 바이트는 팔레트 인덱스(`0~255`)

#### 타일 초기화 확정
- lazy create는 사용하지 않는다.
- DB z=0 snapshot이 0 rows인 recovery 경로에서는 서버 시작 시 memory의 `z=0` 전체 1,024개 tile entry를 all-white 상태로 pre-init 한다.
- 따라서 ready 상태의 memory board에는 정상 범위 `z=0` tile entry가 항상 존재한다.
- 정상 범위 좌표인데 memory tile entry가 없다면 일반적인 조회 miss가 아니라 내부 이상 상황으로 보고 `500 Internal Server Error`로 처리한다.

#### 현재 응답 헤더
```http
Content-Type: application/octet-stream
Content-Encoding: gzip
X-Tile-Version: 12
```

#### 헤더 의미
- `X-Tile-Version`
    - 현재 `InMemoryTileBoard`의 실시간 tileVersion
    - DB `tiles.tile_version`은 마지막 flush snapshot version이므로 항상 같지는 않음

#### 후속 cache 목표
- `ETag`, `If-None-Match`, `Cache-Control`, `304 Not Modified`는 현재 미구현이다.
- `X-Tile-Size`도 현재 응답에 없다.

#### tileVersion
- 각 타일은 tileVersion을 가진다
- 타일 내용이 바뀌면 `tileVersion`이 1 증가한다.
- `tileVersion`은 tile별 version이고 `eventSeq`는 전체 write의 전역 순서다.



#### 에러 예시
```json
{
  "message": "Tile tx is out of range."
}
```

현재 `TileController.ErrorResponse`는 `message` 필드만 반환한다. `z != 0`은 `Only z=0 tiles are supported in MVP.`, `ty` 범위 오류는 `Tile ty is out of range.` 메시지를 사용한다.

---

### C. `GET /api/overview`

#### 현재 구현
- route: `GET /api/overview`
- application-level 사용자 식별 없음. 최종 Security `permitAll`은 13단계 책임이며 현재 제한 slice는 JSON `401`, HTML `/login` `302`를 관측함
- readiness guard 통과 + 게시 이미지 존재: `200 OK`, 완전한 `2048 x 2048` PNG bytes
- readiness guard 통과 + 게시 이미지 부재: `503 Service Unavailable`, `{ "message": "Overview image is not available." }`
- not-ready: readiness interceptor `503`; 이전 정상 이미지가 있어도 controller에 도달하지 않음
- `Cache-Control: no-cache`는 정상 PNG에만 명시적으로 적용

#### 정상 응답 헤더
```http
Content-Type: image/png
Cache-Control: no-cache
```

---

### D. `POST /api/pixels`

#### 역할
- 특정 좌표 픽셀 변경 요청

#### 현재 application-level 식별과 security
- 필수 `X-User-Id` header를 `long`으로 변환해 임시 `userId`로 사용한다.
- `X-User-Id`는 실제 인증이 아니다.
- 현재 제한 security 진단에는 `PixelController`가 포함되지 않아 실제 unauthenticated 응답과 filter/header binding 우선순위는 미검증이다.
- 최종 목표는 Bearer Access JWT principal의 내부 `users.id` 사용이다.

#### 요청 헤더
```http
X-User-Id: 7
Content-Type: application/json
```

#### 요청 Body 예시
```json
{
  "x": 100,
  "y": 200,
  "color": 17
}
```

#### 요청 필드
- `x`: 보드 X 좌표
- `y`: 보드 Y 좌표
- `color`: 팔레트 인덱스(`0~255`)

#### 유효 범위
- `x: 0 ~ 8191`
- `y: 0 ~ 8191`
- `color: 0 ~ 255`

#### 응답 상태코드
- `200 OK`: core write와 dirty mark 성공
- `400 Bad Request`: 필수 body 누락, 잘못된 `X-User-Id` 변환, userId/좌표/색상 검증 실패
- `429 Too Many Requests`: cooldown 활성, body는 `message`와 `remainingMillis`
- `503 Service Unavailable`: readiness 차단 또는 write 전 cooldown 저장소 확인 실패
- `500` 계열: 최초 WAL append/fsync 실패, WAL 성공 후 memory apply 실패, dirty mark 실패 등 내부 오류
- 현재 Security filter의 실제 Pixel `401`/`302`/`403` 우선순위는 미검증이다.

#### 성공 응답 예시
```json
{
  "accepted": true,
  "x": 100,
  "y": 200,
  "color": 17,
  "eventSeq": 12345,
  "tileVersion": 991
}
```

#### 응답 필드
- `accepted`: 반영 여부
- `x`, `y`, `color`: 최종 반영된 값
- `eventSeq`: 이벤트 최종 반영 순서값
- `tileVersion`: 수정 후 타일 버전
- 현재 DTO에 `cooldownRemainingMs`는 없다.

#### Redis 쿨다운 동작 확정
- 키 형식은 `cooldown:user:{userId}` 이다.
- `PTTL > 0` 이면 아직 쿨다운 중이므로 요청을 거부한다.
- 키가 없거나 TTL이 만료된 경우 요청을 진행한다.
- core write와 dirty mark 뒤에 `180초` TTL 설정을 시도한다.
- write 전 cooldown 확인 실패는 `503`이지만 core write 뒤 cooldown start 실패는 완료 write를 취소하지 않고 warning 뒤 성공 응답을 유지한다.
- 유효성 오류 등 승인되지 않은 write에는 쿨다운을 부여하지 않는다.

#### 인증 관련 확정
- 현재 `X-User-Id`도 body에 넣지 않는다.
- 최종 Access JWT는 **Authorization 헤더**로 전달하고 request body의 `userId`를 신뢰하지 않는다.

#### 좌표 오류 예시
```json
{
  "message": "x coordinate is out of board range. x=8192"
}
```

#### 색상 오류 예시
```json
{
  "message": "color index is out of palette range. color=256"
}
```

#### 쿨다운 예시
```json
{
  "message": "Pixel write cooldown is active.",
  "remainingMillis": 120000
}
```

위 controller error body는 현재 실제 `Map` 응답이다. MVC binding 오류와 예상하지 못한 내부 오류는 이 형식으로 통일되어 있지 않다.


---

### E. `WebSocket /ws`

#### 역할
- 픽셀 변경 diff 실시간 수신

#### 연결 경로
```text
/ws
```

#### 현재 security와 readiness
- handler/config 자체에는 application-level 인증 로직이 없다.
- `setAllowedOriginPatterns("*")`는 origin 정책이며 Spring Security handshake 허용을 의미하지 않는다.
- 현재 제한 security 진단에는 `PixelWebSocketConfig`와 handler가 포함되지 않아 실제 `/ws` handshake 결과는 미검증이다.
- MVC `ReadinessGuardInterceptor`는 `/ws` handshake에 직접 적용되지 않는다.
- not-ready 전환 시 기존 WebSocket session을 강제로 종료하는 코드는 없다.
- 전환 이후 새 pixel write 진입은 readiness 검사로 차단되지만, 이미 처리 중인 요청과의 경계는 해당 write 경로의 동시성/readiness 정책에 따른다.
- 최종 WebSocket 인증과 handshake 제한은 13단계에서 별도로 확정한다.

#### 현재 서버 → 클라이언트 단건 payload
```json
{
  "type": "pixel",
  "x": 100,
  "y": 200,
  "color": 17,
  "eventSeq": 12345
}
```

#### 후속 batch 목표 예시
```json
{
  "type": "pixels",
  "events": [
    { "x": 100, "y": 200, "color": 17, "eventSeq": 12345 },
    { "x": 101, "y": 200, "color": 22, "eventSeq": 12346 }
  ]
}
```

#### 필드 의미
- `type`: 이벤트 타입
- `x`, `y`: 변경 좌표
- `color`: 반영 색상 인덱스
- `eventSeq`: 이벤트 최종 반영 순서값

#### 확정 사항
- 현재 wire field는 내부 개념과 같은 **`eventSeq`** 다.
- `seq` 필드와 `tileVersion`은 현재 payload에 없다.
- batch broadcast, 전역 broadcast 순서 보장, session별 send 직렬화는 후속 보강이다.

#### 운영 방식 확정
- 단일 서버 기준 서버 메모리 broadcast를 사용한다.
- WAL append + fsync 성공은 write의 1차 내구성 경계다.
- core write 완료에는 memory apply 성공이 필요하고, 현재 HTTP `200` 성공 응답에는 command 계약상 dirty mark 성공까지 필요하다.
- WebSocket broadcast 실패는 write rollback 사유가 아니다.
- 개별 session send 실패는 해당 session을 registry에서 제거하고 다른 session 전송을 계속한다.
- command 경계의 broadcast 실패는 로그만 남기며 클라이언트는 이후 타일 재동기화 로직으로 정합성을 회복한다.

#### 클라이언트 처리 원칙
- diff 수신 시 해당 픽셀만 즉시 반영
- `eventSeq` gap 감지 시 관련 타일 재요청

---

## 3) API별 내부 처리 흐름

### `GET /api/board`
1. 보드 상수/설정 로드
2. 팔레트 포함 JSON 응답

---

### `GET /api/tiles/{z}/{tx}/{ty}`
1. `z`, `tx`, `ty` 범위 검증
2. 메모리 타일 상태에서 `(z=0, tx, ty)` 타일 조회
3. `data`, `tileVersion` 읽기
4. gzip 압축 후 응답
5. 헤더에 `X-Tile-Version` 포함

---

### `GET /api/overview`
1. default profile의 `@Scheduled` 작업은 context refresh부터 등록될 수 있지만 `ApplicationReadyEvent` 이전 invocation은 lifecycle gate에서 정상 skip
2. event 관측 시 gate를 한 번 열고 기본 `taskScheduler`에 최초 생성 작업을 1회 제출하며, 중복 event는 추가 제출 없이 반환
3. event 이후 별도 `@Scheduled` 작업이 같은 기본 scheduler에서 약 10초 fixed-delay와 같은 initial delay로 후속 생성 요청
4. Scheduler는 readiness를 직접 판단하지 않고, `OverviewService`가 readiness 확인과 Overview 전용 non-blocking single-flight guard 획득
5. `OverviewRenderer`가 canonical 타일별 snapshot을 한 번씩 읽고 각 `4 × 4` 블록의 좌상단 palette index를 unsigned 변환
6. production 256색 팔레트를 RGB로 변환해 `2048 × 2048` PNG를 메모리에서 완성
7. 정상·비어 있지 않은 PNG byte 배열만 atomic reference에 게시
8. 생성 실패 시 기존 정상 PNG 유지, guard 해제, 다음 fixed-delay invocation 허용
9. controller는 readiness guard 뒤 현재 게시본을 읽어 `200 image/png` 또는 no-image `503` JSON 반환

---

### `POST /api/pixels`
1. 현재 Spring Security filter 처리. 실제 Pixel 응답은 제한 진단 범위 밖이라 미검증
2. MVC readiness guard 확인
3. controller의 `X-User-Id`/body binding과 `requiredX()`/`requiredY()`/`requiredColor()` 누락 검사
4. `PixelCommandService` 진입 직후 readiness 재검사
5. userId 검증과 Redis `cooldown:user:{userId}` 확인
6. `PTTL > 0`이면 `429`로 종료
7. `PixelWriteService`의 `synchronized` 진입 직후 readiness 재검사
8. core에서 userId/좌표/색상 범위 재검증
9. `AtomicLong`으로 `eventSeq` 발급
10. `(x, y)`에서 `tx`/`ty`를 계산하면서 WAL record 생성
11. WAL append와 요청 단위 fsync로 1차 내구성 경계 확보
12. 메모리 타일 상태 반영과 `tileVersion++`
13. dirty 타일 표시 성공 뒤에만 현재 HTTP `200` 성공 조건 확정
14. Redis cooldown 시작과 WebSocket broadcast 후처리. 실패해도 완료 write 유지
15. `accepted`, `eventSeq`, `x`, `y`, `color`, `tileVersion` HTTP `200` 응답 반환
16. default runtime의 1초 fixed-delay scheduler가 `FlushWorker.flushOnce()`를 호출해 DB를 후행 반영

---

### `/ws`
1. 현재 security filter를 통과한 클라이언트 연결. 실제 handshake 결과는 미검증
2. 서버는 단일 서버 메모리 기반으로 픽셀 변경 이벤트를 broadcast
3. 클라이언트는 diff를 즉시 적용
4. `eventSeq` gap 또는 누락이 감지되면 관련 타일을 재요청해 재동기화

---

## 4) 이벤트 순서 / 시간 / 충돌 제어 확정

### eventSeq
- `eventSeq`는 단순 wall-clock timestamp가 아니라
- **서버가 직접 발급하는 최종 순서 번호**다.
- 현재 구조에서는 DB auto increment에 의존하지 않는다.
- 런타임에서는 `AtomicLong`으로 관리한다.

즉:
- 승인 직전 서버가 `eventSeq`를 발급한다
- WAL에는 그 값이 그대로 기록된다
- 이후 DB flush 시 `pixel_events.event_seq`에 같은 값을 그대로 넣는다

### eventSeq gap 정책
- WAL record의 `eventSeq`는 strictly increasing 해야 하지만 contiguous할 필요는 없다.
- `100, 102, 105`처럼 gap이 있어도 `101`, `103`, `104`가 없다는 이유만으로 recovery나 flush를 실패시키지 않는다.
- 같은 `eventSeq`의 중복과 역순은 WAL corruption으로 처리하며, active WAL 전체에서 checkpoint 이전 record도 순서 검증 대상에 포함한다.
- eventSeq 발급 뒤 WAL append가 실패할 수 있으므로 gap 자체를 승인 이벤트 누락으로 단정하지 않는다.

---

### created_at
- `pixel_events.created_at`은 별도로 둔다.
- 이 값은 **이벤트 승인 시각 기록용**이다.
- WAL codec과 immutable flush plan은 `WalRecord.createdAt`의 원래 나노초 필드를 보존한다.
- DB entity mapping 경계에서만 `ChronoUnit.MILLIS`로 truncate하고 `DATETIME(3)`에 저장한다.
- 시간값은 정렬, checkpoint 또는 tie-breaker로 사용하지 않는다. 순서 조회는 `ORDER BY event_seq`를 사용한다.

---

### 시간과 순서의 역할 분리
- **시간 기록:** `created_at`
- **정합성 판단 기준:** `eventSeq`

즉:
- 시간은 기록/분석/감사용
- 순서는 실제 반영 정합성 기준

---

### 충돌 제어
- 현재 `PixelWriteService.writePixel(...)` 메서드 전체가 `synchronized`이므로 write core를 service instance 수준에서 직렬 처리한다.
- 직렬 경계에는 core readiness 재검사, validation, eventSeq 발급, WAL append + fsync, memory apply가 포함된다.
- 현재 production code는 tile별 lock이나 DB row lock을 사용하지 않는다.

---

### 확정 결론
- 전역 단일 큐 / 나노초 정렬 / 외부 메시지 브로커까지는 MVP에서 과하다.
- 대신 아래 4개를 가져간다.
  1. **최종 반영 순서값 필요 → `eventSeq`**
  2. **이벤트 발생 시각 기록 필요 → `created_at`**
  3. **승인 순서와 WAL/memory 반영 순서 일치 필요 → 현재 write core의 synchronized 경계로 직렬 처리**
  4. **전파 실패 복구 필요 → broadcast 실패는 로그 처리 + 클라이언트 재동기화**

---

## 5) tileVersion 확정

### 의미
- `tileVersion`은 타일 기준 상태 버전이다.
- runtime에서는 `InMemoryTileBoard`의 실시간 version이다.
- DB `tiles.tile_version`은 마지막 flush snapshot에 저장된 version이므로 runtime 값과 항상 같지는 않다.

### 증가 규칙
- 해당 타일 내부 픽셀이 변경될 때마다 `1` 증가한다.

예:
- 변경 전 memory `tileVersion = 12`
- 픽셀 1개 수정 후 memory `tileVersion = 13`

### 목적
- 클라이언트가 들고 있는 타일이 최신인지 판단하기 위함
- mismatch 감지 시 해당 타일 재요청

---

## 6) 상태코드 규칙

### 조회 API
- 성공: `200`
- 잘못된 좌표/파라미터: `400`
- readiness 차단: `503`
- 서버 오류: `500`

#### 메모
- MVP 기준 ready 상태의 memory board에는 정상 범위 `z=0` tile entry가 모두 존재하므로 `GET /api/tiles/0/{tx}/{ty}` 일반 흐름에서 `404`를 사용하지 않는다.

### 쓰기 API
- 성공: `200`
- 잘못된 요청: `400`
- 쿨다운: `429`
- readiness/cooldown 확인 인프라 사용 불가: `503`
- 서버 오류: `500`
- 현재 Pixel의 Security filter 단계 `401`/`302`/`403` 응답은 제한 진단 범위 밖이라 미검증이다.

---

## 7) 현재 확정 결론 요약

### 현재 구현 API
- `GET /api/board`
- `GET /api/tiles/{z}/{tx}/{ty}` (`z=0`만 지원)
- `GET /api/overview`
- `POST /api/pixels`
- `WebSocket /ws`

### 현재 미구현 목표 API
- 없음

### 현재 응답 방향
- 성공 HTTP API는 `200 + 데이터`다.
- 에러 body는 controller/readiness/Spring 기본 처리별로 다르며 아직 공통 포맷이 아니다.

### 핵심 메타
- overview는 **인메모리 보드 기반 2048x2048 PNG**이며 `ApplicationReadyEvent` 관측 뒤 최초 생성 요청과 event 이후 약 10초 fixed-delay를 사용한다. 10초는 최대 stale 시간 보장이 아니다.
- 이벤트 순서값: **`eventSeq`로 통일**
- `eventSeq`:
  - 서버가 `AtomicLong`으로 직접 발급하는 순서값
  - WAL에 먼저 기록된다
  - 이후 DB flush 시 `pixel_events.event_seq`에 같은 값을 그대로 저장한다
  - 서버가 최종 반영한 순서
- 시간 기록: **`created_at`**
- 타일 버전: **`tileVersion`**, 타일 수정 시 1 증가
- `X-Tile-Version`은 현재 memory `tileVersion`
- DB `tiles.tile_version`은 마지막 flush snapshot version
- `ETag`/조건부 요청/cache header는 후속 목표
- 충돌 제어: **현재 write core의 service-level synchronized 경계로 직렬 처리**


## 8) WAL 반영 최종 DB 스키마

### 현재 구조에서 DB의 역할
- 실시간 authoritative state는 DB가 아니라 **메모리 타일 상태**다.
- 승인된 write의 1차 내구성은 **WAL 파일**이다.
- DB는 **후행 저장소 + 복구 시작점** 역할을 한다.

즉:
- WAL append + fsync 성공 = **write의 1차 내구성 경계**
- core write 완료 = **WAL append + fsync 성공 + memory apply 성공**
- 현재 HTTP `200` 성공 응답 = **core write 완료 + 현재 command 계약상 dirty mark 성공**
- DB flush 완료는 HTTP 성공 조건이 아니다.
- cooldown start와 WebSocket broadcast는 위 성공 경계 뒤의 후처리이며, 실패해도 이미 완료된 core write와 dirty mark를 되돌리지 않는다.

---

### 테이블 구성

현재 `pixel_place.sql`에 정의된 테이블은 다음과 같다.

- `tiles`
- `pixel_events`
- `wal_checkpoint`
- `users`

`users`는 13-A에서 root/test DDL에 추가한 최소 사용자 저장소다. 기존 세 테이블 및 checkpoint seed를 보존하고 `pixel_events.user_id`와 FK로 연결하지 않는다. runtime DB 반영은 후속 D에서 별도로 확인한다.

---

### `users`
#### 역할
- 카카오 식별자와 내부 사용자 ID의 최소 저장소. 로그인 wiring은 후속 단계
- 현재 `pixel_place.sql`에 정의. `UserEntity`/`UserJpaRepository`와 매핑하며 timestamp는 DB 기본값이 소유

#### 컬럼
- `id`
- `kakao_user_id`
- `created_at`
- `updated_at`

---

### `tiles`
#### 역할
- DB에 저장되는 **후행 타일 상태 저장소**
- 서버 재시작 시 메모리 상태를 만들기 위한 **초기 로드 원본**

#### 컬럼
- `z`
- `tx`
- `ty`
- `data`
- `tile_version`
- `updated_at`

#### 설계 포인트
- PK는 `(z, tx, ty)`
- `data`는 `MEDIUMBLOB`
- `pixel_place.sql`은 `tiles` row를 seed하지 않고 `wal_checkpoint.main = 0`만 생성
- 현재 recovery row-shape 계약은 z=0 DB snapshot이 0 rows이거나 canonical `(tx, ty) = 0..31 × 0..31` 전체 1,024 rows인 경우만 허용
- `0 rows + checkpoint 0`은 정상 bootstrap-pending 상태이며 memory만 all-white 1,024개로 pre-init
- 최초 non-no-op flush에서 memory z=0 전체 1,024개 snapshot을 capture하고 `pixel_events`, 전체 `tiles`, checkpoint를 하나의 transaction으로 commit
- 현재 recovery는 `1~1,023 rows`, 조회한 z=0 snapshot의 canonical tx/ty 불완전과 범위 밖 tx/ty를 실패시키며, active bootstrap 정책은 `0 rows + checkpoint > 0`도 ready 전 불일치로 실패하도록 11단계 구현과 테스트에 고정
- 전체 1,024 rows가 형성된 뒤 일반 flush는 WAL affected와 drained dirty 합집합만 저장
- `tile_version`은 현재 memory 값과 항상 같은 값이 아니라 마지막으로 DB에 flush된 snapshot version

---

### `pixel_events`
#### 역할
- 승인된 픽셀 이벤트를 DB에 후행 저장하는 append-only 영속 로그
- `eventSeq` 발급원은 아니다

#### 컬럼
- `event_seq`
- `user_id`
- `z`
- `tx`
- `ty`
- `x`
- `y`
- `color`
- `created_at`

#### 설계 포인트
- `event_seq`는 서버가 발급하고 DB에 그대로 저장
- `event_seq`는 auto increment가 아님
- PK는 `event_seq`
- 최소 인덱스 전략으로 시작
- 현재 `user_id`는 임시 `X-User-Id`를 저장하는 일반 컬럼
- 현재 users FK를 적용하지 않음
- `created_at`에는 DB flush 시각이 아니라 WAL record의 원래 `createdAt`을 저장
- WAL과 immutable plan은 원래 `createdAt` 정밀도를 보존하고, `PixelEventEntity` 생성 경계에서만 밀리초로 truncate
- DDL 정밀도는 `DATETIME(3)`이며 event ordering은 `created_at`이 아닌 `event_seq`만 사용

---

### `wal_checkpoint`
#### 역할
- DB가 어디까지 flush 완료됐는지 저장
- 서버 재시작 시 replay 시작점 제공

#### 컬럼
- `checkpoint_name`
- `last_flushed_event_seq`
- `updated_at`

#### 설계 포인트
- 단일 서버 MVP에서는 `"main"` 1개 row 사용
- `last_flushed_event_seq`는 **완전 flush 완료 지점**이어야 한다
- setup SQL은 `main = 0`을 idempotent하게 seed하지만 기존 값을 0으로 덮어쓰지 않는다
- runtime repository는 누락된 `main` row를 자동 생성하지 않고 fail-fast한다

---

### 최종 DDL

현재 설명은 `pixel_place.sql`의 실제 테이블 정의와 맞춘다. 13-A의 `users` 테이블을 포함하며 기존 event/WAL 식별자를 보존하기 위해 users FK는 추가하지 않는다.

    CREATE TABLE IF NOT EXISTS tiles (
      z TINYINT UNSIGNED NOT NULL,
      tx TINYINT UNSIGNED NOT NULL,
      ty TINYINT UNSIGNED NOT NULL,
      data MEDIUMBLOB NOT NULL,
      tile_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      PRIMARY KEY (z, tx, ty),
      KEY idx_tiles_updated_at (updated_at)
    ) ENGINE=InnoDB;

    CREATE TABLE IF NOT EXISTS pixel_events (
      event_seq BIGINT UNSIGNED NOT NULL,
      user_id BIGINT UNSIGNED NOT NULL,
      z TINYINT UNSIGNED NOT NULL,
      tx TINYINT UNSIGNED NOT NULL,
      ty TINYINT UNSIGNED NOT NULL,
      x SMALLINT UNSIGNED NOT NULL,
      y SMALLINT UNSIGNED NOT NULL,
      color SMALLINT UNSIGNED NOT NULL,
      created_at DATETIME(3) NOT NULL,
      PRIMARY KEY (event_seq),
      KEY idx_pixel_events_user_id (user_id)
    ) ENGINE=InnoDB;

    CREATE TABLE IF NOT EXISTS wal_checkpoint (
      checkpoint_name VARCHAR(64) NOT NULL,
      last_flushed_event_seq BIGINT UNSIGNED NOT NULL,
      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      PRIMARY KEY (checkpoint_name)
    ) ENGINE=InnoDB;

    INSERT INTO wal_checkpoint (checkpoint_name, last_flushed_event_seq) 
    VALUES ('main', 0)
    ON DUPLICATE KEY UPDATE checkpoint_name = checkpoint_name;


    CREATE TABLE IF NOT EXISTS users (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        kakao_user_id BIGINT UNSIGNED NOT NULL,
        created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
        updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
            ON UPDATE CURRENT_TIMESTAMP(3),
        PRIMARY KEY (id),
        UNIQUE KEY uk_users_kakao_user_id (kakao_user_id)
    ) ENGINE=InnoDB;

## 9) WAL 처리 흐름

### WAL의 역할
- WAL은 **승인된 픽셀 write의 1차 내구성 저장소**다.
- WAL append + fsync 성공만으로 현재 HTTP `200` 성공 조건 전체가 충족되는 것은 아니다. memory apply와 현재 command 계약상 dirty mark까지 성공해야 HTTP `200` 응답을 반환할 수 있다.

### WAL 레코드 최소 필드
- `eventSeq`
- `userId`
- `z`
- `tx`
- `ty`
- `x`
- `y`
- `color`
- `createdAt`

### WAL 포맷
- 포맷은 **JSON Lines**
- 레코드 1건 = JSON 1줄
- 메타 파일은 사용하지 않는다

예시:

    {"eventSeq":12345,"userId":7,"z":0,"tx":3,"ty":5,"x":100,"y":200,"color":17,"createdAt":"2026-04-03T06:00:00.123"}

### write 성공과 WAL 내구성 경계 구분
- WAL append + fsync 성공은 write의 1차 내구성 경계다.
- core write는 해당 WAL 내구성 확인 뒤 memory apply까지 성공한 상태다.
- 현재 HTTP `200` 성공 응답은 core write와 현재 command 계약상 dirty mark까지 성공한 상태다.
- DB flush 완료는 HTTP 성공 조건이 아니다.
- 최초 fatal을 일으킨 요청은 해당 내부 오류로 실패한다. 이후 요청은 command/core readiness 검사에서 not-ready로 거부하며, 최초 fatal 요청의 오류를 `503`으로 바꾸지 않는다.

### WAL fail-stop 정책
- WAL append 또는 fsync 실패 시 partial WAL이나 durability 결과가 불확실할 수 있으므로 `FileWalAppender`를 poisoned 상태로 전환한다.
- 같은 실패에서 `ServiceReadiness`도 not-ready로 전환하여 후속 write와 새로운 flush plan capture를 차단한다.
- poison 이후 추가 append를 차단하고 같은 프로세스에서 channel reopen, truncate, reset 또는 후속 write를 허용하지 않는다.
- WAL I/O 실패가 발생한 마지막 요청은 성공으로 응답하지 않지만, 완전한 newline-terminated record가 남았을 가능성이 있으므로 절대 미반영으로 단정하지 않는 unknown outcome이다.
- 운영자 확인 뒤 서버를 재시작하고 startup recovery로 WAL을 다시 판정한다. partial-line truncate recovery는 현재 구현하지 않는다.

### not-ready HTTP guard 범위
runtime fatal 전환 뒤 readiness guard가 보호하는 현재 HTTP 경로는 다음과 같다.

```text
POST /api/pixels
GET /api/board
GET /api/overview
GET /api/tiles/**
```

최초 fatal 요청은 실제 내부 실패로 종료하고, 이후 위 경로의 요청은 not-ready 의미의 `503 Service Unavailable`로 차단한다. 일반 `IllegalStateException` 전체를 `503`으로 변환하지 않는다.

### WAL 마지막 newline 계약
- 정상 WAL record는 JSON 뒤에 `\n`이 붙은 한 줄이다.
- active WAL 파일이 비어 있지 않다면 마지막 byte는 반드시 newline이어야 한다.
- 마지막 newline이 없으면 내용이 완전한 JSON처럼 보여도 recovery와 runtime WAL scan을 실패시킨다.

### WAL 성공 후 memory apply 실패
- WAL fsync 성공 뒤 memory apply가 실패하면 durable WAL과 memory authoritative state가 불일치하므로 서비스를 not-ready/fatal로 전환한다.
- 최초 실패 요청은 내부 오류로 실패하고, 이후 readiness guard 대상 read/write와 command/core를 통한 write를 차단한다.
- not-ready 전환 이후 새로운 flush plan의 시작과 capture를 금지한다.
- 다만 ready 상태의 coordinator boundary에서 이미 일관되게 capture한 immutable plan은 capture한 `flushTargetEventSeq`까지만 DB transaction을 완료할 수 있다.

### 모든 성공 append의 fsync 정책
- 신규 active WAL 파일의 첫 append, 기존 WAL 파일 append, 같은 파일의 두 번째 이후 append를 구분하지 않고 모든 성공 append에서 `FileChannel.force(true)` 완료를 확인한다.
- 정상 append 경로에서 `force(false)`를 사용하지 않으며, `force(true)` 완료 전에 성공을 반환하지 않는다.
- `force(true)` 실패는 WAL I/O fail-stop과 동일하게 poison 처리하며 더 약한 durability 모드로 downgrade하지 않는다.

### parent-directory fsync 미적용 한계
- 현재 durable WAL tail은 완전한 JSON line, newline, file write와 해당 append의 `FileChannel.force(true)` 성공까지 확인된 마지막 record다.
- parent directory fsync는 현재 범위가 아니므로 신규 파일명이나 directory entry가 모든 filesystem crash에서 반드시 보존된다고 보장하지 않는다.
- 14단계 rotation/segment에서도 17단계 완료 전에는 directory metadata의 엄격한 내구성을 보장하지 않으며, 실제 parent-directory fsync는 17단계에서 구현한다.

### 픽셀 요청 처리에서 WAL 흐름
1. 현재 Spring Security filter 처리. 실제 Pixel 응답은 제한 진단 범위 밖이라 미검증
2. MVC readiness guard 확인
3. controller의 `X-User-Id`/body binding과 `requiredX()`/`requiredY()`/`requiredColor()` 누락 검사
4. `PixelCommandService` 진입 직후 readiness 재검사
5. userId 검증과 Redis `cooldown:user:{userId}` 확인
6. `PTTL > 0`이면 `429`로 종료
7. `PixelWriteService`의 `synchronized` 진입 직후 readiness 재검사
8. core에서 userId/좌표/색상 범위 재검증
9. `AtomicLong`으로 `eventSeq` 발급
10. `(x, y)`에서 `tx`/`ty`를 계산하면서 WAL record 생성
11. WAL append와 요청 단위 fsync로 1차 내구성 경계 확보
12. 메모리 타일 상태 반영과 `tileVersion++`
13. dirty 타일 표시 성공 뒤에만 현재 HTTP `200` 성공 조건 확정
14. Redis cooldown 시작과 WebSocket broadcast 후처리. 실패해도 완료 write 유지
15. `accepted`, `eventSeq`, `x`, `y`, `color`, `tileVersion` HTTP `200` 응답 반환
16. default runtime의 1초 fixed-delay scheduler가 `FlushWorker.flushOnce()`를 호출해 DB를 후행 반영

JWT principal 기반 사용자 식별은 13단계의 후속 목표다. 현재 흐름의 `X-User-Id`와 혼합하지 않는다. MVC readiness guard를 통과한 뒤 runtime fatal 전환과 HTTP binding 오류가 경쟁할 수 있으므로 command-level readiness가 모든 header/body binding 오류보다 항상 우선한다고 단정하지 않는다.

### WAL replay
부팅 시 순서:
1. public `StartupRecoveryDbViewCaptureService`의 명시적 read-only `REPEATABLE_READ` transaction 시작
2. `main` checkpoint, 전체 DB tile key metadata, z=0 tile bytes를 순서대로 같은 DB snapshot에서 조회
3. immutable `StartupRecoveryDbView` 반환과 함께 capture transaction 종료
4. 공통 `DbBootstrapClassifier`로 `0 rows + checkpoint 0`, canonical 1,024 rows, inconsistent 상태 판정
5. classifier 결과와 실제 snapshot key/byte length/version shape를 WAL read 전에 재검증
6. active WAL 전체 scan 뒤 `walLastEventSeq >= checkpoint`와 replay batch empty/non-empty tail invariant 검증
7. bootstrap-pending이면 memory를 all-white로 초기화하고, initialized이면 canonical 1,024 snapshots를 load
8. checkpoint 이후 WAL record를 순서대로 memory에 replay
9. 검증된 `walLastEventSeq`를 `EventSeqManager`의 마지막 발급값으로 초기화
10. 모든 단계가 성공한 뒤에만 ready 전환

capture transaction은 DB view 조립까지만 포함한다. bootstrap classifier, WAL scan, memory load/replay, eventSeq seed와 ready 전환까지 transaction을 늘리지 않는다. partial/extra z/범위 밖 key/checkpoint mismatch/row 누락과 WAL tail 불일치는 memory 변경 전에 fail-fast하며 자동 보정하지 않는다.

recovery replay는 `DirtyTileTracker`를 채우지 않는다. 따라서 checkpoint 이후 WAL record가 memory에 replay된 뒤에도 dirty tracker는 비어 있을 수 있으며, runtime flush는 WAL에서 affected `TileKey`를 다시 계산해야 한다.

### Recovery 기준 시퀀스 용어

- `lastFlushedEventSeq`
  - `wal_checkpoint.last_flushed_event_seq`에 저장되는 값이다.
  - DB flush worker가 `pixel_events`와 `tiles`에 모두 반영 완료한 마지막 `eventSeq`를 의미한다.
  - boot recovery에서는 이 값 이하의 WAL 이벤트를 replay하지 않는다.

- `walLastEventSeq`
  - active WAL 파일을 끝까지 읽어서 확인한 마지막 `eventSeq`다.
  - replay 대상 여부와 관계없이 계산한다.
  - startup recovery가 `walLastEventSeq >= lastFlushedEventSeq`와 replay batch tail invariant를 먼저 검증한다.
  - 검증 성공 뒤 `lastIssuedEventSeq`는 `walLastEventSeq`로 초기화하고, 다음 allocate 값은 그보다 1 큰 값이다.

- 두 값의 차이
  - `lastFlushedEventSeq`는 DB가 어디까지 따라왔는지를 나타낸다.
  - `walLastEventSeq`는 WAL이 어디까지 기록됐는지를 나타낸다.

### WAL 정리 규칙

#### 현재 MVP
- active WAL 파일 1개를 사용한다.
- 현재 startup recovery와 runtime flush는 모두 active WAL 전체를 scan한다.
- WAL rotation/segment는 아직 구현하지 않는다.
- archive WAL cleanup은 아직 구현하지 않는다.
- active WAL 자동 truncate도 구현하지 않는다.

#### 후속 목표
- WAL rotation 또는 segment 도입
- archive WAL 파일의 마지막 `eventSeq`가 `wal_checkpoint.last_flushed_event_seq` 이하인 경우에만 안전하게 삭제
- active WAL 전체 scan 비용 제거

## 10) DB flush worker 처리 순서

### flush worker의 역할
flush worker는 **이미 WAL에 승인되어 있고 메모리에 반영된 상태**를  
나중에 DB로 따라가게 만드는 백그라운드 작업이다.

역할은 아래 3개다.
1. checkpoint 이후 실제 WAL record를 `pixel_events`에 저장
2. bootstrap-pending 최초 flush에서는 z=0 전체 1,024개, initialized 이후에는 WAL affected와 보조 dirty tile의 일관된 snapshot을 `tiles`에 저장
3. 두 저장이 모두 완료된 boundary를 `wal_checkpoint`에 기록

### flush 트리거 조건
- default profile에서 `pixel-place.flush.fixed-delay=1s`를 사용하는 fixed-delay trigger 하나만 활성화한다.
- 첫 실행 initial delay도 같은 설정값이다.
- `FlushScheduler`는 `FlushWorker.flushOnce()`를 호출하는 adapter이며 flush 정책이나 DB transaction을 소유하지 않는다.
- scheduled method는 `scheduler = "flushTaskScheduler"`를 명시한다.
- `flushTaskScheduler`는 `defaultCandidate = false`이고 `@Primary` 또는 fallback bean name `taskScheduler`를 사용하지 않는다.
- Boot 기본 `taskScheduler`와 별도 bean으로 격리되므로 qualifier 없는 다른 `@Scheduled` task는 정상 기본 scheduler를 사용한다.
- `stub` profile에서는 scheduler와 runtime flush bean이 비활성이다.
- 1000건 누적 또는 write-count 기반 trigger는 구현하지 않았다.

fixed-delay는 이전 invocation 종료 뒤 다음 간격을 보장하지만 whole-cycle single-flight를 대체하지 않는다. application `RuntimeException`은 scheduler adapter가 기록하고 정상 반환해 다음 invocation을 유지한다. adapter 밖으로 나온 raw JVM-level `Error`는 전용 `FlushSchedulingErrorHandler`가 원래 `Error`와 stack trace를 ERROR level로 기록하려 시도한 뒤 같은 instance로 재전파하여 해당 repeating task를 중단한다.

전용 handler는 `TaskUtils`의 전파/억제 상수를 직접 재사용하지 않는다. raw `Error` logging 실패는 원래 suppressed 순서와 identity를 보존하고 distinct logging failure만 한 번 뒤에 추가할 수 있으며, 원래 `Error`가 항상 primary다. non-Error는 logging 성공 또는 logging `RuntimeException` 뒤 정상 반환하여 scheduling continuity를 유지하고, logging 자체의 `Error`는 삼키지 않고 재전파한다.

### 핵심 불변식
`wal_checkpoint.last_flushed_event_seq` 는  
**그 값 이하에 실제로 존재하는 성공 WAL record가 DB `pixel_events`와 DB `tiles`에 모두 반영 완료된 상태**를 의미해야 한다.

즉 checkpoint는 부분 성공 지점이면 안 된다.

flush 정확성의 source of truth는 WAL이다. `DirtyTileTracker`는 추가 snapshot 대상과 실패 재시도 정보를 제공하는 보조 상태이며 flush 실행 여부, target, checkpoint 또는 필수 snapshot 대상의 유일한 근거가 아니다.

active WAL 전체의 `eventSeq`는 strictly increasing 해야 하지만 gap은 허용한다. checkpoint와 `pixel_events` 저장 범위는 정수 연속성이 아니라 checkpoint 이후 실제 WAL record를 기준으로 한다.

`flushTargetEventSeq`는 coordinator boundary 순간의 durable WAL tail로만 확정하며, 현재 memory에서 과거 eventSeq snapshot을 만들 수 없으므로 WAL tail보다 낮은 임의 중간 target을 선택하지 않는다.

### single-flight guard와 boundary coordinator 책임
- single-flight guard는 readiness 확인, checkpoint 조회, plan capture, DB transaction, 실패 재등록을 포함한 `flushOnce()` 전체의 중첩 실행을 막는다.
- 이미 다른 flush가 실행 중이면 boundary 작업을 시작하기 전에 반환한다.
- `FlushBoundaryCoordinator`는 write의 `eventSeq` 발급, WAL append + fsync, memory apply, dirty mark와 flush의 WAL scan 및 immutable plan capture 사이의 짧은 boundary만 보호한다.
- DB checkpoint와 tile bootstrap 상태 조회 및 실제 DB I/O는 coordinator 밖에서 수행하되 single-flight guard는 flush 종료까지 유지한다.
- readiness 1차 또는 coordinator 내부 2차 검사가 not-ready면 새로운 plan을 만들지 않는다.
- ready 상태에서 immutable plan capture를 마친 뒤 발생한 후속 fatal은 기존 plan을 취소하지 않으며, 그 plan은 capture한 `flushTargetEventSeq`까지만 transaction을 완료할 수 있다.

### flush worker 처리 순서
1. whole-cycle single-flight guard를 try-acquire하고, 이미 실행 중이면 `SKIPPED_ALREADY_RUNNING`을 반환한다.
2. cycle 첫 검사로 fatal-only `requireNotFatal()`을 호출한다. temporary not-ready는 이미 설치된 pending reconciliation을 막지 않는다.
3. pending이 있으면 WAL scan, dirty drain과 새 plan capture 없이 먼저 exact reconciliation한다.
4. pending이 없으면 `FlushPlanCaptureService`가 일반 `requireReady()`를 검사하고 checkpoint와 BLOB 없는 전체 tile key metadata를 조회한다.
5. 공통 `DbBootstrapClassifier`가 `BOOTSTRAP_PENDING`, `INITIALIZED`, `INCONSISTENT`를 판정하고 inconsistent 상태는 WAL/dirty 접근 전에 실패시킨다.
6. `FlushBoundaryCoordinator` 안에서 readiness를 다시 검사한 뒤 active WAL 전체 scan, newline/순서/tail 검증, dirty drain, target snapshot deep copy와 immutable plan 생성을 완료한다.
7. checkpoint 이후 WAL record가 없으면 dirty를 drain하지 않은 no-op plan을 반환한다.
8. bootstrap-pending non-no-op plan은 canonical z=0 전체 1,024 snapshots, initialized plan은 WAL affected와 drained dirty key 합집합 snapshots를 포함한다.
9. coordinator 밖에서 `pixel_events`, captured `tiles`, conditional checkpoint advance를 `REQUIRES_NEW` physical transaction 하나로 실행한다.
10. transaction 시작 실패 또는 body 실패 뒤 rollback 완료가 확인된 경우만 definite rollback이며 실제 drained dirty를 restore한다.
11. commit 호출 중 실패, rollback 완료 미확인, executor의 예기치 않은 실패와 invalid result는 ambiguous outcome으로 처리한다.
12. 모든 성공·실패·raw Error 경로에서 single-flight guard를 해제한다.

### 실패 시 처리 원칙
- `pixel_events`, `tiles`, checkpoint는 반드시 하나의 transaction으로 처리한다.
- commit이 시도되지 않았고 rollback 완료까지 확인된 경우에만 명확한 rollback으로 분류한다.
- commit 호출 중 예외, connection 종료, timeout, commit 시도 여부 불명 또는 rollback 완료 미확인 상태는 ambiguous commit으로 분류한다.
- 예외 class나 message만으로 rollback 완료를 추정하지 않는다. 분류할 수 없는 transaction 실패도 ambiguous commit으로 처리한다.
- definite rollback에서만 실제 drained dirty를 즉시 restore한다. WAL record, WAL affected key, target과 synthetic 전체 target은 별도 재등록하지 않는다.
- ambiguous outcome에서는 drained dirty를 tracker에 즉시 restore하지 않는다. `expected + target + DbBootstrapState + 실제 drained dirty`를 immutable `PendingAmbiguousFlush` candidate에 보존한다.
- `installIfAbsent(candidate)`의 정상 반환과 예외 모두 `current()`가 exact same candidate instance인지 재확인한다.
- exact candidate가 확인되면 설치 완료로 인정하고 `AmbiguousFlushCommitException`을 전파한다. 다음 invocation은 새 plan보다 pending reconciliation을 먼저 수행한다.
- pending이 null/empty/different이거나 `current()` 확인이 실패하면 dirty를 restore하지 않고 같은 process의 irreversible fatal-not-ready를 먼저 설치한 뒤 실패를 전파한다.
- fatal 설치는 non-throwing·idempotent process-local 상태 전환이며 `markReady()`나 `markNotReady()`로 해제되지 않는다. 후속 protected write, pending 조회/reconciliation, WAL scan, dirty drain, 새 plan capture와 DB transaction을 모두 차단한다.
- exact candidate 없이 새 persistence를 허용하면 outcome unknown transaction과 겹쳐 duplicate persistence가 가능하므로 같은 process에서는 fatal을 자동 clear하지 않는다.
- fresh process 재시작과 durable DB checkpoint/WAL bytes 기반 startup recovery가 복구 경계다. fatal state와 memory-only pending을 별도 persistence에 저장하지 않는다.

## 10.5) flush boundary / checkpoint 의미 확정

### 10.5단계의 성격
- 10.5단계는 production code 구현 단계가 아니라 11단계 flush worker 구현 전 flush boundary/checkpoint 설계 문서 확정 단계다.
- 이번 단계에서는 flush worker, repository, scheduler, transaction service, lock 구현체를 만들지 않는다.
- 아래 정책은 10.6단계가 교정한 최종 flush/checkpoint 계약과 충돌하지 않는 의미로 확정한다.

### checkpoint N의 정확한 의미
`wal_checkpoint.last_flushed_event_seq = N`은 아래 두 조건이 동일 DB transaction으로 완료된 상태다.

```text
WAL에 실제로 존재하는 성공 승인 record 중 eventSeq <= N인 모든 record가
DB pixel_events에 저장되어 있음

그 record들이 변경한 tile snapshot이
checkpoint N boundary와 일치하도록 DB tiles에 저장되어 있음
```

두 조건이 모두 만족된 뒤에만 checkpoint를 `N`으로 갱신한다. checkpoint가 실제 DB 반영보다 낮으면 중복 replay가 생길 수 있고, 높으면 복구에 필요한 WAL record를 건너뛸 수 있다.

### eventSeq gap 정책
WAL `eventSeq`는 strictly increasing 해야 하지만 contiguous할 필요는 없다. `100` 다음에 `102`가 있어도 `101`이 없다는 이유만으로 replay나 flush를 실패시키지 않는다.

저장 대상은 다음 범위에 실제로 존재하는 모든 WAL record다.

```text
lastFlushedEventSeq < eventSeq <= flushTargetEventSeq
```

동일 `eventSeq` 중복과 역순은 WAL corruption으로 처리한다. target까지 모든 정수 `eventSeq`가 존재해야 한다는 검증은 하지 않는다.

### WAL이 source of truth인 이유
현재 MVP flush worker는 `pixel_events` 저장 대상과 필수 tile snapshot 대상을 WAL에서 계산한다.

```text
pixel_events 저장 대상:
checkpoint 이후 실제 WAL record

필수 tile snapshot 대상:
checkpoint 이후 실제 WAL record가 변경한 모든 TileKey
```

checkpoint가 `100`이면 WAL에서 `eventSeq > 100`인 실제 record를 읽는다. 별도의 in-memory event buffer는 도입하지 않는다.

WAL은 승인 write의 1차 내구성 source이고 재시작 뒤에도 복구 기준으로 남는다. in-memory buffer는 장애 시 사라지고 dirty tracker는 이벤트 이력을 tile별 최신값으로 축약하므로 둘 다 flush 정확성의 source of truth가 될 수 없다.

### recovery 후 dirty tracker가 비어 있을 수 있음
startup recovery는 checkpoint 이후 WAL record를 memory board에 replay하지만 `DirtyTileTracker`를 채우지 않는다. 따라서 recovery 후 dirty tracker가 비어 있어도 checkpoint 이후 WAL record가 존재할 수 있다.

flush worker는 WAL에서 affected `TileKey`를 다시 계산해야 하며, recovery replay를 dirty tracker에 억지로 등록하는 방식으로 이 책임을 대신하지 않는다.

### flushTargetEventSeq는 durable WAL tail
`flushTargetEventSeq`는 이번 flush cycle에서 DB에 저장 완료할 마지막 `eventSeq`이며 다음 값으로만 정한다.

```text
FlushBoundaryCoordinator로 새 write를 차단한 boundary 순간의
active WAL durable tail
```

checkpoint가 `100`이고 boundary 순간 durable WAL tail이 `150`인 경우의 목표는 다음과 같다.

```text
100 < eventSeq <= 150 범위에 실제 존재하는 WAL record 저장
tiles snapshot도 150번 이벤트까지 반영된 상태로 저장
동일 transaction에서 checkpoint를 150으로 갱신
```

### 임의 중간 target 금지
현재 memory board는 최신 상태 한 벌만 보유하며 과거 `eventSeq`별 snapshot을 재구성할 MVCC나 version history가 없다. durable WAL tail이 `200`인 boundary에서 임의로 target을 `150`으로 잡으면 memory bytes에 `151~200`의 상태가 섞일 수 있다.

다음 값으로 임의 중간 target을 만들지 않는다.

```text
최대 N개 WAL record
dirty tile의 최대 latestEventSeq
EventSeqManager의 현재 발급값
durable WAL tail보다 작은 임의 eventSeq
```

ready 상태의 coordinator boundary에서는 memory에 반영된 마지막 승인 `eventSeq`, durable WAL tail, `flushTargetEventSeq`가 일치해야 한다.

### WAL affected tile 정책
checkpoint 이후 실제 WAL record가 변경한 모든 `TileKey`는 dirty tracker 상태와 관계없이 필수 snapshot 대상이다. dirty tiles만 drain해서 저장하는 것만으로 checkpoint를 올릴 수 없다.

```text
eventSeq 1: tile A 변경
eventSeq 2: tile B 변경
eventSeq 3: tile A 변경
```

dirty tracker는 최종적으로 다음 상태일 수 있다.

```text
A -> latestEventSeq 3
B -> latestEventSeq 2
```

A와 B를 drain해 저장했다는 사실만으로 checkpoint를 `3`으로 올릴 수 없다. 해당 범위에 실제로 존재하는 WAL record가 `pixel_events`에 저장되고 그 record가 변경한 tile snapshot도 boundary 3과 일치해야 한다.

### initialized 이후 일반 snapshot target 정책
DB에 canonical z=0 전체 1,024 rows가 형성된 이후 일반 snapshot 대상은 다음 합집합이다.

```text
snapshotTargetTileKeys =
    walAffectedTileKeys
    ∪ drainedDirtyTiles의 TileKey
```

WAL affected 집합은 checkpoint 정확성을 위한 필수 대상이고, drained dirty 집합은 추가 snapshot 및 실패 재시도 정보를 보존하는 보조 대상이다.

### bootstrap-pending 최초 full snapshot target 정책
`0 rows + checkpoint 0`인 bootstrap-pending 상태의 최초 non-no-op flush는 일반 합집합을 사용하지 않는다.

```text
firstSnapshotTargetTileKeys =
    canonical allZ0TileKeys(z=0, tx=0..31, ty=0..31)
```

checkpoint 이후 실제 WAL record가 하나라도 있으면 memory z=0 전체 1,024개를 target으로 정한다. 전체 bytes와 `tileVersion`을 같은 coordinator boundary에서 deep copy하고 bootstrap mode와 함께 immutable plan에 고정한다. 최초 transaction commit으로 canonical 1,024 rows가 형성된 뒤부터 일반 합집합 target을 사용한다.

### DirtyTileTracker의 역할
`DirtyTileTracker`는 live write 이후 dirty 상태 추적, flush 실패 후 재등록, 운영 관측, 추가 snapshot 대상 병합에 사용한다. 현재 Overview 생성 trigger에는 사용하지 않는다.

다음 항목의 source of truth로 사용하지 않는다.

```text
flush 실행 여부
flushTargetEventSeq
checkpoint boundary
필수 snapshot 대상의 유일한 출처
ambiguous commit 결과 판정
```

### write 성공과 WAL 내구성 경계 구분
WAL append + fsync 성공은 write의 1차 내구성 경계일 뿐 HTTP `200` 성공 조건 전체가 아니다. core write는 memory apply까지, 현재 HTTP `200` 성공 응답은 command 계약상 dirty mark까지 성공한 상태를 뜻한다. DB flush 완료는 HTTP 성공 조건이 아니다.

### command/core 이중 readiness 검사
`PixelCommandService`는 command 진입 직후 readiness를 사전 검사하고, `PixelWriteService`는 기존 `synchronized` monitor에 진입한 직후 core readiness를 다시 검사한다. 이미 외부 guard를 통과했거나 두 검사 사이 또는 monitor에서 대기한 write도 fatal 전환 뒤에는 WAL append를 시작하지 못해야 한다.

최초 fatal을 발생시킨 요청에는 해당 내부 오류를 그대로 전파하고, 이후 새 요청은 not-ready 의미로 차단한다. 최초 fatal 요청과 후속 `503` 요청을 같은 HTTP 의미로 처리하지 않는다.

### flush 중 새 write 처리 정책
DB tile snapshot은 memory보다 오래된 상태여도 되지만 checkpoint보다 미래 상태를 포함하면 안 된다. boundary 순간 target이 `150`이면 target tile bytes를 coordinator 안에서 deep copy하고, 이후 생성된 `151` write는 이번 plan에 섞지 않고 다음 flush 대상으로 넘긴다.

아래 상태는 허용하지 않는다.

```text
checkpoint = 150
DB tile snapshot = 151까지 반영된 상태
```

정리하면 다음과 같다.

```text
DB tile snapshot은 메모리보다 오래된 상태여도 된다.
하지만 checkpoint N을 저장한다면,
DB tile snapshot은 N까지의 상태와 맞아야 한다.
N 이후 이벤트가 섞이면 안 된다.
```

### flush single-flight guard
single-flight guard는 `flushOnce()` 전체의 중첩 실행을 막는다. readiness 확인, checkpoint 조회, plan capture, DB transaction, 실패 재등록이 끝날 때까지 유지하며, 이미 실행 중인 flush가 있으면 boundary 작업 전에 반환한다.

### write/flush boundary coordinator
single-flight guard와 `FlushBoundaryCoordinator`는 같은 lock이 아니다. coordinator는 write와 plan capture 사이의 boundary만 짧게 보호한다.

write가 coordinator로 보호할 범위는 다음과 같다.

```text
eventSeq allocate
WAL append + fsync
memory apply
dirty tile mark
```

cooldown, WebSocket broadcast, HTTP response와 DB I/O는 coordinator 밖에서 수행한다.

### checkpoint와 DB bootstrap 상태 조회 위치
`FlushBoundaryCoordinator`를 획득하기 전에 다음 DB 상태를 함께 조회한다.

```text
lastFlushedEventSeq
DB z=0 tile row 수
canonical key 완전성(z=0, tx=0..31, ty=0..31)
```

`0 rows + checkpoint 0`, canonical 1,024 rows, partial/inconsistent 상태를 coordinator 밖에서 판정한다. 1~1,023 rows, 조회한 z=0 snapshot의 canonical tx/ty 불완전·범위 밖 tx/ty 포함 또는 `0 rows + checkpoint > 0`은 dirty drain과 새 plan 전에 실패한다. checkpoint와 bootstrap 상태 DB I/O를 coordinator 안으로 옮기지 않는다.

### readiness 1차/2차 검사
coordinator 밖에서 checkpoint 조회 전에 readiness를 1차 확인하고, checkpoint 조회 뒤 coordinator를 획득한 직후 WAL scan 전에 2차 확인한다.

두 검사 중 하나라도 not-ready면 active WAL scan, dirty drain, snapshot capture, DB transaction, checkpoint 갱신을 시작하지 않고 새로운 flush plan 생성을 금지한다.

### coordinator 내부 plan capture 범위
coordinator 내부에서는 다음 작업만 수행한다.

```text
active WAL 파일 상태 검증
WAL 크기와 마지막 newline 검증
WAL 전체 record parsing과 strictly increasing 순서 검증
checkpoint 이후 실제 WAL record 확정
boundary 순간 durable WAL tail을 flushTargetEventSeq로 확정
WAL affected TileKey 계산
dirty tiles drain
bootstrap mode 판정 결과에 따라 target 선택
bootstrap-pending이면 canonical z=0 전체 1,024개
initialized이면 WAL affected TileKey와 drained dirty TileKey의 합집합
target tile bytes deep copy
tileVersion capture
captured snapshot 기준 dirty boundary 불변식 검증
bootstrap mode와 target snapshot을 포함한 immutable flush plan 생성
```

runtime WAL scan의 coordinator precondition은 flush orchestration이 보장한다. WAL replay source가 coordinator를 직접 획득하지 않는다.

### FlushBoundaryCoordinator 해제 보장
coordinator를 획득한 상태에서 직접 return하지 않는다. no-op, plan 생성 실패, WAL scan 예외와 invariant 위반을 포함한 모든 경로에서 `finally` 또는 동등한 구조로 coordinator를 해제한다.

### no-op plan과 coordinator 밖 return
checkpoint 이후 실제 WAL record가 없으면 dirty tracker를 drain하지 않은 no-op plan을 coordinator 안에서 만든다. coordinator를 해제한 뒤 밖에서 no-op 여부를 판정하고 반환한다. dirty tracker가 비어 있다는 이유로 WAL record가 있는 flush를 skip하지 않는다.

### dirty boundary 불변식과 snapshot capture 순서
target tile bytes와 `tileVersion`을 같은 coordinator boundary에서 먼저 capture한 뒤 다음 조건을 검증한다.

```text
drain한 모든 DirtyTile.latestEventSeq <= flushTargetEventSeq

drain한 모든 DirtyTile.latestTileVersion <=
같은 boundary에서 capture한 해당 tile snapshot.tileVersion
```

위반 시 DB transaction과 checkpoint 갱신을 금지하고 실제 drain한 dirty tiles만 재등록한 뒤 내부 불변식 위반 예외를 전파한다. WAL affected `TileKey`와 target은 checkpoint 미갱신 상태에서 다음 flush가 다시 계산한다. 예외 경로에서도 `FlushBoundaryCoordinator`와 single-flight guard를 반드시 해제한다.

### flush plan capture 이후 fatal 전환
ready 상태의 coordinator boundary에서 일관된 immutable plan capture를 완료했다면, coordinator 해제 뒤 새로운 fatal 전환이 발생해도 기존 plan을 자동 취소하지 않는다. 기존 plan은 capture한 `flushTargetEventSeq`까지만 DB transaction을 완료할 수 있다.

not-ready 전환 이후에는 새로운 flush plan의 시작과 capture를 금지한다. 후속 readiness 전환만을 이유로 transaction 직전에 이미 안전하게 capture한 plan을 취소하지 않는다.

### readiness와 WAL-memory 일치 불변식
서비스가 ready이고 flush가 허용되는 상태라면 durable WAL tail까지의 모든 성공 WAL record가 memory에 반영되어 있어야 한다. WAL fsync 뒤 memory apply 실패는 이 불변식을 깨므로 fatal이며, not-ready 상태에서 WAL tail과 memory의 불일치를 감춘 새 plan을 만들지 않는다.

### WAL 전체 스캔의 MVP 한계
현재 startup recovery와 runtime flush는 active WAL 파일 1개를 처음부터 끝까지 scan한다. rotation/segment/archive cleanup, WAL read offset과 자동 truncate는 후속 범위다.

### WAL record가 없는 경우
checkpoint 이후 실제 WAL record가 없으면 dirty tracker 상태와 무관하게 checkpoint를 전진시키지 않는다. bootstrap-pending이어도 dirty drain과 DB write 없이 no-op plan만 만들고 coordinator 밖에서 반환하며 DB `tiles`는 0 rows로 유지한다.

### tile snapshot deep copy
snapshot target 전체의 tile bytes는 coordinator 안에서 새 배열로 deep copy하고 같은 boundary에서 `tileVersion`을 capture한다. coordinator 해제 뒤 memory tile이 바뀌어도 captured plan bytes가 함께 변하지 않아야 한다.

### runtime active WAL scan의 안정된 boundary
active WAL 파일 상태와 크기 확인, 마지막 newline 검증, 전체 record parsing과 strictly increasing 순서 검증, durable tail 확정, WAL affected `TileKey` 계산, dirty drain, snapshot capture와 immutable plan 생성을 하나의 `FlushBoundaryCoordinator` 구간에서 수행한다. 중간에 coordinator를 해제하거나 write append를 허용하지 않는다.

`FileWalReplaySource`가 coordinator를 직접 획득하지 않으며 flush orchestration이 이 runtime scan precondition을 보장한다.

### FileChannel.force(true) 기준 durable WAL tail
현재 durable WAL tail은 완전한 JSON line, 마지막 newline, file write 완료와 해당 append의 `FileChannel.force(true)` 성공까지 확인된 마지막 실제 record다. eventSeq 발급값이나 memory의 추정값으로 대체하지 않는다.

### 모든 성공 append의 force(true)와 force(false) 금지
신규 파일 첫 append, 기존 파일 append와 같은 파일의 두 번째 이후 append를 포함한 모든 성공 append는 `force(true)` 완료 뒤에만 성공을 반환한다. 정상 append 경로에서 `force(false)`를 호출하거나 이를 durable WAL tail의 근거로 사용하지 않는다.

### parent-directory fsync 미적용 한계
현재는 parent directory fsync를 수행하지 않으므로 file creation과 directory entry의 엄격한 crash durability를 보장하지 않는다. 14단계 rotation/segment도 같은 한계를 유지하며 parent-directory fsync 실제 구현은 17단계 범위다.

### 실패 후 dirty 소유권
현재 dirty tracker의 `drainDirtyTiles()`는 현재 쌓인 dirty tile 목록을 반환하고 내부 목록을 비운다.

따라서 다음 문제가 생길 수 있다.

```text
dirty tiles drain 완료
DB 저장 실패
checkpoint는 안 올라감
하지만 dirty tracker는 이미 비워짐
```

definite rollback은 checkpoint 미갱신과 rollback 완료가 확인된 상태이므로 실제 drain한 항목만 tracker에 restore한다. restore 시 이미 더 최신 dirty가 있으면 `SynchronizedDirtyTileTracker`의 큰 `eventSeq`/version 보존 정책을 따른다.

```text
ambiguous commit:
tracker 즉시 restore 금지
expected + target + bootstrap state + 실제 drained dirty를 pending candidate에 보존
다음 flush는 새 plan 전에 exact reconciliation
```

WAL record, WAL affected `TileKey`, `flushTargetEventSeq`와 최초 full snapshot의 synthetic 전체 target은 별도로 재등록하지 않는다. pending은 WAL/snapshot/full plan을 저장하지 않으며 과거 immutable plan을 재실행하는 경로가 아니다.

### DB transaction 필수
`pixel_events`, `tiles`, `wal_checkpoint`는 반드시 하나의 DB transaction으로 처리한다. checkpoint 갱신은 transaction 내부의 application-level 마지막 작업이다.

```text
1. lastFlushedEventSeq < eventSeq <= flushTargetEventSeq 범위에 실제 존재하는 WAL record를 pixel_events에 저장
2. captured target tile snapshot을 tiles에 저장
3. 1, 2 성공 뒤 wal_checkpoint.last_flushed_event_seq를 flushTargetEventSeq로 갱신
4. 하나의 transaction으로 commit
```

bootstrap-pending 최초 transaction은 `pixel_events + canonical z=0 전체 1,024 tiles + wal_checkpoint`를 함께 commit한다. initialized 이후에는 일반 target tile snapshot만 저장한다. 최초 transaction이 명확히 rollback되면 `0 rows + checkpoint 0`, commit되면 `canonical 1,024 rows + target checkpoint`가 관측되어야 하며 partial row 상태를 허용하지 않는다.

### DB transaction commit 결과 불명확 상태
DB 처리 중 예외가 발생했다고 항상 rollback된 것은 아니다.

commit 호출 중 예외, connection 종료, timeout처럼 DB commit 여부를 애플리케이션이 확정할 수 없는 상태를 ambiguous commit으로 다룬다. 예외 class나 message만으로 rollback 완료를 추정하지 않는다.

### 명확한 rollback과 ambiguous commit 분류 기준

```text
명확한 rollback:
commit이 시도되지 않았고 rollback 완료까지 확인된 경우

ambiguous commit:
commit 호출 중 예외, connection 종료, timeout
commit 시도 여부를 확정할 수 없는 경우
rollback 완료를 확인할 수 없는 경우
그 밖에 결과를 분류할 수 없는 transaction 실패
```

명확한 rollback은 commit이 시도되지 않았고 rollback 완료까지 확인된 경우로만 제한한다. 분류할 수 없는 transaction 실패도 ambiguous commit이다.

### DB checkpoint 기반 ambiguous commit reconciliation
ambiguous commit이면 동일 plan을 즉시 다시 실행하지 않는다. 다음 flush는 새 plan보다 pending을 먼저 읽고, `JpaFlushDbStateProbe`의 별도 transaction에서 main checkpoint lock과 전체 key metadata를 관측해 exact equality만 판정한다.

```text
commit 확인:
observed checkpoint == pending.flushTargetEventSeq
AND observed state == INITIALIZED
→ pending dirty 폐기
→ exact same pending clear

rollback 확인(Bootstrap pending):
observed checkpoint == pending.expectedLastFlushedEventSeq == 0
AND observed state == BOOTSTRAP_PENDING
→ pending dirty restore
→ restore 성공 뒤 exact same pending clear

rollback 확인(Initialized):
observed checkpoint == pending.expectedLastFlushedEventSeq
AND observed state == INITIALIZED
→ pending dirty restore
→ restore 성공 뒤 exact same pending clear

그 밖의 checkpoint/state 조합:
pending 유지
새 plan 금지
fail-fast
```

`checkpoint >= target`이나 `checkpoint < target` 같은 범위 판정은 사용하지 않는다. 다른 flush 결과를 이전 transaction에 귀속하거나 같은 reconciliation invocation에서 재계획하지 않으며, `INSERT IGNORE`나 eventSeq 중복 skip으로 결과 불명확 상태를 은폐하지 않는다.

### MVP에서 금지할 구현
현재 flush worker 계약에서 다음 구현을 금지한다.

```text
dirty tracker가 비어 있다는 이유만으로 flush를 no-op 처리하는 구현
dirty tile의 latestEventSeq만 보고 checkpoint를 올리는 구현
dirty tiles를 DB에 저장했다는 이유만으로 checkpoint를 max latestEventSeq까지 올리는 구현
dirty tile만으로 필수 snapshot 대상을 정하는 구현
durable WAL tail보다 낮은 임의 flushTargetEventSeq를 만드는 구현
EventSeqManager 발급값을 durable WAL tail로 사용하는 구현
eventSeq의 정수 연속성을 요구하는 구현
pixel_events DB 저장 없이 tile snapshot만 저장하고 checkpoint를 올리는 구현
tile snapshot 저장 실패 후 checkpoint를 올리는 구현
checkpoint 갱신 실패 후 dirty tile을 잃는 구현
flushTargetEventSeq 이후 write가 섞인 tile snapshot을 checkpoint N의 snapshot처럼 저장하는 구현
checkpoint DB 조회를 FlushBoundaryCoordinator 내부에서 수행하는 구현
coordinator를 획득한 상태에서 no-op return하는 구현
single-flight guard와 FlushBoundaryCoordinator를 동일한 lock으로 취급하는 구현
DB `tiles`가 암묵적으로 pre-init됐다고 가정하는 구현
최초 bootstrap flush에서 일부 tile만 저장하는 구현
partial DB snapshot을 white tile로 채워 정상화하는 구현
synthetic 전체 1,024 target을 dirty로 재등록하는 구현
bootstrap 상태를 확인하지 않고 checkpoint만으로 ambiguous commit을 판정하는 구현
not-ready 이후 새로운 flush plan을 만드는 구현
ambiguous commit 직후 동일 plan을 다시 실행하는 구현
모든 transaction 예외를 명확한 rollback으로 단정하는 구현
DB 저장 중 장시간 write 전체를 막는 구현
WebSocket broadcast나 overview 성공 여부를 checkpoint 조건에 포함하는 구현
```

### 현재 MVP 범위 밖
현재 flush/recovery MVP는 다음을 구현하지 않는다.

```text
WAL rotation/segment
WAL archive/index/offset/truncate
group commit
partial WAL line 자동 복구
JDBC/Hibernate batch tuning
다중 인스턴스 distributed flush lock
Redis flush coordination
ShedLock/Quartz
pending ambiguous state persistence
동일 immutable plan 자동 재실행
1000건 누적 또는 write-count trigger
flush 전용 scheduler의 application 전역 기본 scheduler 등록
production metrics/alert 체계와 custom scheduler thread-pool tuning
```

single-flight와 scheduler는 JVM 단일 process 보호다. 다중 application instance의 동시 flush를 막는 분산 lock이 아니다. active WAL 전체 scan은 coordinator 안에서 수행하므로 WAL이 커지면 write blocking 시간이 길어질 수 있다. parent-directory fsync도 적용하지 않는다.

후속 최적화 판단을 위해 checkpoint lag, WAL backlog, 한 plan의 event/tile 수와 byte 크기, coordinator 보유 시간, WAL scan 시간과 DB transaction 시간을 측정 후보로 둔다. canonical 1,024-tile bootstrap은 운영 예정 heap에서 peak heap과 전체 transaction elapsed time을 실측한다. 현재 단계에서 production metric/alert나 근거 없는 통과 임계값을 추가하지 않는다.

### 현재 bootstrap/recovery 검증
- `0 rows + checkpoint 0 + WAL 없음`: dirty drain과 DB write 없는 no-op, DB tiles 0 rows 유지
- `0 rows + checkpoint 0 + WAL 1건`: canonical z=0 전체 1,024 snapshot을 포함한 최초 transaction
- 최초 flush commit 뒤 restart: canonical 1,024 rows load, target checkpoint 이하 WAL replay 생략, 변경 pixel/version 보존
- 1 row, 1,023 rows, 조회한 z=0 snapshot의 canonical tx/ty 불완전·범위 밖 tx/ty 포함: dirty drain과 새 plan 전에 실패
- `0 rows + checkpoint > 0`: startup recovery의 markReady 전과 flush plan 생성 전에 불일치로 실패
- 최초 transaction 명확한 rollback: drained dirty restore 뒤 다음 invocation이 현재 WAL에서 full bootstrap을 새로 capture
- ambiguous commit: exact target/expected checkpoint와 `BOOTSTRAP_PENDING`/`INITIALIZED` state 조합만 commit/rollback으로 판정
- pending 설치 저장 전 실패, 저장 뒤 install 예외, 다른 pending과 current 확인 실패에서 exact identity/fail-closed 검증
- 실제 MySQL `REPEATABLE_READ` capture가 checkpoint 뒤 concurrent flush-shaped commit 중에도 checkpoint/key/snapshot 세 read의 동일 snapshot을 유지
- 최초 full capture 중 write 차단, 전체 bytes/version deep copy와 immutable plan 유지
- WAL append/fsync 실패 뒤 `FileWalAppender` poison과 추가 append 차단
- active WAL이 비어 있지 않을 때 마지막 newline 검증
- WAL 성공 뒤 memory apply 실패 시 ServiceReadiness fatal 전환
- `PixelCommandService` 진입 직후 command-level readiness 사전 검사
- `PixelWriteService`의 기존 `synchronized` 진입 직후 core readiness 재검사
- ready 상태에서는 durable WAL tail까지 memory apply 완료
- readiness 1차 또는 coordinator 내부 2차 검사가 false면 새 flush plan 생성 금지
- checkpoint와 DB tile bootstrap 상태를 coordinator 밖에서 함께 조회하고 partial/inconsistent 상태 fail-fast
- bootstrap-pending 최초 non-no-op plan은 canonical z=0 전체 1,024개 capture
- ready 상태에서 이미 일관되게 capture한 plan은 후속 fatal에도 capture target까지만 transaction 완료 가능
- not-ready 전환 이후 새로운 flush plan 시작과 capture 금지
- 신규 파일, 기존 파일과 동일 파일 후속 append 모두 `FileChannel.force(true)` 완료 뒤 성공 반환
- parent-directory fsync와 partial-line truncate recovery는 아직 구현하지 않음

### 최종 의미
- WAL = flush event range와 필수 affected tile의 source of truth
- 메모리 = 실시간 authoritative state이며 coordinator boundary에서 target snapshot capture 원본
- DirtyTileTracker = 추가 snapshot 대상과 실패 재등록을 위한 보조 상태
- DB = `pixel_events`, `tiles`, checkpoint를 하나의 transaction으로 따라가는 후행 저장소
- DB tile bootstrap = 최초 non-no-op transaction에서 canonical z=0 전체 1,024 rows 원자 형성
- single-flight guard = `flushOnce()` 전체 중첩 방지
- FlushBoundaryCoordinator = write와 WAL scan/snapshot capture 사이의 짧은 boundary 보호
- flush worker = WAL 기준 immutable plan을 capture하고 DB checkpoint를 안전하게 전진시키는 작업

## 11) 현재 flush/recovery 구현 구조

현재 runtime flush와 startup recovery의 핵심 package 경계는 다음과 같다.

```text
flush/application
  DbBootstrapClassifier, FlushBoundaryCoordinator
  FlushPlanCaptureService, FlushWorker, FlushSingleFlightGuard
  PendingAmbiguousFlush(Store), FlushReconciliationService
  FlushPersistenceService

flush/infra
  ProgrammaticFlushTransactionExecutor, JpaFlushDbStateProbe

flush/scheduling
  FlushScheduleProperties, FlushScheduler
  FlushSchedulingConfiguration, FlushSchedulingErrorHandler

pixel/infra
  PixelEventEntity, PixelEventJpaRepository, JpaPixelEventWriter

tile/infra
  TileEntity, TileJpaRepository
  JpaTileSnapshotLoader, JpaTileSnapshotWriter

checkpoint/infra
  JpaCheckpointReader, JpaCheckpointFence
  WalCheckpointEntity, WalCheckpointJpaRepository

recovery/application
  StartupRecoveryDbView, StartupRecoveryDbViewCaptureService
  StartupRecoveryService, ServiceReadiness

overview/application
  OverviewRenderer, OverviewService

overview/scheduling
  OverviewScheduler

overview/web
  OverviewController
```

`FlushWorker`는 scheduler 없이 직접 호출 가능한 public entrypoint이며 scheduler는 호출 adapter다. startup recovery와 runtime plan/persistence/reconciliation은 같은 `DbBootstrapClassifier`를 사용해 partial DB 의미가 경로마다 달라지지 않게 한다. Overview는 이 경로와 분리된 read-only in-memory renderer/service/scheduler/controller 구조다. 실제 package와 test가 이 목록의 source of truth이며 이후 보안·성능 기능은 이 구조에 포함하지 않는다.

## 12) stub profile 범위와 recovery adapter 선택

### profile의 책임

`stub` profile은 모든 외부 인프라를 제거하는 profile이 아니라 startup recovery의 입력 adapter만 결정론적 stub으로 교체하는 profile이다.

| recovery 입력 port | 기본 profile | `stub` profile | 현재 production 소비자 |
|---|---|---|---|
| `CheckpointReader` | `JpaCheckpointReader` (`!stub`) | `StubCheckpointReader` (`stub`) | `StartupRecoveryDbViewCaptureService`, `FlushPlanCaptureService` (`!stub`) |
| `TileSnapshotLoader` | `JpaTileSnapshotLoader` (`!stub`) | `StubTileSnapshotLoader` (`stub`) | `StartupRecoveryDbViewCaptureService` |
| `WalReplaySource` | `FileWalReplaySource` (`!stub`) | `StubWalReplaySource` (`stub`) | `StartupRecoveryService`, `FlushPlanCaptureService` (`!stub`) |

각 profile에서는 위 port별 bean이 정확히 하나만 활성화된다. 세 stub은 production adapter 미구현을 대신하는 임시체가 아니며 checkpoint 0, z=0 snapshot 전체 미존재, 빈 WAL replay라는 startup recovery 입력만 대체한다.

### 대체하지 않는 infrastructure

`stub` profile에서도 다음 production infrastructure는 남을 수 있다.

- `FileWalAppender`와 실제 WAL append 경로
- Redis cooldown adapter
- DataSource/JPA auto-configuration과 Spring Data repository
- recovery 입력 외의 production bean

따라서 `stub`은 DB/Redis/WAL이 없는 전체 application profile이 아니다. 다만 `FlushScheduler`, `FlushSchedulingConfiguration`, `FlushWorker`, persistence/reconciliation runtime bean은 `!stub`으로 비활성화된다. 외부 인프라가 없는 제한 context 테스트는 recovery adapter bean 선택만 검증하고 adapter의 DB/WAL 메서드를 호출하지 않는다.

### runtime 소비자 안전장치

Spring profile의 bean 교체는 특정 injection 지점이 아니라 해당 port의 모든 소비자에게 적용된다. startup capture는 선택된 checkpoint/tile adapter를 사용하고, startup recovery와 runtime plan capture는 같은 `WalReplaySource.readAfter(...)` 계약을 공유한다. recovery/runtime WAL read port를 분리하지 않는다.

다음 조합은 실제 WAL과 DB checkpoint 정합성을 깨뜨릴 수 있으므로 허용하지 않는다.

```text
stub profile 활성
+ FileWalAppender 실제 WAL 기록
+ StubWalReplaySource runtime WAL read
+ 실제 pixel write 또는 활성 FlushWorker/scheduler
```

`stub` profile의 정상 기능 테스트 범위는 startup recovery와 제한 context bean 선택 테스트다. 이 profile에서 실제 pixel write, `FileWalAppender` 기록, runtime flush/scheduler, DB persistence 정합성 검증을 수행하지 않는다. `FlushWorker` 자체 테스트는 profile 대신 constructor fake/mock을 직접 주입한다.

## 13) 현재 Overview PNG runtime 계약

### source와 표본 규칙

- source of truth는 `InMemoryTileBoard`의 canonical z=0 타일 1,024개다. DB, WAL, checkpoint, eventSeq, tileVersion, dirty/pending 상태를 읽거나 변경하지 않는다.
- renderer는 각 canonical 타일을 한 번 조회하고 해당 `TileState.pixels()` snapshot을 한 번 얻는다. 타일 사이의 write를 막는 전체 보드 lock이나 전체 보드 선복사를 사용하지 않는다.
- 출력 좌표 `(ox, oy)`는 입력 `(ox * 4, oy * 4)`의 palette index를 사용한다. 각 `4 × 4` 블록의 평균이나 다수결이 아니라 좌상단 한 픽셀 표본이다.
- Java signed byte는 `Byte.toUnsignedInt`로 `0..255` index로 변환하고 production `PaletteConstants`의 정확한 256개 `#RRGGBB` 순서를 사용한다.
- 누락 타일, snapshot 길이 불일치, palette 길이/형식 오류, PNG writer의 false/checked exception/null·empty 결과는 실패다.

### 게시와 실패 격리

- `OverviewService`는 별도 `AtomicBoolean`으로 generation 전체를 non-blocking single-flight 처리한다. 이미 생성 중인 호출은 기다리지 않고 즉시 반환한다.
- renderer가 완전한 비어 있지 않은 PNG를 반환한 뒤에만 하나의 `AtomicReference<byte[]>`를 교체한다. 조회자는 부분 생성 byte를 볼 수 없다.
- `RuntimeException`은 throwable을 포함해 기록하고 기존 정상 PNG와 readiness를 그대로 유지한다. guard는 성공·실패 모두 해제돼 다음 invocation이 재시도할 수 있다.
- raw `Error`는 삼키지 않으며 guard 해제 후 원래 오류가 전파된다.
- 최초 생성 실패 시 게시본은 없는 상태를 유지하고, 재생성 실패 시 마지막 정상 게시본을 유지한다.

### lifecycle, scheduler와 profile

- `OverviewScheduler`는 `!stub`에서만 존재한다. `stub`에는 renderer/service/controller는 존재하지만 Overview 자동 trigger와 scheduling infrastructure는 존재하지 않는다.
- 정기 작업은 context refresh 단계부터 등록될 수 있으므로 Overview 전용 lifecycle gate가 `ApplicationReadyEvent` 이전 invocation을 정상 skip한다. 이 gate는 Service readiness 상태가 아니며 Scheduler는 readiness를 직접 조회하지 않는다.
- `ApplicationReadyEvent` listener는 gate를 한 번 연 뒤 최초 생성 작업을 동기 실행하지 않고 named Boot 기본 `taskScheduler`에 정확히 1회 제출한다. 중복 event는 최초 작업을 중복 제출하지 않는다.
- event 이후 정기 작업만 `@Scheduled(fixedDelay=10000, initialDelay=10000)`의 기본 scheduler routing으로 `OverviewService.refresh()`에 위임한다. 이는 flush 전용 `flushTaskScheduler(defaultCandidate=false)`와 분리되며 Overview용 추가 scheduler pool을 만들지 않는다.
- 최초 작업 제출 실패 또는 null future 반환 시 gate를 되돌리지 않고 기록한 뒤 다음 fixed-delay invocation이 재시도한다. application `RuntimeException`은 service가 격리하며 raw `Error`는 scheduler adapter에서 가로채지 않는다.
- refresh 시작 시 readiness가 false이면 renderer를 호출하지 않는다. 이후 not-ready가 되면 기존 게시본은 보존하지만 MVC readiness guard가 API 노출을 차단한다.

### HTTP와 metadata

- `GET /api/overview` 정상 응답은 `200`, `Content-Type: image/png`, `Cache-Control: no-cache`, 완전한 `2048 × 2048` PNG다.
- readiness를 통과했지만 게시본이 없으면 `503`과 `{ "message": "Overview image is not available." }`를 반환한다.
- `BoardInfoResponse.overviewRefreshSeconds`는 runtime fixed-delay와 같은 `BoardConstants.OVERVIEW_REFRESH_SECONDS == 10`을 사용한다.
- OAuth2/JWT, 명시적 `SecurityFilterChain`, final `permitAll`, dirty 기반 부분 렌더링, Overview DB 저장은 12단계 범위가 아니다.

## 14) 인증·사용자 처리 흐름 — 13단계

현재는 인증 설정·사용자 저장 기반, JWT·요청 쿠키와 Spring 로그인·handoff 교환 구성요소가 구현돼 있다. 설정과 사용자 저장 역할은 production에 등록돼 있고, 로그인·교환은 테스트에서 명시적으로 조립한다. production 로그인·교환 endpoint와 JWT 인증 chain은 아직 활성화하지 않아 기존 Basic/form과 `X-User-Id` 쓰기 경로가 유지된다. 구간별 완료·검증 결과는 `../작업기록/phase-13-progress.md`에서 관리한다.

### 시작 시 설정 준비

1. `auth/config`에서 외부로 주입된 두 암호키, 토큰·쿠키 수명과 Origin/callback 설정을 검증한다. 잘못된 설정은 비밀 원문을 포함하지 않는 오류로 시작을 중단한다.
2. 검증된 설정, Origin 정책과 공용 UTC `Clock`을 소비자에게 공급한다. 토큰과 쿠키는 같은 설정·시계를 사용하며 별도 기본키나 시계로 우회하지 않는다.
3. 요청의 Host/Forwarded 값으로 callback을 추측하지 않고 설정된 URI를 사용한다. 로컬은 같은 localhost의 frontend 3000/API 8080 구성이며, Origin과 쿠키 속성은 이 배치를 전제로 검증한다.

### 카카오 식별자에서 내부 사용자 ID 얻기

1. `UserProvisioningService`는 양의 카카오 사용자 ID로 기존 사용자를 조회하고, 있으면 내부 ID를 반환한다.
2. 없으면 신규 사용자를 저장한다. 동시에 같은 사용자를 생성해 UNIQUE 충돌이 발생하면 실패한 저장 transaction이 종료된 뒤 별도 조회로 이미 생성된 사용자를 찾는다.
3. 재조회에서도 사용자를 찾지 못하면 원래 무결성 오류를 전파한다. 호출자가 연 transaction에 조회·실패한 저장·재조회를 한꺼번에 묶지 않는다.

`users`의 저장 구조는 이 문서 §8과 `pixel_place.sql`을 따른다. 사용자 조회·저장은 JWT 발급의 책임과 분리하며 기존 WAL/event 데이터의 사용자 식별자를 이 흐름에서 바꾸지 않는다.

### 서비스 토큰 발급과 검증

1. `ServiceJwtTokens`는 내부 사용자 ID를 받아 Access 또는 handoff 용도의 토큰을 발급한다. 발급 시각·만료는 공용 설정과 Clock에서 계산한다.
2. Access는 보호 API 인증, handoff는 로그인 결과 전달을 위한 교환에만 사용한다. 각 소비자는 자신의 전용 decoder를 사용하며 두 종류를 서로 대신 받아들이지 않는다.
3. 수신 토큰은 서명과 용도·사용자 ID·시간을 검증하고 원본 claim 타입도 확인한다. 잘못된 값·만료·변환 오류는 토큰 원문을 포함하지 않는 인증 실패로 처리한다.

정확한 claim·수명·허용 시간차와 경계 사례는 실행 명령문 및 `auth/jwt` 코드·테스트에 둔다. 여기서는 발급과 검증의 책임·순서를 설명한다.

### 로그인 요청을 임시 쿠키로 보관하기

1. 저장할 OAuth 요청의 callback·state·PKCE 정보를 검사한다. 요청 필드를 JSON으로 만들고 매번 새 nonce를 사용해 AES-GCM으로 암호화한다.
2. 쿠키 값과 완성된 응답 헤더의 크기를 각각 검사한 뒤에만 게시한다. 검사에 실패하면 새 live cookie를 일부만 게시하지 않는다.
3. 요청 쿠키를 읽을 때는 형식·크기 확인 후 GCM 인증을 먼저 수행한다. 인증된 내용만 JSON으로 복원하고 서버 만료·요청 필드·PKCE 일치를 확인한다.
4. 쿠키가 없거나 중복·변조·만료된 경우 repository는 요청 없음으로 처리한다. 삭제는 생성 때와 같은 쿠키 속성에 만료를 지정하며, 이미 commit된 응답에 저장·삭제가 성공한 것처럼 처리하지 않는다.

이 흐름의 역할은 `AuthorizationRequestCookieCodec`과 `CookieAuthorizationRequestRepository`가 나눈다. 쿠키에 저장된 state의 유효성과 실제 callback으로 받은 state의 일치 검사는 별개이며, 후자는 Spring 로그인 연결에서 처리한다. 브라우저의 새 로그인은 기존 요청 쿠키를 덮어쓸 수 있고, 쿠키 Path나 port를 별도 보안 경계로 사용하지 않는다.

### Spring 로그인과 결과 전달

1. `KakaoAuthorizationRequestResolver`는 명시적 시작 GET만 Spring 기본 resolver에 전달하고, 매 요청의 새 verifier와 S256 challenge를 생성한다. 간접 registration 요청으로 새 로그인을 시작하지 않는다.
2. callback에서는 Spring 필터가 요청 쿠키를 복원하고 state를 비교한 뒤, 같은 verifier를 token HTTP 요청에 전달한다. `KakaoOAuth2UserService`는 registration을 먼저 확인하고 실제 userinfo 처리를 Spring에 위임한다. 원본 ID를 Spring 사용자 객체 생성 전에 검증한 뒤 기존 provisioning으로 내부 ID를 얻는다.
3. 성공 handler는 내부 ID·handoff·완성 쿠키·고정 callback 준비를 끝낸 후에만 live handoff를 게시한다. callback에서는 Access를 발급하지 않는다. 준비 실패와 미commit 인증 실패는 두 쿠키와 SecurityContext를 정리하고 고정 실패 callback으로 이동한다. provisioning 실패는 Spring 자체 오류 로그에도 DB/provider 원문 cause가 전달되지 않도록 고정 인증 실패로 바꾼다.
4. 시작 필터에도 failure handler와 no-save request cache를 공식 확장 지점으로 연결한다. OAuth redirect는 no-referrer를 사용하고 callback 입력을 frontend URL에 복사하지 않는다. commit 이후 전송 오류는 응답을 다시 변경하지 않고 전파한다.

명시적 테스트 chain은 stateless session 정책·null SecurityContext 저장소·no-save request cache와 실제 no-op authorized-client 저장소를 사용한다. provider token을 session·서비스 저장소로 넘기지 않으며 principal에는 내부 ID와 카카오 ID를 구분해 남긴다. production chain과 method/path guard 연결은 C의 책임이다.

### handoff를 Access로 교환하기

1. HTTP 역할은 trusted Origin을 먼저 확인한다. Origin이 없거나 복수·금지 값이면 쿠키 해석과 token 처리 전에 거부한다.
2. 교환 service는 handoff 전용 decoder를 사용하고 내부 ID로 새 Access를 발급한다. 인증 실패와 발급 이후 준비 실패를 분리하여 각각 401과 일반화된 500으로 응답한다.
3. 발급된 실제 exp와 공용 Clock의 응답 계산 시각으로 남은 완전한 초를 구한다. 허용 범위를 벗어난 시간을 고정 TTL로 대체하지 않는다. JSON 준비 뒤 handoff 쿠키를 삭제하고 캐시 금지 응답을 게시한다.

브라우저 후속 연결에서는 교환에 기존 Authorization을 붙이지 않고 필요한 경우 credentials를 포함한다. 받은 Access는 sessionStorage에 보관하여 보호 API의 Bearer로 사용하며 공개 read에는 자동 첨부하지 않는다. 클라이언트 logout은 보관 token 삭제다. 유효 handoff 복제본의 수명 내 재교환을 막는 서버 상태는 없으므로 일회용 코드라고 설명하지 않는다.

### 후속 연결 경계

다음 C에서는 테스트로 확인한 구성요소를 production registration·chain·교환 route에 연결하고 Pixel 사용자 ID 공급원을 함께 전환한다. 현재 테스트 조립의 성공은 production 인증 활성화, 실제 카카오 PKCE binding 또는 runtime DB 반영의 증거가 아니다. 실제 CORS·Resource Server 선행 거부와 HTTP/WS guard, 실제 callback route·카카오 검증은 후속 범위다.
