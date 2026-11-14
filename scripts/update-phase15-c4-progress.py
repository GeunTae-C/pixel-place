"""실행을 중단하지 않고 C-4 재개 요약만 현재 증거로 갱신. 부하 기동·성공 판정 책임 없음."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import argparse
import json

parser=argparse.ArgumentParser()
parser.add_argument('--entry',required=True)
parser.add_argument('--session',default='c4r220260923')
parser.add_argument('--tool-session',required=True)
parser.add_argument('--next',required=True)
args=parser.parse_args()
root=Path.cwd()
runs=Path('E:/pixel-place-phase15/phase15-a-20260918/agent-runs')
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
trials=[]
for p in sorted(runs.glob('phase15-C-4-r*/manifest.json')):
    m=read(p)
    if m['session']!=args.session:continue
    inv=list(runs.glob('phase15-C-4-invoke*/'+m['trialId']+'-recovery-exit.txt'))
    completed=bool(inv) and all(int(q.read_text(encoding='utf-8-sig'))==0 for q in inv)
    trials.append((m,completed))
finished=[m for m,c in trials if c]
active=next((m for m,c in trials if m['matrixEntry']==args.entry),None)
last=read(sorted((runs/'phase15-C-4-budget-ledger').glob('*.json'))[-1])
p=root/'작업기록/phase-15-progress.md'
body=p.read_text(encoding='utf-8')
begin=body.index('- 실행 관측 ')
end=body.index('- 동결 후보 ',begin)
now=datetime.now(timezone(timedelta(hours=9))).isoformat(timespec='seconds')
replacement=(f'- 실행 관측 {now}: 세션 `{args.session}` **측정/복구 종료 {len(finished)}/15**. 완료 entry: '+', '.join(m['matrixEntry'] for m in finished)+'.\n'
    +f'- 현재 `{args.entry}` trial `{active["trialId"] if active else "manifest 게시 대기"}`·tool session {args.tool_session}·runner exit 대기. 다음은 종료/복구 확인 뒤 `{args.next}`. C-4/전체 15 미완료·최종 선택 전. 실행 Java의 PID/시작시각은 해당 manifest/runtime 및 runner 증거로 대조하며 PID만으로 소유권을 판정하지 않음.\n'
    +f'- 최신 ledger {last["sequence"]:08d}: charge **{last["chargedBytes"]:,} / {last["maximumBytes"]:,} bytes**, 승인 잔여 {last["maximumBytes"]-last["chargedBytes"]:,}, E free {last["freeBytes"]:,} bytes. 관측 {last["savedUtc"]}. 실행 중 증가분은 다음 ledger에 누적. 승인 2026-09-23T02:04:39+09:00 사용자 “승인”·74GiB·감소분 반환 없음.\n')
p.write_text(body[:begin]+replacement+body[end:],encoding='utf-8')
print(f'progress updated; completed={len(finished)}; active={args.entry}; session={args.tool_session}')
