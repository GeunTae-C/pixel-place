# pixel-place-details — MVP API 상세 스펙 확정본

## 0) 공통 원칙

### 인증 적용 범위
- `GET /api/board` : 비로그인 허용
- `GET /api/tiles/0/{tx}/{ty}` : 비로그인 허용
- `GET /api/overview` : 비로그인 허용
- `POST /api/pixels` : 로그인 필요
- `WebSocket /ws` : MVP에서는 비로그인 허용 가능, 이후 JWT 적용 가능

### 응답 원칙
- 성공 응답은 **HTTP 200 + 데이터 바디**로 함께 내려간다.
- 에러 응답은 **HTTP 4xx/5xx + 공통 JSON 포맷**으로 내려간다.

### 공통 에러 응답 예시
```json
{
  "timestamp": "2026-03-11T04:00:00",
  "status": 429,
  "error": "Too Many Requests",
  "code": "COOLDOWN_ACTIVE",
  "message": "쿨다운이 남아 있습니다.",
  "path": "/api/pixels"
}
```

### 공통 에러 응답 필드
- `timestamp`: 에러 발생 시각
- `status`: HTTP 상태코드
- `error`: HTTP 상태 텍스트
- `code`: 서버 내부 에러 코드
- `message`: 사용자/개발자 확인용 메시지
- `path`: 요청 경로

---

## 1) 에러 코드 초안

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

## 2) API 상세 스펙

---.

### A. `GET /api/board`

#### 역할
- 초기 설정값 조회

#### 인증
- 불필요

#### 요청
- Query 없음
- Body 없음

#### 응답 상태코드
- `200 OK`
- `500 Internal Server Error`

#### 응답 예시
```json
{
  "size": 8192,
  "tileSize": 256,
  "paletteSize": 256,
  "palette": ["#FFFFFF", "#E4E4E4", "#888888", "#222222", "..."],
  "overviewRefreshSeconds": 10
}
```

#### 응답 필드
- `size`: 보드 한 변 길이
- `tileSize`: 타일 한 변 길이
- `paletteSize`: 팔레트 색상 수
- `palette`: 팔레트 색상 배열
- `overviewRefreshSeconds`: overview 갱신 주기

#### 확정 메모
- 현재 값이 고정에 가깝더라도 **팔레트 전달 때문에 유지**한다.

---

### B. `GET /api/tiles/0/{tx}/{ty}`

#### 역할
- 원본 타일 1개 조회

#### 인증
- 불필요

#### 의미
- `z=0` 레벨에서 `(tx, ty)` 위치의 타일 1개를 가져온다.
- MVP 기준 타일 좌표 범위:
    - `tx: 0 ~ 31`
    - `ty: 0 ~ 31`

#### 요청 예시
```http
GET /api/tiles/0/3/5
```

#### 응답 상태코드
- `200 OK`
- `400 Bad Request` : 잘못된 좌표
- `500 Internal Server Error`

#### 성공 응답
- `Content-Type: application/octet-stream`
- `Content-Encoding: gzip`
- body 내용: `256 * 256 = 65536 bytes`
- 각 바이트는 팔레트 인덱스(`0~255`)

#### 타일 초기화 확정
- lazy create는 사용하지 않는다.
- 서버 시작 시 `z=0` 전체 타일을 all-white 상태로 pre-init 한다.
- 따라서 정상 범위의 `z=0` 타일은 항상 row가 존재한다고 본다.
- 정상 범위 좌표인데 타일 row가 없다면 일반적인 조회 miss가 아니라 내부 이상 상황으로 보고 `500 Internal Server Error`로 처리한다.

#### 권장 응답 헤더
```http
Content-Type: application/octet-stream
Content-Encoding: gzip
Cache-Control: no-cache
ETag: "tile-0-3-5-v12"
X-Tile-Version: 12
X-Tile-Size: 256
```

#### 헤더 의미
- `X-Tile-Version`
    - 현재 타일의 논리 버전 숫자
    - 서버 내부 `tiles.tile_version` 값과 동일
    - 즉, **`X-Tile-Version == tileVersion`**
- `ETag`
    - HTTP 캐시 검사용 리소스 식별자
    - 예: `ETag: "tile-0-3-5-v12"`

#### ETag 해석
- `tile` = 타일 리소스
- `0` = `z=0`
- `3` = `tx=3`
- `5` = `ty=5`
- `v12` = `tileVersion=12`

즉:

> `z=0`, `(tx=3, ty=5)` 타일의 현재 버전이 `12`라는 뜻이다.

#### tileVersion
- 각 타일은 tileVersion을 가진다
- 타일 내용이 바뀌면 `tileVersion`이 1 증가한다.
- `tileVersion`이 바뀌면 `ETag`도 함께 바뀐다.



#### 에러 예시
```json
{
  "timestamp": "2026-03-11T04:00:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_TILE_COORDINATE",
  "message": "타일 좌표가 범위를 벗어났습니다.",
  "path": "/api/tiles/0/40/2"
}
```

---

### C. `GET /api/overview`

#### 역할
- 전체 보기용 축약 이미지 조회

#### 인증
- 불필요

#### 요청
- Query 없음
- Body 없음


#### 응답 상태코드
- `200 OK`
- `500 Internal Server Error`

#### 성공 응답 헤더 예시
```http
Content-Type: image/png
Cache-Control: no-cache
```

#### 성공 응답 Body
- `image/png` 포맷의 overview 이미지

#### 확정 사항
- 크기: **`2048 x 2048`**
- 목적: 전체 탐색용 read-only 이미지
- 최신 보드 상태와 **최대 10초 이내 차이**가 있을 수 있음

#### 처리 방향
- 요청 때마다 즉석 생성보다,
- **서버가 주기적으로 재생성한 최신 overview 결과물**을 내려주는 방향으로 간다.

#### 운영 상세 확정
- overview는 dirty 상태를 기준으로 재생성한다.
- 포맷은 `PNG`로 고정한다.
- 생성 실패 시 이전에 정상 생성된 overview를 계속 유지한다.
- 단일 서버 기준 최신 overview는 서버 메모리에 들고 있어도 된다.

---

### D. `POST /api/pixels`

#### 역할
- 특정 좌표 픽셀 변경 요청

#### 인증
- 필요

#### 요청 헤더
```http
Authorization: Bearer <JWT>
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
- `200 OK`
- `400 Bad Request` : 좌표/색상 범위 오류
- `401 Unauthorized` : 로그인 안 됨 / 토큰 없음
- `403 Forbidden`
- `429 Too Many Requests` : 쿨다운 중
- `500 Internal Server Error`

#### 성공 응답 예시
```json
{
  "accepted": true,
  "x": 100,
  "y": 200,
  "color": 17,
  "eventSeq": 12345,
  "tileVersion": 991,
  "cooldownRemainingMs": 180000
}
```

#### 응답 필드
- `accepted`: 반영 여부
- `x`, `y`, `color`: 최종 반영된 값
- `eventSeq`: 이벤트 최종 반영 순서값
- `tileVersion`: 수정 후 타일 버전
- `cooldownRemainingMs`: 요청 처리 직후 기준, 다음 픽셀 요청까지 남은 시간

#### Redis 쿨다운 동작 확정
- 키 형식은 `cooldown:user:{userId}` 이다.
- `PTTL > 0` 이면 아직 쿨다운 중이므로 요청을 거부한다.
- 키가 없거나 TTL이 만료된 경우 요청을 진행한다.
- write가 성공적으로 반영된 경우에만 `180초` TTL을 설정한다.
- 유효성 오류, DB 오류 등 실패한 write에는 쿨다운을 부여하지 않는다.

#### 인증 관련 확정
- 토큰은 body에 넣지 않는다.
- 추후 인증 토큰은 **Authorization 헤더**로 전달한다.

#### 좌표 오류 예시
```json
{
  "timestamp": "2026-03-11T04:00:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_COORDINATE",
  "message": "픽셀 좌표가 범위를 벗어났습니다.",
  "path": "/api/pixels"
}
```

#### 색상 오류 예시
```json
{
  "timestamp": "2026-03-11T04:00:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_COLOR",
  "message": "유효하지 않은 팔레트 인덱스입니다.",
  "path": "/api/pixels"
}
```

#### 쿨다운 예시
```json
{
  "timestamp": "2026-03-11T04:00:00",
  "status": 429,
  "error": "Too Many Requests",
  "code": "COOLDOWN_ACTIVE",
  "message": "쿨다운이 남아 있습니다.",
  "path": "/api/pixels"
}
```


---

### E. `WebSocket /ws`

#### 역할
- 픽셀 변경 diff 실시간 수신

#### 연결 경로
```text
/ws
```

#### 인증
- MVP에서는 읽기 채널 성격으로 보고 인증 없이 연결을 허용할 수 있다.
- 단, 쓰기 권한은 `POST /api/pixels`에서만 인증으로 제한한다.

#### 서버 → 클라이언트 이벤트 예시 1: 단건
```json
{
  "type": "pixel",
  "x": 100,
  "y": 200,
  "color": 17,
  "eventSeq": 12345
}
```

#### 서버 → 클라이언트 이벤트 예시 2: 배치
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
- 기존 `seq` / `eventSeq` 혼용을 없애고
- **`eventSeq`로 통일**한다.

#### 운영 방식 확정
- 단일 서버 기준 서버 메모리 broadcast를 사용한다.
- WAL append + fsync가 성공했다면 write는 성공으로 본다.
- WebSocket broadcast 실패는 write rollback 사유가 아니다.
- broadcast 실패 시 서버는 로그만 남기고, 클라이언트는 이후 타일 재동기화 로직으로 정합성을 회복한다.

#### 클라이언트 처리 원칙
- diff 수신 시 해당 픽셀만 즉시 반영
- `eventSeq` gap 감지 시 관련 타일 재요청

---

## 3) API별 내부 처리 흐름

### `GET /api/board`
1. 보드 상수/설정 로드
2. 팔레트 포함 JSON 응답

---

### `GET /api/tiles/0/{tx}/{ty}`
1. `tx`, `ty` 범위 검증
2. 메모리 타일 상태에서 `(z=0, tx, ty)` 타일 조회
3. `data`, `tileVersion` 읽기
4. gzip 압축 후 응답
5. 헤더에 `X-Tile-Version`, `ETag` 포함

---

### `GET /api/overview`
1. 서버가 보관 중인 최신 정상 overview 이미지 조회
2. 최신 이미지가 있으면 그대로 반환
3. 생성 작업이 실패했더라도 이전 정상 overview가 있으면 그 이미지를 계속 사용
4. PNG 응답 반환

---

### `POST /api/pixels`
1. JWT 인증 확인
2. Redis에서 `cooldown:user:{userId}` 조회
3. `PTTL > 0` 이면 `429 COOLDOWN_ACTIVE`로 종료
4. body 검증
5. 좌표/색상 범위 검증
6. `(x, y) → (tx, ty)` 계산
7. 서버가 `AtomicLong`으로 `eventSeq` 발급
8. WAL 레코드 생성
9. WAL append
10. 요청 단위 fsync 수행
11. fsync 성공 시 승인 가능
12. 메모리 타일 상태 반영
13. `tileVersion++`
14. dirty 타일 표시
15. 성공 시에만 Redis 쿨다운 키에 `180초` TTL 설정
16. 성공 응답 반환
17. `eventSeq`, `tileVersion` 포함 WebSocket diff broadcast 시도
18. broadcast 실패는 write rollback 사유가 아니며, 로그만 남긴다
19. 이후 flush worker가 DB 반영을 수행한다

---

### `/ws`
1. 클라이언트 연결
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

---

### created_at
- `pixel_events.created_at`은 별도로 둔다.
- 이 값은 **이벤트 승인 시각 기록용**이다.

---

### 시간과 순서의 역할 분리
- **시간 기록:** `created_at`
- **정합성 판단 기준:** `eventSeq`

즉:
- 시간은 기록/분석/감사용
- 순서는 실제 반영 정합성 기준

---

### 충돌 제어
- 같은 타일에 대한 동시 수정은 **타일 단위 직렬 처리** 원칙을 유지한다.
- 다만 현재 구조에서는 DB row lock이 아니라,
  **메모리 authoritative state 기준의 애플리케이션 레벨 직렬 처리**로 본다.
- 즉, 같은 `(z, tx, ty)` 타일에 대한 승인 이벤트는 순서대로 반영된다.

---

### 확정 결론
- 전역 단일 큐 / 나노초 정렬 / 외부 메시지 브로커까지는 MVP에서 과하다.
- 대신 아래 4개를 가져간다.
  1. **최종 반영 순서값 필요 → `eventSeq`**
  2. **이벤트 발생 시각 기록 필요 → `created_at`**
  3. **같은 타일에 대한 충돌 제어 필요 → 타일 단위 직렬 처리**
  4. **전파 실패 복구 필요 → broadcast 실패는 로그 처리 + 클라이언트 재동기화**

---

## 5) tileVersion 확정

### 의미
- `tileVersion`은 타일 기준 상태 버전이다.
- 각 타일 row의 `tile_version` 컬럼 값을 의미한다.

### 증가 규칙
- 해당 타일 내부 픽셀이 변경될 때마다 `1` 증가한다.

예:
- 변경 전: `tile_version = 12`
- 픽셀 1개 수정 후: `tile_version = 13`

### 목적
- 클라이언트가 들고 있는 타일이 최신인지 판단하기 위함
- mismatch 감지 시 해당 타일 재요청

---

## 6) 상태코드 규칙

### 조회 API
- 성공: `200`
- 잘못된 좌표/파라미터: `400`
- 존재하지 않는 리소스: `404`
- 서버 오류: `500`

#### 메모
- MVP 기준 `GET /api/tiles/0/{tx}/{ty}` 의 정상 범위 `z=0` 타일은 pre-init 되므로 일반 흐름에서 `404`를 사용하지 않는다.

### 쓰기 API
- 성공: `200`
- 잘못된 요청: `400`
- 인증 실패: `401`
- 권한 없음: `403`
- 쿨다운: `429`
- 서버 오류: `500`

---

## 7) 현재 확정 결론 요약

### 확정 API
- `GET /api/board`
- `GET /api/tiles/0/{tx}/{ty}`
- `GET /api/overview`
- `POST /api/pixels`
- `WebSocket /ws`

### 확정 응답 방향
- 성공: **HTTP 200 + 데이터**
- 에러: **HTTP 4xx/5xx + 공통 JSON 포맷**

### 확정 핵심 메타
- overview 크기: **2048x2048**
- overview 특성: **전체 탐색용 read-only 이미지**, 최신 상태와 **10초 내외 차이**
- 이벤트 순서값: **`eventSeq`로 통일**
- `eventSeq`:
  - 서버가 `AtomicLong`으로 직접 발급하는 순서값
  - WAL에 먼저 기록된다
  - 이후 DB flush 시 `pixel_events.event_seq`에 같은 값을 그대로 저장한다
  - 서버가 최종 반영한 순서
- 시간 기록: **`created_at`**
- 타일 버전: **`tileVersion`**, 타일 수정 시 1 증가
- `X-Tile-Version == tileVersion`
- `ETag`는 **타일 위치 + 버전 기반 HTTP 캐시 식별자**
- 충돌 제어: **타일 단위 직렬 처리**


## 8) WAL 반영 최종 DB 스키마

### 현재 구조에서 DB의 역할
- 실시간 authoritative state는 DB가 아니라 **메모리 타일 상태**다.
- 승인된 write의 1차 내구성은 **WAL 파일**이다.
- DB는 **후행 저장소 + 복구 시작점** 역할을 한다.

즉:
- 성공 응답 의미 = **WAL append + fsync 성공**
- DB 반영은 나중에 flush worker가 수행한다

---

### 테이블 구성
- `users`
- `tiles`
- `pixel_events`
- `wal_checkpoint`

---

### `users`
#### 역할
- 카카오 로그인 사용자 식별용 최소 저장소

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
- MVP 기준 `z=0` 전체 1024 row pre-init 유지

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
- PK는 `event_seq`
- 최소 인덱스 전략으로 시작
- FK는 `user_id -> users(id)`만 유지

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

---

### 최종 DDL

    CREATE TABLE users (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
      kakao_user_id BIGINT UNSIGNED NOT NULL,
      created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      PRIMARY KEY (id),
      UNIQUE KEY uk_users_kakao_user_id (kakao_user_id)
    );

    CREATE TABLE tiles (
      z TINYINT UNSIGNED NOT NULL,
      tx TINYINT UNSIGNED NOT NULL,
      ty TINYINT UNSIGNED NOT NULL,
      data MEDIUMBLOB NOT NULL,
      tile_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      PRIMARY KEY (z, tx, ty),
      KEY idx_tiles_updated_at (updated_at)
    );

    CREATE TABLE pixel_events (
      event_seq BIGINT UNSIGNED NOT NULL,
      user_id BIGINT UNSIGNED NOT NULL,
      z TINYINT UNSIGNED NOT NULL,
      tx SMALLINT UNSIGNED NOT NULL,
      ty SMALLINT UNSIGNED NOT NULL,
      x SMALLINT UNSIGNED NOT NULL,
      y SMALLINT UNSIGNED NOT NULL,
      color TINYINT UNSIGNED NOT NULL,
      created_at DATETIME(3) NOT NULL,
      PRIMARY KEY (event_seq),
      KEY idx_pixel_events_user_id (user_id),
      CONSTRAINT fk_pixel_events_user
      FOREIGN KEY (user_id) REFERENCES users(id)
    );

    CREATE TABLE wal_checkpoint (
      checkpoint_name VARCHAR(64) NOT NULL,
      last_flushed_event_seq BIGINT UNSIGNED NOT NULL,
      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
      PRIMARY KEY (checkpoint_name)
    );

    INSERT INTO wal_checkpoint (checkpoint_name, last_flushed_event_seq) 
    VALUES ('main', 0);


## 9) WAL 처리 흐름

### WAL의 역할
- WAL은 **승인된 픽셀 write의 1차 내구성 저장소**다.
- 현재 구조에서 성공의 의미는 **DB 반영 완료가 아니라 WAL append + fsync 성공**이다.

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

### 픽셀 요청 처리에서 WAL 흐름
1. JWT 인증
2. Redis 쿨다운 확인
3. 쿨다운 중이면 즉시 `429`
4. body 검증
5. 좌표 / 색상 범위 검증
6. 타일 좌표 계산
7. `AtomicLong`으로 `eventSeq` 발급
8. WAL 레코드 생성
9. WAL append
10. 요청 단위 fsync 수행
11. fsync 성공 시 승인 가능
12. 메모리 타일 상태 반영
13. `tileVersion++`
14. dirty 타일 표시
15. Redis 쿨다운 TTL 180초 설정
16. 성공 응답 반환
17. WebSocket diff 전송
18. 이후 flush worker가 DB 반영

### WAL replay
부팅 시 순서:
1. `wal_checkpoint.last_flushed_event_seq` 읽기
2. DB `tiles` 전체 로드
3. WAL 마지막 레코드의 `eventSeq` 확인
4. lastFlushedEventSeq 이후 WAL만 순서대로 replay
5. 메모리 상태 복구
6. `lastIssuedEventSeq = max(lastFlushedEventSeq, walLastEventSeq)` 초기화
7. 서비스 오픈

### Recovery 기준 시퀀스 용어

- `lastFlushedEventSeq`
  - `wal_checkpoint.last_flushed_event_seq`에 저장되는 값이다.
  - DB flush worker가 `pixel_events`와 `tiles`에 모두 반영 완료한 마지막 `eventSeq`를 의미한다.
  - boot recovery에서는 이 값 이하의 WAL 이벤트를 replay하지 않는다.

- `walLastEventSeq`
  - active WAL 파일을 끝까지 읽어서 확인한 마지막 `eventSeq`다.
  - replay 대상 여부와 관계없이 계산한다.
  - boot 이후 `lastIssuedEventSeq`는 `max(lastFlushedEventSeq, walLastEventSeq)`로 초기화하고, 다음 allocate 값은 그보다 1 큰 값이다.

- 두 값의 차이
  - `lastFlushedEventSeq`는 DB가 어디까지 따라왔는지를 나타낸다.
  - `walLastEventSeq`는 WAL이 어디까지 기록됐는지를 나타낸다.

### WAL 정리 규칙
- active WAL 파일 1개 사용
- 일정 크기 또는 일정 이벤트 수 기준으로 rotate
- archive WAL은 해당 파일의 마지막 `eventSeq`가 `last_flushed_event_seq` 이하일 때만 삭제 가능

## 10) DB flush worker 처리 순서

### flush worker의 역할
flush worker는 **이미 WAL에 승인되어 있고 메모리에 반영된 상태**를  
나중에 DB로 따라가게 만드는 백그라운드 작업이다.

역할은 아래 3개다.
1. 승인된 이벤트를 `pixel_events`에 batch insert
2. dirty 타일 상태를 `tiles`에 update
3. 완전 반영 지점을 `wal_checkpoint`에 기록

### flush 트리거 조건
- **1초 경과**
- 또는 **1000건 누적**

둘 중 먼저 도달한 조건으로 flush를 시작한다.

### 핵심 불변식
`wal_checkpoint.last_flushed_event_seq` 는  
**그 값 이하의 이벤트가 DB `pixel_events`와 DB `tiles`에 모두 반영 완료된 상태**를 의미해야 한다.

즉 checkpoint는 부분 성공 지점이면 안 된다.

### flush worker 처리 순서
1. flush 대상 eventSeq 범위 확정
2. WAL에서 해당 범위 이벤트 수집
3. `pixel_events` batch insert
4. dirty `tiles` batch update
5. `wal_checkpoint.last_flushed_event_seq` update
6. commit 성공 후 dirty 해제

### 실패 시 처리 원칙
- `pixel_events` insert 실패 → flush 실패, checkpoint 갱신 금지
- `tiles` update 실패 → checkpoint 갱신 금지, dirty 해제 금지
- checkpoint update 실패 → 완전 flush 성공으로 간주하지 않음

### 최종 의미
- WAL = 승인 직후 내구성
- 메모리 = 실시간 authoritative state
- DB = 후행 저장소
- flush worker = WAL/메모리 상태를 DB가 뒤늦게 따라가게 만드는 작업

dev.cgt.pixelplace
├─ PixelPlaceApplication.java
├─ common
│  ├─ config
│  │  ├─ SecurityConfig.java
│  │  └─ JacksonConfig.java
│  ├─ error
│  │  ├─ ErrorCode.java
│  │  ├─ ApiException.java
│  │  ├─ ErrorResponse.java
│  │  └─ GlobalExceptionHandler.java
│  ├─ response
│  │  └─ ApiResponses.java
│  └─ constant
│     └─ BoardConstants.java
├─ board
│  ├─ api
│  │  └─ BoardController.java
│  ├─ application
│  │  └─ BoardMetaService.java
│  └─ dto
│     └─ BoardResponse.java
├─ tile
│  ├─ api
│  │  └─ TileController.java
│  ├─ application
│  │  └─ TileReadService.java
│  ├─ domain
│  │  ├─ TileKey.java
│  │  ├─ TileState.java
│  │  └─ InMemoryTileBoard.java
│  ├─ dto
│  └─ infra
│     ├─ TileEntity.java
│     └─ TileJpaRepository.java
├─ overview
│  ├─ api
│  │  └─ OverviewController.java
│  ├─ application
│  │  └─ OverviewService.java
│  └─ domain
│     └─ OverviewState.java
├─ pixel
│  ├─ api
│  │  └─ PixelController.java
│  ├─ application
│  │  ├─ PixelWriteService.java
│  │  └─ EventSeqManager.java
│  ├─ dto
│  │  ├─ PixelWriteRequest.java
│  │  └─ PixelWriteResponse.java
│  └─ infra
│     ├─ PixelEventEntity.java
│     └─ PixelEventJpaRepository.java
├─ wal
│  ├─ application
│  │  ├─ WalAppender.java
│  │  ├─ WalReplayService.java
│  │  └─ WalRecordParser.java
│  └─ domain
│     └─ WalRecord.java
├─ checkpoint
│  ├─ application
│  │  └─ CheckpointService.java
│  └─ infra
│     ├─ WalCheckpointEntity.java
│     └─ WalCheckpointJpaRepository.java
├─ recovery
│  └─ application
│     └─ StartupRecoveryService.java
├─ flush
│  └─ application
│     └─ FlushWorker.java
├─ cooldown
│  ├─ application
│  │  └─ CooldownService.java
│  └─ infra
│     └─ RedisCooldownRepository.java
├─ realtime
│  ├─ application
│  │  └─ WebSocketBroadcastService.java
│  └─ ws
│     ├─ PixelWebSocketHandler.java
│     └─ WebSocketConfig.java
├─ auth
│  ├─ security
│  │  ├─ AuthUser.java
│  │  └─ JwtAuthenticationFilter.java
│  └─ application
└─ user
└─ infra
├─ UserEntity.java
└─ UserJpaRepository.java
