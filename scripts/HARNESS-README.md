# Pixel-place 실행형 하네스

이 하네스는 상세 명령문을 Writer Codex에 전달하고, 검증 실패를 같은 세션에 다시 넣어 수정과 재검증을 반복합니다. 결정론적 gate가 모두 통과한 뒤에는 별도 read-only Codex가 docs/review-protocol.md 기준으로 최종 검토합니다.

자동 commit, merge, branch/worktree 생성, 다음 단계 진입은 하지 않습니다. .gitignore도 변경하지 않으며 AGENTS.md, docs/review-protocol.md, .codex는 기존처럼 로컬 전용으로 둘 수 있습니다.

## 구현 실행 흐름

1. repository hygiene와 MySQL 환경 preflight
2. Writer를 workspace-write sandbox로 실행
3. Stop hook에서 빠른 gate 실행
4. 외부 supervisor에서 전체 gate 재실행
5. 실패 로그를 같은 thread_id로 resume
6. 동일 실패 3회 또는 최대 6회면 FAILED
7. 전체 gate 통과 뒤 별도 read-only reviewer 실행
8. BLOCKER/MUST FIX가 있으면 Writer 재개
9. gate와 reviewer가 모두 통과할 때만 PASS / IMPLEMENTED

Writer 최초 실행과 resume은 모두 프로세스 exit 0, 유효한 JSONL의 turn 완료,
세션 ID 일치 및 비어 있지 않은 최종 응답이 확인되어야 다음 gate로 진행합니다.
프로세스 nonzero 또는 top-level `turn.failed`/`error`는 즉시 FAILED(10)로 종료하며,
JSONL·완료 증거·세션 ID 계약 오류는 HARNESS_ERROR(30)로 종료합니다.
top-level error는 뒤에 완료 이벤트가 있어도 중단하는 하네스 정책입니다.
item 내부 도구 실패는 Writer가 같은 turn에서 해결할 수 있으므로 이 정책과 구분합니다.
각 attempt의 `writer-status.json`에 판정 근거를 남깁니다.
이 실행 완료 판정은 구현 완료를 뜻하지 않으며 이후 Full gate와 Reviewer는 계속 필요합니다.

## 시작 기준선과 검토 증거

Writer 시작 전 `baseline/baseline.json`과 `baseline/task-frozen.md`를 저장합니다.
기준선은 HEAD·branch·index entry와 시작 working tree의 파일 분류·SHA-256·검토 가능한 원문을 포함합니다.
tracked/untracked/ignored를 모두 확인하며 Git 추적 상태를 바꾸지 않습니다.
Writer 최초 실행·resume과 Reviewer의 작업 계약은 고정 명령문입니다.
원본 명령문이나 보존 증거, HEAD·branch·index가 실행 중 바뀌면 BLOCKED로 중단합니다.

매 attempt의 Full 통과 후 `review-evidence/changes.json`에 시작 상태 대비 누적 A/M/D 변경을 기록합니다.
각 항목에는 before/after 원문 경로와 별도 diff가 연결됩니다. Full 로그의 경로·해시도 기록하고 검토 종료까지 보호합니다.
기존 dirty 변경을 이번 실행의 변경으로 계산하지 않으며, 같은 파일의 기존 사용자 수정 보존 여부도 Reviewer가 확인합니다.
이 비교만으로 변경한 사람이 Writer인지 사용자인지 자동 식별하지는 않습니다.

Reviewer에는 고정 명령문·기준선·변경 목록·Full 로그 경로를 전달하며, 응답의 `evidence` 해시 세 개를 확인합니다.
이 값은 검토 근거의 식별 계약이며 모델이 실제로 모든 내용을 읽었다는 증명은 아닙니다.
Reviewer 종료 후 보존 증거와 현재 파일 상태를 다시 확인하여 검토 중 변경된 상태에 이전 PASS를 적용하지 않습니다.

제외 범위는 루트 `.git`, `build`, `.gradle`, `.idea`, `.vscode`, `out`, `bin`, `node_modules`,
`graphify-out`, `data`, `셀프백업`과 `*.zip`, `*.wal`입니다. `src/.../build` 같은 source 경로는 제외하지 않습니다.
비밀·접속정보가 의심되는 파일, 비 UTF-8/바이너리, 2MiB 초과 파일은 원문 복제 없이 해시만 보존합니다.
이 파일의 추가·수정·삭제가 있으면 `unreviewable`에 기록하고 자동 PASS를 보류합니다.
따라서 JDBC URL이 포함된 application.yml 등의 변경은 현재 자동 검토로 완료되지 않을 수 있습니다.
필요한 비교 근거를 사람이 확인해야 하며, 검증을 통과시키려고 제외 목록을 늘리지는 않습니다.
감지 규칙이 모든 비밀을 식별하는 것은 아니므로 증거 폴더는 로컬 검토용으로 취급합니다.
link/reparse point와 submodule은 현재 자동 증거 수집에서 지원하지 않습니다.
보존 해시 비교는 filesystem atomic snapshot이나 악의적인 동일 사용자 프로세스에 대한 보안 격리를 제공하지 않습니다.

4단계 회귀 검증(실제 Codex/API/DB 호출 없음):

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-harness-evidence.ps1

임시 Git 저장소의 증거 검증 20개, 기존 Writer 검증 44개, 증거 연결 흐름 12개를 순서대로 수행합니다.
사용자 저장소를 staging/commit하지 않으며 fixture 안에서만 Git init/add/commit을 사용합니다.

## 처음 한 번 준비

아래 Codex 설치·로그인·프로젝트 신뢰 절차는 구현 실행에 필요합니다. `-ValidateOnly`는 Codex를 사용하지 않으며 Java/Gradle/MySQL 검증 환경만 필요합니다.

1. Codex CLI와 Java 21을 PATH에서 확인합니다.

       codex --version
       java -version

2. Codex CLI 로그인이 필요하면 실행합니다.

       codex login

3. MySQL 8.4.11을 localhost:3306에서 실행하고 pixel_place_test database를 준비합니다. 테스트가 MariaDB나 다른 MySQL 버전을 거부하며, 이 전용 test database의 table을 삭제하고 root DDL로 다시 만듭니다. 실제 운영 database를 가리키면 안 됩니다.

4. 현재 PowerShell 세션에 DB_PASSWORD를 설정합니다.

       $env:DB_PASSWORD = "실제 로컬 비밀번호"

5. Codex를 repository에서 한 번 대화형으로 열고 프로젝트 .codex layer를 신뢰합니다. /hooks에서 새 Stop hook의 정확한 내용을 검토하고 신뢰합니다. rules 파일을 새로 읽게 Codex를 재시작합니다.

구현 모드 시작 시 codex execpolicy check로 safety.rules의 구문·inline test 및 git reset --hard의 실제 forbidden 판정을 확인합니다. stderr 경고는 JSON stdout과 별도 저장합니다.

## 실행 전 확인

전체 gate만 확인하고 Codex가 코드를 수정하지 않게 하려면:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\harness.ps1 -ValidateOnly

검증 전용 모드는 Codex 조회·rules 검사·Writer·Reviewer를 호출하지 않습니다. 성공 결과는 `VERIFIED`이며 명령문의 구현 완료를 판정하지 않습니다.
`-TaskFile`을 함께 전달해도 검증 전용 모드가 우선합니다. 테스트 과정의 build 산출물·로그와 전용 테스트 DB 처리는 그대로 발생합니다.
검토만 원하는 요청을 구현용 `-TaskFile` 실행에 넣지 않습니다. 이 스크립트에 자연어 요청의 수정 권한을 자동 판별하는 기능은 없습니다.

빠른 gate만 직접 확인하려면:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-fast.ps1

## 상세 명령문 실행

TaskFile은 현재 repository 내부 파일이어야 합니다.

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\harness.ps1 -TaskFile ".\명령문\013단계_상세실행명령문.md"

기본 retry budget:

- 최대 Writer attempt: 6회
- 동일 gate 실패 또는 동일 Reviewer 지적 fingerprint: 각각 연속 3회

필요하면 안전 한도 안에서 조정할 수 있습니다.

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\harness.ps1 -TaskFile ".\명령문\013단계_상세실행명령문.md" -MaxAttempts 8 -SameFailureLimit 3

## 6단계 시간 제한과 반복 예산

진입점은 `harness.ps1`입니다. 기존 실행 로직은 내부 `harness-worker.ps1`로 분리했습니다.
외부 supervisor가 worker 전체를 감시하므로 증거 수집·Git 호출 중 정체도 전체 시간 상한을 적용받습니다.

| 실행 인자 | 기본값 | 적용 범위 |
|---|---:|---|
| `-WriterTimeoutSeconds` | 1800초 | 최초 Writer와 매 resume, 그 안에서 호출한 훅 포함 |
| `-ReviewerTimeoutSeconds` | 900초 | 매 독립 Reviewer 호출 |
| `-GateTimeoutSeconds` | 900초 | preflight·Full 등 worker가 호출하는 각 검증 프로세스 |
| `-TotalTimeoutSeconds` | 7200초 | worker 시작부터 최종 종료까지 전체 실행 |
| `-MaxAttempts` | 6회 | 최초 Writer를 포함한 총 실행 횟수, 최대 20 |
| `-SameFailureLimit` | 3회 | 동일 gate 실패 또는 동일 Reviewer 지적 연속 횟수, 최대 10 |

시간 인자의 허용 범위는 1~86400초입니다. 개별 제한보다 전체 제한이 먼저 소진되면 전체 제한이 우선합니다.
rules preflight에는 별도 30초 제한이 있습니다. bootstrap의 Add-Type/준비 I/O와 최종 보고 I/O는 worker 예산 밖이며,
강제 종료 뒤 프로세스 정리 확인에 최대 5초를 추가로 사용합니다.
직접 실행한 `verify-fast.ps1`/`verify-full.ps1`이나 내부 worker만 실행한 경우에는 외부 supervisor의 전체 시간 상한이 적용되지 않습니다.

Windows Job Object에 suspended 상태의 자식을 먼저 배속한 뒤 시작합니다. 정상 종료와 시간 초과 모두 해당 job의 잔여 자손을 정리합니다.
시간 초과는 BLOCKED(20)로 종료하며 gate/Reviewer/Writer resume을 추가 호출하지 않습니다. 정리를 확인하지 못하거나 실행기 내부 오류가 발생하면 HARNESS_ERROR(30)입니다.
worker가 먼저 저장한 PASS가 있더라도 외부 시간 제한을 초과한 실행은 PASS로 처리하지 않습니다.
사용자가 실행해 둔 다른 java/Codex 프로세스를 이름으로 일괄 종료하지 않습니다.
기존 서비스·원격 요청 자체를 종료하거나 이미 수행한 파일·DB 변경을 되돌리는 기능은 아닙니다.

하네스에서 실행한 Fast/Full은 `PIXEL_PLACE_SUPERVISED=1`을 상속받아 Gradle에 `--no-daemon`을 전달합니다.
이때 생성되는 single-use daemon도 job의 자손으로 관리합니다. 하네스 외부에서 직접 실행하는 기존 Gradle 명령에는 이 설정을 추가하지 않습니다.
Job Object의 자손 관리와 중첩 동작은 [Microsoft Job Objects 문서](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects),
Gradle 실행 옵션은 [Gradle Daemon 문서](https://docs.gradle.org/current/userguide/gradle_daemon.html)를 기준으로 합니다.
Windows 10/11의 Windows PowerShell 5.1을 검증 대상으로 하며 Linux 실행은 지원하지 않습니다.

Reviewer 반복 판정은 blockers/must_fix의 공백·순서를 정규화한 텍스트 해시입니다. 의미가 비슷하지만 문구가 달라지는 지적까지 식별하는 기능은 아닙니다.
지적이 바뀌면 해당 연속 횟수를 초기화합니다. gate 실패가 발생하면 Reviewer 연속 횟수가 끊기며, gate가 통과하면 gate 연속 실패 횟수가 끊깁니다.
전체 MaxAttempts와 전체 시간 상한은 이런 초기화와 관계없이 유지합니다.

실행별 `budget.json`, 최종 `result.json`의 limits/경과 시간, 각 프로세스의 `process-*.result.json`, attempt의 `reviewer-repetition.json`을 남깁니다.
강제 종료 시 중첩 단계의 마지막 기록이 없을 수 있으며 외부 total 실행 기록으로 종료·정리 결과를 확인합니다.
15초마다 경과 시간과 로그 위치를 표시합니다. Gradle 출력 전체는 로그에 남고 종료 후 콘솔에는 마지막 부분을 표시합니다.

6단계 회귀 검증은 실제 Windows 자식 프로세스를 생성하여 종료/자손 정리를 확인합니다. 실제 Codex/API/DB는 호출하지 않습니다.

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-harness-budgets.ps1

예상 총합은 182개(process 18 + budget-flow 7 + 기존 157)입니다. 실행 성공 로그를 확인하기 전에는 6단계 완료로 판정하지 않습니다.

## 오류 분류 근거

Fast/Full은 공통 `harness-failures.ps1`을 사용합니다. 실패 로그 전체에 특정 단어가 있다는 이유만으로 환경 문제로 판정하지 않습니다.

| 근거 | 처리 |
|---|---|
| 현재 MySQL XML의 SQL 인증·접속·DB 선택 오류, 알려진 DB 버전/전용 catalog guard | BLOCKED(20) |
| Hibernate Dialect 초기화 실패 + 같은 suite의 구체적인 JDBC 원인 | 원인에 따라 BLOCKED(20) |
| Dialect 메시지만 존재, 임의 placeholder 누락, 일반 assertion/SQL 문법 오류, 원인 불명 | FAILED(10) 유지 |
| 코드 실패와 DB 환경 실패가 혼합 | FAILED(10), 분류 이유에 혼합 원인 기록 |
| 실제 Gradle 실패 구간의 의존성 네트워크/TLS·인증 장애 또는 JDK 설치 오류 | BLOCKED(20) |
| artifact 좌표/404, 명시적인 컴파일·build 설정 오류 | FAILED(10) |
| 하네스가 요구한 MySQL task/생성 init 설정 오류, 로그·XML 파싱/I/O 오류 | HARNESS_ERROR(30) |
| child gate가 0/10/20/30 이외 코드를 반환 | HARNESS_ERROR(30)으로 정규화 |

`compileJava` task 이름만으로 코드 오류라고 단정하지 않고 실제 컴파일 진단과 의존성 다운로드 실패를 구분합니다.
Fast는 과거 기본 테스트 XML을 분류 근거로 읽지 않습니다. Full은 해당 MySQL 호출의 고유 XML 디렉터리만 읽습니다.
일반 assertion이 출력한 SQL 오류 예문이나 테스트 stdout의 ConnectException/JAVA_HOME 문자열은 환경 실패 근거로 확대하지 않습니다.
알 수 없는 실패의 FAILED는 코드 결함이 확정됐다는 뜻이 아니라 실패 상태를 유지한다는 뜻입니다. 분류는 지원하는 진단 형식에 의존하며 모든 오류의 근본 원인을 자동 확정하지 않습니다.

Full native 실패의 분류는 해당 mysql-<GUID>/failure-classification.json에 남습니다.
Fast는 RunDirectory/verify-fast-failure-<GUID>.json에 남깁니다. status, exit_code, native_exit_code, reason, source, test_reasons만 기록하며 원문 계정·URL은 추가하지 않습니다.
로그·XML 파일 자체를 읽을 수 없는 등의 예외는 기존 main의 내부 오류 경로로 종료하며, 이런 경우 분류 JSON이 생성되지 않을 수 있습니다.
성공 판정의 현재 실행 marker·XML·실제 testcase 검증과 Writer/Reviewer gate는 그대로 유지합니다.

5단계 검증 명령 하나로 신규 47개와 기존 Full 34개·증거/Writer 76개를 순서대로 실행합니다.

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-harness-failures.ps1

실제 Codex/API/DB 호출은 없으며 기존 증거 검증이 만드는 임시 Git fixture 안에서만 init/add/commit을 수행합니다.

## 7단계 결과 범위와 검토 권한

기존 status와 exit code를 유지하고 최종 result.json에 mode, completion, implementation_completed를 추가합니다.
구현 완료를 확인하는 소비자는 exit=0 또는 status=PASS만 보지 말고 아래 범위 필드도 확인해야 합니다.

| 실행 | status / exit | mode | completion | implementation_completed |
|---|---|---|---|---|
| 검증 전용 성공 | PASS / 0 | VALIDATE_ONLY | VERIFIED | false |
| 구현 실행의 Writer·Full·Reviewer 통과 | PASS / 0 | IMPLEMENT | IMPLEMENTED | true |
| 검증 실패·시간 초과·내부 오류 | 기존 실패 상태 / 10·20·30 | 호출 모드 | NONE | false |

외부 supervisor는 worker가 반환한 모드와 호출 모드를 대조하고 완료 필드·boolean·attempt를 확인합니다.
필드 누락, 다른 모드, 실패인데 완료로 표시, Writer attempt 없는 구현 완료 등 모순은 HARNESS_ERROR(30)입니다.
시간 초과에도 NONE/false로 기록하며 worker의 이전 완료 표시를 재사용하지 않습니다.
이 결과 필드는 기존 증거 확인과 Writer/Full/Reviewer 검증의 범위를 표시하며, 별도의 실행 증명이나 모델 검토 품질 보증을 추가하는 것은 아닙니다.

AGENTS.md, review-protocol, 상세 명령문 독립 검토 프롬프트와 생성 가이드는 검토 전용 요청을 issue·교체안·판정 보고로 종료하도록 맞췄습니다.
수정은 현재 요청과 세션의 유효한 허용 범위에서 수행하며, 이미 허용되고 취소되지 않은 수정은 다시 승인받지 않습니다.
read-only Reviewer는 직접 수정하지 않습니다. 구현이 허용된 하네스에서는 Reviewer 지적을 Writer에게 전달합니다.
문서 작업은 적용되는 테스트·정적 검증을 명시하고, 필수 테스트 미실행은 해당 대상 완료 판정에서 미검증으로 남깁니다.

7단계 회귀 명령:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-harness-scopes.ps1

예상 208개(scope 23 + validate-flow 3 + 기존 182)입니다. 실제 Codex/API/DB 호출은 없으며 이전 회귀에 포함된 임시 Windows 프로세스와 Git fixture는 사용합니다.
검증 전용 실행에서 Codex가 조회되면 실패하는 fixture와 Writer/Reviewer 호출 수 0 검증을 포함합니다.
7단계는 사용자 Windows의 208개 PASS 로그로 완료 확인했습니다. 이후 변경의 검증 범위는 각 단계 체크리스트를 따릅니다.

## 8단계 — 훅·Rules를 왜 점검하는가

파일이 존재해도 명령 연결·조건·규칙 판정이 잘못되면 의도한 동작이 나오지 않습니다. 이번 단계는 기존 Stop 피드백과 Git 변경 제한을 점검합니다.

- Stop은 Writer의 `PIXEL_PLACE_HARNESS=1`에서만 Fast gate를 실행합니다. 일반 세션과 Reviewer는 실행하지 않습니다.
- `stop_hook_active=true`이면 재차 continuation하지 않습니다. 종료 코드 10만 수정 요청을 반환하며, 환경/내부 오류는 외부 supervisor가 Full gate로 재판정합니다.
- PS 5.1의 native stderr 경고와 실제 실패 exit를 구분하고 stdout에는 JSON 한 줄만 반환합니다.
- Stop timeout은 900초(15분)입니다. 실제 훅이 Writer 자식으로 실행되면 기존 Writer 30분·전체 2시간의 바깥 제한도 적용됩니다.
- `build/agent-runs/stop-hook/<id>/hook-result.json`은 Fast 실행의 최소 기록입니다. `FAST_PASS`는 Fast 성공만 뜻하고 구현 완료 또는 실제 Codex 이벤트 발화를 단독으로 증명하지 않습니다.
- Rules의 `git worktree list` 조회 차단을 제거하고 worktree 변경 subcommand는 계속 제한합니다. 새 allow 규칙은 추가하지 않았습니다.
- Rules는 sandbox 밖 명령 실행을 통제하는 기능입니다. PowerShell 문자열·옵션 변형·다른 실행 도구까지 모든 파일 변경을 막는 장치로 간주하지 않습니다. [공식 Rules 문서](https://learn.chatgpt.com/docs/agent-configuration/rules).

8단계 Windows 검증 명령:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-harness-hooks.ps1
```

예상 96개(config 1 + hook 28 + rules 20 + rules-flow 3 + writer 44). 기존 14개 hook 사례를 cmd와 PowerShell 경유로 각각 실행합니다. Codex CLI는 PATH에 필요하지만 `execpolicy check`만 호출하므로 모델/API/로그인/DB를 사용하지 않습니다. 테스트에 나온 git reset/clean 등은 **규칙 검사 입력**이며 실행하지 않습니다. 임시 저장소에서 git init과 등록된 Windows Stop 명령을 실행하며, Fast만 fixture로 대체합니다. 사용자 저장소 staging/commit과 Git 추적 정책 변경은 없습니다.

이번에 변경한 Worker preflight와 연결되는 기존 Writer 44개를 재검증합니다. 관련 구현이 바뀌지 않은 Full·증거·시간 제한 검증 전체를 다시 늘려 실행하지 않습니다.

96개 통과 뒤 프로젝트에서 Codex를 재시작하고 `/hooks`에서 기존 Graphify와 Stop의 출처·정의·신뢰 상태를 확인합니다. Windows Stop 명령 정의가 바뀌었으므로 변경된 훅의 검토·신뢰가 다시 필요합니다. 신뢰가 필요한 이유는 Codex가 변경된 비관리 훅을 검토 전에는 건너뛰기 때문입니다. [공식 Hooks 문서](https://learn.chatgpt.com/docs/hooks).

Windows Stop 명령은 UTF-16LE `-EncodedCommand`를 사용합니다. 바깥 PowerShell이 `$repoRoot`를 먼저 확장하여 내부 명령이 `= git ...`로 바뀌는 문제를 피하기 위한 것입니다. `-NonInteractive`로 예기치 않은 입력 대기도 막습니다. 실제 hook JSON 입력·stdout 및 Fast 결과 계약은 기존 handler가 유지합니다. 검증 통과는 실제 Codex Stop 자동 호출의 성공을 뜻하지 않으며, 변경된 훅 신뢰 후 `build/agent-runs/diagnose-stop.ps1`로 자동 호출을 별도 확인합니다. 이전 `diagnose-stop-launch.ps1`은 수정 전 설정 해시를 고정한 진단이므로 새 설정에서 재실행하지 않습니다.

이 검증은 **등록된 명령의 직접 호출**입니다. 실제 Codex가 Stop 이벤트를 발화하고 프로젝트 설정을 로드하는지는 9단계에서 확인합니다. Graphify의 기존 절대 경로·PreToolUse 정의는 유지하며 실제 설치/실행 상태는 사용자 PC에서 확인해야 합니다.

## 남은 단계와 종료 조건

| 단계 | 왜 하는가 | 완료 조건 |
|---|---|---|
| 8. 훅·Rules 점검 | 피드백 연결 오류와 정상 조회의 과도한 차단 방지 | 초기 82개 통과; 9단계 명령 보완 후 96개 재검증 및 `/hooks` 신뢰 확인 |
| 9. 작은 실제 작업 1회 | fixture에서 대체했던 Codex·로그인·설정·Writer/Full/Reviewer 연결 확인 | 좁은 작업의 실제 변경, 현재 실행 검증 증거, Reviewer, 결과 범위 및 Stop 호출 기록 확인 |

현재 계획은 9단계의 실제 연결 확인으로 로컬 하네스 1차 구축을 마칩니다. CI·배포·추가 자동화·Graphify 이식성 개선은 현재 필수 단계가 아닙니다. 추가 문제가 발견되면 현재 완료 조건을 막는 결함인지 먼저 설명하고 해당 단계에서 처리하며, 선택 개선은 따로 기록합니다. 앞으로도 각 단계 시작 시 목적·변경 범위·종료 조건, 종료 시 결과·남은 일·다음 목적을 기록합니다.

## 종료 상태

| 상태 | exit code | 의미 |
| --- | ---: | --- |
| PASS | 0 | 해당 모드 성공. VERIFIED 또는 IMPLEMENTED 구분 필수 |
| FAILED | 10 | Writer 실행 실패, 또는 코드·테스트 실패를 retry budget 안에 해결하지 못함 |
| BLOCKED | 20 | 실행 시간 초과, DB, JDK, 인증, 충돌, 의심스러운 기존 파일 등 후속 조치 필요 |
| HARNESS_ERROR | 30 | JSONL, reviewer JSON, 스크립트 등 하네스 자체 오류 |

실행 증거는 build/agent-runs/실행ID 아래에 남습니다. build는 기존 .gitignore 정책에 따라 Git 관리 대상이 아닙니다.

## 파일별 책임

| 파일 | 책임 |
| --- | --- |
| scripts/harness.ps1 | 외부 진입점·시간/횟수 인자·전체 worker 감독 |
| scripts/harness-worker.ps1 | Writer 실행, session resume, gate/Reviewer, 반복 예산 |
| scripts/harness-process.ps1 | 제한 프로세스 실행·전체 감독·시간/정리 결과 기록 |
| scripts/harness-process-child.ps1 | 전달된 인자·stdin·훅 환경·UTF-8 출력 처리 |
| scripts/harness-job.cs | Windows Job Object 생성·suspended 배속·자손 정리 |
| scripts/test-harness-budgets.ps1 | 시간 제한·자손 정리·반복 및 이전 단계 회귀 |
| scripts/test-harness-scopes.ps1 | 완료 범위·검증 전용 모드 및 이전 단계 회귀 |
| scripts/test-harness-hooks.ps1 | 등록된 Windows Stop 명령·실제 CLI 규칙 판정·Worker preflight 검증 |
| scripts/harness-evidence.ps1 | 시작 기준선·고정 명령문·변경 증거·Reviewer 근거 식별 |
| scripts/harness-failures.ps1 | 실패 근거 분류·공통 종료 상태·분류 기록 |
| scripts/verify-fast.ps1 | repo hygiene, Git whitespace 검사, MySQL 제외 테스트 |
| scripts/verify-full.ps1 | 빠른 gate와 실제 MySQL 통합 테스트 |
| scripts/repo-hygiene.ps1 | 충돌 및 자동 삭제하면 안 되는 의심스러운 파일 감지 |
| scripts/reviewer-output.schema.json | reviewer 결과를 안정적인 JSON으로 제한 |
| .codex/hooks/stop_gate.ps1 | Codex 종료 직전 빠른 gate 실패 시 한 번 더 계속하도록 차단 |
| .codex/hooks.json | 기존 Graphify hook과 새 Stop hook 병합 |
| .codex/rules/safety.rules | 대표적인 파괴적 직접 명령 차단 |

Writer가 위 하네스 파일 자체를 변경하면 supervisor가 SHA-256 무결성 검사를 통해 BLOCKED로 중단합니다. 실행 전부터 있던 dirty diff의 whitespace 오류는 baseline으로 기록해 Writer가 만든 새 오류와 구분합니다.

## 공식 문서

- Hooks: https://learn.chatgpt.com/docs/hooks
- Non-interactive mode: https://learn.chatgpt.com/docs/non-interactive-mode
- Rules: https://learn.chatgpt.com/docs/agent-configuration/rules
- Sandboxing: https://learn.chatgpt.com/docs/sandboxing
