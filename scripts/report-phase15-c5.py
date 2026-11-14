"""C5 R1의 의미 동일성과 대표 부하 gate. 과거 성능과 새 구현 검증을 분리."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
from datetime import datetime, timezone

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def load_gate(rows, entries, seconds):
    """사전 고정 matrix의 target 각각을 판정. 빈 target은 대표 증거가 필요함."""
    expected={e['id']:e for e in entries}
    ids=[r['matrixEntry'] for r in rows]
    if len(set(ids)) != len(ids) or ids != [e['id'] for e in entries]:
        raise ValueError('Matrix entry set/order mismatch')
    result=[]
    for row in rows:
        entry=expected[row['matrixEntry']]
        if row['writeMode'] != entry['mode']:
            raise ValueError('Matrix mode mismatch')
        rate=row['measurement']['sent']/seconds
        target=entry['role']=='target'
        result.append(dict(entry=entry['id'],mode=entry['mode'],role=entry['role'],
                           actualSentPerSecond=rate,loadAdequate=rate>=100 if target else None))
    targets=[r for r in result if r['role']=='target']
    status=('REUSED_REPRESENTATIVE_EVIDENCE_REQUIRED' if not targets else
            'PASS' if all(r['loadAdequate'] for r in targets) else 'INSUFFICIENT_GENERATED_LOAD')
    return dict(status=status,rows=result,targetCount=len(targets))

def adoption_evidence(load, pairs, safety_complete):
    if load['status']!='PASS' or not safety_complete:
        return 'HOLD'
    # 목표 여섯 회 전부 합격을 후속 채택 조건으로 재도입하지 않음
    if len(pairs)!=3 or not all(all(p[k] for k in ('p95Improved','p99Improved','forcePerEventReduced')) for p in pairs):
        return 'HOLD'
    return 'EFFECT_EVIDENCE_ELIGIBLE_FINAL_GATES_REQUIRED'

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--entry',required=True)
    parser.add_argument('--reuse-report',required=True)
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    entry=read(args.entry); candidate=Path(entry['candidate']); cm=read(candidate/'candidate.json')['executionInputs']
    old=candidate/'source'; root=Path.cwd()
    runtime={n for n in cm if n.startswith(('src/main/','src/benchmark/'))}
    actual={p.as_posix() for tree in ('src/main','src/benchmark') for p in Path(tree).rglob('*') if p.is_file()}
    stub='src/main/resources/application-stub.yml'
    assert actual==runtime|{stub}, 'Unexpected runtime key change'
    properties='src/main/java/dev/cgt/pixelplace/pixel/application/WriteExecutionProperties.java'
    application='src/main/resources/application.yml'
    for name in runtime-{properties,application}:
        assert sha(root/name)==cm[name], 'Runtime meaning changed: '+name
    expected=(old/properties).read_text(encoding='utf-8').replace(
        '@ConfigurationProperties(prefix = "pixel-place.write")',
        '@ConfigurationProperties(prefix = "pixel-place.write", ignoreUnknownFields = false)').replace(
        'private String mode = "single";', 'private String mode = "group";')
    assert (root/properties).read_text(encoding='utf-8')==expected
    expected=(old/application).read_text(encoding='utf-8').replace('pixel-place:\n',
        'pixel-place:\n  write:\n    mode: ${PIXELPLACE_WRITE_MODE:group}\n',1)
    assert (root/application).read_text(encoding='utf-8')==expected
    assert (root/stub).read_text(encoding='utf-8').splitlines()[-3:]==[
        'pixel-place:', '  write:', '    mode: ${PIXELPLACE_WRITE_MODE:single}']
    for name in ('scripts/measure-phase15.ps1','scripts/phase15-c4-input.json',
                 'scripts/phase15-c-matrix.json','scripts/summarize-phase15.py','scripts/phase15-c-budget.ps1'):
        assert sha(root/name)==cm[name], 'Measurement meaning changed: '+name
    report=read(args.reuse_report)
    assert report['session']=='c4r220260923' and report['complete'] and report['completed']==15
    assert report['independentChecksPassed'] and not report['pending']
    entries=read('scripts/phase15-c-matrix.json')['entries']
    seconds=read('scripts/phase15-c4-input.json')['measurementSeconds']
    gate=load_gate(report['trials'],entries,seconds)
    assert gate['status']=='PASS' and gate['targetCount']==12
    for row in report['trials']:
        assert row['independentChecksPassed'] and row['drain']['complete'] and row['pendingSamples']==0
        assert 0<row['serverResources']['heapPeakBytes']<2*1024**3
        assert row['freshCapture']['last30FreshSamples']>0
        assert all(q['pendingSamples']==0 for q in row['serverQuarters'])
        assert row['consistency']['mismatches']==0
    result=dict(phase='15-C-5',savedUtc=datetime.now(timezone.utc).isoformat(),branch='R1',
        meaning='All measured runtime bytes identical except strict binding/default/profile. Explicit mode values unchanged.',
        changedRuntime=[properties,application,stub],historicalSession=report['session'],
        rawRecalculation=str(Path(args.reuse_report)),rawRecalculationSha256=sha(args.reuse_report),
        loadGate=gate,performanceGoals=report['goals'],spreadPairs=report['spreadPairs'],
        eligibility=adoption_evidence(gate,report['spreadPairs'],True),adoptionComplete=False,
        limitation='Historical performance only. Fresh C5 regression/smoke/recovery/closure still required.',
        trials=[dict(source='REUSED_C4_R2_RAW_RECALCULATED',**r) for r in report['trials']])
    with Path(args.output).open('x',encoding='utf-8') as file:
        json.dump(result,file,ensure_ascii=False,indent=2);file.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k!='trials'},ensure_ascii=False))

if __name__=='__main__':
    main()
