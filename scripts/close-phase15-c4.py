"""수동 최종 검토의 감사표와 종료 사실 저장. 부하·회귀·DB·Git 변경 실행 책임 없음."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OWNED = Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS = OWNED / 'agent-runs'
AUDIT = RUNS / 'phase15-C-4-20260923-final-audit-r1'
OUT = RUNS / 'phase15-C-4-20260923-closing-r1'
C3 = RUNS / 'phase15-C-3-20260922-regression-r3'
FINAL = RUNS / 'phase15-C-4-20260923-final-regression-r1'
ANALYSIS = RUNS / 'phase15-C-4-20260923-analysis-r3'
PROGRESS = ROOT / '작업기록/phase-15-progress.md'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args):
    result = subprocess.run(['git', '-c', 'core.quotepath=false', '-c', 'core.safecrlf=false', *args],
                            capture_output=True, encoding='utf-8', check=True)
    return result.stdout


# 앞서 같은 실행자가 완료한 검토 기록. 아래 사실 검사와 구분하며 자동 코드 검토로 사용하지 않음.
REVIEW = """## C-4 최종 구현 검토·C-T/V 감사

검토 기준: 사용자 C-4 지시, 015-C-4 실행문, 015 통합 공통 기준, AGENTS, review-protocol, 현재 specs/details와 실제 코드·설정·DDL. 원본 main/HEAD와 기존 사용자 변경을 보존한 같은 실행자가 수행. 별도 AI Reviewer·서브에이전트·자동 구현/검토 하네스 없음. 이 기록 도구는 사실/경로 확인과 문서 저장만 수행.

1. 구조·명시 계약 패스: tracked 전체 diff 3,214줄과 최종 후보 대비 변경/삭제, 신규 파일, 예산 guard/발생기/분석 도구 및 문서 흐름 대조. 74GiB 연결·cold-start single·폐기 group 입력 거부·공유 storage 종료 순서 확인. C4 변경 외 동결 선행 입력은 해시로 확인하여 기존 검토/실행 증거를 재사용.
2. 실패·경계 패스: single 등록/close 경합과 boundary·force·memory·dirty 잔여, eventSeq MAX, WAL poison/원본 보존, raw Error identity, write fatal과 pending 소유권 fatal의 차이, 기존 pending exact reconciliation 확인. 예산 이하/초과/overflow, 실패 trial 보존·새 run-id, source 불일치·누락 raw 거부 확인.
3. 교차 계약 패스: command→gate→Redis→single→core→WAL→memory/dirty→cooldown→WS, flush/capture/retention/recovery, 실제 bean/profile, HTTP/JWT/Origin/tileVersion/no-store, XML/task/runner/bootJar와 docs 대조. timer 정상 반환/예외 의미 유지; HTTP 성공과 정합성으로 요청 결과 판정.
4. 반대 관점 패스: 같은 15개 이름만 있는 불충분한 부하를 완료로 오인하지 않는지, A21과 C source 혼합, 낮은 force만으로 내구성 추정, 계측 off의 0 채움, stale capture·서버/발생기 시계 혼용, 제거된 worker 사례를 현재 runtime 검증으로 오인하는지 재검토. r1은 약 68/s로 목표 부하 부적합; r2는 최소 실제 송신 104.975/s와 모든 필수 raw 관측 확인.

Issue 통합·재판정:

| 항목 | 판정·조치·회귀 근거 |
|---|---|
| r1 발생기 짧은 park wake-up 손실 | MUST FIX 해결. Phase15LoadClient/BenchmarkPacer의 발생기 대기만 보완. 무DB 진단→32개 제한 검증→새 양 mode smoke→새 r2 15회. 원래 예정 도착/not_sent/maxInFlight/timeout 유지. r1 전부 보존·혼합 없음. |
| report/render DB transaction outcome 필터 | MUST FIX 해결. DB는 committed인데 success만 선택해 N/A였음. committed 대조로 교정하고 analysis-r3 전체 raw 재계산/표/도구 snapshot 저장. 원시 관측·HTTP 수치 불변, 부하 재실행 불필요. analysis-r2 보존. |
| sourceIdentity 일부 key만 대조할 가능성 | MUST FIX 해결. 후보 runtime의 exact key set와 각 hash를 함께 요구. 15개 manifest/source snapshot 대조 통과. |
| group 미채택 후 production/batch 전용 경로 | GC-08 필수 정리 완료. worker/bean·batch core/port·UNKNOWN mapping·group 설정/전용 test 제거. 당시 후보와 XML·DB/WAL 보존. |
| 철회한 fatalFailure 기반 core timer 변경 | NOT AN ISSUE. 사용자 철회에 따라 미적용. 정상 반환/예외 의미와 실제 요청 결과를 구분. |
| legacy batch JSON 필드·group timer enum 및 private 저장 루프 | NOT AN ISSUE. 공통 과거 분석 schema/활성 single 저장 알고리즘 보존 목적. 외부 batch 진입점·worker·mutable batch 수집 경로 없음. on 빈 분포/off null 구분. |
| group heap/일부 DB·disk 악화, 저부하/off 각 n=1, rotation 0 | 성능 판단의 제한으로 공개. 목표 미달 single 선택을 바꾸거나 일반적 개선을 주장하지 않음. 막연한 장기 시험 자동 추가 없음. |

수정 후 전체 regression 관점: 최종 311개 실행 입력으로 일반 1,123·실제 Redis 1·실제 MySQL 34, 117 suites, 실패/오류/skip 0, 11 task 실제 실행, Gradle/runner/storage exit 0. bootJar에 benchmark/test/devtools/제거 group 클래스 0. single/command/gate byte 동일, core 활성 구간 동일, storage 검증·직렬화·write·회전·force 알고리즘은 활성 single 그대로이며 불가능한 batch 진입과 계수 분기만 제거. default single도 그대로이므로 비교 single의 실행 의미를 재사용하고 같은 부하를 형식적으로 반복하지 않음.

최종 독립 관점: 이전 완료 주장을 배제하고 후보 324개↔15회 manifest/runtime/recovery↔최종 311개↔fresh XML/task/runner↔artifact를 다시 대조. 목표 합격과 작업 완료를 분리. group 대표 3/6·single 3/6이므로 목표 미달이고 GC-08(5) single 유지. 추가 BLOCKER/MUST FIX 없음. 남은 필수 미검증 없음; 아래 후속 범위는 이번 필수 검증이 아님.

증거 약호(모두 기존 E: agent-runs 아래): C3=`phase15-C-3-20260922-regression-r3`, C3S=`phase15-C-3-20260922-candidate-r3/source`, F=`phase15-C-4-20260923-final-regression-r1`, R=`phase15-C-4-20260923-analysis-r3`, A=`phase15-C-4-20260923-final-audit-r1`. XML 경로는 `<약호>/<task>/TEST-<package>.<suite>.xml`; `ct-evidence.json`에 suite별 전체 testcase/건수/해시 저장. 최종 제거 group 사례는 C3S와 C3 XML로 감사하며 현재 코드에 재추가하지 않음.

| 계약 | 실제 증거·재사용/갱신 판정 |
|---|---|
| C-T01 | C3 WriteExecutionConfigurationTest 18·SinglePixelWriteExecutorTest 6의 후보 mode/범위/close 증거 재사용. F 동일 suite 21·6으로 일반/stub default·명시 single·group/폐기 설정 거부·WAL 생성 0·executor→storage close 갱신. group 채택 default 검사는 미채택 분기에 해당 없음. |
| C-T02 | C3 GroupPixelWriteExecutorTest 17 중 FIFO 20→16+잔여·한 platform worker·claimed 포함 capacity, GroupWriteStorageTest의 실제 21 records/3 forces. C3S 동일 production의 r2 비교 후보에 재사용. |
| C-T03 | C3 GroupPixelWriteExecutorTest의 음수/long 경계·caller/worker deadline·interrupt/timeout·gate 유지, 실제 Redis 2건. F PixelUserWriteGate/PixelCommandConcurrency 및 실제 Redis 1건으로 single TTL180/동일 사용자 직렬화 갱신. |
| C-T04 | C3 SegmentedWalBatchTest 23 중 3→1 force/단건3, exact/1byte/oversize/many/empty-active/기존 forced 파일, WalMeasurementTest 파일별 coverage. r2 실제 append 크기 1..16·force/event 독립 확인. F WalMeasurementTest 3으로 단건 coverage/rotation/실패/off 재확인. |
| C-T05 | C3 SegmentedWalBatchTest의 short/0/negative/partial/record/empty force/close/open/active 및 Error 조합. F 기존 SegmentedWalFailure/Append/Scan/FileSet/Restart와 EventSeqExhaustion으로 단건 poison·순수 입력·충돌·고갈·원본 보존 유지. |
| C-T06 | C3 PixelWriteBatchTest 14의 force 전 memory/dirty 0·순서/버전, GroupExecutor selectedSuccess/publication lock·GroupWriteStorage 실제 파일 게시 증거. 제거된 경로의 동결 감사로 재사용. |
| C-T07 | C3 PixelWriteBatchTest 준비/seq/append·memory/dirty RuntimeException/Error prefix/suffix, GroupExecutor worker 실패·재기동 금지. F EventSeqExhaustion/SingleExecutor/PixelWriteService/Command/ServiceReadiness로 남은 경로 재검증. |
| C-T08 | C3 BatchWriteBoundaryTest 7·GroupWriteStorage의 force/rotation/memory/fatal capture 경쟁·DB transaction 밖 진행·정상 pending. F FlushWorker exact pending 및 capture/retention concurrency/MySQL 회귀 갱신. |
| C-T09 | C3 GroupExecutor start/close/queued/claimed/grace/선택·게시 경합, GroupWriteStorage native force 지연 중 UNKNOWN 게시와 실제 Spring worker→storage 종료. F single 종료 6+설정 close 사례. native I/O hard deadline 주장 없음. |
| C-T10 | C3 GroupPixelMvcTest 3의 실제 JWT/400/429/busy/unknown/fatal/dirty 경계. F ProductionPixelBoundary/PixelController/WS Embedded·Concurrency/Command 및 실제 Redis로 유지 경로 검증. group UNKNOWN mapping만 미채택 정리. |
| C-T11 | C3 MySQL 38 중 groupRotation… 4(commit/rollback/lost-response/cleanup-delay), GroupWriteStorage 사전 고정 실제 파일 6개 fresh JVM fixture 재사용. F MySQL 34와 WAL restart 회귀. r2 15회 각각 새 JVM·동일 코드/mode/config·schema/seed/users false·mismatch 0. |
| C-T12 | A audit.json과 F fresh XML/해시/time/testcase·117 suites/1,158 tests·11 task·all exits 0. source311 불변·default/profile wiring·bootJar 제외 검사 통과. C3 전체 회귀는 비교 후보에만 재사용하고 최종 코드는 새 전체 회귀 수행. |
| C-T13 | C3 A21 분석 보존; r2 source324/exact matrix15/각 recovery와 R report/comparison의 independentChecks PASS. Python 합성11·Java BenchmarkC/Runtime 계약, budget 경계, 필수 drain 전 계수·fresh zero capture·off null/사유 확인. |
| C-T14 | GC-08 대입: 완전한 목표 개선+부작용 없음→group 후보/채택, 의미 있는 trade-off·관측 부족→보류, 효과 없음/악화→single, 개선했으나 목표 미달→single, 정합성·필수 비교/회귀 실패→미완료. 이번은 개선/목표 미달로 single. decision.json·최종 코드/회귀/A·본 기록에 연결. |
| V-01 | F 일반/Redis/MySQL 및 bootJar 실제 실행. JDK21, E build/cache/temp, 기존 비밀 주입, Redis DB1 exact key·3307 UUID/catalog/path guard, original3306Executed=false. 회귀 XML을 기록만을 이유로 재생성하지 않음. |
| V-02 | F 실제 context/HTTP/WS·A artifact/input 대조, tracked/cached diff checks, 신규 whitespace/conflict/newline 검사. Graphify AST 갱신은 탐색 갱신이며 실행 증거 대체 아님. |
| V-03 | 같은 실행자의 4패스→issue 통합/수정→전체 regression 관점→독립 최종 관점 완료. unresolved BLOCKER/MUST FIX 0. 기존 사용자 변경/증거/실패 실행 보존·소유 프로세스 정상 종료·승인 예산 승계. |

최종 변경 이유: 예산 연결 JSON/Java/PowerShell/Python/관련 테스트는 승인74GiB와 누적 charge guard 일치; BenchmarkPacer/LoadClient는 실제 고정 도착률 확보; report/render는 exact 입력·원시/자원 대조; executor 설정/core/storage/measurement/controller와 관련 테스트는 group 미채택 정리. specs/details는 현재 single 흐름만 갱신, 로드맵 목표/순서는 변경 없음. 기존 A/B/C frozen inputs, 실패/부적합 r1, 모든 DB/WAL과 agent-runs 파일 보존. source snapshot은 비교 후보와 최종 코드를 별도로 보존.

미검증/후속 인계: 32MiB 실제 rotation 성능, 장기 부하·운영 장애/실배포 안정성, directory fsync, production 수신 클라이언트 한계는 이번 결과로 입증하지 않음. 17단계 directory fsync, 18단계 운영·장기 안정성, 19단계 수신 한계로 인계. 16단계 이후·운영 배포·Git 변경 작업은 시작하지 않음.
"""


def main():
    assert OUT.is_dir() and not (OUT / 'final-record.json').exists()
    audit = read(AUDIT / 'audit.json')
    report = read(ANALYSIS / 'report.json')
    decision = read(ANALYSIS / 'decision.json')
    assert report['complete'] and report['completed'] == 15 and report['targetLoadAdequate']
    assert report['independentChecksPassed'] and decision['adoption'] == 'single'
    assert report['goals'] == {'single': {'passed': 3, 'required': 6}, 'group': {'passed': 3, 'required': 6}}
    assert sha(ANALYSIS / 'report.json') == audit['analysisSha256']
    current = audit['executionInputs']
    assert len(current) == 311 and all(sha(ROOT / n) == h for n, h in current.items())
    assert all(sha(AUDIT / 'source' / n) == h for n, h in current.items())
    assert sha(AUDIT / 'final-boot.jar') == audit['bootJar']['sha256']
    for n in ('docs/pixel-place-specs.md', 'docs/pixel-place-details.md'):
        assert sha(ROOT / n) == sha(AUDIT / n)
    assert git('branch', '--show-current').strip() == 'main'
    assert git('rev-parse', 'HEAD').strip() == 'cb3a5ddadd494881668343325ac6e4f32097aea7'
    assert not git('diff', '--cached', '--name-only').strip()
    git('diff', '--check'); git('diff', '--cached', '--check')
    assert git('diff', '--') == (AUDIT / 'full-diff.txt').read_text(encoding='utf-8')
    status = git('status', '--short', '--untracked-files=all')
    assert status == (AUDIT / 'status.txt').read_text(encoding='utf-8')
    # 후속 문서 작업이 테스트를 무효화하지 않는지와 원본 사용자 변경 보존 확인
    original = read(RUNS / 'phase15-C-3-20260922-candidate-r3/candidate.json')['executionInputs']
    compared = read(RUNS / 'phase15-C-4-20260923-candidate-r2/candidate.json')['executionInputs']
    removed = sorted(set(compared) - set(current))
    assert removed == audit['deleted'] and len(removed) == 13
    unchanged_original = sum(1 for n, h in original.items() if n in current and current[n] == h)
    static_names = set(current) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
    static_names |= {str(p.relative_to(ROOT)).replace('\\', '/') for p in (ROOT / 'scripts').glob('*phase15*c4*') if p.is_file()}
    static_names |= {'docs/pixel-place-specs.md', 'docs/pixel-place-details.md'}
    for n in static_names:
        raw = (ROOT / n).read_bytes(); content = raw.decode('utf-8-sig')
        assert not raw or raw.endswith(b'\n'), ('newline', n)
        assert not re.search(r'^(<<<<<<< |=======|>>>>>>> )', content, re.M), ('conflict', n)
        if not n.endswith('.md'):
            assert all(line.rstrip() == line for line in content.splitlines()), ('whitespace', n)
    evidence = {}
    for label, location in (('C3', C3), ('F', FINAL)):
        suites = {}
        for task in ('test', 'redisIntegrationTest', 'mysqlIntegrationTest'):
            for p in sorted((location / task).glob('*.xml')):
                tree = ET.parse(p).getroot(); cases = tree.findall('testcase')
                assert int(tree.get('tests')) == len(cases) > 0
                assert all(int(tree.get(k)) == 0 for k in ('failures', 'errors', 'skipped'))
                assert not any(c.find(k) is not None for c in cases for k in ('failure', 'error', 'skipped'))
                suites[tree.get('name')] = dict(path=str(p), task=task, tests=len(cases),
                    sha256=sha(p), cases=[c.get('name') for c in cases])
        evidence[label] = suites
    assert sum(v['tests'] for v in evidence['C3'].values()) == 1234
    assert sum(v['tests'] for v in evidence['F'].values()) == 1158
    assert evidence['F']['dev.cgt.pixelplace.pixel.application.WriteExecutionConfigurationTest']['tests'] == 21
    processes = read(OUT / 'processes.json')
    stop = read(RUNS / 'phase15-C-4-20260923-mysql-stop/shutdown-verification.json')
    assert stop['SqlExit'] == 0 and not stop['Port3307Listening']
    assert processes['JavaCount'] == 0 and processes['Port3307Count'] == 0
    assert sorted((p['LocalPort'], p['OwningProcess']) for p in processes['OriginalListeners']) == [(3306, 6860), (6379, 4804)]
    assert processes['OriginalProcessIdentityPreserved']
    graph = read(OUT / 'graphify-verification.json')
    assert graph['ExitCode'] == 0
    ledger = RUNS / 'phase15-C-4-budget-ledger'
    last_path = sorted(ledger.glob('*.json'))[-1]
    last = read(last_path)
    owned = sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
    charge = last['chargedBytes'] + max(0, owned - last['ownedBytes'])
    reserve = 1024**2
    free = shutil.disk_usage('E:/').free
    assert charge + reserve <= 79456894976 and free - reserve >= 5 * 1024**3
    now = datetime.now(timezone.utc).isoformat()
    budget = dict(ownedBytes=owned, chargedBytes=charge + reserve, maximumBytes=79456894976,
                  freeBytes=free, sequence=last['sequence'] + 1, savedUtc=now,
                  closingEvidenceReserveBytes=reserve,
                  reserveReason='Closing record, review, XML index, progress snapshot and ledger; never refunded')
    # 종료 기록 자체와 진행 기록 사본까지 1MiB를 보수적으로 누적 반영. 기존 legacy/ancillary 재가산 없음
    with (ledger / f"{budget['sequence']:08d}.json").open('x', encoding='utf-8') as stream:
        json.dump(budget, stream, indent=2); stream.write('\n')
    (OUT / 'final-review.md').write_text(REVIEW, encoding='utf-8')
    (OUT / 'ct-evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    shutil.copyfile(__file__, OUT / Path(__file__).name)
    summary = f"""## C 현재 재개 요약 — 15-C-4 및 전체 15 완료

- 완료 기록 UTC {now}. **C-4 및 전체 15 작업 완료**, 성능은 **목표 미달**, 도입은 **single 유지**. GC-08(5) 적용; 목표 미달 group 채택 승인 요청이나 후속 단계 실행 없음.
- 유효 비교 세션 `c4r220260923`: 고정 순서 15/15와 각 새 JVM 복구·runner exit 전부 0. 후보 `phase15-C-4-20260923-candidate-r2/` 324개, SHA-256 `{read(RUNS / 'phase15-C-4-20260923-candidate-r2/candidate.json')['identitySha256']}`. 본측정 중 입력 변경 없음. 대표 실제 송신 최소 104.975/s; single/group 각각 목표 3/6(same 모두 합격·spread 모두 미달).
- group spread p95/p99(ms)는 r1 156.1474/221.7333, r2 180.3960/284.4722, r3 172.1860/253.4981. 대응 single보다 모두 개선·force/event 약 0.7972/0.8034/0.7965이나 p95 100ms 목표 미달. 오류·정책 거부·정합성 불일치 0; not_sent/계획·실제 송신/drain 및 자원·fresh capture 추세는 `{ANALYSIS}/comparison.md`와 report.json에 반복별 보존. 모든 backlog/활동 잔여 소진·checkpoint/tail·회복 통과. heap peak 및 일부 DB/disk 악화도 공개.
- group worker/bean·batch 전용 경로/설정 제거 완료. 일반/stub default single, 명시 group/폐기 group 설정 거부. core/command/gate와 활성 single 저장 의미 유지, hot switch/fallback 없음. 최종 실행 입력 **311개**, SHA-256 `{audit['identitySha256']}`. A의 final-only.diff/source와 최종 bootJar 별도 보존.
- 최종 회귀 F: **일반 1,123·실제 Redis 1·실제 MySQL 34**, 117 suites·실패/오류/skip 0·11 task 실제 실행·Gradle/runner/storage exit 0. fresh XML/입력311/bootJar/bean/profile/default 대조 완료. C3 1,194/2/38은 비교 후보 선행/제거 group 감사로 재사용; 발생기 변경 영향은 새 제한32·합성11·양 mode smoke/r2 15회로 갱신. r1 부하 부적합 15회·실패/과거 분석·A21 전부 별도 보존.
- 수동 4패스·issue 통합·수정 후 regression 관점·최종 독립 관점과 C-T01~14/V01~03 감사 완료. **미해결 BLOCKER/MUST FIX 0, 이번 필수 미검증 0**. Graphify 갱신 exit 0; 실행 증거 대체 아님. 상세 감사 `{OUT}/final-review.md`, XML/testcase index `ct-evidence.json`, 최종 사실 A/audit.json.
- 보수적 종료 누적 charge **{budget['chargedBytes']:,} / 79,456,894,976 bytes(74GiB)**, 승인 잔여 **{79456894976-budget['chargedBytes']:,} bytes**. 마감 기록 1MiB를 포함해 ledger `{budget['sequence']:08d}.json`에 승계, 감소분 반환·legacy/ancillary 중복 없음. 관측 E free **{free:,} bytes**, 마감 예약 반영 후 최소 **{free-reserve:,} bytes**(5GiB 이상). 승인 시각/답변은 아래 이력 유지.
- 소유 3307 SQL 정상 종료·launcher/child 종료·listener 없음·Java 없음. 기존 MySQL3306 PID6860/Redis6379 PID4804와 시작 identity 유지. 종료 증거 `phase15-C-4-20260923-mysql-stop/shutdown-verification.json`, 현재 프로세스 `closing-r1/processes.json`. DB/WAL/binlog/agent-runs 원본 보존, purge/삭제/이동 없음.
- 원본 main/HEAD `cb3a5ddadd494881668343325ac6e4f32097aea7`, 단일 worktree·staged 0 유지. 사용자 변경 중 허용된 C4 예산/발생기/최종 정리·문서 범위만 갱신. 다음 작업 없음: 17 directory fsync·18 운영/장기 안정성·19 수신 한계 인계 후 중단. **16단계 이후·운영 배포·Git 변경 작업 미착수**.

"""
    old = PROGRESS.read_text(encoding='utf-8-sig')
    start = old.index('## C 현재 재개 요약')
    end = old.index('### C-4 진입·승인·후보 동결 이력')
    new = old[:start] + summary + old[end:] + '\n\n' + REVIEW
    PROGRESS.write_text(new, encoding='utf-8')
    shutil.copyfile(PROGRESS, OUT / 'phase-15-progress.md')
    record = dict(savedAt=now, status='COMPLETE', c4Complete=True, phase15Complete=True,
        performance='TARGET_MISSED', adoption='single', mandatoryUnverified=[],
        unresolvedBlocker=0, unresolvedMustFix=0, comparisonSession=report['session'],
        candidate=report['candidate'], finalInputCount=311, finalIdentity=audit['identitySha256'],
        comparisonEvidence=str(ANALYSIS), finalAudit=str(AUDIT), regression=audit['regression'],
        manualReview=str(OUT / 'final-review.md'), reviewPasses=4, regressionReview=True, finalIndependentReview=True,
        reusedOriginalExecutionInputs=unchanged_original, preservedDeletedGroupInputs=removed,
        staticFilesChecked=len(static_names), gitDiffCheck=0, cachedDiffCheck=0, staged=0,
        gitStatusSha256=hashlib.sha256(status.encode()).hexdigest(), graphify=graph,
        budget=budget, remainingApprovedBytes=79456894976-budget['chargedBytes'],
        minimumFreeAfterClosingReserve=free-reserve, ownedShutdown=stop, processes=processes,
        progressSha256=sha(PROGRESS), phases16AndLaterStarted=False)
    (OUT / 'final-record.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    actual_after = sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
    assert actual_after - owned < reserve, 'Closing evidence exceeded reserved bytes'
    assert shutil.disk_usage('E:/').free >= 5 * 1024**3
    print(json.dumps({k: record[k] for k in ('status', 'c4Complete', 'phase15Complete', 'performance', 'adoption',
        'finalInputCount', 'reusedOriginalExecutionInputs', 'staticFilesChecked', 'remainingApprovedBytes', 'minimumFreeAfterClosingReserve')}))
    print(json.dumps(budget))


if __name__ == '__main__':
    main()
