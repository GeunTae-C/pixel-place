# 18 실행 기반 계약 v2

2026-10-03 C/F 실행 전환 이후의 3307 기동·종료·새 환경/계획 생성은 [현재 로컬 DB 실행](db-test-runtime.md)을 따른다. MySQL·Redis·WAL의 F 배치는 유지하고 캐시는 `F:/pixel-place-work/gradle-home`, session 작업 영역은 `F:/pixel-place-work/sessions/<sessionId>/{build,project-cache,temp,jna}`다. C에는 프로젝트 원본을 유지하고 신규 증거는 `F:/pixel-place-work/evidence/<sessionId>`에 생성한다. 닫힌 과거 증거는 검증된 archive 이동 목록으로 읽는다. 일반 앱 WAL은 `F:/pixel-place-wal/runtime/pixel-place.wal`, 새 측정은 `F:/pixel-place-wal/phase18/<sessionId>/<runId>/pixel-place.wal`로 분리한다. 현재 실행에 E를 허용하지 않으며 F 준비 실패를 E로 fallback하지 않는다. 과거 실행별 환경 생성 스크립트와 PID/hash는 재사용하지 않는다.

현재 제공 범위는 A-1 실행 기반, A-2의 제한 production fixture·HTTP/read/gzip/WS 발생기·동일 initial 복구, A-3 유한 관측·안전 중단·정식 분석기, A-4의 공식 PILOT과 후속 단일 trial 실행 연결이다. 기존 15/17 plan·guard의 소유권 의미를 유지한다.

## k6 전환 목표와 현재 구현 경계 — 2026-10-11

2026-10-10 k6 전환 **수정 1 문서 정책**: 인계문(2026-10-07, 10-08 보완)의 수정 1~4는 작업 묶음이며 기존 CAP-0→CAP-1→CAP-2→CAP-3→D-1→D-2의 단계 번호·전체 순차 목차를 바꾸지 않는다. 해당 수정 1 수행은 문서 정리와 PASS 1~6 검토뿐이었다. 코드 수정·설치·서비스/부하·장부 append·기존 잠금 처리를 하지 않으며 수정 2를 자동 시작하지 않는다.

로컬 주 부하 도구는 k6로 전환한다. HTTP·k6/websockets·arrival-rate/VU·기본 통계·내장 웹 대시보드/HTML을 우선 사용한다. 프로젝트에는 인증·fixture·최소 결과 전달·정합성·DB/파일 관측·소진·정상 종료·새 JVM 복구 연결을 남긴다. 별도 Grafana 서버/Cloud·Docker·새 프로파일러나 자체 부하 엔진·실행/감사/보고 프레임워크를 기본 필수로 추가하지 않는다.

**현재 구현과 후속 책임:** 2026-10-11 수정 3에서 `ProduceK6`를 k6-initial의 실제 결과 소비·정합성·소진·정상 종료와 같은 initial을 읽는 k6-recovery 새 JVM 및 기존 분석 CLI에 연결했다. 입력 v2와 유한 지원/예산 연결은 아래 계약을 따른다. 기존 `Run`/Java `Phase18LoadClient`와 과거 결과 경로는 유지한다. **수정 4**는 현재 자원/예산·기존 실패/잠금 전환·등록 정책과 S1/S2 실제 통합 검증 및 CAP-0 마감의 책임이다. 이번 무서비스 검증은 실제 DB/WAL 복구나 CAP 성능 PASS 증거가 아니다. 이전 검증의 재사용과 새 실행 근거는 CAP 진행 기록에서 구분한다.

아래 k6 정책은 새 측정에 한해 종전 Java pacing/raw·모든 nonzero 즉시 실패·과거 임시 쓰기 전량 재구성 요구를 명시적으로 교정한다. 기존 계획/정책/receipt·완료·실패는 재해석하거나 덮어쓰지 않는다. 부하·기간·반복·정합성·소유권·유한 중단 조건은 유지한다. 과거 작성일·수치는 당시 근거이며 현재 완료 상태는 진행 기록과 실제 최종 입력으로 판단한다.

측정용 access token은 발급부터 **6시간**, 실제 서비스는 **15분**이다. 6시간은 CAP 준비·CAP 측정 및 같은 k6 경로의 D-1에 한정한다. D-2 production 인증 회귀와 기본 application.yml은 15분을 유지한다. handoff 60초·authorization request 180초·실제 cooldown 180초는 그대로이며, token 수명은 trial 만료·서비스 시간 예산을 연장하지 않는다.

`AuthProperties`의 15분 제약과 production 기본 발급/검증은 유지한다. 수정 2의 `ServiceJwtTokens.benchmarkAccess()`는 같은 엄격한 validator에 고정 6시간을 전달하며 benchmark source set의 `Phase18K6Auth`를 `k6-initial`/`k6-recovery` primary source로만 등록한다. production artifact는 이 구성을 포함하지 않는다. YAML을 6h로 바꾸지 않는다. issuer/audience/token_use/사용자 ID/서명/만료와 30초 skew 검증은 공통으로 유지하며 production 검증기가 같은 키의 6시간 토큰을 거부하는 시험을 추가했다.

시작 전 남은 유효기간이 준비·예열·측정·요청 소진 중 인증이 필요한 마지막 시점까지 덮는지 확인하고 부족하면 시작 전에 새 발급한다. 주기적 갱신기는 기본 구현하지 않는다. token 원문/인증 헤더는 로그·HTML·출력·해시에 넣지 않는다. 고정 Clock과 context/token 테스트로 측정6시간 수용, production15분 수용/6시간 거부, 잘못된 서명/용도/만료 거부를 검증한다. access의 현재30초 clock skew 경계도 확인하며6시간 실제 부하로 대신하지 않는다.

### 필요한 연결과 검증

- 수정 2: k6 시나리오·외부 입력을 받는 앱 기동/단일 deadline·6시간 측정 인증/production 격리·원시 exit/필수 출력 수집. 스크립트·실제 버전·시나리오/태그/VU·입력·출력 형식을 고정하며 아직 존재하지 않는 실행 명령을 인계하지 않는다.
- 수정 3: accepted/거절/UNKNOWN·read version/content·WS 연결별 payload/집합·checkpoint 관측→정상 소진/종료→동일 initial 복구→분석. Phase18Plan/Phase18Events의200,000 제한, Phase18Cap의140,000 terminal·16,000,000 delivery/512MiB 결합 제한과 실제 보유 구조를 함께 확인한다. 유한 스트리밍/전수 대조를 우선하며 parser 숫자만 올리거나 전수 검증을 샘플로 축소하지 않는다.
- 수정 3의 규모 검증: W105/WS100/20분, W105/WS200/120초, 작은 CAP-3 일정을 전 경로로 검증한다. 10,000/s·3200WS는 설계 탐색 상한이며 모든 조합 지원 보장이 아니다. 실제 서비스 없는 경계/자원 검증과 수정 4의 승인 smoke를 구분한다.
- 수정 4: 변경 영향 Windows 검증과 현재 자원/예산·기존 잠금 전환 후 S1 W2/R2/WS2·2+5초, S2 사용자2/WS2·0/1/200/201초 write·220초·10/60/110/160초 read. 기존 S1 FAILED2/S2 0·150,511ms를 승계한다. 문서 수정은 실제 전환/closure가 아니다.
- 결과 분류는 CAP 상세문의 '인계문 7.1절 채택'과 D 상세문을 따른다. 현재 Wait-CapWorker의 nonzero 실패를 전역 완화하지 않는다. k6 경계에서 실제 nonzero/threshold/전체 기간/필수 증거를 수집한다. 확인된 성능 threshold 미달과 CAP/D 상세문의 정상 마감 조건을 충족하는 INPUT_LIMIT는 소진·정합성·정상 종료·새 JVM 복구·분석까지 연결한다. 실제 종료코드와 입력 부족 판정은 보존하며, 마감 완료만으로 INPUT_LIMIT를 PASS로 바꾸지 않는다. INPUT_LIMIT를 서버 성능 상한으로 사용하지 않으며, INPUT_LIMIT 이후 후속 부하는 CAP-1에 명시된 하강 예외에 한해 허용한다. 실행 오류·안전 중단·정합성·복구·소유권 실패의 차단 조건은 유지한다. exit0도 정합성/복구가 없으면 PASS가 아니다. optional HTML/복사 실패는 별도 보고한다.
- 새 CAP 입력은 전체/10초 창≥0.999, 성공 write k6 http_req_duration p95≤100ms/p99≤250ms를 쓴다. 과거 RTT/nearest-rank와 다른 시리즈로 기록한다. HTTP 출력 timestamp가 완료 시점인 점, dropped와 실제 송신 실패 구분, VU/앱 간 시각 오차, WS/DB 기준은 CAP 상세문에서 고정한다.
- 기존 trace/collector/정상 종료/DB 소유권은 재사용한다. 부하 엔진·상태 머신·보고서 생성기를 다시 만들지 않는다. k6 내장 대시보드/HTML은 선택이며 최소 필수 입력·결과·정합성/복구 증거와 분리한다.

공식 정의 참조: [HTTP metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/), [Thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/), [Dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/), [Response timings](https://grafana.com/docs/k6/latest/javascript-api/k6-http/response/), [내장 대시보드](https://grafana.com/docs/k6/latest/results-output/web-dashboard/). 이 문서 조회는 로컬 k6 설치/버전/실행 검증이 아니다.

### 예산·기존 이력

새 k6 시험의 예산은 **현재 C/F 여유·이번 유한 최대 증가/임시 peak allowance·다음 예약·중단선**으로 판단한다. build/cache/temp/JNA·k6 출력/HTML·WAL·DB data/redo/binlog·정합성/분석 임시 공간을 실제 위치별로 포함한다. 시작 전 free-이번 추가 최대 점유-다음 예약≥최소 여유를 확인하고 진행 중 한도/최소 여유 위반은 STOP한다. 이미 점유한 bytes를 중복 예약하지 않는다. 반환된 임시 공간은 현재 여유에만 반영하며 과거 소비 환급으로 쓰지 않는다.

기존 CAP 소비 하한·시도·서비스 시간·실패는 승계한다. **C20GiB/F100GiB는 새로 더 주는 예산이 아니며** 기존 확정 하한+전환 이후 보수적 증가 계상의 합에 적용한다. per-trial 총 예약32GiB·볼륨별 최소 여유10GiB·더 작은 유효 등록 한도를 유지한다. 다음 예약 C64MiB/F5GiB는 소비와 분리한다. 기존 allowance C16MiB/F1GiB에 외부 비용이 모두 포함됐다고 가정하지 않는다.

전환 시점·현재 점유/여유·기존 확정 하한·미확정 항목·새 시험 포함 범위/유한 allowance를 한 번 기록한다. 보존 인계 sequence1/committed·C770,866,876/F1,936,764,260 bytes는 과거 확정 하한이고 현재 전체 누적의 정확한 값이 아니다. 외부 C79,459,777 bytes의 기존 계상 귀속, 소멸 임시/후속 보고 비용은 미확정으로 남긴다. 이를0·전량 신규·전량 기계상으로 만들거나 장부를 재초기화하지 않는다.

이 교정은 **과거 삭제된 모든 임시 쓰기의 정확한 재구성을 새 실행 선행 조건으로 삼던 요구를 폐기**한다. 현재 점유는 실제 여유에 반영하고 과거 미확정은 한계로 보존한 채 전환 이후 유한 비용을 관리한다. **수정 3**에서 기존 장부 CLI의 명시적 schema2 전환과 guard 연결을 격리 검증했다. **수정 4**에서 현장 값·전환 범위를 확정한 뒤 실제 장부에 적용한다. 도구 거부를 수동 파일 수정/다른 LedgerRoot로 우회하지 않는다. 새 대형 장부·감사기를 만들지 않으며 예산 증액·부하·기존 잠금 처리를 이번 문서로 승인하지 않는다.

같은 trial에서는 최초 `baseObserved` 대비 root별 양의 추가 점유 합계에 아직 필요한 예약과 transient allowance를 한 번 더한 필요 peak를 `registeredTrialBytes`와 대조한다. 점유로 채운 예약은 잔여 예약에서 제외하며 root별 예상 예약 자체를 새 독립 상한으로 삼지 않는다. `trialBudget.violation`에는 최초 trial 한도 초과의 sequence·필요 peak·등록값·사유가 남고, 감소·close·재읽기 뒤에도 `gateMet=false`를 유지한다. close는 미래 예약을 해제하지만 과거 소비와 위반은 환급하지 않는다. 최소 여유 부족 등 다른 실패를 영구 고정하지 않으며 C/F 누적·여유·다음 예약 검사는 각각 유지한다. CLI는 초과 관측도 append·출력 후 exit1로 전달하므로 후속 실행은 차단되고 실패 상태의 정리는 가능하다. `load_chain()`은 새 필드가 없는 기존 정상 schema1/2의 원본 bytes/hash를 보존해 검증하며, 과거 trial 초과를 누락한 기록은 성공으로 정상화하지 않는다.

현재 k6 계약과 기존 Java 구현 이력을 아래에 구분한다. Java raw/schema/CLI·기존 budget/closure 규칙은 해당 입력의 해석 기준이며, 새 k6 v2에는 별도 k6 계약 절을 적용한다. 새 실행의 정책 충돌은 위 전환 범위와 해당 CAP/D 상세문으로 해결하고 기존 실행 증거는 재해석하지 않는다.

## 실행 인터페이스

`dev.cgt.benchmark.Phase18Main --action Plan|VerifyTools|VerifyTransport|VerifyObservation|Pilot|Run|ProduceK6 --plan <절대 JSON 경로>`.
JDK21과 이미 컴파일한 benchmark runtime classpath로 직접 실행한다. `Plan`은 파일 읽기·해시·경로·여유 검사와 stdout 출력만 수행한다. Gradle compilation 자체는 별도 준비 작업이다. 서비스 action은 `-Dphase18.environment=<절대 현장 JSON>` 또는 script의 `-EnvironmentFile`이 필요하다. `Analyze`, `Close` 및 알 수 없는 action은 입력 파일 읽기 전 거부한다. 이름/PID만 보고 다른 프로세스를 종료하지 않는다.

`phase18ToolsClasspath` Gradle task는 `phase18ClasspathFile`로 지정한 새 파일에 benchmark runtime classpath를 기록하고 `.json` sidecar에 source/compiled class/resources/JAR 해시를 연결한다. `scripts/measure-phase18.ps1 -Action Plan|VerifyTools|VerifyTransport|VerifyObservation|Pilot|Run|ProduceK6 -PlanPath ... -Java ... -ClasspathFile ...`은 해당 클래스 경로로 직접 JVM을 실행한다. 직접 Java 호출도 `-Dphase18.runtimeReceipt=<ClasspathFile>.json`이 필요하다. actual runtime bytes와 source receipt가 다르면 action을 거부한다. launcher는 실행 정책/서비스/Git 설정을 변경하지 않는다. 실제 동결 입력과 복사 가능한 호출은 `작업기록/phase-18-progress.md`에 기록한다.


## k6 생산·소비·복구 계약 — 수정 3, 2026-10-11

고정 실행 파일은 `F:/pixel-place-work/sessions/k6-mod2-20261010-01/k6-2.3.0/k6.exe`, 실제 버전은 `k6 v2.3.0`/`e088784614`/Windows amd64다. 전역 설치·설정 변경 없이 이 절대 경로를 사용하며 input/runtime의 binary/script SHA256을 대조한다. 지원 실행 범위는 CAP과 기존 A-2 진단이다. D-1은 이 연결에서 거부한다. D의 SLO/장시간 지원을 CAP에서 자동 상속하지 않는다. A-1은 무서비스 fixture의 계획 형식으로만 허용한다.

수정 4에서 새 현장 계획·환경·runtime receipt와 최초 expiry·소유권/예산 조건을 갖춘 뒤 사용할 구현된 호출 형식이다. 소스가 바뀌었으므로 과거 runtime receipt를 새 실행 입력으로 재사용하지 않는다.

```powershell
& ./scripts/measure-phase18.ps1 -Action ProduceK6 -PlanPath $planPath `
  -Java 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe' `
  -ClasspathFile $classpathFile -EnvironmentFile $environmentFile `
  -ObservationPolicy $observationPolicy -K6 $k6Executable
& $python -B ./scripts/analyze-phase18.py --plan $planPath `
  --run-dir $initialDirectory --output $newAnalysisPath --execution-expiry $originalExpires
```

`ProduceK6`는 `Phase18Transport.produceK6()`에서 initial와 recovery를 순차 실행한다. 두 앱 JVM은 각각 2GiB heap이며 같은 최초 기한·DB catalog·WAL·users·plan/input/runtime/관측 정책을 사용한다. 기존 `BenchmarkFixtures(!recovery)`에 따라 recovery는 DDL·reseed 없이 저장된 fixture를 읽는다. 이전 writer의 실제 종료·정상 manifest·WAL snapshot·모든 k6 source 해시를 확인한 뒤 복구한다.

initial의 순서는 앱 준비→정의된 bootstrap/소진→전체 WS 준비→CAP-3 원래 초기 GET 집합 검증/소진→READY→예열/소진→본측정/소진→WS 마감이다. 서버 drain은 bridge lock 밖에서 활동 집합, pending write, fresh DB checkpoint/capture로 확인한다. accepted를 DB event 전수, 메모리/DB tile 전체, 잔존 WAL suffix와 대조하고 seq gap·retention을 허용하는 기존 검사를 재사용한다. read는 기존 version/replay/hash 표본 규칙을 적용한다. WS는 각 연결의 정확한 집합·payload·중복·누락·extra를 대조하며 HTTP보다 이른 WS와 역순 callback도 보존한다. 검사를 완료하고 정상 종료한 뒤 새 JVM이 같은 검사를 다시 수행한다.

### 입력 v2와 필수 출력

| 파일 | 현재 의미 |
| --- | --- |
| `k6-input.json`, `k6-runtime.json` | `phase18-k6-v2` 계획·hash·binary/script·최초 expiry·6h/15m 인증·예약, 실제 k6 버전/한도 |
| `k6-records.jsonl` | 원래 ID/kind/phase/ordinal/due, 사용자/attempt, 실제 송신·body·검증 epoch ms, status·ACCEPTED/REJECTED/UNKNOWN/NOT_SENT, read 검증. WS는 open/close lifecycle만 저장 |
| `k6-ws-canonical.jsonl` | 고유 WS event payload. 별도 `k6-ws-receipts.bin`은 연결별 exact bitset, `k6-ws-times.bin`은 연결×예약 event의 big-endian int64 수신 epoch ms |
| `unstarted` 행 | 일정형 원래 ID와 due offset. 실제 anchor가 없으면 absolute 시각을 만들지 않음. 고정 도착률 미시작은 계획 ID 공간과 관측 terminal의 차이 |
| `k6-<phase>-metrics.json`, `k6-<phase>-summary.json`, `k6-<phase>-exit.json` | 기본 k6 NDJSON/summary, 실제 exit0 또는 검증된 threshold99, required output 해시/크기·실패 threshold. accepted-only duration은 본측정 write의 `res.timings.duration` |
| `k6-runner.json`, `k6-producer.json` | 최초 실패·미해결 claim·각 process의 실제 exit/Release·실제 phase 경계·시각 bracket·생산/소진 상태. 자체 최종 분류는 `NOT_EVALUATED` |
| `cap-observer.json`, `cap-db.jsonl`, `cap-http.jsonl` | CAP DB observer schema3/epoch ms·약1초 query·정상 connection close/hash. k6 HTTP는 native records에서 읽으므로 cap-http는 빈 owned 파일 |
| `initial/manifest.json`, `recovery/manifest.json` | schema2/`inputKind=phase18-k6-v2`, 실제 consistency/drain/shutdown 및 k6Hashes. recovery만 `consistencyRecoveryConnected=true`. 구형 `k6-handoff.json`은 새 실행에서 생성하지 않음 |
| `analysis.json` | 기존 CLI의 k6 v2 소비 결과. 역할별 정합성/복구·소유권과 실패 상세, 실제 exit, 입력·HTTP·WS·DB 및 CAP 판정 |

기존 Java raw·nanoTime 행을 합성하지 않는다. k6/Java 동일 호스트 epoch는 RPC와 WS clock bracket으로 오차를 보수적으로 산출한다. 임계값을 가로지르는 오차, DB 관측 공백/표본 부족은 TOOL_LIMIT이며 PASS를 만들지 않는다. 필수 NDJSON은 기존 Python 표준 JSON decode로 한 줄 16KiB·정의 1,024개 상한을 검사한다. summary는 1MiB, 각 stdout/stderr는 1MiB다. 정상 accepted 0표본/조건부 metric 생략은 출력 형식상 허용하되 성능 지연을 성공으로 채우지 않는다. 선택 HTML은 `not_requested`다.

본측정 write/read별 전체 및 겹치지 않는 10초 입력창의 99.9%를 확인한다(S1은 5초 창 하나). 일정형은 원래 due 안에서 보낸 집합도 대조한다. 성공률≥99.9%, 시스템 오류율≤0.1%(busy503 포함), accepted-only HTTP p95≤100ms/p99≤250ms는 k6 선형 보간이다. WS는 연결별 및 전체 nearest-rank p95≤250ms/p99≤500ms다. DB pending은 검증 완료된 실제 accepted 집합과 checkpoint를 비교한다. query 공백≤3초, B(t)≤10초, 처음/마지막 1/3 각각≥10표본의 중앙값 증가≤max(1초, 처음의10%)를 확인하며 S1 추세는 N/A다. P=0은 N/A, S=0은 INPUT_LIMIT다.

기존 CAP 상태와 `failureCategories`/`failureDetails`에 PASS, 유효 PERFORMANCE_MISS, INPUT_LIMIT, TOOL_LIMIT, 실행 오류/STOP, 정합성·복구·소유권 실패의 의미를 보존한다. invalid 이유를 우선 분류해도 입력/성능/도구 사유와 실제 exit를 삭제하지 않는다. 유효 threshold99와 정상 마감 가능한 입력 부족은 정합성→종료→복구→분석을 거친다. 공통 `Wait-CapWorker`의 nonzero 차단은 그대로다. smoke PASS는 capacityPoint가 아니며 INPUT_LIMIT는 서버 상한이 아니다.

### 유한 지원과 자원 예약

HTTP 전체≤140,000, WS≤200, delivery≤16,000,000, 종류별 fixed VU≤128(합계256), 일정형 active owner≤128이다. 준비·bootstrap·초기 read·예열도 전체량에 포함한다. WS는 한 VU의 단일 FIFO·동시 전송1을 유지한다. batch는 최대512건/UTF-8 envelope524,288bytes, 대기+전송 중은8,192건/4MiB다. callback 수신 시각·연결별 receiveOrdinal을 먼저 고정하며, 동일 body의 escape 결과만16,384개/16MiB 안에서 재사용하고 delivery 전수 전송·payload 검증은 유지한다. 크기 도달 즉시 또는5ms timer로 잔여분을 전송한다. 전체 필수 저장 뒤 saved/batchId/count 확인 전까지 전송 중으로 계상하며 확인 실패는 재전송하지 않는다. STOP에서는 timer·socket 입력을 닫고 이미 예약된 callback의 추가 큐 유입을 차단한다. 정상 종료는 대기/전송 중/미확인0과 전수 기대 집합·100연결 close를 함께 검증한다. JVM은 기존 primitive canonical/bitset과 ≤128MiB 수신 시각 배열을 보유하고 마지막에 64KiB씩 쓴다. HTTP JSONL은 ≤640MiB/행≤4KiB이며 동시 claim 수는 O(1) counter로 검사한다. app 보유 산식≤1.5GiB, 실제 heap2GiB를 지킨다.

| 필수 조합 | 계획 규모와 검증 경계 |
| --- | --- |
| W105/WS100, 예열30초+본측정1,200초 | bootstrap 포함 write129,151, WS delivery12,915,000. Java 전수 수신 저장 및 Python 전체12,600,000 본측정 지연 대조·마지막 bit 누락 검증 |
| W105/WS200, 예열30초+본측정120초 | bootstrap 포함 write15,751, WS delivery3,150,000. 동일 구조/산식의 지원 gate 검증 |
| 작은 CAP-3 재사용 일정 | 기존 계획 산식/초기 GET·due·181초 재사용 경계와 실제 유한 k6 일정 fixture 검증 |
| S1/S2 | 기존 정확한 일정·사용자2/WS2 정의 유지. `new-phase18-cap-plan.py --k6`와 controller 연결, 실제 실행은 수정4 |

네이티브 metrics 예약은 `96MiB + HTTP전체×65,536 + WSdelivery×128` bytes다. 출력 최소 예약은 여기에 기존 `counts.diskBytes`(원시/WS/분석 임시 산식), `HTTP전체×4,096 + 16MiB`를 더한다. 각 metrics 파일은 그 예약과 기본 최대12GiB(CAP-1의 검증된 확대 profile은60GiB)로 제한하고 trial 전체 출력/시간 guard도 유지한다. 기본 k6 동시 process≤2의 합산 WorkingSet/PrivateBytes≤512MiB, 분석 프로세스≤512MiB를 감시한다. CAP-1은 Go soft heap512MiB를 유지하면서 native process별768MiB/동시합계1,280MiB를 별도 동결·감시하며 실제 peak는 실행 증거에 남긴다. 분석 보유 사전 산식은 `128MiB + HTTP전체×2,300`이며 records는 SQLite, WS는 연결 하나의 지연 배열과 8MiB SQLite cache/파일 정렬로 전수 quantile을 계산한다. 10,000/s·3,200WS 및 상한을 넘는 조합은 지원 선언하지 않는다. 상세 실행 수치·실패 보존·재사용 근거는 CAP 진행 기록에만 누적한다.

### 수정 4 호출과 예산 전환 경계

등록된 정책은 `k6.inputKind=phase18-k6-v2`, `k6.executable`/`k6.script`의 path/sha256, `k6.reservations.S1`/`S2` 각각의 path/sha256을 가진다. 예약 파일은 schemaVersion2, `scope=k6-mod3`, 고유 id/issuedEpochMillis, 과거 미확정 항목 `pastUncertain`, `additionalPeak`의 evidence/graph/work/cache/mysql/wal 6개, `transientAllowance` C/F, 정수 `registeredTrialBytes`≤32GiB(더 작은 등록 한도 우선)를 명시한다. S1과 S2는 별도 예약이며 닫힌 ID를 재사용하지 않는다. smoke 최소 예약은 evidence192MiB/work2GiB/cache1GiB/mysql2GiB/wal64MiB/transient F1GiB다. 실제 준비/실행/분석 peak가 더 크면 등록값도 그에 맞아야 한다.

```powershell
powershell.exe -NoProfile -NonInteractive -File ./scripts/run-phase18-cap-smoke.ps1 `
  -Smoke S1 -LedgerRoot $existingLedgerRoot `
  -ResumeApproval $registeredApproval -ResumeApprovalHash $approvalSha256 `
  -K6 $k6Executable -BudgetReservation $registeredS1Reservation
```

S1 전체 마감 PASS 뒤에만 같은 등록 정책의 S2 예약으로 `-Smoke S2`를 호출한다. 이 변수들은 실제 재개 시 기존 이력·실패·잠금·현재 여유를 재확인하고 정식 등록한 파일을 사용한다. 발급·마감·실패 이력은 CAP 진행 기록에서 확인한다. controller는 기존 장부의 schema2 전환 append 및 마감 close를 연결하지만 자동 초기화/기존 잠금 우회를 하지 않는다. 과거 C20GiB/F100GiB 소비 하한·allowance·미확정을 보존한다. 새 양의 증가만 누적하고 transient allowance는 예약별 한 번 계상한다. 현재 점유는 free에 이미 반영돼 있으므로 남은 추가 peak와 다음 C64MiB/F5GiB를 공제한 여유가 각10GiB 이상이어야 한다. 삭제·마감으로 소비를 환급하지 않는다. `mysql-test-instance.ps1`의 EvidenceBytes 최대20GiB는 입력 가능 상한이며 기본64MiB/등록 예산을 자동 늘리는 권한이 아니다.

controller와 준비 실패 마감은 같은 `cap-preparation-close.guard`를 OpenOrCreate/FileShare.None으로 먼저 획득한다. guard는 절단·삭제하지 않으며 기존 controller 잠금을 보존 이동하는 동안에도 배타 보유한다. controller는 최종 worker/후손/stream join 뒤 `Release`를 호출하고 closure의 worker별 `Released`를 기록한다. 해제 실패는 필수 자원 마감 실패로 보존하며 PASS나 다음 smoke 진입으로 처리하지 않는다.

명시 재개 승인 schemaVersion2는 세 번째 S1의 원래 FAILED·SQL 정상 종료 미확인을 보존한 채, hash로 연결한 과거/현재 부재 관측과 배타 잠금 증거를 요구한다. 이 승인만 S1누적4/S2누적1/전체5를 적용하며 일반 실행과 schemaVersion1의 한도는 유지한다. 과거 서비스 시간 하한은 별도 보존하고, 미확정 세 번째 시도는 발급부터 보존된 부재 관측까지를 보수적으로 시간 예산에 계상한다. 새 closure의 ServiceMillisCumulative는 이 보수적 계상값, RecordedServiceMillisCumulativeFloor는 원래 하한에 이후 기록을 더한 값이며 HistoricalServiceTimeUncertain을 함께 남긴다.

명시 재개 승인 schemaVersion3은 2026-10-12의 추가 S1 1회·조건부 기존 S2 1회에 한정해 S1 누적5/S2 누적1/전체6을 적용한다. schema1/2와 일반 한도·시간·저장 예산은 유지한다. 네 번째 FAILED의 원래 종료코드 미확인/하한을 보존하고 별도 SQL 종료 로그·소유 identity·DB 사용 종료·worker/stream/Release 및 현재 배타 잠금 아래 부재 관측을 연결한다. S2에는 이번 등록으로 발급한 새 S1의 전체 PASS·필수 마감을 요구하며 실패나 경로 변경으로 슬롯을 반환하지 않는다.

시작 worker nonzero는 살아 있는 서비스 후손과 무관하게 즉시 최초 실패로 고정한다. 준비 실패 시 cleanup-service 또는 기존 readiness offer의 실제 Job 후손·PID/start/실행 파일/hash를 확인한 cleanup-handoff는 Ready=false/normal-stop-only다. DB 사용 worker/stream 종료와 이 소유 서비스만 남았음을 확인한 경우에만 기존 MySQL Stop을 허용한다. 이 상태는 Joined/READY/PASS를 만들지 않으며, 다른 후손이나 identity 불일치가 있으면 정리를 허용하지 않는다. 최종 자원 마감은 모든 후손 종료와 Release를 계속 요구한다. MySQL Job의 추가 콘솔은 소유 mysqld 부모·Windows System32/conhost.exe 경로·실제 PID/start를 대조한 경우에만 인계한다. CIM과 Process의 시간 정밀도 차이는 millisecond 비교로 맞추고, 이후에는 확보한 Process start identity를 그대로 검사한다. 이름만 일치하는 다른 후손은 허용하지 않는다.

Stop 전 `Wait-CapCleanup -ForStop`은 같은 cleanup clock의 반복 안에서 실제 시작된 mysql3307 worker의 미완료 인계를 재확인한다. worker와 stdout·stderr task가 모두 끝난 뒤에만 기존 cleanup-only 검증을 수행하고, 전체 registry의 DB 사용 종료를 다시 판정한다. 정상/정리용 인계가 끝난 대상은 재저장하지 않는다. controller는 같은 `Invoke-CapMysqlStopWhenReady` 분기를 사용해 기존 MySQL Stop을 호출하며, 인계 직전과 Stop 직전에 남은 시간을 확인한다. 실패 정리는 180초 cleanup clock을 갱신하지 않고 Stop 이후 최종 후손 종료·Release까지 승계한다. 정상 실행의 Stop은 기존 실행 clock도 충족해야 한다. 일반 대기와 `-Final`에는 인계 예외를 적용하지 않는다.

완료된 worker의 인계 자료·identity·인계 저장 실패는 `CleanupFailure`에 별도로 남겨 최초 실행 실패와 함께 보존하고 자동 재시도하지 않는다. 부분 저장 파일을 덮어쓰지 않는다. 미시작 worker는 인계 대상에서 제외하며, 정리 한도 종료 뒤 새 인계나 Stop을 시작하지 않는다. 정리 snapshot의 `ActualExit`는 나중에 확인한 실제 worker exit이며 원래 `Result.Exit`·`FirstFailure`·`NextTaskAllowed`를 고치지 않는다. 최종 join과 Release 전제는 그대로다.

## 계획 schema

새 실행의 sessionRoot는 위 F sessions의 해당 sessionId와 정확히 같아야 하며 build/project-cache/temp/jna 이름도 고정한다. Java runner·앱·발생기는 source/runtime receipt와 함께 실제 classpath가 해당 F build 또는 F Gradle cache인지, JVM temp/JNA·TEMP/TMP가 계획과 일치하는지 검사한다. 상속 경로 환경과 JVM 경로에 E가 섞이면 실행을 거부한다. 과거 계획/receipt를 읽는 Python 분석은 이 실행 gate와 별개이며 과거 bytes/해시를 변경하지 않는다.

정확한 필드·자료형은 `Phase18Plan`의 불변 record가 정의한다. 모든 key 필수, 중첩 unknown/duplicate key·null·숫자 문자열·소수·지수형 수·NaN·무한대·overflow 거부. 속도는 정수 requests/second만 지원한다. byte/millisecond/second/ppm 단위는 필드 접미사에 고정한다. 리스트도 방어 복사한다.

- 루트: `schemaVersion=2`, `phase=A|B|C|D`, `stage=A-1|A-2|A-3|A-4|B|C|D`, `state=draft|ready`, `sessionId`, `sourceBaseline`(40 hex), `predecessor`(path/sha256), `inputs`, `ownership`, `bounds`, `cases`. WS 예약 의미를 교정한 v2만 수용하며 이전 v1 계획·증거는 재해석하지 않는다. raw v1과 source/runtime receipt v1은 독립 version이다.
- planHash는 자기참조 필드가 아니라 읽은 JSON 원본 bytes SHA-256이다. `Loaded.planHash`·Plan 출력·runtime/결과 receipt에 저장한다. source 입력 집합은 src/scripts의 비 ZIP·비 pyc 파일, build.gradle/settings.gradle/gradlew.bat/DDL/이 계약 문서다. 상대 path+SHA256 집합을 현재 파일의 추가·삭제까지 비교한다. 선행 인계는 별도 파일 hash다.
- ownership: repo/JDK·session/evidence/build/cache/temp/JNA/WAL의 정확한 local absolute 경로. 신규 evidence는 F:/pixel-place-work/evidence/sessionId, sessionRoot는 F:/pixel-place-work/sessions/sessionId. root 안의 build/cache/temp/JNA는 상호 비중첩. Windows reparse/별칭/상대 경로/ADS/예약 이름/끝 공백·점 거부. WAL은 기존 C repo/data/sessionId 아래 또는 검증한 F:/pixel-place-wal/phase18/sessionId/runId/pixel-place.wal만 허용한다. F runId는 해당 plan case에 속해야 하며 앱 runtime·다른 session·build/evidence와 겹칠 수 없다. dbInstance/catalog/redisIndex/owner는 실제 서비스 실행의 재확인 입력이다. tools에서는 WAL/DB/catalog 빈 문자열·Redis -1 사용.
- bounds: write/read terminal 각각 최대200,000, users200,000, WS event200,000, sampler10,000, 연결100. 메모리 최대512MiB와 입력별 보수적 예약 산식을 별도 검사한다. output/WAL/DB/binlog/build·볼륨 최소 여유·누적 시간·stream log 상한을 체크한다. 메모리 산식은 pilot 측정값이 아니며 A-2 실제 구조/A-4 실측으로 확인해야 한다.
- case: 유일 caseId/runId·repeatIndex·role(pilot/target/diagnostic/soak)·kind(tools/prepare/write/read/mixed/soak)·plannedStartUtc, workload/runtime/observation. 반복은 서로 다른 ID의 명시 case로 표현하며 caseId도 중복 금지다.
- workload: write/read rate/pattern, seed, WS 연결, 예열/측정초, 독립 in-flight, request/ready/control/drain millis, once/reuse·userPool·reuseMarginMillis, 입력 허용 ppm·판정 창·동시 구간, bootstrap 여부. write0/read만 허용하며 write target으로 승격하지 않는다. user reuse/UNKNOWN retire는 A-2 producer의 실제 pool에 연결된다.
- runtime: group/single, batch16/outstanding128/queue1000ms/shutdown10000ms/flush1000ms·segment bytes·measurement on/off, JVM options `[-Xmx512m,-XX:+UseG1GC]`, InnoDB flush/syncBinlog 각1. 실제 launcher·DI 대조는 VerifyTransport에서 수행한다. A-1 synthetic child는 별도128MiB로 제한한다.
- observation: sample/maxAge/maxMissing/stopAck millis, required/auxiliary 지표 목록, fallback와 stopRule. 활성 수집기/안전 확인은 A-3다. draft는 서비스 현장 공백을 남길 수 있으나 VerifyTools는 ready/A-1/tools 1개만 허용한다. 미구현 실행은 ready 표시만으로 해제되지 않는다.

계산은 checked arithmetic만 사용한다. write/read 각 phase의 계획량은 rate×phase seconds, bootstrap은 별도0/1이다. once users는 bootstrap을 포함한 전체 write 수, reuse는 bootstrap+pool이다. 표본은 전체 예정 시간과 timeout 예약을 sample 간격으로 나눈 상한이다. case별 보유량과 순차 전체 출력/시간을 모두 검사한다.

WAL 용량은 소유 session 전체를 합산하고 여유 공간은 WAL이 실제 놓인 volume에서 검사한다. collector는 `walPath`·`walVolumeFreeBytes`를 남기며 안전 판정의 최소 여유에도 포함한다. F는 현재 MySQL·Redis와 같은 물리 장치를 공유하므로 host disk 관측은 공유 I/O이고 WAL 전용 비용이 아니다. F 결과를 기존 C WAL 결과와 같은 저장 조건의 반복으로 합산하지 않는다. 지원 범위 밖 경로는 기존 native 검증 없이 사용 가능으로 판정하지 않는다.

`bounds.wsEvents`와 `Counts.wsEvents`는 전역 **고유 이벤트** 상한/예약이다. WS 연결이 있으면 예열+측정 write 수이며 연결 전 bootstrap을 제외한다. 연결0이면 WS 고유 이벤트/수신량/bitset 예약은0이다. `wsDeliveries=wsEvents×wsConnections`는 연결 전체의 예상 수신 횟수이며 고유 이벤트 상한과 비교하지 않는다. `wsBitsetBytes=ceil(wsEvents/64)×8×wsConnections`는 연결별 수신 집합의 word 정렬 예약이다. 공통 eventSeq→ordinal/canonical 필드 저장소는 고유 이벤트당128bytes, 연결별 관리256bytes와 bitset을 메모리에 예약한다. WS 출력은 고유 이벤트당256bytes+bitset+연결별 요약1024bytes이며 payload를 연결마다 복제하지 않는다. A-2의 메모리 예약은128MiB+read 처리 슬롯당512KiB+write 처리 슬롯당34KiB+terminal당512bytes+user당256bytes+WS 예약+sample당256bytes다. 128MiB는 작은 실제 client의 초기 heap 관측을 반영한 보수적 예약이며 heap 실측값 자체가 아니다. 출력은1MiB+terminal당2048bytes+WS 예약+sample당1024bytes다. 실제 primitive WS 배열/bitset과 제한 fixture heap은 A-2, A-4에서는 작은 PILOT의 실제 heap과 보유 구조를 확인하며 대표·SOAK peak 실측으로 확대하지 않는다. 과거 A-1 계획의 해시/예약값은 보존하며 재해석하지 않는다.

대표 W105/s·30/120초·WS100은 고유15,750/수신1,575,000/bitset197,600bytes, SOAK W105/s·30/1800초·WS100은 고유192,150/수신19,215,000/bitset2,402,400bytes다. bootstrap은 write terminal/user에는 포함하고 WS 기대 집합에는 포함하지 않는다. 기존 고유200,000·연결100·512MiB 상한은 유지한다.

## raw·시간·receipt 연결 규칙

raw v1은 A-2 producer와 A-3 analyzer의 계약이다. `Phase18Raw`가 실제 HTTP 결과를 생산하며 A-1 제어 fixture를 실제 HTTP 표본으로 사용하지 않는다.

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

## A-2 production 연결과 제한 검증

`VerifyTransport`는 ready/A-2/diagnostic 한 case, write/read 각각 최대64개·WS 최대4개·예열5초/측정10초 이내·기본32MiB segment를 허용한다. `Plan.executionAvailable`은 파일·구조상의 실행 후보이며 live 환경 확인을 대신하지 않는다. 일반 group16/outstanding128/queue1초/shutdown10초·flush1초와 실제 보안/180초 cooldown을 유지한다. initial/recovery는 bounds.totalSeconds의 단일 누적 기한을 공유하고 마지막 receipt 전에 실제 출력 한도도 확인한다. A-2 당시의 제한 action 이후 A-4에서 공식 `Pilot`과 후속 단일 trial `Run`을 제공한다. 실제 B/C/D·장기 부하는 별도 실행 요청과 새 현장 입력이 필요하다.

환경 JSON의 정확한 필드는 `Phase18Environment` record다. plan의 dbInstance는 MySQL UUID와 환경 파일 SHA-256을 함께 고정한다. 현재 소유 MySQL3307의 PID/start/실행 파일·설정 hash/live SQL data path·durability, JDK21/계정/실제 NTFS 비-reparse WAL, temp/JNA와 예산을 검사한다. SYSTEM Redis는 Java ProcessHandle 조회가 불가능한 환경이므로 사전 CIM PID/start 증거와 live INFO process_id/run_id로 연결하며 매 fixture guard에서 재대조한다. standalone·미사용2~15 index 검사는 기존 fixture guard를 사용한다. E18 기록은 실행 전 다시 수집하며 이전 PID나 빈 index를 현재 사실로 승계하지 않는다.

새 catalog는 live identity·허용 namespace·부재·생성 intent 이후 CREATE, 실제 선택 catalog·빈 상태 확인 이후 DDL로 진행한다. 현재 run 소유의 precreated-empty 채택도 같은 빈 상태 검사 대상이다. 각 DDL/user batch 직전에 live guard가 호출된다. recovery는 initial의 정상 종료·실제 process exit·동일 plan/input/runtime/DB/WAL/segment/mode/raw/hash를 검사하고 users/DDL/seed를 재생성하지 않는다. DB/WAL은 증거로 보존하며 Redis key를 지우지 않는다.

`Phase18LoadClient`의 두 pacing thread는 write/read 예정 시각을 독립적으로 지킨다. 종류별 유한 슬롯은 수신, gzip/hash 검증, 필수 raw flush까지 소유한다. body 완료 시각은 Subscriber에서 callback 검증보다 앞에 고정한다. `Phase18Raw`의 추가 필드는 userOrdinal/userAttempt, 좌표/색, rawSha256/wireBytes/validationNanos다. token은 최대128개 cache·15분 TTL·만료90초 전 갱신을 사용하고 값은 기록하지 않는다. read 표본은 실행 전 예정 ordinal로 최대1000개를 고정하며, 종료 후 accepted 이벤트를 tile/version 순서로 replay해 응답 version의 snapshot과 대조한다. `Phase18Consistency`는 동결 terminal·DB 전수와 잔존 WAL 구간·memory를 대조하며 정식 분포/SLO 분석기가 아니다.

WS canonical은 공유 primitive 배열과 seq→ordinal 표 하나에 저장한다. `ws-canonical.jsonl`의 0-based 행 번호가 ordinal이며, `ws-received.bin`은 연결 순서 × ceil(planned unique events/64)개의 BIG_ENDIAN long word다. 같은 word의 bit0은 낮은 ordinal이다. 실제 unique 수와 계획 예약 수를 혼동하지 않는다. producer.json에 연결 시작/close callback 시각·중복/누락/extra·전달 횟수·primitive 배열 bytes·heap snapshot을 남긴다. bootstrap은 연결 전이라 제외한다. 모든 close callback 소진 뒤 최종 집합을 대조한다.

STOP은 정상 메시지 대기와 송신 중 모두 처리한다. APPLIED는 자식 수신/적용 시각·실제 dispatch 수, DRAINED는 callback 소진, DONE은 필수 raw 저장 경계다. 부모는 서버 write/read handler, executor, pending, readiness 및 idle 이후 fresh empty capture/checkpoint를 별도로 확인한다. read timeout 진단은 실제 handler를 제한 latch로 지연시켜 client 종료 후에도 소진이 거부되는지 검사한다. 늦은 서버 완료로 timeout terminal을 바꾸지 않는다.

최상위 `Phase18Transport`의 최초 누적 timeout은 즉시 실패로 고정하고 앱에 CLOSE를 보낸다. 앱의 단일 stdin reader는 준비/정상 메시지/결과 대기와 독립적으로 이를 받아 `Phase18ParentControl`에 중단을 고정하고 소유 발생기에 STOP을 전달한다. 발생기 시작·실제 Process 인계와 중단은 같은 짧은 gate로 직렬화하며, 발생기는 START 전 bootstrap을 포함한 HTTP를 시작하지 않는다. 앱 준비의 DB/native 작업은 취소하지 않고 정상 반환 뒤 중단을 다시 확인한다. 앱의 진단 HTTP도 같은 gate에서 비동기 시작만 인계한다.

발생기 stdout는 기존 reader queue를 body와 이어지는 정리 경로의 동일 소비자가 순서대로 읽는다. 정리의 APPLIED/소진 관측은 최초 실행 기한과 별도인 유한 절대 기한이며 실행 시간을 연장하지 않는다. `upstream-stop.json`은 앱 수신/STOP 전송 성공/실제 producer APPLIED/미확인 시점/callback DRAINED/raw DONE/최종 소유를 구분한다. `drain.json`과 `server-closed.json`, 두 자식의 exit 증거를 함께 대조한다. 중단 확인·정리 실패를 최초 실패에 보존하며 late ACK·exit0으로 복원하지 않는다. 실제 자식 종료까지 서버 소유도 유지한다. 예기치 않은 upstream 중단은 성공 manifest와 recovery를 차단한다.

`Phase18StopFixture transport <plan> <environment> <initial-budget-millis>`는 `upstream-before`/`upstream-timeout` 전용 음성 검증 진입점이다. 후자는 실제 Transport 경로의 최초 누적 기한을 실행 전에 단축하고 `runner-budget.json`에 고정하며, plan 기한보다 늘릴 수 없다. 전자는 실제 APP_PREPARING에서 CLOSE를 전달한다. 실행 exit1과 이를 대조한 외부 검증 PASS는 별개다. `protocol` 모드는 서비스 없는 실제 JVM의 시작 경합·ACK 누락/지연 단위 검증용이며 production 증거로 사용하지 않는다.

caseId `stop`/`stop-active`는 각각 제어 대기/실제 read handler 진행 중의 사전 정의된 중단 검증이다. 실행 상태는 FAILED이고 stop-verification의 verificationPassed만 별도 판정한다. `timeout`은 실제 read 요청의 timeout/producer 필수 실패를 유발하는 음성 fixture이며 action exit1·recovery 미진입이 기대값이다. 이 ID들을 정상 성능 실행에 사용하지 않는다. producer-failure(-final).json은 원래 planned, clock/anchor, 최초 실패, callback/raw 불확실 ID 및 저장 결과 UNKNOWN을 보존한다. WS 원시 집합도 실패 뒤 유지한다. 앱/producer 종료 기한 초과는 실패로 고정하고 실제 I/O/자식 종료까지 소유하며 강제 kill하지 않는다.

`server-closed.json`은 앱 context close 뒤 실제 flush scheduler 종료와 handler 완료 자료다. initial/recovery manifest의 complete는 제한 기능 검증·정상 자원 종료의 receipt이며 공식 PILOT/성능 성공이나 중단 case의 정상 실행 상태를 뜻하지 않는다.

## A-3 유한 관측·안전 중단

`VerifyObservation`은 stage=A-3의 동일한 소규모 diagnostic gate를 사용한다. launcher에 `-EnvironmentFile`과 `-ObservationPolicy`를 함께 전달한다. 직접 JVM 호출에서는 `-Dphase18.observationPolicy=<절대 JSON>`을 지정한다. policy schema1은 `Phase18ObservationPolicy` record로 정의하며 원본 planHash/caseId, 서버 heap·실제 backlog·outstanding·WAL 파일 수·trace entry/bytes·sample bytes 상한을 고정한다. policy hash는 initial/recovery 관측/manifest에 남기며 recovery에서 재대조한다. 일반 계획 schema2, raw/producer schema1은 유지하고 과거 A-2 결과를 새 collector 결과로 해석하지 않는다.

계획 observation.required는 heap/outstanding/backlog/disk/deadline/metrics/wal-metadata, auxiliary는 after-gc/process-io/host-disk 중 필요한 항목이다. fallback=`planned-writes-upper-bound`, stopRule=`stop-new-dispatch`만 실제 구현된 정책으로 수용한다. 필수 수집은 READY 이후 prepare/warmup/measurement/drain에 적용한다. sampleMillis/maxAgeMillis/maxMissingMillis/stopAckMillis는 기존 계획 필드로 동결한다. backlog는 capture 시점의 실제 records와 계획 전체 쓰기 상한을 따로 보존한다. pending·초기 미관측에서는 최신 backlog를 null로 두고, 계획 상한이 안전 한도 내이며 유한 공백 시간 안일 때만 계속한다. stale는 직전 capture와 같다는 뜻이며 maxAge를 넘기면 중단한다. metadata의 NoSuchFileException은 삭제 경합 결측으로 유한 허용하고 다른 collector 오류는 즉시 실패다.

`Phase18Collector`는 원래 collector 예외/안전 실패를 `Phase18ParentControl.stop`에 연결한다. 이는 앱의 정상 응답 대기와 독립적으로 발생기에 STOP을 전달한다. 실행 실패, APPLIED 기한, callback 소진, raw 저장, 서버 소진·실제 프로세스 종료를 구분하며 실패 뒤 recovery/성공 manifest를 차단한다. 정책 fixture=`collector-failure`는 실제 measurement handler 진입 뒤 필수 collector 예외를 주입하고, `safety-heap`은 같은 구간의 실제 heap을 사전 고정된 작은 한도와 비교한다. 이 두 음성 fixture는 prepare/warmup의 작은 heap 한도를 적용하지 않는다. 일반 fixture=`none`에는 전 구간 한도가 적용된다.

관측 자료는 resource-samples.jsonl, collector.json, observation.json, client-resources.jsonl, client-collector.json, phase-clocks.json이다. sample은 디스크에 유한 출력하고 전체 표본을 메모리에 보유하지 않는다. 서버·발생기의 완료 요약은 실제 sample 수·bytes·SHA-256과 연결하며 initial/recovery 관측·발생기 출력 예약을 합쳐 plan 출력 한도를 검사한다. trace는 startup/steady phase, 파일별 force 집계와 제한된 R/S/Q/native close·파일 생성/채택/삭제 journal을 보유한다. record force와 반복 identity 조회는 집계만 하고 R/S 안의 native open/close에는 parent call을 남긴다. 실제 capturePlan/transaction 결과에서 event/tile/dirty/expected checkpoint/target/outcome을 관측하며 retention 결과와 이미 확정된 DB outcome을 분리한다. transaction·capture·retention·cycle 시간은 중첩되므로 합산하지 않는다. trace loss/overflow는 필수 관측 실패다. 마감 WAL hash는 파일 수·총 bytes 제한을 적용한 streaming 방식이며 종료 뒤 한 번만 수집한다.

PixelMeasurement off에서는 timer/cycle/broadcast 실패/배치 분포를 disabled/null로 표현한다. 내구성 counter와 benchmark 파일 수명 관측은 계속하며 추가 비용이 같아야 비교 가능하다. timer count/total은 누적, windowMaxMillis는 60초×3 rolling 값이다. 전체 p99/최대값이나 각 구간 p95의 합으로 해석하지 않는다. GC MXBean의 마지막 실제 GC별 after heap과 시점을 보존하며 sampler heap을 after-GC로 바꾸지 않는다. process I/O는 네트워크·증거까지 포함한 전체 프로세스 값이다.

별도 `scripts/observe-phase18-host.ps1 -Output <새 JSONL> -Samples <유한 수> -IntervalMillis <간격>`은 Windows 물리 장치 전체 raw counter를 수집한다. counter 차분의 bytes/operation/latency와 장치 매핑은 WAL·DB data/redo/binlog·증거의 실제 물리 장치와 함께 해석한다. 미지원 counter는 missing이며 WAL 단독 비용으로 귀속하지 않는다. 수집 실패가 보조 원인 분해를 막는 것과 필수 heap/disk 안전 근거 상실을 구분한다. JFR·세부 SQL lock/commit·정확한 WAL durable→commit/WS 지연은 이 수집기의 확정값이 아니다.

서버와 발생기는 자기 clockId의 duration만 직접 계산한다. phase-clocks의 control 송수신 구간과 producer anchor UTC로 보수적인 정렬 범위를 남기며 프로세스 간 nanoTime 차이는 지연으로 보고하지 않는다. UTC 정밀도/동기화 오차가 보정되지 않았다면 원인 분해도 그 한계를 유지한다.

## A-3 정식 분석 CLI

설치된 Python으로 `scripts/analyze-phase18.py --plan <원본 계획> --run-dir <initial 폴더> --output <새 분석 JSON>`을 실행한다. 여러 case 계획에는 `--case <caseId>`를 추가한다. Java `Analyze`는 별도 집계기를 만들지 않고 계속 미제공이며 이 Python CLI가 유일한 정식 집계 경로다. 원본 계획/raw/producer·검증 증거는 읽기만 하고 새 결과를 CREATE_NEW로 기록한다. 분석 exit0은 분석 완료이며 원시 실행 성공을 뜻하지 않는다. 입력 형식 거부는 INPUT_REJECTED와 exit2로 구분한다.

보조 host 자료가 있으면 `--host-disk <JSONL> --devices <실제 장치 매핑 JSON>`을 함께 지정한다. 물리 장치별 raw counter 차분의 bytes/operation/latency를 전체 수집 구간 값으로 표시하며 idle 구간 latency는 null, counter reset/wrap은 계산 불가다. `_Total`은 개별 장치와 합산하지 않는다. 서버 collector·trace·발생기 sampler 원시 자료, manifest hash, 실제 child/runner exit와 서버 요청 소진을 교차 확인하며 요약 complete 플래그만으로 성공 판정하지 않는다.

분석 schema1은 원래 planned 집합, 예정 kind/phase별 terminal·중복/누락/계획 밖 ID, UNKNOWN/unresolved와 최초 미송신 결정 시각을 보존한다. 완료창 [start,end)의 accepted/s와 송신집합 accepted RTT·보조 처리량을 별도로 계산한다. send delay·scheduled-to-response·nearest-rank, 오류율의 분자/sent 분모·not_sent/planned 분모와 sent0의 null을 출력한다. 독립적인 고정 입력창마다 write/read 충족과 연속 동시 충족 시간을 확인한다. 입력 부족으로 쓰기 하위 지표를 폐기하지 않으며 mixed 전체 목표 달성과 구분한다. processing_capacity만으로 서버 포화 원인을 확정하지 않는다.

종료 상태는 INPUT_REJECTED/EXECUTION_FAILED/MEASUREMENT_INVALID/VALID_TARGET_MISSED/VALID_TARGET_MET/VALID_NO_TARGET다. 정합성 complete/mismatch/missing/unresolved·drain/recovery·WS raw canonical/연결 bitset 판정은 성능과 별도다. A-2에는 collector가 없었다는 한계를 유지하며 A-3 이후 필수 collector 누락은 정상 합격이 아니다. 원래 timeout을 늦은 완료로 바꾸거나 과거 계획을 현재 코드의 재실행 승인으로 사용하지 않는다. read hash 표본 수를 전수 version/content 검증으로 확대하지 않는다.

initial/recovery 각각 manifest·runner·실제 process identity/exit, runtime identity, consistency·drain·server 종료와 연결된 원시 파일의 선언 해시를 대조한다. initial은 별도 producer identity/exit·clock도 필요하다. A-3 관측은 양쪽 서버 collector·sample·trace·phase clock을 검증하고, initial에만 client collector/sample과 부하 phase envelope를 요구한다. recovery에는 새 발생기나 client 부하 시계를 요구하지 않는다. 모든 필수 검사 뒤 complete를 확정하며 누락·손상·해시/식별자 모순은 MEASUREMENT_INVALID, 실제 nonzero exit·미해결 종료·최초 실패 증거는 EXECUTION_FAILED로 보존한다. 유효한 하위 지표와 역할별 실패 근거는 결과에 남으며, 의도한 중단의 성공 manifest/recovery 부재가 최초 실행 실패를 바꾸지 않는다. 과거 입력의 source/class/WAL 원본 재검증 효력은 해당 실행 receipt와 작업기록의 해시 대응으로 별도 확인한다.

trace 종료의 관측 Error는 일반 작업 예외보다 우선 전파하되 그 작업 예외를 suppressed로 보존한다. 원래 작업이 Error이면 원래 객체를 유지하고 뒤따른 관측 Error를 중복 없이 보존한다. 정상 호출의 관측 Error도 전파하며 자기 자신을 suppressed로 추가하지 않는다.

## 공식 PILOT과 단일 trial

`Pilot`은 ready/A-4/role=pilot의 작은 group mixed 한 건만 받는다. `Run`은 ready/B·C·D의 단일 case를 받으며 다른 case나 단계로 자동 진행하지 않는다. 두 action 모두 `-EnvironmentFile`·`-ObservationPolicy`가 필수이고 정상 initial 후 같은 DB/WAL·입력·policy의 새 JVM recovery까지 한 실행이다. 분석은 기존 Python CLI로 별도 수행한다. 정상 process receipt만으로 측정/정합성 분석 완료를 주장하지 않는다. Plan의 executionAvailable은 구조·경로상 준비 상태이며 live DB/Redis 소유권 검사를 대신하지 않는다.

A-2/A-3의 작은 진단 제한과 실패 주입은 보존한다. 공식 trial은 fixture=none만 허용하고 중단 주입용 case ID를 거부한다. 공식 PILOT은 별도 deliberate read timeout 진단을 반복하지 않으며 bootstrap 뒤의 cooldown 거절은 측정 집합 밖에 남긴다. 실행 중 collector는 DB/redo/binlog 전체 증가·증거/build/WAL과 최소 여유도 확인하여 한도 실패를 신규 송신 STOP에 연결한다. A 현장 증거는64MiB/DB 증가2GiB의 제한을 유지하고, 후속 현장 증거는 plan의 명시 출력·DB/binlog 예약 내에서만 받는다.

서버 활동 보유 상한은 write/read 예정 수와 준비 진단4건으로 계산한다. 예열 완료에서는 저장된 bootstrap/예열 terminal을 대조하고 실제 송신 집합·accepted tail로 서버 소진을 확인한다. arrival_late/processing_capacity/user_pool 미송신은 원래 planned에 남겨 입력 미충족으로 분석하며 UNKNOWN은 정상화하지 않는다. 정합성 검증은 삭제된 WAL prefix를 DB 전수로 확인하고 잔존 구간 내부의 누락·순서·payload를 대조한다. 긴 실행의 서버 활동 JSON도 예정 요청량에 비례하는 유한 byte 예약을 사용한다.

`Phase18Deadline`은 검증된 계획의 실행 예산으로 runner가 한 번 정한 UTC 만료를 앱·발생기·recovery에 전달한다. JVM별로 남은 시간만 monotonic 기한에 고정하며 자식 시작·복구에서 전체 예산을 재부여하지 않는다. 계획 없는 Process fixture는 기존600초 상한, 계획 있는 실행은 검증된 totalSeconds 상한을 적용한다. STOP 적용 확인과 실패 후 cleanup 관측의 기존 상한은 별도이며 늦은 응답·exit0으로 최초 실패를 복원하지 않는다.

제어 대기는 `Phase18Execution.responseWaitMillis`의 선행 작업 예약과 전체 실행 잔여 시간 중 작은 값으로 제한한다. CONNECT는 서버 소진·5초 cooldown 검사·control, MEASURE는 예열 raw 준비/control·서버 소진, WARMUP_DONE은 예열·callback 소진·control을 포함한다. BOOTSTRAP은 자식 준비·callback 소진, READY는 자식 준비(bootstrap 없는 경우)·WS 전체 연결, SEND_DONE은 측정 기간, DRAINED는 callback→WS 전달→전체 socket close→최종 WS 집합 확인을 포함한다. WS 연결/close는 각 단계의 하나의 기한을 공유한다. START/WARMUP의 짧은 handoff, DONE의 raw 저장, STOP ACK와 실패 정리는 각 기존 기한을 유지한다. 정상 대기를 늘려도 독립 stdin 수신과 dispatch gate는 계속 STOP을 즉시 적용한다.

현재 계획/actual runtime hash·실행 명령·대표 반복 순서·예산 산정과 장기 미확정 항목은 phase-18 작업기록의 A-4 인계에 둔다. live PID/start/UUID·Redis 비사용·공간은 매 새 세션에서 확인하고 새 plan과 receipt로 동결하며, 소비한 PILOT plan을 다시 실행하지 않는다.

write의 same/spread 좌표·색은 `BenchmarkResults.pixel`의 기존 규칙을 사용한다. bootstrap ordinal0 뒤 예열과 측정의 전역 ordinal을 이어 붙이며 동일 seed의 비교 의미를 유지한다. A-2/A-3와 A-4 첫 검토 전의 별도 생성식 결과는 당시 receipt에 한한 기능 증거이며 새 대표 분포 결과로 합산하지 않는다.


## CAP 판본2 도구 계약 (2026-10-04)

CAP은 schema3/phase=CAP/stage=CAP-0..3의 새 계약이다. legacy schema2의 A/B/C/D 실행·오류 분류와 과거 분석 의미는 유지한다. `Run`은 검증된 ready 단일 CAP case도 받으며 자동으로 다음 단계나 반복을 시작하지 않는다. 이번 실행 승인은 CAP-0 등록 S1/S2에 한한다.

지원은 요청률·시간·WS·read·사용자 방식의 결합 제약이다. `Phase18Cap`의 terminal 합계140,000, delivery16,000,000, WS≤200, 계획 memory≤512MiB 및 분석 예약128MiB+terminal×2300bytes를 모두 만족해야 한다. 입력 parser의 WS3200/10,000RPS 숫자는 지원 약속이 아니다. W105/WS100/예열30초+측정1200초와 W105/WS200/예열30초+측정120초는 구조 검증 대상이고 실제 큰 부하는 후속 요청에서 수행한다. CAP-1/2 reuse pool은 `min(예열+측정 writes, rate×193+128)`이며 bootstrap 한 명은 별도다. 실제 사용자 준비 불가/181초 대기/처리 슬롯 부족은 원래 예정 ID의 미송신으로 남긴다.

CAP-3 준비 도구는 200초 주기, 정수 ordinal/nano offset, 적극 사용자 ordinal=논리 WS index, 초기 GET100/s·10ms 기한을 제공한다. 정식 일정은 읽기만 수행하는30초 예열과 `max(60,ceil(WS/100))`초 재조회 주기를 적용한다. 사용자 j의 viewport는 `(floorMod(seed,1024)+j)%1024` 타일로 고정하며 준비·예열·본측정에 공통 적용한다. read 샘플링용 전역 ordinal은 viewport identity와 분리한다. 예열 읽기 분모·예약·정합성도 포함하고 본측정 전 기존 소진 경계를 유지한다. S2는 별도 role/schedule로 write0/1/200/201초, read10/60/110/160초와1초 기한을 사용한다. 논리 WS 대응은 인증된 연결을 뜻하지 않는다. 이번에 formal CAP-3 서비스는 실행하지 않는다.

CAP write_busy503/cooldown429의 정확한 DTO만 REJECTED로 계속 수집한다. SYSTEM_ERRORS의 server_5xx 분류는 유지하며 bootstrap·읽기 거절·UNKNOWN은 STOP한다. callback, 예열/최종/복구 reader, initial/recovery manifest의 CAP 원시 hash와 분석이 같은 terminal을 대조한다. WS canonical/receipt 집합은 전수 비교하고 CAP 시각 파일은 planned event×connection×8bytes를 CREATE_NEW로 예약한다. 송신 gate 안에서 최종 monotonic 검사에 사용한 시각을 실제 dispatch raw에 기록한다.

발생기 소유 checkpoint observer는 별도 읽기 전용 JDBC 연결을 연다. UUID·3307·catalog·CONNECTION_ID를 확인하고, producer clock의 query start/end·성공 body/검증 완료를 기록하여 실제 accepted seq 집합의 B(t)를 계산한다. connection은 producer 종료 전에 닫는다. seq 간격을 backlog 건수로 쓰지 않는다. CAP 안전 collector는 마지막 capture의 실제 records에 capture 직전 표본 이후 WAL started records와 outstanding을 더한 보수적 상한을 사용한다. capture를0으로 가정하지 않으며 legacy fallback은 유지한다. CAP도 동결된 maxAge/maxMissing 초과 시 capture-safety-evidence-lost로 중단한다. 낮은 backlog 상한이 오래되거나 결측인 capture의 무기한 사용을 허용하지 않는다. 정상 NO_OP capture 갱신은 sparse 구간에도 계속 유효하다. 보조 device I/O는 MySQL/Redis/WAL이 공유하는 장치 전체 값이다.

`run-phase18-cap-smoke.ps1 -Smoke S1|S2 -LedgerRoot <CAP 준비 증거 경로>`가 준비 시작 시2400초 만료를 한 번 발급한다. C/F helper→새 build/receipt→Plan→Run→새 JVM recovery→Python 분석→소유 MySQL Stop을 연결한다. CAP 계획은 bootstrap 유무별8/9개 소진 구간과 준비 ready3회/control/STOP ACK, build180초·recovery120초·analysis60초·정상 종료10초·진단5초 예약을 계산하며 실제 전체 만료가 우선한다. formal 초기 GET 소진은 한 구간을 더 예약한다. 분석의 `--execution-expiry`는 이 만료와 일치해야 한다. 과거 증거 재분석 때는 이 옵션을 생략하며 실행 승인이 되지 않는다. 실제 argv와 실행 결과는 CAP 증거의 cap-commands.md/각 command·exit·closure 파일을 사용한다.

CAP 추가 판정은 INVALID_OR_STOPPED > TOOL_LIMIT(관측 불능) > INPUT_LIMIT > PASS/PERFORMANCE_MISS 순으로 적용하고 모든 원인을 보존한다. P=0·S=0·성공0의 N/A, 예정 cohort와 실제 송신창, success/sent와 accepted/planned를 구분한다. smoke는 별도 smokeVerification이고 capacityPoint=false다. 후속 선택의 순수 gate는 첫 PASS 뒤 상승, 정상 마감 INPUT_LIMIT 뒤 하강, 동일 조건 WS100 fallback,120초 개별3PASS 이후20분 조건을 검사한다. confirm-20min은 후보의 conditionsHash를 명시하고, 서로 다른 sessionId/runId/analysisHash/closureHash와 검증된 증거 연결(evidenceVerified)을 요구한다. 호출자는 실제 plan·정식 분석·마감의 해시 및 identity 연결을 검증한 기록만 전달한다. conditionsHash는 source/runtime·workload·관측 정책·환경 구성 등 비교 조건의 동결 해시이고, session/run/경로가 달라지는 전체 planHash의 동일성을 요구하지 않는다. 이 순수 함수가 파일 증거를 직접 검증하거나 실행을 승인하지는 않는다. capability/예산/현재 사용자 승인은 별도 필수다.

CAP controller는 기존 C/F helper와 동일하게 Windows PowerShell5.1의 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/run-phase18-cap-smoke.ps1 ...`로 실행한다. PowerShell7은 JSON UTC 날짜를 DateTime으로 바꾸어 문자열 기반 identity를 거짓 불일치로 만들 수 있어 첫 파일 생성 이전에 거부한다. 실패한 원래 closure는 보존하고 정상 SQL 종료를 추가로 확인한 경우만 원래 hash·동일 실패 상태·늘어난 소비 시간을 연결한 cap-closure-final.json을 새로 남긴다.

Windows PowerShell5.1에서 native stderr는 정상 경고일 수 있으므로 Gradle/계획 생성/분석 및 Java launcher 호출 구간에만 ErrorActionPreference=Continue를 적용하고 즉시 원복한다. 원시 stderr를 보존하고 실제 exit를 별도 기록하여 nonzero는 기존대로 중단한다. 이는 이미 실패한 session 재개를 허용하지 않는다.

최종 검토에서 WS 소진/정상 close/재확인과 준비 구간 전체 합산을 보완했다. 새 CAP 준비 만료2400초는 이 유한 예약 합계로 정했으며 이전1800초 계획/정책/실패는 바꾸지 않는다. 두 S1 준비 시도의 횟수는 그대로 소비되었고 이 보완이 추가 실행을 허용하지 않는다.


### CAP-0 이전 제출 이력 — 2026-10-04 수정본

아래 절은 이전 패키지 제출 당시 상태를 보존한 이력이다. 현재 후보의 실행·장부·시작 계약은 뒤의 2026-10-05 절을 적용한다.

최신 제공 source와 Git HEAD `3ae4bfd`를 기준으로 수정했다. 이전 완료 보고·실패·정책·receipt는 보존하며 수정된 source에 이전 runtime receipt를 붙여 실행하지 않는다. 이번 수정에는 서비스 시작이나 추가 S1 승인, 재시도 한도 확대가 없다.

- `phase18-cap-process.ps1`은 기존 Phase17C의 유한 stdout/stderr capture만 재사용하고, 무한 대기하는 Finish나 강제 종료 helper를 호출하지 않는다. `phase18-cap-worker.ps1`이 named parameter map으로 PS script를 실행하며 native stderr와 실제 exit를 분리한다. controller의 Inspect/Start/Verify/Environment·Redis 선택·build·Plan·Run·분석은 소유 worker로 관측한다.
- 같은 원래 UTC 만료와 parent monotonic 잔여 시간을 적용한다. 새 worker마다 전체 시간을 다시 주지 않는다. host observer에도 원래 만료와 cooperative stop 파일을 전달한다. 정상 마감은 원래 만료를 지켜야 하며 실패 뒤 정리 관측은 기존 drain 상한에 맞춘 별도180초 안에서만 한다. 늦은 exit0은 최초 실패를 지우지 않는다.
- 미종료 worker가 있으면 DB를 먼저 종료하지 않는다. 종료/stream join·SQL 정상 종료·잔여 JVM/listener/lock 확인이 끝나지 않으면 closure에 미해결 소유와 시간의 하한을 남기고 controller lock 파일을 유지한다. 자동 잠금 삭제나 재개는 없다. 모든 정리가 확인된 실패는 실패 상태를 유지한 채 마감한다.
- `phase18_cap_budget.py`는 제출된 최종 `cap-budget.json`을 seed로 요구한다. 파일이 없거나 계상 합계가 맞지 않으면 새 기준선으로 대체하지 않고 거부한다. 기존 C/F 계상액·과거 미계측 allowance를 보존하고 evidence/graph/work/cache/mysql/wal의 양의 관측 증가만 추가한다. 현재 점유의 감소는 누적 소비를 환급하지 않는다. 다음 실행 예약 C64MiB/F5GiB는 소비와 별도이며 매번 중복 가산하지 않는다. 원본 seed와 순차 `cap-storage-ledger-NNNNNN.json`의 hash chain을 매번 대조한다. 이 값은 관측 증가와 보수적 계상이지 관측 사이의 모든 임시 생성량 실측은 아니다.
- 각 worker의 argv/parameter·원래 만료·PID/start·stdout/stderr·최초 실패·exit/stream join은 `step-NNN-<name>.request.json`과 sidecar에 기록한다. cap-issued는 원본 장부 hash와 budget-before를 연결하고 closure는 최종 장부 snapshot과 검증 여부를 기록한다. 종전 step 로그 파일명이 아닌 새 sidecar를 사용한다.

Linux 수정 환경에서는 Python 선택 회귀만 직접 실행한다. JDK21/Windows PowerShell5.1 검증·현재 runtime receipt/Plan 생성·Graphify 갱신·실제 S1/S2는 별도 미검증이다. 서비스 없는 Windows fixture는 `scripts/test-phase18-cap-process.ps1 -Output <새 F 증거 폴더>`이며, 먼저 현재 `Set-PixelPlaceWorkEnvironment`로 새 F 작업/temp를 설정한다. Java 선택 회귀는 `Phase18CapTest`, `Phase18CapFlowTest`, `Phase18CollectorTest`와 일정/reader 영향 범위다. 이 검증이 끝나기 전 수정본을 실행 준비 완료로 표시하지 않는다.

### CAP-0 독립 Windows 후보 계약 — 2026-10-05

원본 작업 트리와 실제 HEAD 이력을 별도의 C 저장소에 재현한 후보용 계약이다. 원본 적용 및 추가 S1/S2 실행 승인은 포함하지 않는다. 기존 S1 준비 2회·서비스 시간 150,511ms와 기존 실패/receipt/정책은 그대로 유지한다. 최종 검증 판정과 원시 증거는 새 후보 패키지의 `review/REPORT.md` 및 실행 receipt를 기준으로 한다.

- controller는 worker를 생성 순간 Windows Job에 원자적으로 연결한다. Job은 강제 종료 플래그를 사용하지 않는다. worker와 분리된 일회성 후손, stdout/stderr의 최종 완료·오류 및 실제 종료코드를 모두 확인한다. 소유권 조회 실패는 종료로 추정하지 않는다. native 명령에는 원래 만료와 STOP 경로를 프로세스 환경으로 전달하고 MySQL probe·Redis 대기·Java launcher의 하위 작업 진입 및 대기를 남은 시간으로 제한한다. 만료 뒤 새로운 준비·부하는 금지하고 유한 cleanup 관측만 별도로 수행한다.
- 계속 실행할 서비스는 요청의 사전 `ServiceRole`과 helper의 검증된 준비/identity 증거가 필요하다. MySQL 시작은 사전 `mysql3307` 역할, 기존 UUID/경로/SQL 준비 검증, 실제 launcher/server PID·시작 시각·실행 파일 해시를 연결한다. 서비스의 표준 handle은 전용 파일로 제한한다. controller가 Job 소속과 실제 identity를 재확인하고 CREATE_NEW 인계 증거를 durable하게 저장한 뒤에만 다음 작업을 허용한다. 인계 실패·미등록 후손은 후속 작업과 DB 종료 진입을 차단하며 사후 서비스 재분류는 없다.
- 정상 인계한 서비스는 DB 사용 작업의 소진 조건에서 구분하고, DB 사용 일회성 후손 종료 확인 → 소유 SQL 정상 종료 → 모든 Job 후손 및 stream 종료 순서를 지킨다. 정리 완료가 최초 timeout·인계·로그·저장 실패를 지우지 않는다. 실패 시에도 controller는 Job 소유를 유지한다.
- native argv는 Windows CRT 규칙으로 인코딩하며 PowerShell 표현식으로 재평가하지 않는다. batch 호출은 정규화한 `cmd.exe`/batch 경로를 사용한다. 현재 batch 계약은 환경 확장을 일으키는 `%`, `!`, 줄바꿈을 거부하며 공백·한글·빈 인자·작은따옴표·큰따옴표를 보존한다. 실제 native 0/7과 worker host의 종료코드가 같아야 한다.
- 장부는 자동 초기화하지 않는다. 기존 seed에서 첫 사용을 명시적으로 시작할 때만 `python scripts/phase18_cap_budget.py --ledger-root <기존 seed 폴더> --initialize-anchor <그 폴더 밖의 새 절대 JSON 경로>`를 사용한다. 이 명령은 기존 장부·marker·anchor가 있으면 거부하며 실제 기존 CAP 장부에 이번 작업으로 실행하지 않았다. 초기화 marker는 seed hash·독립 anchor·고유 초기화 ID를 고정한다. 이후 intent → prepared anchor → ledger → committed anchor → commit witness를 저장하며 각 단계 사이 중단, anchor/끝부분 유실, 미확정 pending은 재시작 시 오류다. 낮은 소비량으로 자동 복구하거나 missing tip을 새 seed로 취급하지 않는다. 원래 allowance, 감소 환급 금지와 예약/소비 분리를 유지한다.
- CAP-3는 초기 GET 전체를 소진한 뒤 정확한 예정 ID, accepted/200·sent·검증 결과와 고정 viewport를 확인해야 READY를 발행한다. 미송신·누락·거절·검증 실패는 READY/예열을 차단하고 최초 실패를 보존한다. 분석기의 초기 GET 성공 검사도 유지한다.
- CAP 사용자 pool은 원래 due와 이전 검증 완료+181초를 비교한다. 늦은 dispatch에서 준비된 사용자를 보충 송신하지 않는다. 실제 전송 직전 STOP/전체 만료와 phase 지각도 다시 검사한다. 주사유는 STOP/만료 → phase 지각 → due 기준 user_not_ready → processing_capacity이며 ID·분모를 유지한다. legacy의 fixed-rate 동작, 읽기만 30초 예열·고정 viewport·sampling ordinal·UNKNOWN retire·S2 일정은 그대로다.

선택 회귀는 서비스 없는 Python CAP/기존 분석, `test-phase18-cap-process.ps1`, `test-phase18-cap-boundaries.ps1`, 직접 helper 회귀 및 Java CAP/collector/plan·시작/일정·deadline 계열이다. C에 후보·원시 증거를 두고 새로운 F build/project-cache/temp/JNA와 기존 F Gradle 캐시를 사용한다. 새 draft Plan은 해당 후보의 runtime receipt를 검증하는 용도이며 실제 DB/Redis 소유권과 실행 가능성을 주장하지 않는다.


## CAP-0 명시 재개 gate (2026-10-06)

`run-phase18-cap-smoke.ps1`의 일반 실행은 기존 같은 smoke 최대2회/RetryOf 규칙을 유지한다. 이번 사용자 재개는 같은 원본 LedgerRoot에 등록한 `cap-resume-registration.json`과 `-ResumeApproval`/`-ResumeApprovalHash`를 함께 지정할 때만 별도 검증한다. `phase18-cap-resume.ps1`은 지시·이전 정책·seed·두 FAILED의 attempt/issued/original·supplemental closure/SQL 정상 종료 hash, 전체 issued 목록, 누적 시간을 대조한다. 슬롯은 controller lock 안에서 CREATE_NEW로 소비하며 output 폴더 변경/재시작으로 반환하지 않는다. 한도는 전체4/S1최대3/S2최대1이며 기존 실패를 PASS로 수정하지 않는다.

S2는 이번 S1의 PASS, 실제 Run/분석exit0, `smokeVerification=PASS`, `capacityPoint=false`, 정합성/소진/WS/새 JVM recovery 완전성, 모든 worker 정상 join, DB 정상 종료와 최종 저장장부 예약을 요구한다. issued·Plan predecessor·closure는 새 승인/정책 hash를 연결하고 실행 증거 index의 hash도 대조한다. 기존 최초2400000ms 만료·누적3600000ms·180초 실패정리와 C/F 예약·저장 guard는 유지한다.

이 연결은 무서비스 경계21개와 일반 경로 거부 검증을 통과했다. 실제 재개는 저장 관측의 역사 symlink-fixture reparse 거부로 S1 발급 전에 중단됐다. 현재 실행 가능/실환경 검증 완료를 뜻하지 않는다. `build/agent-runs/phase18-cap0-resume-20261006/`과 `작업기록/phase-18-cap-progress.md`의 차단 상태·장부 sequence0·보존 준비잠금을 먼저 확인한다. 과거 fixture 삭제, reparse guard 우회, 잠금 수동 삭제로 재개하지 않는다.


## CAP-0 역사 fixture 저장 관측 보완 — 2026-10-06 수정 후보

저장 관측의 evidence root(`build/agent-runs`)에서만 `phase17-B-20260928-190355/symlink-fixture/wal`을 고정된 보존 예외로 다룬다. 해당 항목이 실제 symbolic link이며 같은 fixture의 일반 파일 `unrelated-target`을 절대 경로로 가리키고 내용이 정확히 `preserved`인지 확인한다. Windows에서는 symbolic-link reparse tag도 확인한다. 상위 경로에 reparse가 있거나 다른 링크·junction·대상 변경·읽기 실패·기대 fixture 유실이 있으면 계속 거부한다. production WAL의 reparse 거부 정책은 변경하지 않는다.

링크를 따라 순회하지 않고 링크 자체의 논리 길이를 계상한다. 대상은 원래 일반 파일 경로에서 정확히 한 번 계상하며, 앞뒤 identity·대상 내용·계상 identity를 대조한다. 성공 관측에는 `storageScan.version=2`와 링크/대상 audit를 남긴다. 값은 파일 논리 길이이며 할당 cluster·관측 사이 생성 후 삭제된 모든 임시 쓰기의 정확한 누적량으로 해석하지 않는다. 기존 양의 관측 증가량 누적, seed 소비 하한, allowance, 예약·여유 상한, intent/commit/anchor 계약을 유지한다.

장부는 이미 초기화되어 있다. 이번 보완 적용 시 `--initialize-anchor`를 다시 실행하지 않는다. 보존된 `cap-controller.lock`을 단순 삭제해 controller를 시작하지 않는다. Windows에서 현재 소유권을 확인하고 기존 잠금의 배타 핸들을 보유한 상태에서 최초 만료가 연결된 관측·장부 검증을 수행한다. 준비 실패를 별도 closure로 마감한 뒤 기존 잠금 파일을 고유 보존 경로로 이동하는 절차는 전달 패키지 `WINDOWS-VERIFY-AND-CLOSE.md`에 있다. 원래 실패 기록·seed·초기화 marker·승인/정책은 덮어쓰지 않으며, 정상 장부 append에 따른 anchor 전진만 허용한다.

새 `scripts/test_cap_storage_scan.py`와 기존 `test_cap_budget.py`를 함께 실행한다. Linux에서는 24개 중 23개 통과, Windows junction 1개 skip이다. Windows에서는 해당 junction과 Windows symbolic-link 경로를 포함한 24개 전체의 실제 통과 및 실제 보존 fixture 읽기 전용 대조가 필요하다. Python과 Windows의 파일 identity 동작 차이는 Linux 결과로 확정하지 않는다. 현재 상태는 후보 검증이며 현장 저장 관측·잠금 마감 완료가 아니다.


## CAP-0 storage r2 후보 — Windows stat 비교 및 검증 보조 보완

2026-10-06 r1 Windows 검증은 24개 중 10개 통과·14개 symlink 권한 skip이었으며, 보존 fixture도 target의 lstat/fstat ctime_ns 불일치로 거부됐다. r1에서 서로 다른 API의 ctime을 같은 의미라고 가정한 비교를 수정한다. API 간에는 유효한 device/file ID, 종류, 길이, mtime을 대조하고, handle의 fstat 전후 및 경로의 lstat 전후에는 ctime을 포함한 기존 snapshot 대조를 유지한다. 정확한 `preserved` 내용, reparse/상위 경로/대상 검증도 유지한다. 관측 audit version 3은 path/opened handle snapshot과 비교 정책을 함께 기록한다. 기존 version 2 장부는 수정하지 않는다.

새 `IdentityComparison` 8개는 일반 임시 파일만 사용해 symlink 생성 권한 없이 실행한다. 제공된 Windows 관측값 재생은 대역 검증으로 명시한다. 전체 suite는 기존 24개+8개로 32개가 되며, 링크 생성 권한이 필요한 기존 14개를 삭제·대체하거나 통과로 계산하지 않는다.

전달 패키지의 `verification/verify-windows.ps1`은 이전 실패 증거를 덮어쓰지 않는 새 보조 스크립트다. 고유 C/F session에서 실제 보존 fixture 읽기 검증과 회귀를 실행한다. `Diagnostic`은 identity 8+장부 9+Windows junction 1의 18개이며, 성공해도 `RequiredRegressionComplete=false`다. `Full`은 32개 전부와 skip 0을 요구한다. 원래 runner의 잘못된 `JobCapture.Dispose()`는 제거하고 final join/stream 확인 뒤 `Release()`를 사용한다. cleanup·release 실패를 보존한 다음 최종 결과를 저장하며 저장 실패는 nonzero다. 실제 PS5.1 실행·실패 분기 검증은 Windows 후속이다.

이전 보조의 전체 로그 `skipped` 검색은 성공한 `test_link_read_failure_is_not_silently_skipped` 이름까지 거부한다. 새 보조는 실제 `... ok` 행 수와 `Ran N tests`, 마지막 `OK` 요약을 함께 확인한다. 문자열 판정 대역 6개는 실제 PS5.1 실행과 구분한다.

기존 비상승 cgt 정책을 유지한다. Full의 14개를 실행할 허용 환경이 없으면 Diagnostic 결과만 보고하고 전체 검증 완료를 선언하지 않는다. 이 패키지는 권한 상승·개발자 모드·사용자 권한 변경을 승인하거나 자동 수행하지 않는다. Diagnostic/Full 보조 스크립트는 저장 장부 append·준비 실패 closure·기존 잠금 이동·서비스·S1/S2를 수행하지 않는다. 실제 준비 마감은 Full, 현재 소유권, 유효 저장 관측과 별도 closure 조건을 충족한 뒤에만 가능하다.


## CAP-0 마감 배열·기존 S1 보완과 후속 S2 정책 (2026-10-12)

controller의 최종 저장은 `Save-CapClosure`를 사용한다. `OwnedJava`는 probe의 배열을 조건식 출력 열거 없이 직접 대입해 0/1/여러 개에서 JSON 배열을 유지한다. probe/필드 부재·null·객체/문자열·listener/lock 관측 누락은 빈 관측이 아니며 정상 자원 마감을 선언하지 않는다. 실패 closure 저장은 허용하고 원래 최초 실패·exit·시간을 보존한다. CREATE_NEW·최초 만료·최종 Job 후손/stream·Release 조건은 기존 helper를 유지한다.

S1 `phase18-cap0-s1-20261012-041711`/run `cap0-s1-20261012-041711`/attempt5의 원본 `OwnedJava:{}`만 `-S1ClosureSupplement <절대 JSON 경로> -S1ClosureSupplementHash <SHA256>`로 연결할 수 있다. schema3의 S2에만 허용하며 보완의 원본 closure·attempt/slot·현재 승인/당시 정책·원래 실행 index·그 index에 포함된 raw probe의 해시와 identity를 검증한다. raw probe와 내부 Probe는 유효한 빈 배열·3307잔여0·runlock없음이어야 한다. 그 뒤 원본 PASS/실제 exit/첫 실패 없음·MySQL 정상 종료·worker join/Release·정합성/새 JVM 복구·최종 저장·시간 gate를 모두 계속 검증한다. 임의 `{}`/null/누락을 전역 수용하거나 원본 파일을 다시 저장하지 않는다.

읽기 전용 `Get-CapResumeContext`는 당시 정책으로 보완 S1을 확인할 수 있다. 실제 controller는 보완 입력이 있을 때 후속 정책 등록을 추가 요구한다. 후속 정책은 `s1Predecessor`로 S1 당시 정책, `s1ClosureSupplement`로 같은 보완 path/hash를 연결한다. sourceBinding의 같은 파일 집합을 현재 해시로 갱신하는 것과 이 두 참조 외 정책 변경은 거부한다. S1은 당시 Plan/predecessor/receipt/runtime/manifest로 검증하고, S2는 현재 sourceBinding 및 새 실행의 기존 build/receipt/Plan 검사를 따른다. 승인 5/1/6·서비스 3600000ms·실행 예약2400000ms·k6/저장 예약은 확대하지 않는다. 후속 정책 위에 다시 후속 정책을 잇는 일반 전환 기능은 없다.

현재 보완/후속 입력은 `build/agent-runs/cap-closure-fix-20261012-01/`의 `s1-ownedjava-supplement.json`, `cap-policy-s2-successor.json`, `cap-resume-registration.s2-candidate.json`이다. 실제 등록은 아직 이전 정책이다. 후보는 실행 허가나 등록 전환 완료가 아니며 S2 직전에 현재 source·원본 증거·S2 미사용·자원/여유/기존 예약·장부/anchor를 재확인한다. 기존 배타 준비 절차에서 등록을 원자적으로 전환한 뒤 controller에 같은 보완 path/hash와 기존 approval/k6/S2 reservation을 전달한다. 정확한 호출과 전환 순서는 같은 증거 루트의 `S2-HANDOFF.txt`를 따른다. 이번 작업에서는 등록·장부·잠금 변경과 실제 S1/S2·서비스를 수행하지 않았다.

## CAP-0 새 S2 출력 후보: 준비 단계 50%·5배 확장 (2026-10-12)

새 k6 S2에는 `cap0-s2-output-50pct-v1` profile을 사용한다. 기존 sample64MiB/run256MiB/session320MiB·표본10,000·trace2MiB/6,000은 확장 출발값이다. 정수 예상량 U에 대해 2U≥L이면 L을5배 늘리고 반복한다. 한도가 커져도 사용량 U를 그 한도로 바꾸지 않는다. 준비 완료값을 Plan/policy에 동결하며 실행 중50% 초과만으로 STOP하거나 값을 바꾸지 않는다. 현재 지원 입력은 본측정220초·공통최초만료2400초·유한정리180초·250ms·WS2·inflight4/4다. initial/recovery 각각에2580초를 보수 적용하고, trace의 쓰기·시작/복구/종료 여유와 제어RPC 출력도 포함한다. 이는 실제 JVM 실행 시간이나 모든 비정상 조합의 완전한 byte 상한 증명이 아니다.

`new-phase18-cap-plan.py --s2-profile`은 원본 C/F template path/hash와 U/출발값/확장값을 검증하여 표본파일320MiB·50,000개, trace50MiB·30,000개, 공유metrics505,943,040bytes, run1280MiB, session1600MiB를 연결한다. controller의 배타 guard 안에서 슬롯 발급 전 profile/참조/예약을 순수 검사하고 기존 MySQL Start receipt→Verify→Environment 승계로 동결 evidence 값을 전달한다. 실제 environment/Plan/JVM 생성 순서는 유지한다. S1과 Java 발생기 경로 및 과거 증거에는 소급하지 않는다.

k6 metrics는 WS와 measurement 등 모든 phase의 공유 한도다. 종료·Release된 phase도 실제 파일 합계에 포함하고 실행 중·종료·최종 저장 시 다시 검사한다. 새 S2의 run 예상량은 initial/recovery sample·직렬화 observation, 공유metrics, 그 밖의 필수파일32MiB를 한 번씩 합산한다. session 예상량은 run과 준비/마감64MiB다. 부모 한도가 자식 한도 합보다 작을 수 있으며 실제 run/session 합계 guard는 유지한다. 출력 위반이 `/stop` 전달을 막지 않으며 최초 실패·실제 exit·stream join·Release를 보존한다. JVM/k6/분석 메모리·최소 여유·실행시간·부하·재시도는 이 확장 대상이 아니다.

분석기는 검증한 policy/manifest/hash의 역할별 한도를 사용한다. CAP server sample은 실제 `policy-cap.json` 원본까지 대조하며 Java client는 기존 예약을 따른다. 새 S2만50,000행을 허용하고 실제 예상표본10,322개를 검증한다. 수집기는 행6,144bytes·표본 수·파일bytes를 검사하며 한도를 배열 선할당 크기로 사용하지 않는다. 일반 metadata JSON16MiB·줄64KiB 제한은 유지하고, 확대 trace가 들어가는 observation.json만 검증된 trace 한도+1MiB를 적용한다. trace 계상량과 직렬화 크기는 별도 확인하며 분석512MiB와 기존 만료 guard를 유지한다.

저장 예약은 기존 schema2에 `storagePolicy.kind=s2-storage-50pct-v1`을 명시한다. 앞 절의 C20GiB/F100GiB·trial32GiB 경로는 기존 정책에 유지하며, 새 S2만 C20GiB/F100GiB/trial8GiB를 출발값으로 동일 규칙을 적용한다. 새 trial 후보는40GiB다. 과거 charged/미확정+양의증가+추가peak+다음예약으로 누적 U를 계산하고, 물리적 여유에서는 과거 소비를 다시 빼지 않는다. 최소여유10GiB와 다음C64MiB/F5GiB는 유지한다. 후보의 이전tip·산정관측을 동결하고 최초예약 때 현재 증가에도2U<L인지 확인한다. 동일 예약이 시작된 뒤에는50% 규칙을 다시 적용하지 않고 동결hard limit·실제 여유·최초위반을 유지한다. 실제 예약/장부 append는 별도 실행 범위다.

현재 출력 후보의 정상 준비 검증은 가능하지만 기존 실제 S2 슬롯은 attempt6에서 소비되어 FAILED로 마감됐다. S1 보완용 sourceBinding-only successor는 당시 계약이며 새 출력·슬롯·시간을 바꾸는 경로가 아니다. 다음 실제 S2에는 추가슬롯1개와 누적시간·새정책/승인/등록 계약이 필요하다. 저장 확장은 그 승인이나 구현을 대신하지 않는다. 부족 시 기존 만료를 연장하지 않고 안전 정리 후 부족값·대안을 질문하며, 이번 승인 범위의 저장5배 계산은 다시 허락을 묻지 않는다. 현재 검증/한계/다음 시작점은 CAP 진행기록과 `build/agent-runs/cap-s2-storage-expand-20261012-01/REVIEW.txt`, `S2-HANDOFF.txt`를 따른다.


## 추가 S2 1회·누적 75분 재개 승인 (2026-10-12)

schema4 `cap0-additional-s2-75min` 승인은 기존 schema3의 소진과 구분한다. S1 5회·S2 2회·총7회와 누적4,500,000ms만 허용하며 기존6회, 보수서비스시간·기록하한·미확정을 승계한다. 단일40분·실패정리180초·S2 본측정220초와 source-only successor 계약은 유지한다. 원본S1 PASS/배열보완, 실패S2의 issued/slot/closure/원시증거, 이전등록/장부, 현재전체소스/runtime와 최종 확장profile/저장예약을 발급 전에 검증한다. 준비와 controller는 같은 배타guard를 쓰고, 준비에서 연 저장예약은 같은 참조로 이어받는다. 실제 슬롯은 기존controller의 CREATE_NEW 지점에서 한 번 소비하며 실패후 자동 재시도하지 않는다. CAP-1은 별도 실행 요청 대상이다. 실제 실행·소비·완료 상태는 Phase18 진행기록을 따른다.

승인에 연결한 기존 runtime receipt는 검증 재사용 근거다. 실제 controller는 현재 session의 `phase18ToolsClasspath`로 실행 classpath/receipt를 만들며, 이전 session의 classpath 경로를 복사하여 실행하지 않는다. `Phase18Runtime`의 현재 session build 또는 F Gradle cache 경계 검증을 유지한다.

## CAP-0 완료까지의 후속 실행 권한 (2026-10-13)

사용자의 후속 지시가 위 2026-10-12의 추가1회/75분 및 매번 추가승인을 요구하는 제한을 대체한다. 기존 schema4에 `kind=cap0-completion`을 사용하며 `totalAttempts`, `s2Max`, `serviceMillisMax`와 해당 정책 상한은 null(누적 실행 차단 없음)이다. S1 5회 및 기존 PASS를 보존하며 S1 재실행·CAP-1은 허용하지 않는다. 횟수·보수 서비스 소비·기록 하한·과거 미확정은 계속 계상한다. 새로운 승인 UI나 관리 도구는 사용하지 않는다.

기존 승인/정책/등록 파일은 현재 source/runtime·출력 profile·저장 예약을 실행별로 동결하는 용도로 유지한다. attempt7 이후의 모든 실제 실패는 `continuationFailures`에 순서대로 연결하여 원본 issued/slot/closure/index/정상 SQL종료/장부와 소비를 검증한다. 미정리 실패나 이미 PASS인 S2의 재실행은 거부한다. 슬롯은 실제 attempt 번호를 붙여 CREATE_NEW로 기록하며 같은 후보를 중복 실행하지 않는다. 이전 등록은 기존 배타 준비에서 보존 교체하고 같은 저장 예약을 controller가 이어받는다.

관련 원인 수정·영향 검증 뒤 새 S2 실행을 추가 확인 없이 계속할 수 있다. 출력·저장 예약은 기존 유한 산정/5배 경로를 이용해 새 실행 전에 필요한 값으로 조정할 수 있다. 이미 실행 중인 값과 최초 만료는 바꾸지 않는다. 단일40분·유한정리180초·본측정220초·정합성/복구/성능 기준·최소실제여유10GiB·현재소유권/정상마감은 유지한다. 실패·중단 측정을 이어 붙이거나 실패 기록을 성공으로 변경하지 않는다. 실적과 완료 판정은 진행 기록에 남긴다.


## CAP-1 W-SPREAD 전용 연결 — 2026-10-13

사용자가 수행을 요청한 2026-10-13 CAP-1 명령문은 CAP-1의 누적 시도·시간 고정 중단 및 후보 실패 뒤 전체 종료 규칙을 교정한다. 원본 CAP-0의 승인/등록/실패와 장부는 보존한다. `run-phase18-cap1.ps1 -LedgerRoot <기존 장부> -PolicyPath <새 동결 정책> -PolicyHash <sha256>`는 별도 등록 `cap1-registration.json`의 실제 CAP-1/cap-target 입력만 받는다. S1/S2 전용 controller는 그대로다.

`phase18_cap1.py`와 `Phase18Cap1`은 W-SPREAD/R0/WS100, bootstrap 1건, 예열30초 및 본측정60/120/1200초의 유한 시간·출력 산식을 연결한다. 기존 seed15와 재사용 사용자 산식, fixed VU128/read4, 실제 앱2GiB/안전heap1.5GiB, k6 Go soft heap512MiB·native 개별768MiB/합산1,280MiB를 사용한다. 새 F CAP-1의 분석 예약은1GiB이며 다른 단계의512MiB는 유지한다. 준비·소진9회·복구·분석·SQL종료·마감을 포함한 항목별 예약 합으로 최초 만료를 발급하고 실행 중 연장하지 않는다. 3,600초 초과는 새 CAP-1 profile에만 허용하며 기존 단계의 제한은 보존한다. 출력 예상량 U와 한도 L은 구분하고 2U≥L인 동안 L을5배 확장한다. 초기/복구의 전체 관측 수명과 종료 후 남아 있는 metrics 파일을 포함한다.

새 CAP-1 storage policy는 기존 append-only 장부의 같은 산정·anchor·최초 위반·고정 baseObserved를 재사용한다. 과거 소비와 미확정을 환급하거나 초기화하지 않는다. 원본 S1/S2 전체 실행 증거·현재 source·stage/role·이번 조합의 지원·기존 시도/소유 마감을 서비스 전에 검사한다. 20분 진입은 실제 다른120초 PASS3회와 같은 조건의 상충 실패 부재를 요구한다. 높은 조합의 구형 HTTP/WS 보유량 상한 초과는 준비 보완 대상이며 그 자체로 최종 TOOL_LIMIT나 서버 경계가 아니다.

이 절은 실행 연결의 설명이다. 실제 검증·실패·진행 상태와 지원 확인 범위는 CAP 진행 기록과 연결된 원본 증거를 따른다. W-SAME/MIXED 및 CAP-2 이후를 수행했다는 의미가 아니다.

CAP-1 저장 산식은 binlog의 타일 before/after 전체 이미지(이벤트당 128KiB와 4KiB 부대 비용), 사용자 준비, canonical DB 저장 및 MySQL 공유/임시 1GiB 여유를 포함한다. WAL·catalog·binlog·MySQL 증가의 예상량과 한도를 별도로 계산하며 기존 4MiB/1GiB/1GiB/2GiB에서 50%·5배 규칙을 적용한다. MySQL Start의 기존 5GiB 초과는 CAP-1/cap-target 정책의 동결 mysqlBytes와 일치할 때만 허용하며 새 CAP-1 절대 상한은250GiB다. 기존 호출의 기본2GiB·5GiB 경계는 유지한다. 저장 실패에서도 실제 storage-after를 먼저 남기며 정상 완료 manifest는 실패 시 만들지 않는다.

재개 호출은 원본 실패·소유 마감·장부·해결 증거를 모두 검증한다. 준비 중 작은 template 할당 오류와, initial 전수 대조 이후 단독으로 발생한 catalog/binlog 예약 오류를 구분한다. 후자는 원래 input/performance 미달이 없고 정상 종료·worker 마감·실제 증가량·수정된 Plan이 확인되며 recovery를 시도하지 않은 경우에 한정한다. 이때도 원본 FAILED/INVALID_OR_STOPPED와 recovery 미실행을 보존하며 성능 PASS로 바꾸지 않는다. 다른 정합성/복구 실패나 미해결 소유는 거부한다.

CAP의 capture 상한은 직전 표본의 종료 시각이 새 capture보다 늦은 경우에도, 최대64개의 유한 표본 이력에서 capture 이전임이 확인된 가장 최근 누적 카운터를 사용한다. 모든 적격 표본이 없으면 기존 보수0을 유지한다. 실제 backlog/age/missing/observer/heap/디스크 중단선은 유지하며 시각이 겹친 표본을 안전한 선행 표본으로 가정하지 않는다. C/F 물리 예약은 독립 파일 hard limit의 합 대신 조합의 보수 예상 점유와 다음 C64MiB/F5GiB를 사용하고, 실행 중 개별 hard guard와 최소10GiB는 그대로 적용한다. 다른 단계의 Plan 저장 규칙은 유지한다.

수집기 counter-history 재개는 원래 단독 capture 상한 STOP과 전체 원시 표본의 수정 코드 재생, 정상 종료·소유 마감, 실제 HTTP가 accepted 또는 STOP 후 확정 미송신뿐임을 함께 확인한 경우만 허용한다. 중단된 입력을 유효 INPUT_LIMIT로 바꾸거나 원래 STOP/미복구를 성공으로 고치지 않는다.

실패한 W250의 사본 진단은 `Phase18StoppedRecovery`와 `mysql-test-instance.ps1 -DiagnosticDescriptor <path>`로 원본 Plan/runtime에 대응하는 완전한 cold copy만 사용한다. 사본 catalog는 보존하며 DDL/reseed를 하지 않는다. 진단은 과거 HTTP 응답이나 원본 PASS를 만들지 않는다. CAP-1 재개 예외는 해당 원본 Plan 해시·두 요청의 내구 효과·새 JVM 정합성·원본 보존·정상 SQL종료·소유 마감·장부와 수정된 WS 전체규모/실패 검증을 연결하여 매 준비 시 확인한다. 새 trial의 UNKNOWN/unresolved0 및 유효 성능/입력 미달 계약에는 이 예외를 적용하지 않는다.

CAP-1 출력 상한은 실제 사용 예상량과 분리해 동결한다. 현재 streamed k6 metrics의 인자 상한은 60GiB, MySQL 관리 경로의 CAP-1 증거 상한은 200GiB이며 큰 값을 선할당하지 않는다. 기존 단계의 12GiB/20GiB 경계는 유지한다. 20GiB를 넘는 증거 한도는 CAP-1 Start 정책의 정확한 profile 값과 일치해야 한다. 전체 규모 fixture의 W250·W500 결과는 각각의 실행 근거로 판단하며 서버 성능 PASS로 사용하지 않는다.


W500 실제 실행에서 수신 callback이 몰리는 동안 native k6 메모리 중단과 STOP 확인 지연이 확인됐다. 현재 전달 경로는 매 배치의 saved/id/count 확인과 함께 stop/closing을 검증하며, 중단된 batch와 저장 완료 batch를 구분한다. 실패 cleanup은 원래 만료 잔여와 180초 중 작은 값으로 동결한다. 대기 소진 실패 기록을 남긴 뒤에는 새 입력 없이 기존 소유만 유지하며 강제 종료로 마감을 만들지 않는다.

body escape cache는 전달 항목을 제거하지 않는 직렬화 재사용이며 16,384개/16MiB로 제한한다. 이 범위는 실패 시 보존된 12,651-event 구간을 포함하고, 대기/전송 중 8,192건·4MiB 및 native memory/Go heap은 확대하지 않는다. cache hit/miss·최대 count/bytes를 보존하고 입력·producer·두 분석 경로가 대조한다. 정상 fixture 외에 최대 5초·5,000-frame·8MiB의 연결별 burst를 기존 fixture 안에서 선택 검증하며, fixture의 지연은 서버 성능 판정에 사용하지 않는다.

재개 예외는 원래 W250 queue 실패와 보존된 W500 memory 실패의 정확한 Plan/runtime/진단에 한정한다. 후자는 W500 정상 및 연결별 burst 전체 검증, 원래 데이터·복구·소유 마감, 실제 실패 원인 수정·회귀 근거까지 있어야 통과한다. 실패 이력은 후보 PASS나 유효 U로 바뀌지 않으며 다른 새 실패는 별도 진단 없이 허용하지 않는다.

추가 W500 정상 전달 fixture는 501/s·30+60초로 공급하고 실제 accepted45000-event 이상 및 계획 대비99.9% 이상을 요구한다. 종료 경계의 arrival_late는 원시 미송신으로 보존하고 실제 accepted 전체×100의 전수 WS를 대조한다. 정확한 W25022500-event 및 W500 burst45000-event 검증은 별도로 유지한다. 이는 추가 전달량 지원 검증이며 CAP trial 요청률·기간·판정 기준의 변경이 아니다.

## 2026-10-14 F 생성물·archive·장부 전환

이 절은 위 과거 C evidence 기본값을 신규 실행에 한해 대체한다. 최신 Graphify는 아래 사용자 지정 C 예외를 따르며 실행 TEMP/TMP는 F를 유지한다. `pixel-place-work.ps1`, Windows wrapper, Plan 생성기/검사기, CAP controller/worker, 하네스와 실패/fixture-backup 경로는 F 전용 신규 출력을 사용한다. 일반 개발은 F projects/pixel-place, 소유 실행은 F sessions, 증거는 F evidence로 분리한다. k6/HTTP/WS/collector/log/분석 SQLite는 호출자의 F Plan 경로를 따르며 신규 C 또는 상대 output fallback을 거부한다.

과거 bytes/hash/절대 identity는 바꾸지 않는다. `F:/pixel-place-archive/pixel-place/relocations.json`과 파일별 manifests는 닫힌 archive의 읽기 연결이며, 신규 runtime 소유권이 아니다. 활성 제어 위치는 `scripts/pixel-place-storage.json`을 따르고 독립 anchor와 기존 append-only intent/commit 갱신을 유지한다. 이전 C guard 파일의 존재만으로 handle 사용 중이라고 단정하거나 수동 삭제하지 않는다.

장부의 원래 `GROUPS`와 schema1/2 replay 산식은 보존한다. 명시적 schema3 `volumeLayout=f-output-v1`에서만 evidence/graph/archive/project와 나머지 F 그룹의 동시 추가 peak를 합산하며 남은 C fixture는 retained 그룹이다. 복사 쓰기와 준비 증가를 한 번 계상하고 이전 charged C/F·allowance·미확정을 승계한다. 현재 점유는 실제 free에 이미 반영되어 있으므로 미래 증가와 다음 예약만 free에서 뺀다. F 전환 뒤 다음 예약은 F5GiB+64MiB, C source/기록 예약64MiB다. 과거 소비를 환급하거나 한도를 묵시적으로 확대하지 않는다.

아래는 저장 계약 보완·검증 후 사용할 기존 장부 도구의 서비스 없는 관측 호출 예다. 수동 정리 직후에는 C Graphify와 f-output-v1의 F graph 그룹이 일치하지 않았으므로, 아래 「누적 자료 수동 정리와 현재 Graphify 예외」에 따라 실제 C/F 배치를 반영하는 저장 계약과 관측·계상 경로를 먼저 보완·검증해야 한다. 현재 예약이 닫혔고 배타 guard를 얻은 상태에서 해당 선행조건까지 충족한 경우에만 새 관측을 append한다. trial/attempt 발급이나 만료 연장은 없다. 당시 문서 정정은 저장 계약 보완이나 장부 연결의 완료 증거가 아니었다. 현재 신규 관측은 아래 f-output-c-graph-v2 successor를 요구한다.

```powershell
& 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe' -B .\scripts\phase18_cap_budget.py `
  --ledger-root 'F:/pixel-place-work/projects/pixel-place/control/phase18-cap0-prep-20261004-181654' `
  --repo "$PWD" --observe-current --output 'F:/pixel-place-work/evidence/<새-session>/storage-observation.json'
```

Graphify의 설치된 `GRAPHIFY_OUT` 환경과 `update` AST 기능은 `scripts/update-graphify.ps1`로 연결한다. 최신 index/cache/backup은 사용자 결정에 따라 C:/Users/cgt/Desktop/Java/pixel-place/graphify-out, 옛 날짜별 사본은 archive다. 현재 장부와의 차이는 아래 수동 정리 절을 따른다. 자동 정리·테스트 종료 삭제·기간/용량 삭제·예약 작업을 추가하지 않는다.

출력 장치가 F로 바뀌어 WAL/MySQL과의 공유 I/O 조건이 달라졌다. 기존 W250/120초 3회 PASS는 과거 조건으로 보존하며 새 조건 반복에 자동 합산하지 않는다. 이 저장 전환은 CAP-1 완료나 20분 지원/PASS가 아니다. CAP-2/3·D·production 튜닝은 별도 범위다.


## 누적 자료 수동 정리와 현재 Graphify 예외 (2026-10-14)

사용자가 실행명령문 수행을 지시할 때만 현재 사용·참조·소유권을 확인하여 누적 자료를 수동 정리한다. 자동 삭제·테스트 종료 hook·기간/용량 임계값·예약 작업을 추가하지 않는다. 원시 HTTP/WS/k6·측정 WAL·판정/종료/보존 증명·현재 참조되는 runtime/도구·CAP 장부는 유지한다. 제거된 진단 DB 사본 bytes로 같은 실패 상태를 재현하는 능력은 포기하며, 과거 증명 JSON을 현재 사본 존재 증명으로 해석하지 않는다.

최신 Graphify는 사용자 결정에 따라 `C:/Users/cgt/Desktop/Java/pixel-place/graphify-out`과 현재 `scripts/update-graphify.ps1`의 C 설정을 유지한다. F의 최신 Graphify는 사용자가 이미 제거했으며 수동 정리의 확보량에 포함하지 않는다. 이전 F 전환 기록은 당시 이력이다. 수동 정리 당시 `phase18_cap_budget.py`/`pixel_place_paths.py`의 f-output-v1 graph 그룹은 F를 가리켰으므로, 위 `--observe-current`를 그대로 실행한 관측을 현재 C/F 전체 배치의 유효 장부 갱신으로 승인하지 않는다. 이 수동 정리에서는 guard/산식을 완화하거나 전환을 대신하지 않고 chain을 보존하고 별도 관측을 남겼다. CAP 재개 전 현재 C Graphify를 포함하는 명시적 볼륨 계약으로 정리 후 점유·추가 쓰기·예약을 연결해야 한다. 과거 charged·allowance·시도/시간·미확정·실패는 환급/초기화하지 않는다.

실제 제거 목록·유지 자료·검증·장부 미연결은 `F:/pixel-place-work/evidence/manual-cleanup-20261014-01/누적테스트자료_수동정리_결과_20261014.txt`와 `post-verification.json`을 따른다. 정리 완료는 새 trial, 한도 확대, 만료 연장 또는 CAP-1 완료의 근거가 아니다.


## CAP-1 F 재개용 저장·보유 계약 보완 (2026-10-14)

현재 신규 관측·예약은 명시적 `f-output-c-graph-v2`를 사용한다. 기존 f-output-v1과 그 이전 chain의 replay·hash는 유지하며, 닫힌 v1에서만 C Graphify 위치로 한 번 전환한다. 전환은 그룹별 확인된 양의 쓰기와 양의 순증 중 큰 값을 계상하고, 별도 외부 C 쓰기는 분리한다. 순감소를 과거 charged의 환급으로 쓰지 않는다. C Graphify와 F evidence/archive/project/work/cache/MySQL/WAL의 중복 없는 현재 점유를 새 기준으로 삼고, 기존 소비·allowance·미확정과 독립 anchor를 승계한다. 전환 원본과 실제 수행 결과는 CAP 진행기록이 가리키는 장부·증거를 따른다.

CAP-1의 유한 생산자/소비자 상한은 HTTP terminal320,000, 고유 WS event320,000, delivery32,000,000, timing256MiB, HTTP 기록1,280MiB다. 이 값은 출력 한도 L의 선할당이나 실제 도달 성능을 뜻하지 않는다. Plan/Java/Python은 동일한 CAP-1 scope와 profile을 검사하며 CAP-0·legacy의 제한은 유지한다. W250·30+1,200초는 bootstrap 포함307,501 HTTP·307,500 event·30,750,000 WS message를 별도 합성 자료로 검증한다. 실제 부하의 정합성·복구·성능 판정과 구분한다.

분석 시간은 최소120초와 예상 WS delivery/25,000의 올림 중 큰 값으로 최초 profile에 포함한다. 분석 memory1GiB와 앱2GiB/안전heap1.5GiB, native k6의 기존 memory/Go 한도는 구분한다. 작업 공간 U에는 기존2GiB와 HTTP당4,096bytes, WS delivery당72bytes의 파일 보유·정렬 여유를 포함하고, build hard limit은 그 U에 기존5배 규칙을 적용한다. 각 실행의 현재 메모리·물리 여유와 해당 조합의 실제 검증을 별도로 요구한다.

MySQL Start는 기존 worker의 mysql3307 소유 역할, 등록 policy, 동일 session의 실제 발급/원래 만료, 활성 장부 예약, 현재 source binding과 profile을 모두 확인한 CAP-1에서 물리 U를 사용한다. 단독 큰 인자·잘못된 stage·닫힌/불일치 예약으로 이 경로에 들어갈 수 없다. 실제 DB/evidence 증가 hard guard는 L을 유지하며, F 물리 여유에는 DB/WAL/evidence/work의 남은 U와 다음 F5GiB+64MiB를 적용한다. 전체 cache·임시 peak와 C64MiB 다음 예약은 같은 controller 장부에서 함께 검증한다. PowerShell GiB 연산은 명시적 Int64를 사용한다. Java Plan은 DB baseline이 없으므로 DB U는 전량 보수 예약하고, live Environment는 확인한 증가분을 뺀다.

WS는 single FIFO/전송 중1배치·512건/524,288bytes·pending8,192건/4MiB·escape cache16,384건/16MiB를 유지한다. callback 항목을 FIFO에 그대로 보유하고 batch 경계/실패의 식별 요약만 따로 만들어 중복 객체 생성을 줄인다. 수신 시각·receiveOrdinal, 전수 payload와 연결별 집합, saved/id/count ACK, 최초 실패와 미확인 수량의 의미는 동일하다. 성능·메모리 지원은 현재 Windows 선택 시험 결과를 따른다.

과거 특정 offline 분석의 실행 소스는 원본 correspondence의 정확한 hash와 일치하는 보존 사본으로 검증할 수 있다. 현재 코드를 과거 실행 소스로 소급하지 않는다. 새 F 시리즈의 conditions에는 현재 source·볼륨 계약·분석 memory를 사용하며 과거 C 반복 PASS는 합산하지 않는다. 과거 실패의 데이터/복구 진단과 소유 마감, 현재 수정/선택 검증을 모두 대조한 뒤에만 새 부하를 시작한다.

새 F 배치의 현재 Windows 선택 검증은 W250 정상/연결 집중 burst를 재개 지원 범위로 동결할 수 있다. 이 경우 재개 gate는 원래 W250/W500 진단과 현재 source/runtime, W250 전수 정상·burst, W500/501 native memory-limit 원본·마감·장부를 모두 확인하며 250/s 초과 발급을 거부한다. 이는 과거 실패의 PASS 변경이나 서버 상한 판정이 아니다. 높은 후보의 지원 부족은 해당 원본을 보존하고 명령문의 낮은 후보 절차로 연결한다.


### CAP-1 후속 시각·native profile

`cap1-causal-memory-v2`는 schema4 CAP-1 전용이며 기존 schema3/profile의 의미와 과거 판정을 보존한다. 단일 FIFO·512건/524288bytes·pending8192건/4MiB·escape cache16384건/16MiB는 동일하다. Go soft heap512MiB/GOGC100과 별도로 native 개별1280MiB/합산1792MiB를 최초 준비에 동결한다. 실제 지원 발급은 현재 binary/source/runtime, 해당 범위의 W250 및 필요한 W500 정상·burst 전수 자료와 정상 마감이 연결된 경우에만 검증한 최대250 또는500/s까지 허용한다. 정상 fixture의 인과 지연 상단과 native 수신/callback 적체도 검사하며, 수량만 모두 처리한 결과로 지원 범위를 확장하지 않는다. 높은 조합 미검증·실패를 서버 상단이나 해소 불가능의 증거로 바꾸지 않는다. 각 기간의 보유·출력·현재 자원 준비는 별도이며 fixture는 CAP 성능 PASS가 아니다.

원래 callback epoch와 receiveOrdinal을 유지하고 단일 bridge 단조 시계에서 요청 claim/result와 이전 저장 ACK/현재 batch 수신을 연결한다. 실제 송신과 callback이 포함되는 구간으로 각 delivery 지연의 하한/상한을 구한다. 두25bit 밀리초 값은 메모리7bytes로 보유하고 `k6-ws-causal.bin`에는 연결별 canonical ordinal 순서의 big-endian int32 두 개로 저장한다. 같은 bridge의 System.nanoTime/QPC만 사용하며 서로 다른 JVM의 nanoTime이나 저장 시각을 callback 시각으로 쓰지 않는다. QPC 분해능 확인, 바깥 방향1ms guard와6시간 미만 범위를 검사한다. 종료/시계 이상/누락 근거를 유리한 오차로 대체하지 않는다. 전체/각 연결 nearest-rank 하한이 기준을 넘으면 성능 미달, 상한까지 기준 이하이면 PASS, 걸치면 TOOL_LIMIT다. 기존 epoch 오차는 DB/원시 진단에 남고 과거48ms를 재작성하지 않는다.

native 실행 중 개별·합산 감시는 bridge 응답 대기에도 계속하며 250ms 간격의 유한 `k6-native-memory.jsonl`을 남긴다. 큰 k6 metric 파일 내용 검사는 WS·HTTP native 종료 뒤에 수행한다. 실제 exit/stream/소유 join·Release와 필수 내용 검사는 별개이며 내용 실패는 전체 성공으로 바뀌지 않는다. analyzer는 새 시각 파일 길이/전수 집합/미사용 슬롯, native 관측 내용·한도·합산·실제 runner peak/phase, initial/recovery hash 연결을 검사한다. 자세한 검증 범위와 지원 판정은 최신 `작업기록/phase-18-cap-progress.md` 참조.


schema4의 요청별 송신 허가는 `/claim`이 사용자·토큰 준비 후 수행하는 최종 app/STOP/만료/기간 검사에 연결한다. 같은 요청의 추가 `/state` 왕복은 생략하되, k6는 허가 필드와 실제 송신 직전 만료·기간을 다시 검사한다. 이전 schema의 상태 조회는 유지한다. 허가 뒤의 이미 진행 중 요청과 후속 claim을 구분하며, 결과의 STOP과 WS ACK/상태를 통한 STOP 전파는 유지한다.

v2의 작업 공간 예상량은 delivery당216bytes로 causal 파일·분석 SQLite/index·임시 peak를 포함한다. 출력 L은 원래 U/L의5배 규칙을 적용하고 heap/VU/timeout에는 적용하지 않는다. 변경된 최대 보유량 검증은 실제307,501 HTTP·307,500 event·30,750,000 delivery 생성/소비와 같은 분석 코드의 시간·메모리·원시 참조를 묶어 검증한다. 이는 실제20분 서버 성능이나500/20분 지원을 뜻하지 않는다. 실패 fixture도 원래 만료 안에서 기존 Process의 종료/stream join을 관측하며, 조기 실패 판정과 소유 회수 완료를 분리한다.

### 2026-10-15 중단 정리와 한정 A/B 비교 연결

Phase18W500StoppedRecovery는 Plan b43d77df…dfdb6a3의 W500 중단만 별도 사본에서 판독한다. HTTP raw의 WS open 제어 행은 HTTP terminal과 구분하며, 45개 미확정 ID와 전체 Plan 할당·유효 WAL·보존 사용자·DB prefix/타일을 개별 대조한다. 기존 production runtime으로 새 JVM 복구를 수행하고 과거 HTTP 응답과 INVALID_OR_STOPPED 판정은 유지한다. MySQL descriptor는 현재 F cold-copy/정상 SQL 종료 증거 또는 보존된 이전 C 증거의 정확한 session 경로를 받는다. 임의 경로나 원본 datadir 참조는 허용하지 않는다.

cap-diagnostic-ab는 2026-10-15 중단 정리·전후 비교 명령문 해시에 고정된 별도 role이다. 기존 CAP-1 controller·Plan·producer·분석·MySQL·장부 경로를 사용하며, W105/120초·W105/1200초·W500/60초의 A/B 각 1회와 이유가 연결된 추가 한 쌍, 총 8회 이내로 제한한다. 실패 발급도 소비한다. 준비 policy는 해당 45건의 복구/원본 보존/소유 마감, 기존 이력, 공통 실행 도구, 원래 production 기준 A 또는 한 개 production 변경 B, 현재 저장 예약과 전체 시간 상한을 검증한다. 새 UNKNOWN/안전 중단/복구·소유 실패를 같은 예외로 넘기지 않는다.

비교 분석은 기존 통계·입력·전수 증거 기준으로 판정하되 diagnosticComparison=true, capacityPoint=false를 남긴다. 정식 cap-target의 120초 3회 후 20분 gate는 유지되며 진단 결과를 정식 후보 입력으로 소비하지 않는다. 이 연결의 실제 선택 검증·발급·완료 상태는 작업기록을 따른다.

Phase18WalScanProbe는 별도 F fixture의 세 가지 누적량/세 checkpoint에서 실제 production 파싱 횟수·thread 할당·실행 시간을 측정하고 유한 latch로 storage monitor/boundary 소유 관계를 확인한다. 보호 WAL이나 앱 처리량을 검증하는 도구는 아니다. 기존 k6 fixture의 선택 응답 지연 hook은 STOP 때 이미 발생한 효과와 미수신 HTTP 응답을 구분하는 진단에만 쓰며 기본 fixture 경로와 production STOP 동작은 유지한다.
