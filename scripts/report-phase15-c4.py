"""동결 C-4 증거의 raw 재계산·서버 시계 추세·반복별 GC-08 수치 대조. runtime 입력 수정 없음."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import importlib.util
import json
import statistics

loader = importlib.util.spec_from_file_location('phase15_analysis', Path(__file__).with_name('summarize-phase15.py'))
analysis = importlib.util.module_from_spec(loader)
loader.loader.exec_module(analysis)
RUNS = analysis.ROOT / 'agent-runs'


def average(values):
    return statistics.mean(values) if values else None


def completed_timer(timer):
    # DB adapter의 확정 commit outcome과 일반 호출의 정상 반환 outcome 구분
    return timer['outcome']==('committed' if timer['operation']=='transaction' else 'success')


def extend(directory, manifest, row):
    runtime = analysis.read(directory / 'runtime.json')
    recovery_runtime = analysis.read(directory / 'recovery-runtime.json')
    for value in (runtime, recovery_runtime):
        assert value['flushDelay'] == '1s' and value['overviewDelayMillis'] == 10000
        assert value['walMaxSegmentBytes'] == 33554432 and value['serverMaxHeap'] == 2*1024**3
    assert manifest['serverHeap'] == '2g' and manifest['clientHeap'] == '512m'
    assert manifest['budgetApproval']['maximumBytes'] == 79456894976
    invocation = list(RUNS.glob('phase15-C-4-invoke*/' + manifest['trialId'] + '-trial-exit.txt'))
    assert len(invocation) == 1
    invocation = invocation[0].parent
    for suffix in ('-trial-exit.txt', '-recovery-exit.txt'):
        assert int((invocation / (manifest['trialId'] + suffix)).read_text(encoding='utf-8-sig')) == 0
    completed = analysis.read(invocation / 'completed-trials.json')
    completed = completed if isinstance(completed, list) else [completed]
    assert len(completed) == 1 and completed[0]['Trial'] == manifest['trialId']
    metrics = analysis.read(directory / 'server-metrics.json')
    counts = metrics['walCounts']
    row['walCounts'] = counts
    row['forcePerEvent'] = counts['recordForces'] / counts['completedRecords']
    row['actualAppendBatchMean'] = counts['batchRecords']/counts['batchCalls'] if counts['batchCalls'] else None
    row['disabledFields'] = metrics['disabledFields']
    transitions = analysis.read(directory / 'control-transitions.json')['parent']
    start = next(t['nanoTime'] for t in transitions if t['type'] == 'sent_MEASURE')
    end = next(t['nanoTime'] for t in transitions if t['type'] == 'received_SEND_DONE')
    samples = [s for s in analysis.read(directory / 'resource-samples.json')['server'] if s['phase'] == 'measurement']
    fresh = [s for s in samples if s['captureStatus'] == 'fresh' and s['capture'] is not None]
    tail = [s for s in fresh if end-30_000_000_000 <= s['capture']['observedNanos'] < end]
    assert fresh and tail
    row['serverObservationWindow'] = dict(startNanos=start, endNanos=end,
        note='Parent/server nanoTime only: sent_MEASURE to received_SEND_DONE; includes pipe boundary delay, never subtracted from client clock')
    row['freshCapture'] = dict(maxRecords=max(s['capture']['records'] for s in fresh),
        last30MeanRecords=average([s['capture']['records'] for s in tail]), last30FreshSamples=len(tail),
        last30MaxRecords=max(s['capture']['records'] for s in tail))
    row['serverQuarters'] = []
    prior_timers = {}
    for i in range(4):
        beginning, ending = start+i*30_000_000_000, min(end, start+(i+1)*30_000_000_000)
        part = [s for s in samples if beginning <= s['nanoTime'] < ending]
        captures = [s['capture']['records'] for s in part if s['captureStatus']=='fresh']
        current_timers = {t['operation']:t for t in part[-1]['timers']
            if t['phase']=='measurement' and completed_timer(t)} if part else prior_timers
        deltas = {}
        for operation, timer in current_timers.items():
            previous=prior_timers.get(operation,dict(count=0,totalMillis=0))
            count=timer['count']-previous['count']; total=timer['totalMillis']-previous['totalMillis']
            assert count>=0 and total>=-1e-6
            deltas[operation]=dict(count=count,totalMillis=total,meanMillis=total/count if count else None)
        prior_timers=current_timers
        row['serverQuarters'].append(dict(quarter=i+1, samples=len(part), freshCaptureMean=average(captures),
            freshCaptureMax=max(captures,default=None), resources=analysis.resources(part),
            timerDeltas=deltas if manifest['instrumentation'] else None,
            executorMax={k:max((s['executor'][k] for s in part),default=None) for k in
                ('queued','claimedUnsettled','workerInFlight','outstanding','unpublishedTerminal','activeCount')},
            commandMax=max((s['commandActive'] for s in part),default=None),
            pendingSamples=sum(s['pending'] for s in part), walBytesLast=part[-1]['wal'].get('bytes') if part else None))
    client=analysis.read(directory/'client-summary.json')
    assert client['resources'] and all(s['heapMax']==512*1024**2 for s in client['resources'])
    client_start=row['measurement']['windowStartNanos']
    row['clientQuarters']=[]
    for i in range(4):
        beginning=client_start+i*30_000_000_000; ending=beginning+30_000_000_000
        part=[s for s in client['resources'] if beginning<=s['nanoTime']<ending]
        row['clientQuarters'].append(dict(quarter=i+1,resources=analysis.resources(part),
            disk=analysis.disk_window(directory,manifest['trialId'],client,beginning,ending)))
    row['drain'] = analysis.read(directory / 'drain.json')
    if manifest['instrumentation']:
        assert any(t['operation']=='transaction' and completed_timer(t) and t['count']>0 for t in row['timers']), 'Required DB commit observation missing'
    row['goalPassed'] = (row['measurement']['acceptedPerSecond'] >= 100 and
        row['measurement']['successRtt']['p95Millis'] <= 100 and row['measurement']['successRtt']['p99Millis'] <= 250 and
        row['measurement']['systemErrorRate'] <= .001 and row['consistency']['mismatches'] == 0)
    row['actualSentPerSecond']=row['measurement']['sent']/manifest['spec']['measurementSeconds']
    row['targetLoadAdequate']=row['actualSentPerSecond']>=100
    return row


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--session',default='c4r120260923')
    parser.add_argument('--candidate',default='phase15-C-4-20260923-candidate-r1')
    parser.add_argument('--partial',action='store_true')
    parser.add_argument('--output')
    parser.add_argument('--entry', help='완료된 한 trial의 중간 독립 검사. 전체 matrix 판정을 대체하지 않음')
    args=parser.parse_args()
    if args.entry:
        assert args.partial and not args.output
        matches=[]
        for p in RUNS.glob('phase15-C-4-r*/manifest.json'):
            m=analysis.read(p)
            if m['session']==args.session and m['matrixEntry']==args.entry:
                matches.append((p,m))
        assert len(matches)==1
        p,m=matches[0]
        row=analysis.trial(p.parent,m)
        assert row['independentChecksPassed'] and analysis.source_matches(m['sourceIdentity'],m['sourceSnapshot'])
        row=extend(p.parent,m,row)
        print(json.dumps({k:row[k] for k in ('trial','matrixEntry','measurement','notSentReasons','forcePerEvent','freshCapture','independentChecksPassed')}))
        return
    rows=[]
    sources=[]
    pending=[]
    frozen=analysis.read(RUNS/args.candidate/'candidate.json')
    assert frozen['measurementSession']==args.session
    candidate=frozen['executionInputs']
    expected_runtime={name for name in candidate if name.startswith(('src/main/','src/benchmark/'))} | {
        'build.gradle','settings.gradle','pixel_place.sql','scripts/measure-phase15.ps1',
        'scripts/phase15-c-budget.json','scripts/phase15-c-matrix.json','scripts/phase15-c-input.json',
        'scripts/phase15-c4-input.json','scripts/phase15-c-budget.ps1','scripts/summarize-phase15.py'}
    for p in sorted(RUNS.glob('phase15-C-4-r*/manifest.json')):
        m=analysis.read(p)
        if m['session'] != args.session:
            continue
        if not (p.parent/'recovery-check.json').exists():
            pending.append(m['trialId'])
            continue
        row=analysis.trial(p.parent,m)
        assert row['independentChecksPassed'], {k:v for k,v in row['independentChecks'].items() if not v}
        assert analysis.source_matches(m['sourceIdentity'],m['sourceSnapshot'])
        assert set(m['sourceIdentity'])==expected_runtime, 'Frozen runtime source identity has missing or extra inputs'
        assert all(candidate.get(name)==digest.lower() for name,digest in m['sourceIdentity'].items())
        sources.append((m['sourceIdentity'],m['specSha256'],m['matrixSha256'],m['mysqlRuntimeConfigSha256']))
        rows.append(extend(p.parent,m,row))
    expected=[e['id'] for e in analysis.fixed_matrix()['entries']]
    assert rows and all(s==sources[0] for s in sources)
    assert [r['matrixEntry'] for r in rows] == expected[:len(rows)]
    if not args.partial:
        assert len(rows)==15 and not pending
    result=dict(savedAt=datetime.now(timezone.utc).isoformat(), session=args.session, candidate=args.candidate, completed=len(rows),
                complete=len(rows)==15 and not pending, completionScope='Raw matrix evidence only; C-4/phase15 completion requires G-01 load adequacy and final code gates',
                pending=pending, independentChecksPassed=True, trials=rows)
    if len(rows)==15:
        target=[r for r in rows if r['matrixEntry'].startswith('r') and not r['matrixEntry'].endswith('-off')]
        modes={mode:[r for r in target if r['writeMode']==mode] for mode in ('single','group')}
        assert all(len(v)==6 for v in modes.values())
        pairs=[]
        for repeat in range(3):
            s=next(r for r in modes['single'] if r['case']==1 and r['repetition']==repeat)
            g=next(r for r in modes['group'] if r['case']==1 and r['repetition']==repeat)
            pairs.append(dict(repetition=repeat+1,
                p95Improved=g['measurement']['successRtt']['p95Millis']<s['measurement']['successRtt']['p95Millis'],
                p99Improved=g['measurement']['successRtt']['p99Millis']<s['measurement']['successRtt']['p99Millis'],
                forcePerEventReduced=g['forcePerEvent']<s['forcePerEvent']))
        result['goals']={mode:dict(passed=sum(r['goalPassed'] for r in values), required=6) for mode,values in modes.items()}
        result['targetLoadAdequate']=all(r['targetLoadAdequate'] for r in target)
        result['minimumTargetActualSentPerSecond']=min(r['actualSentPerSecond'] for r in target)
        result['performanceAssessment']='TARGET_ASSESSABLE' if result['targetLoadAdequate'] else 'INSUFFICIENT_GENERATED_LOAD'
        result['spreadPairs']=pairs
        result['groupNumericallyEligible']=result['goals']['group']['passed']==6 and all(all(p[k] for k in
            ('p95Improved','p99Improved','forcePerEventReduced')) for p in pairs)
        result['finalSelection']=('Requires GC-08 side-effect/backlog/drain/resource review; numerical eligibility alone is not adoption'
            if result['targetLoadAdequate'] else 'HOLD: G-01 prohibits completion when generator cannot apply target load; preserve both candidate paths')
    if args.output:
        output=Path(args.output).resolve()
        assert output.is_relative_to(RUNS.resolve())
        with output.open('x',encoding='utf-8') as stream:
            json.dump(result,stream,indent=2)
    for r in rows:
        m=r['measurement']; f=r['freshCapture']
        print(f"{r['matrixEntry']}: accepted/s={m['acceptedPerSecond']:.4f}, p95={m['successRtt']['p95Millis']:.4f}, p99={m['successRtt']['p99Millis']:.4f}, errors={m['systemErrorRate']}, sent={m['sent']}/{m['planned']}, force/event={r['forcePerEvent']:.4f}, captureMax={f['maxRecords']}, captureLast30Mean={f['last30MeanRecords']:.2f}, checks=PASS")
    print(json.dumps({k:v for k,v in result.items() if k!='trials'}))


if __name__=='__main__':
    main()
