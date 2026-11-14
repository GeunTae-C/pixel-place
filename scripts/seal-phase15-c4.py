"""C-4 예산 연결 후보와 선행 검증 재사용의 입력 차이 보존. 부하·DB 변경 없음."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OWNED = Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS = OWNED / 'agent-runs'
PRIOR = RUNS / 'phase15-C-3-20260922-candidate-r3'
REG = RUNS / 'phase15-C-4-20260923-pacing-r2-validation'
OUT = RUNS / 'phase15-C-4-20260923-candidate-r2'


def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


before = read(PRIOR / 'candidate.json')['executionInputs']
rows = read(REG / 'execution-input.json')
current = {Path(row['Path']).relative_to(ROOT).as_posix(): row['Sha256'].lower() for row in rows}
assert len(current) == 324
assert all(sha(ROOT / name) == digest for name, digest in current.items())
changed = [name for name in before if current.get(name) != before[name]]
assert not any(name.startswith('src/main/') for name in changed)
assert current['scripts/phase15-c-input.json'] == before['scripts/phase15-c-input.json']
for name in ('gradle-exit-code.txt', 'runner-exit-code.txt'):
    assert int((REG / name).read_text(encoding='utf-8-sig')) == 0
assert read(REG / 'input-preservation.json')['Changed'] == 0
total = 0
for p in (REG / 'test').glob('*.xml'):
    hashes = read(p.with_name(p.name + '.hash.json'))
    assert sha(p) == hashes['SavedSha256'].lower()
    raw = OWNED / 'build/test-results/test' / p.name
    assert sha(raw) == hashes['RawSha256'].lower()
    suite = ET.parse(p).getroot()
    assert len(suite.findall('testcase')) == int(suite.get('tests'))
    assert all(int(suite.get(k)) == 0 for k in ('failures', 'errors', 'skipped'))
    total += int(suite.get('tests'))
assert total == 32
last = read(sorted((RUNS / 'phase15-C-4-budget-ledger').glob('*.json'))[-1])
owned = sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
charge = last['chargedBytes'] + max(0, owned-last['ownedBytes'])
reserve = 128 * 1024**2
free = shutil.disk_usage('E:/').free
assert charge + reserve <= 79456894976 and free-reserve >= 5*1024**3
OUT.mkdir()
for name, digest in current.items():
    target = OUT / 'source' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / name, target)
    assert sha(target) == digest
for name in ('seal-phase15-c4.py', 'test-phase15-c-budget.ps1'):
    shutil.copyfile(ROOT / 'scripts' / name, OUT / name)
for name, args in [('candidate.diff', ['diff', '--']), ('status.txt', ['status', '--short', '--untracked-files=all'])]:
    result = subprocess.run(['git', '-c', 'core.quotepath=false', '-c', 'core.safecrlf=false', *args],
                            capture_output=True, encoding='utf-8', check=True)
    (OUT / name).write_text(result.stdout, encoding='utf-8')
identity = hashlib.sha256(json.dumps(current, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
report = dict(savedAt=datetime.now(timezone.utc).isoformat(), identitySha256=identity, executionInputs=current,
              inputCount=len(current), changedFromC3=changed, addedInputs=sorted(set(current)-set(before)),
              limitedTests=32, candidateMode='single/group cold-start', productionInputsUnchanged=True,
              reusedRegression='phase15-C-3-20260922-regression-r3', reusedSmokes=None,
              reuseReason='Production/test configuration remains unchanged from C3; benchmark pacing changes are covered by 32 limited tests and require new two-mode smokes and a separate fixed15 comparison',
              fullRegressionRequiredAfterFinalCodeSelection=True, measurementSession='c4r220260923',
              charge=charge, cap=79456894976, freeBytes=free, reservedBytes=reserve)
(OUT / 'candidate.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='executionInputs'}, indent=2))
