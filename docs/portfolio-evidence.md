# 포트폴리오 근거와 사용 범위

최초 작성 기준: 2026-10-23. 참고자료 처리표와 18-C1 후속 판단은 2026-10-27에 보완했다. 아래 기존 원시·코드 확인 기록은 최초 작성 당시 범위이며, 이번 보완의 확인 수준은 별도로 표시한다. 읽을 본문은 [portfolio.md](portfolio.md)다. 이번 작업은 현재 소스·assert·기존 실행 자료의 읽기와 문서 작성이며, 서비스·시험·측정·배포를 실행하지 않았다. 아래의 과거 PASS는 해당 실행 입력에 한정하며 현재 작업본의 재측정 결과가 아니다.

## 2026-11-10 순서 보장 설명 보완

[본문 사례 1](portfolio.md#사례-1--동시-쓰기의-순서와-수락을-같은-경계에-묶기)과 [요약본](portfolio-summary.md)에 group FIFO 게시 순서와 HTTP 최초 접수 순서의 차이를 명시하고, [면접 답변 노트](interview-notes.md)에 같은 주제의 설명을 추가했다. 이번 보완은 첨부 문서의 대조와 설계 범위 검토다. 소스·테스트를 새로 검증하거나 서비스·시험·성능 측정을 실행하지 않았다. 아래 과거 코드·원시 확인 기록을 이번 실행 결과로 갱신하지 않는다.

| 구분 | 내용 | 근거·확인 수준 | 사용할 수 있는 범위·한계 |
| --- | --- | --- | --- |
| 현재 동작의 문서 근거 | 일반 기본 group은 사용자별 gate 안의 readiness·쿨다운·입력 검사 뒤 유한 FIFO에 게시된 요청을 처리한다. HTTP 최초 접수 선착순은 보장 범위에 포함하지 않는다. | [details의 현재 write 실행 경계](pixel-place-details.md#현재-write-실행-경계) 대조. 구현·시험의 신규 확인 아님 | FIFO 게시 전의 요청 순서와 게시 후 처리 순서를 구분한다. QUEUED 상태의 timeout·interrupt 취소 규칙은 기존 문서 범위를 유지한다. |
| 현재 유지 판단 | 선착순 요구를 추가할 때 접수 순번, 사용자별 gate, 거절·취소·시간 초과, 대기열 한도·종료 처리를 함께 설계하고 정합성·성능을 검증한다. 현재 단계에서는 기존 수락·저장·복구 계약을 유지한다. | 2026-11-10 문서 보완에서 정리한 현재의 범위 판단 | 최초 구현 당시의 선택 이유나 선착순 방식 대비 성능 우위의 증거가 아니다. 비교 구현·새 성능 측정은 하지 않았다. |

## 2026-10-23 작성 당시 기준선과 커밋 분리

원본 `C:/Users/cgt/Desktop/Java/pixel-place`는 Git worktree이며 시작 branch는 `main`, HEAD는 `336d9c55132c7b4a1fa8561c4c39c51bc74ad62e`다. 추가 worktree `C:/Users/cgt/.codex/worktrees/1007/pixel-place`는 detached `1609a47`이며 이번 작업에서 사용하지 않는다. 시작 staged 변경은 없었다.

시작 전 unstaged 변경은 `.gitignore`(+4줄)와 README(+54/-6줄)였다. `.gitignore`는 이번 범위 밖의 사용자 변경으로 보존한다. README의 기존 변경은 19-6 목적·실행 준비·정상 종료·시연 안내로, 이번 문서 작업과 관련되지만 이번 작성분은 아니다. `작업기록/phase-19-progress.md`는 기존 ignore 정책으로 status에 나타나지 않으므로 직접 읽고 기존 byte prefix를 보존하여 뒤에만 기록한다. 기존 portfolio 두 문서는 없었다.

이번 변경 범위는 portfolio 두 문서 신규 작성, README의 짧은 진입 링크, phase-19 기록의 말미 추가다. source/test/config/DDL/scripts/specs/details/로드맵은 바꾸지 않는다. 사용자 수행문을 실행 계획으로 사용했으며 별도 계획·범용 감사 도구·ZIP을 만들지 않는다. 이번 기준선 증거는 `F:/pixel-place-work/evidence/portfolio-20261023-01/`의 `baseline.json`, `git-status-before.txt`, `pre-existing.diff`다.

2026-10-23에는 아래 Git 후보만 제시했고 staging·commit·push는 하지 않아 완료본 커밋 보존이 미완료였다. 2026-10-27 보완에서는 승인된 두 문서 범위를 선별해 커밋한다. 실제 커밋 결과는 Git 이력과 [phase-19 진행 기록](../작업기록/phase-19-progress.md)의 후속 항목에서 확인한다.

| 후보 | 포함 범위 | 메시지 제안·주의 |
| --- | --- | --- |
| 기존 데모 마감 | 이번 시작 전 README diff의 19-6 부분 | `[19-6단계] docs: 최소 데모 시연과 정상 종료 안내 마감` — 현재 README 전체가 아니라 기존 hunk만 해당 |
| 이번 포트폴리오 | `docs/portfolio.md`, `docs/portfolio-evidence.md`, 이번 README 링크 hunk | `[19단계] docs: 포트폴리오 근거와 후속 판단 정리` |
| 진행 기록 보존 | ignored `작업기록/phase-19-progress.md` 말미의 이번 기록 | 기존 정책 아래 파일로 보존. 새 추적·강제 추가는 하지 않았고 커밋 후보의 기본 포함으로 가정하지 않음 |
| 기존 사용자 변경 | `.gitignore` 4줄 | 이번 두 후보에서 제외. ignore 변경의 단계·의도를 추측해 메시지를 정하지 않음 |

기존 README와 이번 안내가 한 파일에 섞이므로 파일 전체를 이번 작업만의 커밋으로 제안하지 않는다. 이미 커밋된 frontend 구현과 현재 HEAD의 문서 이력도 미커밋 작업으로 다시 나열하지 않는다.

## 확인 수준과 판정 기준

| 표기 | 의미 |
| --- | --- |
| 구현 확인 | 현재 코드·설정·test assert를 직접 읽음. 테스트 실행 성공과 구분 |
| 과거 원시 확인 | 기존 HTTP/WS 관측, 요청 행, trace, XML 또는 캡처를 읽음. 당시 조건에 한정 |
| 과거 분석 확인 | 기존 분석·진단 JSON의 결과를 직접 읽음. 그 분석이 읽은 DB/WAL 전체를 이번에 재검증했다는 뜻은 아님 |
| 기록만 확인 | 진행 기록의 보고를 확인했으나 해당 실행 원시의 재검사는 하지 않음 |
| 미확인 | 필요한 실행·자료 대응이 이번 범위에 없음. 본문 주장 제외 또는 제한 |

실행 명령문은 요구사항 근거다. Graphify는 코드 위치를 좁힌 탐색 자료이며 실행 성공 증거가 아니다. 최신 graph를 재생성하지 않았다. 일반 구조 탐색은 작은 query로 시작했고, 실제 판정에는 소스를 사용했다. wiki index는 현재 존재하지 않았다.

후속 정책은 [PROJECT_GOAL](../명령문/PROJECT_GOAL.md), [19단계 계획](../명령문/019_데모우선_구현계획_20261018.md), [데모 우선 점검](../명령문/pixel-place_데모우선_현황점검과최소완성계획_20261016.md)을 따른다. 앞선 프런트 미구현·인증 예정 설명은 당시 이력이다. 현재 인증·프런트는 specs 17·18절과 details 14·15절을 따른다. C4 single 유지/제거는 C5의 명시적 후속 결정으로 교정됐으며 나머지 내구성·검증 계약은 유지된다.

## 2026-10-27 참고자료 처리

원본 폴더는 `C:/Users/cgt/Desktop/Java/포폴 데이터/`다. 아래 6개를 이번 보완에서 읽었으며, 최초 작성일인 2026-10-23에 이미 읽었다는 뜻이 아니다. 원본은 변경하지 않았다. 본문·표의 내용 확인이며, HTML/DOCX의 화면·인쇄 레이아웃 검증이나 각 보고서가 인용한 원시 전량 재검증은 수행하지 않았다.

| 실제 파일명 | 이번 확인 여부 | 본문 반영 위치 또는 제외 이유 |
| --- | --- | --- |
| `pixel-place_portfolio_wal-group-commit_tradeoffs_20260923.md` | MD 원문 확인 | 사례 3과 C4 비교표에 대응. 당시 single 유지 판단은 이력으로만 읽고, 현재 C5 group 채택·목표 미달 구분을 유지한다. |
| `pixel-place_프로젝트소개_목차와핵심사례_20260927.md` | MD 원문 확인 | 쓰기 정합성·DB commit 불확실성·group 채택의 세 주제가 사례 1·2·3과 대응한다. 구성안의 전체 목차를 본문에 추가하지 않는다. |
| `pixel-place_phase15A_portfolio_20260918.md` | MD 원문 확인 | 21회 single 기준선의 선행 자료. 사례 3은 C4 직접 비교를 사용하므로 15-A 전체 수치는 추가하지 않는다. |
| `pixel-place_phase15A_portfolio_20260918.html` | HTML 본문·표·내장 측정 데이터 확인 | 같은 15-A 실험의 다른 표현이다. MD와 별도 실험으로 세지 않고 목표선 그래프를 본문에 중복 추가하지 않는다. |
| `pixel-place_phase18_group-commit_impact-analysis_handoff_20260924.md` | MD 원문 확인 | 당시 영향 분리 계획과 이후 18-C1의 무변경 마감을 구분해 사례 3 후속 단락에 연결한다. 계획의 모든 항목을 수행 완료로 간주하지 않는다. |
| `pixel-place_CAP1_진행보고서_20261017.docx` | DOCX 본문·표 텍스트 확인 | 아래 CAP 표의 과거 45건과 새 미확정 3건 구분을 확인했다. 상세 진단은 새 사례로 추가하지 않고, 당시 데모 계획은 최신 19-6 완료 기록과 구분한다. |

읽기 실패는 없었다. 과거 성능 수치는 해당 실행의 이력으로 유지하며 현재 성능 근거로 바꾸지 않는다.

<a id="demo-evidence"></a>
## 시연과 현재 완료 범위

아래 F 경로는 **이 Windows 로컬 환경에서만 열리는 외부 증거**다. 저장소나 공개 웹에 포함된 것으로 가정하지 않는다. 시연 HTML은 기존 파일을 그대로 사용한다.

- D6: [19-6 시연 HTML](F:/pixel-place-work/evidence/phase19-6-final-20261021-160000/demo.html), 공통 폴더 `F:/pixel-place-work/evidence/phase19-6-final-20261021-160000/`.
- D5: 공통 폴더 `F:/pixel-place-work/evidence/phase19-5-real-resume-20261021-144131/`의 [실제 재연결 관측](F:/pixel-place-work/evidence/phase19-5-real-resume-20261021-144131/browser-real.json).
- FT: [19-5 프런트 테스트 로그](F:/pixel-place-work/evidence/phase19-5-20261021/frontend-tests.log).

| 사례 | 사용할 주장 | 근거 파일·구간 | 확인 수준 | 당시 조건·입력 | 사용할 수 있는 범위·한계 | 추가 확인 |
| --- | --- | --- | --- | --- | --- | --- |
| 데모 로그인 | 로그인 뒤 실제 배치 가능 | D6 `browser-real.json`: authExchange, before.a, acceptedHttp; 진행 기록 19-6 | 과거 원시 관측 확인. authExchange는 별도 network request119의 status를 옮긴 기록 | 2026-10-21 23:41 KST, Windows Edge155, localhost, 별도 A/B context | 실제 인증 입력·교환 본문을 보관/검사한 것이 아님. 새 로그인 미실행 | 추가 측정 불필요 |
| 데모 배치 | POST 2건 중 수락1·429거절1 | D6 `acceptedHttp`, `cooldownHttp`, events, after429 | 과거 원시 확인 | 수락 (4100,4096)/color4/seq4/version4; 뒤 명시 요청 color5 | 쿨다운 179659ms는 해당 응답의 값. 전체 처리량 표본 아님 | 불필요 |
| B 수신·표시 | 대응 frame과 Canvas 남색, 정상 이벤트 추가 GET0 | D6 before/afterAccepted/events; `05-b-realtime.png` | 원시 HTTP/WS·표본·PNG 직접 확인 | 초기 조회 완료 뒤 before.eventStart63, B GET16부터 관측 | WS가 HTTP 응답보다 먼저 관측. DB 저장·모든 연결 전달 증명 아님 | 불필요 |
| 새로고침 | 새 GET version4/color4와 최종 Canvas·로그인 만료 유지 | D6 firstRefresh/after429.tile/settledRefresh; `09-a-refresh-verified.png` | 과거 원시·PNG 확인 | 첫 캡처는 조회 중; 429 뒤 추가 쓰기 없는 보완 reload | 06/earlyZoomSample을 최종 표시 증거로 쓰지 않음. 새로고침 POST0 | 불필요 |
| 재연결 | 놓친 변경을 첫 자동 연결 뒤 GET으로 복구 | D5 disconnect/secondAfter/recovery, b.gets/tileResponses/frames; `second-recovered-b.png` | 과거 원시·PNG 확인 | B만 짧게 차단, A 두 수락 사이 정상 쿨다운 경과; seq3/color2 | 19-5 재사용. fallback GET은 두번째 쓰기 전, 새 GET은 version3/color2, 두번째 frame 없음. 장기 장애/5회 소진 실행 아님 | 불필요 |
| 데모 보존·종료 | 당시 events/checkpoint3→4, 정상 종료 기록 | D6 data-before.log/data-after.log, api-stop.json, mysql-stop.json, shutdown.json | 과거 원시 로그·저장 관측 확인 | WAL354→472bytes, API/프런트 Ctrl+C shell1, MySQL exit0 | 현재 DB 상태 재조회 아님. shell1을 앱 exit0으로 바꾸지 않음 | 불필요 |
| 프런트 회귀 재사용 | 당시 236 PASS, 현재 해당 입력 유지 | FT 끝의 tests/pass/fail/COMMAND_EXIT_CODE; D5 reuse-check.json | 원시 로그 확인 + 현재 hash 대조 | reuse의 frontend16개 MD5 불일치0, CSS SHA256 일치 | 과거 2026-10-21 실행. 이번 새 test/CAP/Java 회귀가 아님 | 범위가 바뀌면 영향 검증 |

현재 구현 근거는 [tile-merge](../frontend/tile-merge.mjs), [tile-loader](../frontend/tile-loader.mjs), [realtime](../frontend/realtime.mjs), [placement](../frontend/placement.mjs), [auth-session](../frontend/auth-session.mjs), [app](../frontend/app.js)다. [tile-merge.test.mjs](../frontend/tests/tile-merge.test.mjs) 15·29·43·55행의 assert는 픽셀별 역순/중복, HTTP/WS 역전, 늦은 snapshot 재병합을 확인한다. [placement.test.mjs](../frontend/tests/placement.test.mjs) 78행 이후는 POST 중 연결 세대가 바뀌어도 수락/180초를 유지하고 해당 타일의 새 GET을 요구한다. [realtime.test.mjs](../frontend/tests/realtime.test.mjs) 115행 이후는 제한된 재시도·소진 뒤 타이머/소켓 증가 없음을 확인한다. 현재 assert를 읽었으며 실제 BFCache 수용·모바일·다른 브라우저 확대는 미확인이다.

## 사례 1·2의 근거 대응

아래 source/test 경로는 저장소 기준이다. 라인 번호는 이번 확인 시점의 탐색 위치이며 함수·test 이름과 함께 대조한다.

| 사례 | 사용할 주장 | 근거 파일·구간 | 확인 수준 | 당시 조건·입력 | 사용할 수 있는 범위·한계 | 추가 확인 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 사용자 gate 안 cooldown 확인·write·저장, 밖에서 broadcast | [PixelCommandService](../src/main/java/dev/cgt/pixelplace/pixel/application/PixelCommandService.java) 57–93; [PixelCommandConcurrencyTest](../src/test/java/dev/cgt/pixelplace/pixel/application/PixelCommandConcurrencyTest.java) 39–92 | 구현·assert 확인 | 같은 사용자 latch 경쟁, core/dirty 호출1, 둘째 cooldown 거절. 저장 실패 때 두 쓰기 허용도 검사 | 한 JVM의 gate. Redis 후처리 실패까지 cooldown 절대 보장 아님 | 분산 writer 주장은 제외 |
| 1 | WAL→memory→dirty 경계와 실패 후 수락 금지 | [PixelWriteService](../src/main/java/dev/cgt/pixelplace/pixel/application/PixelWriteService.java) 60–179; [SinglePixelWriteExecutor](../src/main/java/dev/cgt/pixelplace/pixel/application/SinglePixelWriteExecutor.java) 38–55; [PixelWriteServiceTest](../src/test/java/dev/cgt/pixelplace/pixel/application/PixelWriteServiceTest.java) 97–146,192–260 | 구현·assert 확인 | WAL 실패 memory0·not-ready, memory 실패 WAL1 보존·대기 요청 차단 | dirty 실패에도 기존 durable 효과 가능. HTTP 미수신≠효과 없음 | 자동 재시도/무손실 주장 제외 |
| 1 | 동시성·실패 경계의 당시 성공 | M15 XML 세 파일(아래 위치) | 과거 원시 XML 확인 | 2026-09-16 concurrency7/core13/Redis1, f/e/s0 | 현재 전체 입력 동일성 미대조. 현재 Redis test의 양 mode 매개변수화와 당시 단일 사례 구분 | 현재 회귀라고 쓰지 않음 |
| 1 | WAL 준비·재생·seed 뒤 READY | [StartupRecoveryService](../src/main/java/dev/cgt/pixelplace/recovery/application/StartupRecoveryService.java) 64–97,151–187; [SegmentedWalStorage](../src/main/java/dev/cgt/pixelplace/wal/infra/SegmentedWalStorage.java) 100–125; [SegmentedWalPreparationTest](../src/test/java/dev/cgt/pixelplace/wal/infra/SegmentedWalPreparationTest.java) 83–119 | 구현·assert 확인 | R/S 실패 지점별 board/seq 호출0, ready false; 정상 InOrder | Windows 지원 경계의 동기화. 전원 차단 전체 보장 아님 | OS crash 성과 제외 |
| 1 | group 미flush WAL의 제한된 복구 실행 | R17 trace.json order143–212,498,562; consistency/preparation/drain/runner-result | 과거 원시 확인, 보존 artifacts와 5개 hash/bytes 일치 | 2026-09-29 group-unflushed; 두 R→S→loadAll→replay2→seed5→READY→COMMITTED→NO_OP | 해당 한 실행. 모든 mode/action·현재 실행으로 확대하지 않음 | 불필요 |
| 2 | transaction lifecycle에 따른 세 outcome | [ProgrammaticFlushTransactionExecutor](../src/main/java/dev/cgt/pixelplace/flush/infra/ProgrammaticFlushTransactionExecutor.java) 62–106; [동일 이름 Test](../src/test/java/dev/cgt/pixelplace/flush/infra/ProgrammaticFlushTransactionExecutorTest.java) 63–197 | 구현·assert 확인 | commit 시도 예외는 ambiguous, 이후 rollback 호출 금지 | 예외 메시지로 결과 추정하지 않음. 현재 unit 실행 아님 | 불필요 |
| 2 | 한 transaction·pending 보존·exact 판정 | [FlushPersistenceService](../src/main/java/dev/cgt/pixelplace/flush/application/FlushPersistenceService.java) 45–91; [FlushWorker](../src/main/java/dev/cgt/pixelplace/flush/application/FlushWorker.java) 86–220; [FlushReconciliationService](../src/main/java/dev/cgt/pixelplace/flush/application/FlushReconciliationService.java) 26–57 | 구현 확인 | events→tiles→checkpoint, pending 먼저 판정, restore 성공 뒤 clear | checkpoint≥target로 성공 처리 안 함. unresolved pending 유지 | 불필요 |
| 2 | 경계 밖 checkpoint 거부·새 dirty 보존 | [FlushReconciliationServiceTest](../src/test/java/dev/cgt/pixelplace/flush/application/FlushReconciliationServiceTest.java) 23–118; [FlushWorkerTest](../src/test/java/dev/cgt/pixelplace/flush/application/FlushWorkerTest.java) 812–955 | assert 확인 | target 초과/중간/이전·state mismatch unresolved, 새 dirty seq12/version4 보존 | 테스트 정의 확인, 이번 test 통과 주장이 아님 | 불필요 |
| 2 | 실제 MySQL commit 뒤 응답 유실 주입·중복 재실행 방지 | [FlushPersistenceMySqlIntegrationTest](../src/test/java/dev/cgt/pixelplace/flush/infra/FlushPersistenceMySqlIntegrationTest.java) 992–1040; M17 XML 해당 testcase | 구현·과거 XML 확인 | 실제 executor commit 후 wrapper ambiguous; DB events 유지·pending 보존, 다음 판정 후 transaction count1 | 실제 MySQL + 합성 유실. 네트워크 장애 재현 아님 | 실제 장애 주장은 제외 |
| 2 | probe 안정 관측, rollback 복원, fresh runtime 복구 | 같은 Test 629–684,689–830,1184–1238; M17 XML | 구현·과거 XML 확인 | lock holder 대기, body 실패 rollback, A→B→C→D로 DB/WAL만 승계·event 각1 | probe의 DB 잠금 대기는 통합 assert로 확인. 이 시험이 coordinator 획득까지 검증하지는 않음. fresh graph는 같은 JVM | 새 JVM 시험으로 표현 금지 |

write 실패와 pending 소유권 불명의 상태 차이는 [ServiceReadiness](../src/main/java/dev/cgt/pixelplace/recovery/application/ServiceReadiness.java)의 `markWriteFailed`/`requireNotFatal`과 FlushWorker의 `runCycle`에서 확인한다. `WRITE_FAILED`는 새 write/capture를 차단하지만 정상 기존 pending의 exact reconciliation을 허용하고, `FATAL_NOT_READY`는 그것도 차단한다. reconciliation의 coordinator 비점유는 FlushWorker→FlushReconciliationService→[JpaFlushDbStateProbe](../src/main/java/dev/cgt/pixelplace/flush/infra/JpaFlushDbStateProbe.java)의 현재 호출 경로를 읽은 **정적 확인**으로 한정한다.

M15 공통 경로: `F:/pixel-place-archive/pixel-place/build/agent-runs/phase15-pre-20260916/`. 직접 읽은 성공 XML은 다음 세 파일이다.

- `full-regression-final/test/TEST-dev.cgt.pixelplace.pixel.application.PixelCommandConcurrencyTest.xml`
- `full-regression-final/test/TEST-dev.cgt.pixelplace.pixel.application.PixelWriteServiceTest.xml`
- `full-regression-final/redisIntegrationTest/TEST-dev.cgt.pixelplace.pixel.application.PixelCommandRedisIntegrationTest.xml`

R17 공통 경로: `E:/pixel-place-phase15/phase17-C-20260929-012416-c1/app/c2-group-unflushed-r1-20260929-144237/`. 필요한 파일은 `trace.json`의 위 order 구간과 `consistency.json`, `preparation.json`, `drain.json`, `runner-result.json`이다. 원래 C 인덱스는 `F:/pixel-place-archive/pixel-place/build/agent-runs/phase17-C-20260929-012416-c1/c2-20260929-144237/`로 연결되고, `c2-group-unflushed-r1-20260929-144237-artifacts.json`과 원시 hash를 대조했다.

M17 공통 경로: `E:/pixel-place-phase15/phase17-C-20260929-012416-c1/runs/c4-mysql-r1-20260929-172243/`. `xml/TEST-dev.cgt.pixelplace.flush.infra.FlushPersistenceMySqlIntegrationTest.xml`의 suite header/testcase와 85–87행 MySQL 식별을 읽었다. 2026-09-29T08:45:38.787Z, 해당 suite40/failure0/error0/skip0이며 MySQL8.4.11이다. `result.json`, `command.json`, `inputs-before.json`의 identity를 원래 artifacts와 대조했다. 전체 MySQL task45라는 집계는 별도 verification 보고 수준이며 직접 XML 확인한 suite40과 구분한다.

M17에서 당시 입력과 현재 hash가 같은 관련 파일은 FlushPersistenceService, FlushReconciliationService, FlushWorker, PendingAmbiguousFlushStore, JpaFlushDbStateProbe, ProgrammaticFlushTransactionExecutor, StartupRecoveryService와 해당 MySQL 통합 Test의 8개다. **전체 classpath/config/runtime 동일성을 검증하지 않았다.** 원래 C 인덱스의 현재 위치는 `F:/pixel-place-archive/pixel-place/build/agent-runs/phase17-C-20260929-012416-c1/c4-20260929-172243/`이며 verification/artifacts를 대조했다.

DB 요구사항은 [11-B](../명령문/011-B_상세명령문.md)의 pending·exact reconciliation과 [11-C-D](../명령문/011-C-D_MySQL_Restart_Runtime_통합검증.md) §24의 fresh graph 계약에서 확인했다. 이 명령문의 존재를 실행 성공 근거로 계산하지 않는다. 후속 C5와 현재 details의 실패·복구 계약도 같은 경계를 유지한다.

<a id="group-comparison"></a>
## 사례 3: C4 비교 → C5 채택 → 현재 설정

C4/C5 공통 외부 경로 G15: `E:/pixel-place-phase15/phase15-a-20260918/agent-runs/`. C4 분석은 `phase15-C-4-20260923-analysis-r3/comparison.md` 및 `report.json`이다. C5 결정은 `phase15-C-5-20260924-110905405-closing-r1/index.json`, `final-reuse-check.json`, `phase18-handoff.md`와 `phase15-C-5-20260924-110905405-reuse-r1/raw-recalculation.json`에서 확인했다.

당시 입력은 `c4r220260923`, 100 WS, 계획 쓰기105/s, 예열30초+측정120초, same/spread 각3회, maxInFlight128, timeout30초, drain 상한60초다. 환경은 Windows10/NTFS·JDK21.0.10/G1, server heap2GiB/generator512MiB, 같은 PC CPU·메모리·디스크 공유, C source/E DB·WAL·build·로그다. 현재 F 환경의 결과가 아니다.

아래 spread 원시 6회의 `client-results.jsonl` measurement 행을 읽어 결과를 재계산했다. **기존 파일 분석이며 새 부하 실행이 아니다.**

| 반복·mode | G15 아래 원시 폴더 | planned | sent=성공 송신 cohort 수락 | NOT_SENT | 성공 RTT p95 ms | p99 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| r1 single | phase15-C-4-r20260922190157205_8cbf57 | 12600 | 12599 | 1 | 492.0067 | 801.9431 |
| r1 group | phase15-C-4-r20260922190532970_96045a | 12600 | 12600 | 0 | 156.1474 | 221.7333 |
| r2 single | phase15-C-4-r20260922192338622_bb049d | 12600 | 12600 | 0 | 562.4612 | 871.6697 |
| r2 group | phase15-C-4-r20260922191621670_7dd08f | 12600 | 12599 | 1 | 180.3960 | 284.4722 |
| r3 single | phase15-C-4-r20260922193434860_1a9b11 | 12600 | 12599 | 1 | 555.8881 | 839.2156 |
| r3 group | phase15-C-4-r20260922193814335_c41b6d | 12600 | 12599 | 1 | 172.1860 | 253.4981 |

RTT는 `phase=measurement, outcome=accepted` 행의 `(completedNanos-sentNanos)/1e6` ms이며, 정렬 후 nearest-rank `ceil(n×p)-1` 위치로 p95/p99를 구해 기존 report와 일치했다. 해당 6회의 measurement outcome은 accepted/client_not_sent였고 오류·정책 거부0을 별도로 확인했다. NOT_SENT를 성공에 포함하지 않는다. 개선율은 계산·인용하지 않는다.

처리량 accepted/s는 **측정 완료창**, RTT는 **측정 송신 cohort**라 분모가 다르다. 예를 들어 r1 group은 cohort12600 중3건이 drain에서 완료되어 완료창 accepted/s는104.975다. 계획105/s와 실송신을 혼동하지 않는다. 목표는 accepted≥100/s, 성공 RTT p95≤100ms/p99≤250ms, 시스템 오류율≤0.001, 정합성 불일치0이다.

| 사례 | 사용할 주장 | 근거 파일·구간 | 확인 수준 | 당시 조건·입력 | 사용할 수 있는 범위·한계 | 추가 확인 |
| --- | --- | --- | --- | --- | --- | --- |
| 3 | spread 세 쌍에서 group p95/p99 감소 | 위6개 client-results의 measurement 행과 C4 report | 과거 요청 원시 재계산 | 같은 반복끼리 비교, 오류/거부/미송신 구분 | 현재 N배 성능·최대 인원 주장 불가 | 현재 성능이면 별도 실행 |
| 3 | 양 mode 목표3/6, same 통과·spread 미달 | C4 report/comparison, C5 final-reuse-check/handoff | 과거 분석 확인 | same/spread 각3회 | same6회 HTTP 원시는 이번 개별 재계산 안 함. spread p95 모두100ms 초과 | 본문 제한으로 충분 |
| 3 | 당시 정합성·소진·새 JVM 복구 완료 | 위6개 폴더의 consistency.json/recovery-check.json | 과거 검증 산출물 확인 | complete, mismatch0, newJvmPid, schemaSeedUsersReexecuted=false | DB/WAL 전체 bytes의 이번 재검증 아님 | 불필요 |
| 3 | C5 작업 완료·일반 default group 채택, 성능 미달 유지 | C5 closing index의 work/performance/adoption 및 final-reuse-check; phase-15 최상단 | 과거 분석·기록 확인 | C4 성능 R1 재사용. C5 회귀·짧은 smoke는 별도 | smoke 원시는 이번 재검사 안 함. 신규 대표 측정 근거 아님 | 불필요 |
| 3 | 현재 기본 group·명시 single·stub single | [application.yml](../src/main/resources/application.yml)49–50, [stub 설정](../src/main/resources/application-stub.yml), [WriteExecutionConfiguration](../src/main/java/dev/cgt/pixelplace/pixel/application/WriteExecutionConfiguration.java)17–28, [설정 Test](../src/test/java/dev/cgt/pixelplace/pixel/application/WriteExecutionConfigurationTest.java)36–76 | 현재 구현·assert 확인 | Config Data·환경변수 우선순위, executor1개, stub group 거부 | 지금 앱을 기동해 mode를 관측한 것이 아님 | 불필요 |
| 3 | WAL 완료 전 적용 금지·유한 큐·종료 경계 | [GroupPixelWriteExecutor](../src/main/java/dev/cgt/pixelplace/pixel/application/GroupPixelWriteExecutor.java)136–168, [Test](../src/test/java/dev/cgt/pixelplace/pixel/application/GroupPixelWriteExecutorTest.java)55–64,140–150 | 현재 구현·assert 확인 | WAL 대기 중 memory/dirty0, FIFO·batch, grace 초과 UNKNOWN와 늦은 반환 차단 | native I/O 영구 정지까지 시간 보장하지 않음 | 새 성능 근거 아님 |
| 3 | heap·일부 DB committed·디스크 비용 증가 | C4 comparison/report, C5 phase18-handoff | 과거 분석 확인 | 동일 PC 공유 자원 | resource/force 원시 전량 재계산 안 함. 원인 기여율 미확정 | 인과 주장 제외 |

<a id="c1-follow-up"></a>
### 18-C1 후속 판단의 근거

사례 3의 후속 단락은 [phase-18 진행 기록](../작업기록/phase-18-progress.md)의 「현재 인계 — 18-C-1 원인 분리·C 무변경 마감 (2026-10-02)」을 2026-10-27에 직접 읽어 연결했다. 기록은 기존 B 대표 자료의 DB 작업량·시간 정규화 및 216개 5초창 대조, 타일 작업량 기여 정황과 HTTP 지배 원인·특정 개선 효과의 미확정, 채택할 개선 근거가 없어 production 무변경으로 마감한 결론을 구분한다.

확인 수준은 **진행 기록만 확인**이다. 당시 `build/agent-runs/phase18-c1-20261002-01/`의 `analysis.json`·`review-inputs/` 등 분석 산출물·원시는 이번에 직접 재확인하지 않았다. 새 부하나 과거 원시 전량 재분석 없이 당시 판단을 설명하는 범위이며, 병목 해결·C4 목표 달성·현재 성능 검증의 근거로 사용하지 않는다.

## CAP의 서로 다른 미확정 집단

| 사례 | 사용할 주장 | 근거 파일·구간 | 확인 수준 | 당시 조건·입력 | 사용할 수 있는 범위·한계 | 추가 확인 |
| --- | --- | --- | --- | --- | --- | --- |
| 공통 한계·과거45 | 후속 진단에서 효과·새 JVM 복구 후 정합성 확인, 원래 HTTP는 미확정 | [recovery-diagnostic.json](F:/pixel-place-work/evidence/cap1-ws-diagnostic-20261015-02/recovery-diagnostic.json): requests45, consistency, originalClassification; phase-18-cap 기록1000–1012 | 과거 진단 산출물 확인 | 원래 phase18-cap1-w500-20261015-084327; 45행 effect identified_effect_exists/databaseAfter true, historicalHttpResponse UNRESOLVED | 원래 INVALID_OR_STOPPED 유지. 이번 전체 DB/WAL 재검증 아님 | 원래 응답 성공 소급 금지 |
| 공통 한계·새3 | 개별 효과·정합성·새 JVM 복구 미완료 | [new-w500-stop-review.json](F:/pixel-place-work/evidence/cap1-ab-result-20261016-02/new-w500-stop-review.json); phase-18-cap1035–1039 | 과거 읽기 진단·기록 확인 | phase18-cap1-diag-a-w500-20261016-030056, measurement/8627~8629; k6_memory_limit STOP | individualServerEffects NOT_ESTABLISHED_BY_THIS_READ_ONLY_REVIEW, recoveryExecuted=false. NOT_OBSERVED≠미송신/유실 | 효과 매칭과 복구 확인 별도. 해결·실제 유실 단정 금지 |

W1·CAP 전체 확정, D-2 전체 회귀는 위 자료로 대체되지 않는다. 이 한계를 본문에서 숨기지 않으며, 범위 밖 데이터 문제를 문서 작업 중 임의 수정하거나 시험으로 확대하지 않는다.

## 과거 경로와 원래 identity

[phase18-tools의 archive 정책](phase18-tools.md), [pixel_place_paths.py](../scripts/pixel_place_paths.py), [storage 설정](../scripts/pixel-place-storage.json), `F:/pixel-place-archive/pixel-place/relocations.json`을 대조했다. 원래 C의 닫힌 agent-runs는 helper가 catalog·manifest·선택 파일 hash를 검사한 F archive로 읽었다. 새 실행 소유 root로 사용하지 않았다.

위 C4/C5와 17단계 R17/M17 원시 **E 파일들은 현재도 원래 E 경로에 존재**하며 해당 E→F relocation 항목이 없었다. 따라서 이동을 추정하거나 원래 Plan/index/hash의 경로를 문자열 치환하지 않았다. DB 이동과 과거 측정 파일 위치는 구분한다. 새 출력은 C 문서와 이번 F 증거 폴더뿐이다. 대용량 DB/WAL 복사·archive 전체 탐색·ZIP 읽기는 하지 않았다.

<a id="next-evidence"></a>
## 추가 확인과 새 측정 필요성

| 구분 | 질문·부족분 | 이번 처리 | 다음에 필요한 것 |
| --- | --- | --- | --- |
| 1. 기존 근거로 작성 가능 | 데모 핵심 흐름, 수락/저장/표시 경계, exact reconciliation, C4→C5 결정 | 본문에 작성 | 새 측정 불필요 |
| 2. 기존 파일·설명 확인 | 과거 suite 전체나 C5 smoke까지 독립 재검증하려는가 | 이번 직접 확인 범위를 명시 | 해당 run XML·input·smoke 결과만 추가 대조. 현재 본문의 필수 조건 아님 |
| 2와 3 분리 | 새 미확정3건에 어떤 서버 효과가 있었는가 | 해결/유실 주장 제외 | 먼저 보존 요청/fixture와 WAL·DB의 개별 효과 매칭. 새 JVM 복구가 필요한 부분은 별도 실행 승인·보존 조건에 따라 처리. 새 성능 측정과 다른 과제 |
| 3. 새 실행 필요 | 현재 데모 입력에서 어느 대표 부하를 수용하는가 | 현재 최대인원/성능 수치 제외 | 아래 후보의 조건을 먼저 확정한 신규 관측 |
| 3. 새 실행 필요 | 실제 DB 네트워크·프로세스 장애도 같은 판정으로 복구되는가 | 합성 응답 유실 범위로 제한 | 구체적 장애 지점·효과 판정·보존 계획 필요. 이번 사례 작성에는 불필요 |
| 3. 새 실행 필요 | force 공유가 지연 개선의 지배 원인인가 | 동반 관측까지만 설명 | 동일 조건에서 queue/scan/DB/장치 경쟁 기여를 분리. 현재 인과 성과 주장 제외 |

현재 본문을 위해 다음 4번의 새 측정을 수행할 필요는 없다. 만약 **현재 사용자 흐름의 대표 부하에서 HTTP·WS·DB 지연을 함께 만족하는지**를 새 성과로 넣으려면 다음 초안을 사용할 수 있다. 준비·발급·실행은 하지 않았다.

- 대표 시나리오 후보는 평상시 분산 배치와 특정 영역 집중 배치의 1~2개다. 관전자 WS fan-out, 보이는 타일 GET, 실제 180초 사용자 쿨다운과 필요한 사용자 수를 함께 정의한다.
- 사용자가 정할 값은 목표 쓰기율·관전자 수·조회 빈도·예열/측정 시간·반복 수·HTTP/WS/DB 합격 기준·저장/메모리 중단선과 실행 예산이다. C4의 105/s·100WS·30+120초·p95 100ms/p99 250ms는 **역사적 참고값**이며 새 합격 기준으로 자동 승계하지 않는다.
- 비교가 목적이면 source/runtime·JDK/heap·DB/WAL 초기 상태·장치 배치·사용자/픽셀 분포·측정 구간을 고정하고 같은 조건의 전후 또는 single/group 쌍을 비교한다. 실송신/수락/거절/미확정·측정 완료창 처리량·송신 cohort RTT를 따로 집계한다.
- 지표는 HTTP p95/p99, WS 전달/표시의 정의된 범위, DB checkpoint 지연·backlog, drain·정합성·새 JVM 복구, 메모리·저장 사용량이다. 합격은 사전 정의된 입력 유효성·성능·정합성·소진/복구를 함께 충족할 때만 판정한다.
- 새 UNKNOWN·효과 미확정, 정합성 불일치, 관측 상실, 소유권·저장/메모리 안전선 위반, 정상 소진/종료 실패 시 중단하고 원래 실패를 보존한다. 기존 새3건과 측정 도구 메모리 한계의 미해결 상태를 무시한 재실행은 후보에 포함하지 않는다.
- 개선과 도구 보완을 포함한 최대3회 제한은 향후 상한이다. 3회 의무 실행이나 이번 예산이 아니며 충분한 답을 얻으면 더 일찍 종료한다.

## 문서 검증 범위

요구사항 대응은 위 데모/사례/CAP 표, Git·완료 경계는 첫 절, 추가 근거 판단은 직전 절에 모았다. 새 문서·README 추가분·ignored 진행 기록 추가분의 링크/경로/수치/인용/민감정보와 실제 diff를 검사한다. 소스·설정이 바뀌지 않았으므로 전체 frontend/Java 회귀·로그인·시연·CAP·D-2·Graphify AST 갱신은 반복하지 않는다. 1~4차 검토, issue 통합, 문서 수정과 영향 회귀, 최종 독립 검토의 실제 수행 결과는 [phase-19 진행 기록](../작업기록/phase-19-progress.md)의 말미에만 남긴다.
