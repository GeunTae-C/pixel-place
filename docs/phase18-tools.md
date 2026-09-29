# 18 실행 기반 계약 v2

현재 제공 범위는 A-1 Plan·입력 검사·순차 프로세스·제어 fixture다. production 앱/HTTP·WS 발생기/collector/완성 분석기/공식 PILOT은 연결하지 않았다. 기존 15/17 plan·guard·도구는 변경하지 않는다.

## 실행 인터페이스

`dev.cgt.benchmark.Phase18Main --action Plan|VerifyTools --plan <절대 JSON 경로>`.
JDK21과 이미 컴파일한 benchmark runtime classpath로 직접 실행한다. `Plan`은 파일 읽기·해시·경로·여유 검사와 stdout 출력만 수행한다. Gradle compilation 자체는 별도 준비 작업이다. `Run`, `Analyze`, `Close` 및 알 수 없는 action은 입력 파일 읽기 전 거부한다. 현재 Close는 미구현이며 이름/PID만 보고 다른 프로세스를 종료하지 않는다.

`phase18ToolsClasspath` Gradle task는 `phase18ClasspathFile`로 지정한 새 파일에 benchmark runtime classpath를 기록하고 `.json` sidecar에 source/compiled class/resources/JAR 해시를 연결한다. `scripts/measure-phase18.ps1 -Action Plan|VerifyTools -PlanPath ... -Java ... -ClasspathFile ...`은 해당 클래스 경로로 직접 JVM을 실행한다. 직접 Java 호출도 `-Dphase18.runtimeReceipt=<ClasspathFile>.json`이 필요하다. actual runtime bytes와 source receipt가 다르면 action을 거부한다. launcher는 실행 정책/서비스/Git 설정을 변경하지 않는다. 실제 동결 입력과 복사 가능한 호출은 `작업기록/phase-18-progress.md`에 기록한다.

## 계획 schema

정확한 필드·자료형은 `Phase18Plan`의 불변 record가 정의한다. 모든 key 필수, 중첩 unknown/duplicate key·null·숫자 문자열·소수·지수형 수·NaN·무한대·overflow 거부. 속도는 정수 requests/second만 지원한다. byte/millisecond/second/ppm 단위는 필드 접미사에 고정한다. 리스트도 방어 복사한다.

- 루트: `schemaVersion=2`, `phase=A|B|C|D`, `stage=A-1|A-2|A-3|A-4|B|C|D`, `state=draft|ready`, `sessionId`, `sourceBaseline`(40 hex), `predecessor`(path/sha256), `inputs`, `ownership`, `bounds`, `cases`. WS 예약 의미를 교정한 v2만 수용하며 이전 v1 계획·증거는 재해석하지 않는다. raw v1과 source/runtime receipt v1은 독립 version이다.
- planHash는 자기참조 필드가 아니라 읽은 JSON 원본 bytes SHA-256이다. `Loaded.planHash`·Plan 출력·runtime/결과 receipt에 저장한다. source 입력 집합은 src/scripts의 비 ZIP·비 pyc 파일, build.gradle/settings.gradle/gradlew.bat/DDL/이 계약 문서다. 상대 path+SHA256 집합을 현재 파일의 추가·삭제까지 비교한다. 선행 인계는 별도 파일 hash다.
- ownership: repo/JDK·session/evidence/build/cache/temp/JNA/WAL의 정확한 local absolute 경로. evidence는 repo/build/agent-runs/sessionId, sessionRoot는 repo 밖의 해당 sessionId. root 안의 build/cache/temp/JNA는 상호 비중첩. Windows reparse/별칭/상대 경로/ADS/예약 이름/끝 공백·점 거부. WAL은 repo/data/sessionId 아래만 후보로 허용하며 실제 지원 검증은 A-2 책임이다. dbInstance/catalog/redisIndex/owner는 실제 서비스 실행의 재확인 입력이다. tools에서는 WAL/DB/catalog 빈 문자열·Redis -1 사용.
- bounds: write/read terminal 각각 최대200,000, users200,000, WS event200,000, sampler10,000, 연결100. 메모리 최대512MiB와 입력별 보수적 예약 산식을 별도 검사한다. output/WAL/DB/binlog/build·볼륨 최소 여유·누적 시간·stream log 상한을 체크한다. 메모리 산식은 pilot 측정값이 아니며 A-2 실제 구조/A-4 실측으로 확인해야 한다.
- case: 유일 caseId/runId·repeatIndex·role(pilot/target/diagnostic/soak)·kind(tools/prepare/write/read/mixed/soak)·plannedStartUtc, workload/runtime/observation. 반복은 서로 다른 ID의 명시 case로 표현하며 caseId도 중복 금지다.
- workload: write/read rate/pattern, seed, WS 연결, 예열/측정초, 독립 in-flight, request/ready/control/drain millis, once/reuse·userPool·reuseMarginMillis, 입력 허용 ppm·판정 창·동시 구간, bootstrap 여부. write0/read만 허용하며 write target으로 승격하지 않는다. user reuse/UNKNOWN retire의 실제 동작은 A-2다.
- runtime: group/single, batch16/outstanding128/queue1000ms/shutdown10000ms/flush1000ms·segment bytes·measurement on/off, JVM options `[-Xmx512m,-XX:+UseG1GC]`, InnoDB flush/syncBinlog 각1. 미래 실제 launcher·DI 대조는 A-2다. A-1 synthetic child는 별도128MiB로 제한한다.
- observation: sample/maxAge/maxMissing/stopAck millis, required/auxiliary 지표 목록, fallback와 stopRule. 활성 수집기/안전 확인은 A-3다. draft는 서비스 현장 공백을 남길 수 있으나 VerifyTools는 ready/A-1/tools 1개만 허용한다. 미구현 실행은 ready 표시만으로 해제되지 않는다.

계산은 checked arithmetic만 사용한다. write/read 각 phase의 계획량은 rate×phase seconds, bootstrap은 별도0/1이다. once users는 bootstrap을 포함한 전체 write 수, reuse는 bootstrap+pool이다. 표본은 전체 예정 시간과 timeout 예약을 sample 간격으로 나눈 상한이다. case별 보유량과 순차 전체 출력/시간을 모두 검사한다.

`bounds.wsEvents`와 `Counts.wsEvents`는 전역 **고유 이벤트** 상한/예약이다. WS 연결이 있으면 예열+측정 write 수이며 연결 전 bootstrap을 제외한다. 연결0이면 WS 고유 이벤트/수신량/bitset 예약은0이다. `wsDeliveries=wsEvents×wsConnections`는 연결 전체의 예상 수신 횟수이며 고유 이벤트 상한과 비교하지 않는다. `wsBitsetBytes=ceil(wsEvents/64)×8×wsConnections`는 연결별 수신 집합의 word 정렬 예약이다. 공통 eventSeq→ordinal/canonical 필드 저장소는 고유 이벤트당128bytes, 연결별 관리256bytes와 bitset을 메모리에 예약한다. WS 출력은 고유 이벤트당256bytes+bitset+연결별 요약1024bytes이며 payload를 연결마다 복제하지 않는다. 전체 메모리는16MiB+terminal당512bytes+user당256bytes+WS 예약+sample당256bytes, 출력은1MiB+terminal당2048bytes+WS 예약+sample당1024bytes다. 이는 A-1의 보수적 계획값이며 실제 자료구조 크기·부하 메모리 검증은 A-2/A-4 책임이다.

대표 W105/s·30/120초·WS100은 고유15,750/수신1,575,000/bitset197,600bytes, SOAK W105/s·30/1800초·WS100은 고유192,150/수신19,215,000/bitset2,402,400bytes다. bootstrap은 write terminal/user에는 포함하고 WS 기대 집합에는 포함하지 않는다. 기존 고유200,000·연결100·512MiB 상한은 유지한다.

## raw·시간·receipt 연결 규칙

raw v1은 A-2 producer와 A-3 analyzer의 계약이며 A-1은 실제 HTTP raw나 분석 결과를 생산하지 않는다.

requestId=`runId/caseId/write|read/bootstrap|warmup|measurement/ordinal`. ordinal은 각 namespace의 0-based 값. 해당 namespace 범위를 넘는 ID는 거부한다. 예정 offset nanos=`floor(ordinal×1e9/rate)`. phase 시작은 ready/warmup 소진 handshake 이후의 실제 발생기 clock anchor이며 plannedStartUtc는 예정 실행 시각이다. drain 완료로 예정 phase/ID를 바꾸지 않는다. bootstrap은 ordinal0·offset0이다.

각 terminal은 schemaVersion, planHash, runId/caseId/requestId, kind, scheduledPhase, ordinal, clockId(pid/startUtc), scheduledOffsetNanos, decidedNanos, sentNanos(nullable), completedNanos(nullable), status, reason, httpStatus(nullable), eventSeq/tileVersion(nullable)를 갖는다. raw 상태는 accepted, rejected_429, rejected_4xx, server_5xx, timeout, network_error, malformed_200, unexpected_status, client_not_sent. UNKNOWN은 timeout/network_error 등 송신 뒤 결과 불명 상태의 별도 `outcome=UNKNOWN`; `resolution=unresolved|recorded|not_recorded`로 후속 정합성 판정과 분리한다. sent 여부 자체 불명은 `sendState=unknown`이며 not_sent로 꾸미지 않는다. `sendState=sent|not_sent|unknown`, `outcome=ACCEPTED|REJECTED|UNKNOWN|NOT_SENT`를 명시한다. not_sent는 실제 결정 시각과 이유를 보유하며 미래 예정 시각을 가짜 완료 시각으로 쓰지 않는다.

run 상태는 DRAFT/READY/RUNNING/STOPPING/FAILED/INCOMPLETE/COMPLETE. 입력 미충족·SLO 미달·통계적 판정은 실행 상태와 별도다. unresolved 목록은 requestId·sendState·reason·마지막 관측 시각을 갖는다. callback 소진 뒤 누락된 ID, 실제 송신/결과 불명과 프로세스·pipe 미종료는 INCOMPLETE/FAILED로 남긴다. 원래 planned는 줄이지 않는다. 정상 COMPLETE는 모든 예정 ID에 terminal 정확히1개, 중복/계획 밖0·unresolved0과 필수 증거·정합성·정상 소진/종료 성공을 모두 요구한다. 완전성/분모/분포의 정식 계산은 A-3 책임이다.

시각: UTC는 ISO-8601, duration은 같은 clockId의 nanos끼리만 계산한다. 부모 stop occurrence/notified/ack 수신은 부모 clock, APPLIED payload의 nanos는 자식 clock이다. 둘을 직접 빼지 않는다. cross-JVM 정렬은 handshake의 부모 송수신 범위·UTC 및 오차로 표현해야 한다. fixture는 전달 순서만 검증하며 정확한 cross-JVM 지연이나 실제 WAL→WS 지연을 주장하지 않는다.

runtime receipt 필수 필드: schemaVersion/planHash/input-set hash, case/run/session/stage, 실제 launcher/JDK/user/OS, PID/start identity, command/args hash, actual config/mode/segment/measurement, phase clock anchors·units, ownership/live proof, 시작·종료/exit/evidence status. initial receipt는 이에 initialAction·catalog/WAL identity·동결 source/runtime hash·정상 종료·raw/drain/consistency hash를 추가한다. A-2에서 실제 앱/복구 receipt를 생산하고 source/fixture를 재초기화하지 않는 동일 입력 gate로 사용한다. A-1 runtime.json은 fixture용 최소 receipt이며 이를 production initial receipt로 사용할 수 없다.

## 프로세스·제어 API

`Phase18Process.run(Command, Body)`: ProcessBuilder argv 배열, PID/startInstant·실제 exit·stdout/stderr/identity/exit/manifest 모두 성공해야 반환한다. timeout/interrupt/start 이후 identity 저장 실패에서도 CLOSE 요청 뒤 정상 종료를 관측한다. 정상 종료 기한을 넘으면 실제 Process 참조와 identity를 registry에 보존하고 모든 후속 sequence를 차단한다. 자동 kill·재시도·기존 프로세스 인수 없음. process 시작 전에 설치한 독립 non-daemon 소유 스레드가 호출자 반환과 stdout/stderr EOF 이후에도 부모 JVM의 생존을 유지한다. 유한 관측 기한은 실패 기록·후속 차단 기준이며 살아 있는 Process를 포기하는 시점이 아니다. 중단·원래 Exception/Error를 보존하며 새 Error는 suppressed에 숨기지 않는다. 종료 관측 실패는 늦은 exit0으로 소거하지 않는다. 성공 manifest가 없거나 failure.json이 있으면 성공 receipt가 아니다.

`observeRemaining(millis)`는 같은 JVM이 이미 소유한 실제 Process 소유자의 완료를 유한하게 기다린다. 소유 스레드는 실제 종료→stream 정리→late-exit.json 저장 후 registry를 해제한다. failedEarlier=true/nextTaskAllowed=false와 최초 실패를 보존한다. 늦은 증거 저장 실패는 late-failure/잔여 registry로 남으며 자동 재시도하지 않는다. 기존 실패 task의 성공 변환이나 동일 sequence 재개 API가 아니다. 출력 먼저 종료→부모 main 반환→자식 자체 종료를 사용하는 별도 부모/자식 fixture와 shutdown 시 최종 registry 관측으로 소유 수명을 확인한다.

`Phase18Control.dispatch(start)`와 `stop()`은 동일 monitor에서 실제 시작 인계/중단 적용을 직렬화한다. 시작 전에 소유량을 설치하여 동기 callback도 처리하며, 시작 예외는 부분 시작 가능성을 보존하고 후속 dispatch를 차단한다. A-2는 HTTP 요청 시작 인계를 이 API에 연결하고 complete를 정확히 한 번 호출해야 한다. 실패한 송신 시작의 UNKNOWN 처리는 A-2 책임이며 단순 callback 슬롯 반환을 서버 소진으로 해석하지 않는다.

fixture protocol: READY→START→DISPATCH(write/read)→WAITING_MEASURE. STOP/CLOSE는 정상 MEASURE 대기 중에도 처리한다. APPLIED는 차단 이후의 dispatch count·자식 적용 nanos, DRAINED는 이미 시작된 작업2개의 소진, DONE은 전체 dispatch 수다. 부모는 적용 확인 후 MEASURE를 보내 추가 dispatch가 없음을 확인한다. no-ack와 delayed-ack는 확인 실패와 당시 잔여 소유를 기록하고 정리 후 최종 실제 잔여를 구분한다. delayed-ack는 fixture 한정100ms 확인 기한·600ms 늦은 확인이며 동결 일반 확인 기한을 낮추는 실행 옵션이 아니다. parent safety/collector-failure/child-failure는 합성 원인이며 그 중단 자체가 task 실패다. 실제 HTTP·collector 연동 증거가 아니다.

제어 검사 결과 schema v2는 `runStatus=FAILED`와 `verificationPassed`를 분리한다. `applicationConfirmed`, `drained`, `done`, `received`는 기한 내 실제 수신 사실이며 모드 이름에서 추정하지 않는다. 정상 제어는 timely APPLIED·추가 dispatch 없음·DRAINED·DONE 및 종료 후 원시 stdout 대조를 모두 요구한다. ACK 실패 사례는 지정된 적용 미확인 원인만 예상 실패로 통과하며 다른 프로토콜/정리 오류를 suppressed 아래 숨겨 통과시키지 않는다. 세 결함 주입(APPLIED 누락/추가 DISPATCH/DRAINED 누락)은 해당 제어 검사가 실패해야 VerifyTools의 음성 검증이 통과한다. 늦은 raw ACK와 exit0은 당시 적용 확인 실패를 복원하지 않는다.
