"""C-2 현재 입력과 fresh XML/실행/종료/보존 증거의 독립 대조. 새 감사 경로만 기록."""
from pathlib import Path
from datetime import datetime, timezone
import difflib
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OWNED = Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS = OWNED / 'agent-runs'
RUN = RUNS / 'phase15-C-2-20260922-regression-r2'
ENTRY = RUNS / 'phase15-C-2-20260922-entry'
OUT = RUNS / 'phase15-C-2-20260922-final-audit'
CAP, LEGACY, ANCILLARY, FLOOR = 38654705664, 2824498477, 67108864, 27739610021


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def actual_charge():
    return sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file()) + LEGACY + ANCILLARY


def git(*args):
    return subprocess.run(['git', '-c', 'core.quotePath=false', '-c', 'core.safecrlf=false', *args],
                          capture_output=True, encoding='utf-8', check=True).stdout


budget = read_json(ROOT / 'scripts/phase15-c-budget.json')
assert (budget['maximumBytes'], budget['legacyChargeBytes'], budget['ancillaryChargeBytes'],
        budget['carriedChargeBytes'], budget['minimumFreeBytes']) == (CAP, LEGACY, ANCILLARY, FLOOR, 5 * 1024**3)
charge_before = max(FLOOR, actual_charge())
assert charge_before + 64 * 1024**2 <= CAP
assert shutil.disk_usage('E:/').free - 64 * 1024**2 >= 5 * 1024**3
assert not OUT.exists(), 'Existing audit must remain immutable'
for name in ['gradle-exit-code.txt', 'runner-exit-code.txt', 'storage-verification-exit.txt']:
    assert int((RUN / name).read_text(encoding='utf-8-sig')) == 0, name
inputs = read_json(RUN / 'execution-input.json')
assert len(inputs) == 309
current_inputs = {p.resolve() for p in (ROOT / 'src').rglob('*')
                  if p.is_file() and p.suffix in {'.java', '.yml', '.yaml', '.json'}}
current_inputs |= {ROOT / name for name in ['build.gradle', 'settings.gradle', 'pixel_place.sql',
                                         'scripts/test-phase15.ps1', 'scripts/Phase15RegressionDatabase.java',
                                         'scripts/phase15-c-budget.json']}
assert current_inputs == {Path(entry['Path']) for entry in inputs}, 'Execution input set changed'
assert read_json(RUN / 'input-preservation.json') == {'Count': len(inputs), 'Changed': 0}
started = datetime.fromisoformat((RUN / 'started-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
finished = datetime.fromisoformat((RUN / 'finished-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
log_bytes = (RUN / 'gradle.log').read_bytes()
log = log_bytes.decode('utf-16' if log_bytes.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')
assert 'BUILD SUCCESSFUL' in log
summaries = []
for task, count in [('test', 1184), ('redisIntegrationTest', 2), ('mysqlIntegrationTest', 34)]:
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
        assert all(int(xml.attrib[name]) == 0 for name in ('failures', 'errors', 'skipped'))
        assert not any(case.find(name) is not None for case in cases for name in ('failure', 'error', 'skipped'))
        for source, target in [('tests', 'Tests'), ('failures', 'Failures'), ('errors', 'Errors'), ('skipped', 'Skipped')]:
            totals[target] += int(xml.attrib[source])
        totals['Testcases'] += len(cases)
    assert totals['Tests'] == count, (task, totals)
    summaries.append(dict(Task=task, Suites=len(files), **totals))

for entry in inputs:
    assert sha(Path(entry['Path'])) == entry['Sha256'], f"Input changed: {entry['Path']}"
baseline = read_json(ENTRY / 'baseline-input.json')
changes, c2_diff = [], []
for entry in baseline:
    name = entry['Path'].replace('\\', '/')
    path, before = ROOT / name, ENTRY / 'source' / name
    assert path.exists(), f'Baseline input missing: {name}'
    assert sha(before) == entry['Sha256'], f'Baseline snapshot changed: {name}'
    if sha(path) != entry['Sha256']:
        changes.append(name)
        c2_diff.extend(difflib.unified_diff(before.read_text(encoding='utf-8-sig').splitlines(True),
                                          path.read_text(encoding='utf-8-sig').splitlines(True),
                                          fromfile='C1/' + name, tofile='C2/' + name))
baseline_paths = {entry['Path'].replace('\\', '/') for entry in baseline}
new_inputs = [Path(entry['Path']).relative_to(ROOT).as_posix() for entry in inputs
              if Path(entry['Path']).relative_to(ROOT).as_posix() not in baseline_paths]
allowed_changes = {
    'docs/pixel-place-details.md', 'docs/pixel-place-specs.md', '작업기록/phase-15-progress.md',
    'scripts/test-phase15.ps1', 'scripts/phase15-c-budget.json',
    *('src/main/java/dev/cgt/pixelplace/pixel/application/' + name + '.java' for name in
      ['BatchWriteGuard', 'PixelWriteExecutor', 'SinglePixelWriteExecutor', 'WriteExecutionConfiguration', 'WriteExecutionProperties']),
    'src/main/java/dev/cgt/pixelplace/pixel/web/PixelController.java',
    *('src/test/java/dev/cgt/pixelplace/pixel/application/' + name + '.java' for name in
      ['PixelCommandRedisIntegrationTest', 'SinglePixelWriteExecutorTest', 'WriteExecutionConfigurationTest']),
    'src/test/java/dev/cgt/pixelplace/pixel/websocket/PixelWebSocketEmbeddedTest.java',
}
assert set(changes) <= allowed_changes, set(changes) - allowed_changes
assert set(new_inputs) == {
    *('src/main/java/dev/cgt/pixelplace/pixel/application/' + name + '.java' for name in
      ['GroupPixelWriteExecutor', 'ExecutionSnapshot', 'PixelWriteUnknownException']),
    'src/test/java/dev/cgt/pixelplace/pixel/application/GroupPixelWriteExecutorTest.java',
    'src/test/java/dev/cgt/pixelplace/wal/infra/GroupWriteStorageTest.java',
    'src/test/java/dev/cgt/pixelplace/auth/config/GroupPixelMvcTest.java',
}
for name in new_inputs:
    c2_diff.extend(difflib.unified_diff([], (ROOT / name).read_text(encoding='utf-8-sig').splitlines(True),
                                      fromfile='/dev/null', tofile='C2/' + name))
predecessor = read_json(RUNS / 'phase15-C-1-20260922-final-audit/final-audit.json')
allowed_helpers = {'scripts/start-phase15-c-mysql.ps1', 'scripts/stop-phase15-c-mysql.ps1'}
for name, digest in predecessor['FinalInputHashes'].items():
    if name.replace('\\', '/') not in allowed_changes | allowed_helpers:
        assert sha(ROOT / name) == digest, f'Unrelated predecessor change: {name}'
assert git('rev-parse', '--is-inside-work-tree').strip() == 'true'
assert git('branch', '--show-current').strip() == 'main'
assert git('rev-parse', 'HEAD').strip() == 'cb3a5ddadd494881668343325ac6e4f32097aea7'
assert len(re.findall(r'^worktree ', git('worktree', 'list', '--porcelain'), re.M)) == 1
assert not git('diff', '--cached', '--name-only').strip()
git('diff', '--check')
git('diff', '--cached', '--check')
status, full_diff = git('status', '--short'), git('diff', '--')
untracked = git('ls-files', '--others', '--exclude-standard').splitlines()
helpers = ['scripts/prepare-phase15-c2.ps1', 'scripts/start-phase15-c-mysql.ps1',
           'scripts/stop-phase15-c-mysql.ps1', 'scripts/audit-phase15-c2.py']
for name in set(changes + new_inputs + untracked + helpers):
    path = ROOT / name
    if path.suffix in {'.java', '.md', '.ps1', '.py', '.json'}:
        content = path.read_text(encoding='utf-8-sig')
        assert not re.search(r'^(<<<<<<<|>>>>>>>)(?: |$)', content, re.M), f'Conflict marker: {name}'
        assert content.endswith('\n'), f'Missing final newline: {name}'
        if name in new_inputs + helpers:
            assert not any(line.rstrip() != line for line in content.splitlines()), f'New whitespace: {name}'

limited = []
for label in ['worker-r3', 'storage-lifecycle-r3', 'connections-r2', 'final-contract', 'review-fixes', 'worker-clock']:
    result = read_json(RUNS / ('phase15-C-2-20260922-' + label) / 'results.json')
    assert result['Exit'] == result['GradleExit'] == 0
    assert all(row['Failures'] == row['Errors'] == row['Skipped'] == 0 for row in result['Suites'])
    limited.append(dict(Run=label, Tests=sum(row['Tests'] for row in result['Suites']), Exit=0))
stopped = read_json(RUNS / 'phase15-C-2-20260922-mysql-stop/shutdown-verification.json')
assert stopped['SqlExit'] == 0 and not stopped['Port3307Listening']
assert {item['LocalPort']: item['OwningProcess'] for item in stopped['OriginalListeners']} == {3306: 6860, 6379: 4804}
OUT.mkdir()
(OUT / 'final.diff').write_text(full_diff, encoding='utf-8')
(OUT / 'c2-only.diff').write_text(''.join(c2_diff), encoding='utf-8')
(OUT / 'git-status.txt').write_text(status, encoding='utf-8')
manifest_paths = set(changes + new_inputs + untracked + helpers)
manifest = {name: sha(ROOT / name) for name in sorted(manifest_paths)}
for name in {Path(entry['Path']).relative_to(ROOT).as_posix() for entry in inputs} | set(changes + helpers):
    target = OUT / 'source' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / name, target)
report = dict(Utc=datetime.now(timezone.utc).isoformat(), Branch='main',
              Head='cb3a5ddadd494881668343325ac6e4f32097aea7', StagedCount=0,
              Regression=summaries, GradleExit=0, RunnerExit=0, ExecutionInputCount=len(inputs),
              ExecutionInputChanges=0, BaselineFiles=len(baseline), BaselineSnapshotChanges=0,
              C2ChangedBaselineFiles=changes, C2NewExecutionInputs=new_inputs, LimitedRuns=limited,
              BaselineUnchangedCount=len(baseline)-len(changes), UntrackedFiles=untracked,
              FinalInputHashes=manifest, UserChangesPreserved=True, LegacyCharge=LEGACY,
              AncillaryCharge=ANCILLARY, ApprovedCap=CAP, CarriedFloor=FLOOR, ChargeBeforeAudit=charge_before,
              ChargeAfterArtifacts=max(FLOOR, actual_charge()), FreeBytes=shutil.disk_usage('E:/').free,
              SqlShutdown=stopped, C3Started=False)
assert report['ChargeAfterArtifacts'] <= CAP and report['FreeBytes'] >= 5 * 1024**3
(OUT / 'final-audit.json').write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
print(json.dumps({key: report[key] for key in ['Regression', 'ExecutionInputChanges', 'BaselineUnchangedCount',
                                             'ChargeAfterArtifacts', 'FreeBytes']}, ensure_ascii=False))
