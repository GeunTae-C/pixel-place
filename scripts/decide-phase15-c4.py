"""보존 원시 분석의 GC-08 결정과 자원 검토 근거 저장. 부하 실행·코드 변경 책임 없음."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import json
import shutil

parser=argparse.ArgumentParser();parser.add_argument('--save',action='store_true');args=parser.parse_args()
owned=Path('E:/pixel-place-phase15/phase15-a-20260918');runs=owned/'agent-runs'
directory=runs/'phase15-C-4-20260923-analysis-r3'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
report=read(directory/'report.json');old=read(runs/'phase15-C-4-20260923-analysis-r2/report.json')
assert report['complete'] and report['completed']==15 and report['independentChecksPassed'] and report['targetLoadAdequate']
assert report['goals']=={'single':{'passed':3,'required':6},'group':{'passed':3,'required':6}}
assert all(all(pair[k] for k in ('p95Improved','p99Improved','forcePerEventReduced')) for pair in report['spreadPairs'])
for row,prior in zip(report['trials'],old['trials']):
    assert row['matrixEntry']==prior['matrixEntry'] and row['measurement']==prior['measurement'] and row['freshCapture']==prior['freshCapture']
    on=not row['matrixEntry'].endswith('-off')
    for q in row['serverQuarters']:
        if on:assert q['timerDeltas']['transaction']['count']>0 and q['timerDeltas']['transaction']['meanMillis']>0
        else:assert q['timerDeltas'] is None
    print(json.dumps(dict(entry=row['matrixEntry'],quarters=[dict(q=q['quarter'],captureMean=round(q['freshCaptureMean'],2),
        captureMax=q['freshCaptureMax'],queue=q['executorMax']['queued'],outstanding=q['executorMax']['outstanding'],
        pending=q['pendingSamples'],gcMs=q['resources']['gcMillis'],
        dbMs=round(q['timerDeltas']['transaction']['meanMillis'],2) if on else None,
        wsMs=round(q['timerDeltas']['broadcast']['meanMillis'],3) if on else None) for q in row['serverQuarters']])))
decision=dict(savedAt=datetime.now(timezone.utc).isoformat(),adoption='single',comparisonComplete=True,
    analysis='phase15-C-4-20260923-analysis-r3/report.json',session=report['session'],candidate=report['candidate'],
    performance='EFFECT_OBSERVED_TARGET_MISSED',rule='GC-08(5): improvement below target defaults to single',
    goals=report['goals'],spreadPairs=report['spreadPairs'],
    rationale='All group spread repetitions improve p95/p99 and reduce actual force/event, but p95=156.1474/180.3960/172.1860ms exceeds 100ms in all three; p99 also misses in repetitions 2 and 3. Group is not eligible for default adoption.',
    resourceReview={
        'same':'All six same trials meet goals. Group p99 is slightly worse in r1; same-group r3 has 3 arrival_late not_sent, no system errors.',
        'low':'One pair: group p95/p99 8.6167/10.5983 vs single 8.4302/9.7777ms. No general zero-latency claim.',
        'backlog':'Spread group fresh maxima 275/371/317 vs single 322/344/330; group r2 peak exceeds single range. Last30 means 182.529/181.647/188.353 within single range 176.813..191.312. Quarter trends are bounded but not flat; all final fresh captures are 0 and every queue/claimed/worker/unpublished/outstanding/command/servlet count drains with pending false.',
        'resources':'Spread group server CPU and GC time decrease, but sampled heap peaks increase (773.6/912.7/790.9 vs 623.6/684.8/583.9MiB). DB committed means worsen in r2/r3 and E-volume mean latency is higher in all three group spread runs. Host-wide disk attribution is limited; no free-improvement claim.',
        'off':'One group spread off run has lower latency than paired on; operation timers/batch histogram/lastCycle/broadcastFailures disabled and remain null/N/A. Mandatory safety counts, consistency, drain and recovery pass. No zero instrumentation-cost claim.',
        'rotation':'All fixed trial rotations=0; no rotation performance claim. Actual file/rotation failure correctness remains supported by frozen C3 tests.',
        'pacer':'Same corrected sender in both modes; representative client CPU about 1.17..1.20 cores. Old r1 under-load session remains separate, no mixing with A21.'},
    finalCodeAction='Remove rejected group production worker/bean and unused batch-only paths/settings; preserve measured single semantics and frozen candidates/evidence/tools.',
    finalRegressionRequired=True,phase15Complete=False,
    analysisCorrection='analysis-r2 incorrectly selected success for DB transaction timers; analysis-r3 uses committed. Raw request metrics unchanged, full raw checks rerun without any load rerun.')
if args.save:
    last=read(sorted((runs/'phase15-C-4-budget-ledger').glob('*.json'))[-1])
    size=sum(p.stat().st_size for p in owned.rglob('*') if p.is_file())
    charge=last['chargedBytes']+max(0,size-last['ownedBytes']);reserve=128*1024**2
    assert charge+reserve<=79456894976 and shutil.disk_usage('E:/').free-reserve>=5*1024**3
    with (directory/'decision.json').open('x',encoding='utf-8') as stream:json.dump(decision,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print('DECISION_SAVED single; performance=EFFECT_OBSERVED_TARGET_MISSED; final code gates pending')
