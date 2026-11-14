"""최종 후보의 비민감 실행 입력·diff·예산 보존. 테스트/부하/서비스를 시작하지 않음."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess

ROOT=Path.cwd()
OWNED=Path('E:/pixel-place-phase15/phase15-a-20260918')
OUT=OWNED/'agent-runs/phase15-C-3-20260922-candidate-r3'
CAP,FLOOR,EXTERNAL=38654705664,31123978717,2891607341
charge=max(FLOOR,sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())+EXTERNAL)
# 직전 전체 회귀 실제 증가 1,701,336,142 bytes에 약 715MB 여유를 더한 예상 2.25GiB.
# C-3 runner도 같은 실측 기반 예상량 사용. 누적 cap과 최소 디스크 여유는 변경 없음.
reserve=2304*1024**2+640*1024**2+128*1024**2
free=shutil.disk_usage('E:/').free
assert charge+reserve<=CAP and free-reserve>=5*1024**3
assert not OUT.exists()
paths={p for p in (ROOT/'src').rglob('*') if p.is_file() and p.suffix in {'.java','.yml','.yaml','.json'}}
paths|={ROOT/n for n in ['build.gradle','settings.gradle','pixel_place.sql','scripts/test-phase15.ps1',
                        'scripts/Phase15RegressionDatabase.java','scripts/phase15-c-budget.json',
                        'scripts/measure-phase15.ps1','scripts/summarize-phase15.py','scripts/test-phase15-analysis.py',
                        'scripts/phase15-c-input.json','scripts/phase15-c-matrix.json','scripts/start-phase15-c-mysql.ps1',
                        'scripts/stop-phase15-c-mysql.ps1','scripts/Phase15OwnedDatabaseStop.java']}
hashes={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
for p in sorted(paths):
    target=OUT/'source'/p.relative_to(ROOT)
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(p,target)
for name,args in [('candidate.diff',['diff','--']),('git-status.txt',['status','--short','--untracked-files=all'])]:
    r=subprocess.run(['git','-c','core.quotepath=false','-c','core.safecrlf=false',*args],capture_output=True,encoding='utf-8',check=True)
    (OUT/name).write_text(r.stdout,encoding='utf-8')
identity=hashlib.sha256(json.dumps(hashes,sort_keys=True,separators=(',',':')).encode()).hexdigest()
report=dict(savedAt=datetime.now(timezone.utc).isoformat(),executionInputs=hashes,inputCount=len(hashes),
            identitySha256=identity,charge=charge,cap=CAP,reservedBytes=reserve,estimatedTotal=charge+reserve,
            freeBytes=free,estimatedFree=free-reserve,sourceSnapshot=str(OUT/'source'),
            writeModes=['single','group'],defaultMode='single',maxBatchSize=16,maxOutstanding=128,
            queueTimeoutMillis=1000,shutdownGraceMillis=10000)
(OUT/'candidate.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='executionInputs'}))
