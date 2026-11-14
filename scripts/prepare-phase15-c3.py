"""C-2 완료 입력·증거를 확인하고 C-3 진입 사본과 보수적 예산만 보존."""
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
PRIOR = RUNS / 'phase15-C-2-20260922-regression-r2'
OUT = RUNS / 'phase15-C-3-20260922-entry'
CAP, FLOOR, LEGACY, ANCILLARY = 38654705664, 31123978717, 2824498477, 67108864


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


inputs = read(PRIOR / 'execution-input.json')
assert len(inputs) == 309
assert all(sha(Path(row['Path'])) == row['Sha256'] for row in inputs)
for name in ['gradle-exit-code.txt', 'runner-exit-code.txt', 'storage-verification-exit.txt']:
    assert int((PRIOR / name).read_text(encoding='utf-8-sig')) == 0
for task, count in [('test', 1184), ('redisIntegrationTest', 2), ('mysqlIntegrationTest', 34)]:
    total = 0
    for saved in (PRIOR / task).glob('*.xml'):
        hashes = read(saved.with_name(saved.name + '.hash.json'))
        assert sha(saved) == hashes['SavedSha256']
        xml = ET.parse(saved).getroot()
        assert int(xml.get('tests')) == len(xml.findall('testcase')) > 0
        assert all(int(xml.get(k)) == 0 for k in ['failures', 'errors', 'skipped'])
        total += int(xml.get('tests'))
    assert total == count
owned = sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
charge = max(FLOOR, owned + LEGACY + ANCILLARY)
# 기존 runner의 5GiB 전체 회귀 예약 유지. smoke의 FULL binlog 포함 trial 상한은 각각 320MiB.
estimates = {'fullRegressionDbBinlogWal': 5 * 1024**3,
             'twoSmokeAndFreshJvmRecovery': 640 * 1024**2,
             'limitedTestsBuildCacheTempSnapshotsEvidence': 512 * 1024**2,
             'recoveryRotationFixtures': 256 * 1024**2,
             'affectedRevalidationAndContingency': 512 * 1024**2}
additional = sum(estimates.values())
free = shutil.disk_usage('E:/').free
assert charge + additional <= CAP and free - additional >= 5 * 1024**3
assert not OUT.exists()
OUT.mkdir()
paths = {Path(row['Path']) for row in inputs}
paths |= {p for p in (ROOT / 'scripts').iterdir() if p.is_file() and
          ('phase15' in p.name.lower()) and p.suffix in {'.ps1', '.py', '.java', '.json'}}
paths |= {ROOT / 'docs/pixel-place-details.md', ROOT / 'docs/pixel-place-specs.md'}
paths |= set((ROOT / '\uC791\uC5C5\uAE30\uB85D').glob('phase-15-progress.md'))
manifest = []
for path in sorted(paths):
    relative = path.relative_to(ROOT)
    target = OUT / 'source' / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, target)
    manifest.append(dict(Path=relative.as_posix(), Sha256=sha(path)))
(OUT / 'baseline-input.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
for name, args in [('baseline.diff', ['diff', '--']), ('baseline-status.txt', ['status', '--short', '--untracked-files=all'])]:
    result = subprocess.run(['git', '-c', 'core.quotepath=false', '-c', 'core.safecrlf=false', *args],
                            capture_output=True, encoding='utf-8', check=True)
    (OUT / name).write_text(result.stdout, encoding='utf-8')
report = dict(savedAt=datetime.now(timezone.utc).isoformat(), ownedBytes=owned,
              actualCharge=owned+LEGACY+ANCILLARY, carriedCharge=charge, maximumBytes=CAP,
              estimates=estimates, estimatedAdditionalBytes=additional,
              estimatedTotalBytes=charge+additional, freeBytes=free, estimatedFreeBytes=free-additional,
              baselineInputCount=len(inputs), changed=0, legacy=LEGACY, ancillary=ANCILLARY,
              approval='Existing 36GiB approval carried forward by C-3 user instruction')
(OUT / 'budget-entry.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(json.dumps(report))
