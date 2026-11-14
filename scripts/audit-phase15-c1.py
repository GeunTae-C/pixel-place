"""C-1 종료 증거의 독립 읽기 대조. 실행/재검증/DB 변경 없이 새 감사 파일만 기록."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OWNED = Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS = OWNED / 'agent-runs'
RUN = RUNS / 'phase15-C-1-20260922-regression'
ENTRY = RUNS / 'phase15-C-1-20260922-entry'
OUT = RUNS / 'phase15-C-1-20260922-final-audit'
CAP = 38_654_705_664
LEGACY = 2_824_498_477
ANCILLARY = 67_108_864

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def owned_size():
    return sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())

def git(*args):
    result = subprocess.run(['git', '-c', 'core.quotePath=false', '-c', 'core.safecrlf=false', *args],
                            capture_output=True, encoding='utf-8', check=True)
    return result.stdout

charge_before = owned_size() + LEGACY + ANCILLARY
assert charge_before + 64 * 1024**2 <= CAP
assert shutil.disk_usage('E:/').free - 64 * 1024**2 >= 5 * 1024**3
assert not OUT.exists(), 'Existing audit must remain immutable'
assert int((RUN / 'gradle-exit-code.txt').read_text(encoding='utf-8-sig')) == 0
assert int((RUN / 'runner-exit-code.txt').read_text(encoding='utf-8-sig')) == 0
assert int((RUN / 'storage-verification-exit.txt').read_text(encoding='utf-8-sig')) == 0
assert read_json(RUN / 'input-preservation.json') == {'Count': 303, 'Changed': 0}
started = datetime.fromisoformat((RUN / 'started-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
finished = datetime.fromisoformat((RUN / 'finished-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
# Windows PowerShell Tee-Object 원본은 UTF-16, 다른 JSON 증거는 명시 UTF-8
log_bytes = (RUN / 'gradle.log').read_bytes()
log = log_bytes.decode('utf-16' if log_bytes.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')
assert 'BUILD SUCCESSFUL' in log
summaries = []
for task, count in [('test', 1146), ('redisIntegrationTest', 1), ('mysqlIntegrationTest', 34)]:
    assert re.search(r'^> Task :' + task + r'\s*$', log, re.M), f'Actual task execution missing: {task}'
    totals = dict(Tests=0, Testcases=0, Failures=0, Errors=0, Skipped=0)
    files = list((RUN / task).glob('*.xml'))
    assert files
    for saved in files:
        hashes = read_json(saved.with_name(saved.name + '.hash.json'))
        assert sha(saved) == hashes['SavedSha256']
        raw = OWNED / 'build/test-results' / task / saved.name
        assert sha(raw) == hashes['RawSha256'], f'Raw XML changed: {raw.name}'
        assert started <= raw.stat().st_mtime <= finished, f'Stale XML: {raw.name}'
        xml = ET.parse(saved).getroot()
        cases = xml.findall('testcase')
        assert int(xml.attrib['tests']) == len(cases) > 0
        for name in ('failures', 'errors', 'skipped'):
            assert int(xml.attrib[name]) == 0
        assert not any(case.find(name) is not None for case in cases for name in ('failure', 'error', 'skipped'))
        for source, target in [('tests', 'Tests'), ('failures', 'Failures'), ('errors', 'Errors'), ('skipped', 'Skipped')]:
            totals[target] += int(xml.attrib[source])
        totals['Testcases'] += len(cases)
    assert totals['Tests'] == count
    summaries.append(dict(Task=task, Suites=len(files), **totals))

inputs = read_json(RUN / 'execution-input.json')
for entry in inputs:
    assert sha(Path(entry['Path'])) == entry['Sha256'], f"Input changed: {entry['Path']}"
baseline = read_json(ENTRY / 'baseline-input.json')
changes = []
for entry in baseline:
    path = ROOT / entry['Path']
    assert path.exists(), f"Baseline input missing: {entry['Path']}"
    assert sha(ENTRY / 'source' / entry['Path']) == entry['Sha256'], 'Baseline snapshot changed'
    if sha(path) != entry['Sha256']:
        changes.append(entry['Path'])
baseline_paths = {entry['Path'].replace('\\', '/') for entry in baseline}
new_inputs = [str(Path(entry['Path']).relative_to(ROOT)).replace('\\', '/') for entry in inputs
              if str(Path(entry['Path']).relative_to(ROOT)).replace('\\', '/') not in baseline_paths]
assert git('branch', '--show-current').strip() == 'main'
assert git('rev-parse', 'HEAD').strip() == 'cb3a5ddadd494881668343325ac6e4f32097aea7'
assert not git('diff', '--cached', '--name-only').strip()
git('diff', '--check')
git('diff', '--cached', '--check')
status = git('status', '--short')
full_diff = git('diff', '--')
untracked = git('ls-files', '--others', '--exclude-standard').splitlines()
for name in set(changes + new_inputs + untracked):
    path = ROOT / name
    if path.suffix in {'.java', '.md', '.ps1', '.py', '.json'}:
        content = path.read_text(encoding='utf-8-sig')
        assert not re.search(r'^(<<<<<<<|>>>>>>>)(?: |$)', content, re.M), f'Conflict marker: {name}'
        assert content.endswith('\n'), f'Missing final newline: {name}'
        assert not any(line.rstrip() != line for line in content.splitlines()) or name in baseline_paths, f'New whitespace issue: {name}'

limited = []
for label in ['baseline', 'single', 'wal-r2', 'core-r2', 'boundary', 'contract', 'lifecycle']:
    evidence = RUNS / ('phase15-C-1-20260922-' + label)
    result = read_json(evidence / 'results.json')
    assert result['Exit'] == 0
    rows = result['Suites']
    assert all(row['Failures'] == row['Errors'] == row['Skipped'] == 0 for row in rows)
    limited.append(dict(Run=label, Tests=sum(row['Tests'] for row in rows), Exit=0))

stopped = read_json(RUNS / 'phase15-C-1-20260922-mysql-stop/shutdown-verification.json')
assert stopped['SqlExit'] == 0 and not stopped['Port3307Listening']
assert {item['LocalPort']: item['OwningProcess'] for item in stopped['OriginalListeners']} == {3306: 6860, 6379: 4804}
OUT.mkdir()
(OUT / 'final.diff').write_text(full_diff, encoding='utf-8')
(OUT / 'git-status.txt').write_text(status, encoding='utf-8')
manifest_paths = set(changes + new_inputs + untracked + [
    'scripts/prepare-phase15-c1.ps1', 'scripts/start-phase15-c-mysql.ps1',
    'scripts/stop-phase15-c-mysql.ps1', 'scripts/audit-phase15-c1.py', 'scripts/phase15-c-budget.json'])
manifest = {name: sha(ROOT / name) for name in sorted(manifest_paths)}
report = dict(Utc=datetime.now(timezone.utc).isoformat(), Branch='main',
              Head='cb3a5ddadd494881668343325ac6e4f32097aea7', StagedCount=0,
              Regression=summaries, GradleExit=0, RunnerExit=0, ExecutionInputCount=len(inputs),
              ExecutionInputChanges=0, BaselineFiles=len(baseline), BaselineSnapshotChanges=0,
              C1ChangedBaselineFiles=changes, C1NewExecutionInputs=new_inputs, LimitedRuns=limited,
              BaselineUnchangedCount=len(baseline)-len(changes), UntrackedFiles=untracked,
              FinalInputHashes=manifest, UserChangesPreserved=True, LegacyCharge=LEGACY,
              AncillaryCharge=ANCILLARY, ApprovedCap=CAP, ChargeBeforeAudit=charge_before,
              ChargeAfterArtifacts=owned_size()+LEGACY+ANCILLARY, FreeBytes=shutil.disk_usage('E:/').free,
              SqlShutdown=stopped, C2Started=False)
(OUT / 'final-audit.json').write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
print(json.dumps({key: report[key] for key in ['Regression', 'ExecutionInputChanges', 'BaselineUnchangedCount', 'ChargeAfterArtifacts', 'FreeBytes']}, ensure_ascii=False))
