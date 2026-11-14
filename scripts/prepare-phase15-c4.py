"""C-4 진입의 선행 증거 대조와 예산 제안만 담당. 부하·DB 기동·승인값 변경 없음."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import math
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OWNED = Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS = OWNED / 'agent-runs'
CAND = RUNS / 'phase15-C-3-20260922-candidate-r3'
REG = RUNS / 'phase15-C-3-20260922-regression-r3'
AUDIT = RUNS / 'phase15-C-3-20260922-final-audit-r3'
GIB, MIB = 1024**3, 1024**2


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args):
    return subprocess.run(['git', '-c', 'core.quotepath=false', '-c', 'core.safecrlf=false', *args],
                          capture_output=True, encoding='utf-8', check=True).stdout


def verify():
    assert ROOT == Path('C:/Users/cgt/Desktop/Java/pixel-place')
    assert git('rev-parse', '--is-inside-work-tree').strip() == 'true'
    candidate, handoff = read(CAND / 'candidate.json'), read(AUDIT / 'final-record.json')
    inputs = candidate['executionInputs']
    assert len(inputs) == handoff['InputCount'] == 320
    assert all(sha(ROOT / name) == digest == sha(CAND / 'source' / name) for name, digest in inputs.items())
    assert candidate['identitySha256'] == handoff['CandidateSha256']
    regression_inputs = {Path(row['Path']).relative_to(ROOT).as_posix(): row['Sha256'].lower()
                         for row in read(REG / 'execution-input.json')}
    assert regression_inputs == inputs
    for name in ['gradle-exit-code.txt', 'runner-exit-code.txt', 'storage-verification-exit.txt']:
        assert int((REG / name).read_text(encoding='utf-8-sig')) == 0
    log = (REG / 'gradle.log').read_text(encoding='utf-16')
    for task in ['test', 'redisIntegrationTest', 'mysqlIntegrationTest', 'bootJar']:
        assert '> Task :' + task in log.splitlines()
    assert '11 actionable tasks: 11 executed' in log
    started = datetime.fromisoformat((REG / 'started-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
    totals = {}
    for task, expected in [('test', 1194), ('redisIntegrationTest', 2), ('mysqlIntegrationTest', 38)]:
        files = list((REG / task).glob('*.xml'))
        count = 0
        for path in files:
            raw = OWNED / 'build/test-results' / task / path.name
            hashes = read(path.with_name(path.name + '.hash.json'))
            assert sha(path) == hashes['SavedSha256'].lower() and sha(raw) == hashes['RawSha256'].lower()
            assert raw.stat().st_mtime >= started
            suite = ET.parse(path).getroot()
            cases = suite.findall('testcase')
            assert len(cases) == int(suite.get('tests')) > 0
            assert all(int(suite.get(k)) == 0 for k in ['failures', 'errors', 'skipped'])
            assert not any(c.find(k) is not None for c in cases for k in ['failure', 'error', 'skipped'])
            count += len(cases)
        assert count == expected
        totals[task] = dict(suites=len(files), tests=count, failures=0, errors=0, skipped=0)
    smoke_evidence = []
    for path in sorted(RUNS.glob('phase15-C-3-r*/manifest.json')):
        manifest = read(path)
        if manifest['session'] != 'c3r320260922':
            continue
        directory = path.parent
        assert all(inputs[name] == digest.lower() for name, digest in manifest['sourceIdentity'].items())
        runtime, recovery = read(directory / 'runtime.json'), read(directory / 'recovery-check.json')
        assert manifest['writeMode'] == runtime['writeMode']
        assert runtime['executorBeans'] == 1 and runtime['sharedMeasurementVerified'] and runtime['sharedStorageVerified']
        assert recovery['newJvmPid'] != runtime['pid'] and recovery['schemaSeedUsersReexecuted'] is False
        for name in ['consistency.json', 'recovery-check.json']:
            value = read(directory / name)
            assert value['complete'] and value['mismatches'] == 0 and value['unknownUnresolved'] == 0
            assert value['accepted'] == value['databaseEvents'] == 15
            assert value['checkpoint'] == value['walTail'] and value['canonicalTiles'] == 1024
        summary = read(directory / 'summary.json')
        assert summary['complete'] and summary['normalShutdown'] and recovery['normalShutdown']
        smoke_evidence.append(dict(runId=directory.name, mode=manifest['writeMode'], trialPid=runtime['pid'],
                                   recoveryPid=recovery['newJvmPid']))
    assert sorted(x['mode'] for x in smoke_evidence) == ['group', 'single']
    for name in ['python-contract', 'matrix', 'smoke-analysis', 'a21-analysis']:
        assert int((AUDIT / (name + '-exit.txt')).read_text(encoding='utf-8-sig')) == 0
    assert handoff['CurrentAnalysisTests'] == 10 and handoff['MissingServletNegativeExit'] == 1
    jar = read(AUDIT / 'audit.json')['bootJar']
    assert sha(Path(jar['path'])) == jar['sha256'] and jar['prohibited'] == []
    # 기존 관측 하락분을 반환하지 않고 마지막 실제 관측 이후 양의 증가만 인계값에 추가
    budget = read(ROOT / 'scripts/phase15-c-budget.json')
    assert budget['maximumBytes'] == handoff['MaximumBytes'] == 36 * GIB
    assert budget['legacyChargeBytes'] == 2824498477 and budget['ancillaryChargeBytes'] == 67108864
    owned_bytes = sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
    actual = owned_bytes + budget['legacyChargeBytes'] + budget['ancillaryChargeBytes']
    charge = handoff['ConservativeHandoffCharge'] + max(0, actual - handoff['ActualCharge'])
    spec = read(ROOT / 'scripts/phase15-c-input.json')
    matrix = read(ROOT / 'scripts/phase15-c-matrix.json')
    expected_order = ('low-single low-group r1-same-single r1-same-group r1-spread-single r1-spread-group '
                      'r2-same-group r2-same-single r2-spread-group r2-spread-group-off r2-spread-single '
                      'r3-same-single r3-same-group r3-spread-single r3-spread-group').split()
    assert [entry['id'] for entry in matrix['entries']] == expected_order
    # BenchmarkSpec.plan + BenchmarkStorageBudget.nextLogEstimate의 FULL binlog 상한과 독립 대조
    seconds = spec['warmupSeconds'] + spec['measurementSeconds'] + 3 * spec['maxDrainSeconds'] + 3 * spec['readyTimeoutSeconds'] + 10 * spec['controlTimeoutSeconds']
    samples = 2 + seconds * 1000 // spec['sampleIntervalMillis']
    trials = []
    for entry in matrix['entries']:
        case = spec['cases'][entry['caseIndex']]
        attempts = 1 + math.ceil(case['offeredRate'] * spec['warmupSeconds']) + math.ceil(case['offeredRate'] * spec['measurementSeconds'])
        base = 192 * MIB + attempts * 8192 + samples * 8192
        updates = min(attempts, seconds + 1) if case['pattern'] == 'same-pixel' else attempts
        logs = 80 * MIB + updates * (2 * 65536 + 1024) + attempts * 2048
        trials.append(dict(id=entry['id'], attempts=attempts, baseBytes=base, fullBinlogBytes=logs, totalBytes=base + logs))
    fixed = sum(t['totalBytes'] for t in trials)
    assert fixed == 21752478720
    estimates = dict(fixed15=fixed, finalFullRegression=5 * GIB, oneFullRegressionRetry=5 * GIB,
                     oneMatchedSpreadPairRetry=2 * 2534044672, limitedBudgetTestsBuildCacheTemp=512 * MIB,
                     mysqlSharedStartupShutdownTempLogs=256 * MIB, candidateFinalSnapshotsAnalysisEvidence=512 * MIB,
                     conditionalTwoSmokeRecovery=640 * MIB, additionalContingency=GIB)
    additional = sum(estimates.values())
    proposed = math.ceil((charge + additional) / (2 * GIB)) * 2 * GIB
    free = shutil.disk_usage('E:/').free
    assert proposed == 74 * GIB and free - (proposed - charge) >= 5 * GIB
    return dict(savedAt=datetime.now(timezone.utc).isoformat(), status='BUDGET_APPROVAL_PENDING',
                branch=git('branch', '--show-current').strip(), head=git('rev-parse', 'HEAD').strip(),
                candidateIdentity=candidate['identitySha256'], unchangedExecutionInputs=320,
                regression=totals, regressionTasksExecuted=11, regressionExits=0, smokes=smoke_evidence,
                analysisSyntheticTestsReused=10, bootJarHashMatches=True, mandatoryC4ComparisonExecuted=0,
                actualOwnedBytes=owned_bytes, actualCharge=actual, inheritedConservativeCharge=handoff['ConservativeHandoffCharge'],
                conservativeCharge=charge, maximumBytes=budget['maximumBytes'], remainingBytes=budget['maximumBytes']-charge,
                freeBytes=free, samplesPerTrial=samples, trials=trials, estimates=estimates,
                additionalBytes=additional, expectedTotalBytes=charge+additional, proposedMaximumBytes=proposed,
                increaseOverApprovedBytes=proposed-budget['maximumBytes'], expectedFreeBytes=free-additional,
                freeAtProposedCapBytes=free-(proposed-charge), evidenceReuseRequiresBudgetOnlySemanticEquivalence=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--save', action='store_true')
    args = parser.parse_args()
    report = verify()
    if args.save:
        # 진입 증거는 기존 승인 안의 64MiB만 예약. 제안 상한은 실행 권한으로 사용하지 않음
        assert report['conservativeCharge'] + 64 * MIB <= report['maximumBytes']
        assert report['freeBytes'] - 64 * MIB >= 5 * GIB
        output = RUNS / ('phase15-C-4-entry-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
        output.mkdir()
        for name, command in [('baseline.diff', ('diff', '--')), ('cached.diff', ('diff', '--cached', '--')),
                              ('status.txt', ('status', '--short', '--untracked-files=all')),
                              ('worktrees.txt', ('worktree', 'list'))]:
            (output / name).write_text(git(*command), encoding='utf-8')
        (output / 'candidate-input.json').write_text(json.dumps(read(CAND / 'candidate.json'), indent=2) + '\n', encoding='utf-8')
        shutil.copyfile(__file__, output / Path(__file__).name)
        shutil.copyfile(ROOT / '작업기록/phase-15-progress.md', output / 'progress-before.md')
        report['evidenceDirectory'] = str(output)
        (output / 'entry-budget-proposal.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
