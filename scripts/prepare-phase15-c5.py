"""C5 세 상태와 승인 범위의 진입 증거 보존. 서비스·Git 상태를 변경하지 않음."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess

ROOT = Path.cwd()
OWNED = Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS = OWNED / 'agent-runs'

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None

def main():
    now = datetime.now(timezone.utc)
    prefix = 'phase15-C-5-' + now.strftime('%Y%m%d-%H%M%S%f')[:18]
    out = RUNS / (prefix + '-entry')
    candidate = RUNS / 'phase15-C-4-20260923-candidate-r2'
    final = RUNS / 'phase15-C-4-20260923-final-audit-r1'
    cm = read(candidate / 'candidate.json')['executionInputs']
    fm = read(final / 'audit.json')['executionInputs']
    for location, manifest in ((candidate, cm), (final, fm)):
        actual = {p.relative_to(location/'source').as_posix(): sha(p)
                  for p in (location/'source').rglob('*') if p.is_file()}
        assert actual == manifest, 'Frozen source key/hash mismatch'
    assert all(sha(ROOT/name) == digest for name, digest in fm.items())
    actual_src = {p.as_posix() for p in Path('src').rglob('*')
                  if p.is_file() and p.suffix in ('.java', '.yml', '.yaml', '.json')}
    assert actual_src == {n for n in fm if n.startswith('src/')}
    latest = sorted((RUNS/'phase15-C-4-budget-ledger').glob('*.json'))[-1]
    ledger = read(latest)
    owned = sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
    charge = ledger['chargedBytes'] + max(0, owned-ledger['ownedBytes'])
    # 예약은 잔여 작업 전체 예상량. helper별 순간 진입 예약과 중복 합산하지 않음
    plan_mib = dict(finalRegression=5120, limitedAndBaseline=512, twoSmokeRecovery=640,
                    regressionReworkAllowance=2048, snapshotArtifact=128,
                    sharedDatabaseAndTemporary=256, closing=64, uncertainty=1024)
    estimate = sum(plan_mib.values()) * 1024**2
    free = shutil.disk_usage('E:/').free
    assert charge + estimate <= 79456894976 and free-estimate >= 5*1024**3
    assert subprocess.check_output(['git','rev-parse','--is-inside-work-tree'], text=True).strip() == 'true'
    out.mkdir()
    rows = []
    for name in sorted(set(cm)|set(fm)):
        current = sha(ROOT/name)
        action = 'restore' if cm.get(name) != fm.get(name) else 'preserve'
        contract = ('C5-T02' if 'WriteExecution' in name else 'C5-T09' if 'benchmark' in name or 'measurement' in name
                    else 'C5-T03~T08' if action == 'restore' else 'C5-T01/T10')
        rows.append(dict(path=name, candidateSha256=cm.get(name), c4FinalSha256=fm.get(name),
                         entrySha256=current, action=action, contract=contract,
                         reason='Restore reviewed removal delta; preserve later strict binding/default integration'
                         if action=='restore' else 'Current bytes equal C4 final; preserve existing user changes',
                         performanceImpact='R1 subject to final semantic comparison'))
        if current:
            target=out/'source'/name; target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(ROOT/name,target)
    for name in ('AGENTS.md','docs/review-protocol.md','docs/pixel-place-details.md',
                 'docs/pixel-place-specs.md','작업기록/phase-15-progress.md','명령문/015_통합_공통기준.md'):
        target=out/'documents'/name; target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/name,target)
    for name,args in {'baseline.diff':['diff'], 'cached.diff':['diff','--cached'],
                      'status.txt':['status','--short'], 'name-status.txt':['diff','--name-status'],
                      'stat.txt':['diff','--stat'], 'worktrees.txt':['worktree','list']}.items():
        with (out/name).open('wb') as file:
            subprocess.run(['git','-c','core.quotepath=false','-c','core.safecrlf=false',*args],stdout=file,check=True)
    data=dict(phase='15-C-5',prefix=prefix,savedUtc=now.isoformat(),entry=str(out),
              branch=subprocess.check_output(['git','branch','--show-current'],text=True).strip(),
              head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
              candidate=str(candidate),c4Final=str(final),rows=rows,
              budget=dict(latestLedger=str(latest),ledgerSha256=sha(latest),ownedBytes=owned,
                          chargedBytes=charge,maximumBytes=79456894976,planMiB=plan_mib,
                          estimatedIncreaseBytes=estimate,estimatedFinalCharge=charge+estimate,
                          freeBytes=free,estimatedFreeAfter=free-estimate),
              performanceBranch='R1 provisional: measured semantics to be restored exactly; default/profile/helper changes only',
              approvalScope='Existing cumulative 74GiB final code/regression/evidence plus explicit C5 instruction; no increase')
    (out/'entry.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(prefix=prefix,entry=str(out),currentMatchesC4Final=True,
                         restoreFiles=sum(r['action']=='restore' for r in rows),budget=data['budget'])))

if __name__=='__main__':
    main()
