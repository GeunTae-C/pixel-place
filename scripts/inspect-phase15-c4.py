"""진행 중 실행을 건드리지 않고 게시된 비민감 결과만 요약. 완료 판정은 runner exit까지 필요."""
from pathlib import Path
import argparse
import json

parser=argparse.ArgumentParser()
parser.add_argument('entry')
parser.add_argument('--session',default='c4r220260923')
args=parser.parse_args()
runs=Path('E:/pixel-place-phase15/phase15-a-20260918/agent-runs')
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
matches=[]
for p in runs.glob('phase15-C-4-r*/manifest.json'):
    m=read(p)
    if m['session']==args.session and m['matrixEntry']==args.entry:matches.append((p,m))
assert len(matches)<=1
result=dict(entry=args.entry,session=args.session,manifestPublished=bool(matches))
if matches:
    p,m=matches[0];d=p.parent
    result['trial']=m['trialId']
    for filename in ('summary.json','recovery-check.json'):
        q=d/filename
        if not q.exists():continue
        value=read(q)
        if filename=='summary.json':
            v=value['measurement']
            result['measurement']={k:v[k] for k in ('acceptedPerSecond','systemErrorRate','sent','planned','notSentRate','drainAccepted')}
            result['measurement'].update(p95=v['successRtt']['p95Millis'],p99=v['successRtt']['p99Millis'])
        else:result['recovery']={k:value[k] for k in ('complete','mismatches','normalShutdown','schemaSeedUsersReexecuted','newJvmPid')}
    result['exitFiles']={q.name:q.read_text(encoding='utf-8-sig').strip()
        for q in runs.glob('phase15-C-4-invoke*/'+m['trialId']+'-*-exit.txt')}
last=read(sorted((runs/'phase15-C-4-budget-ledger').glob('*.json'))[-1])
result['lastBudget']={k:last[k] for k in ('chargedBytes','maximumBytes','freeBytes','savedUtc')}
print(json.dumps(result))
