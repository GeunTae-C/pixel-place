"""C-3 동결 입력·fresh XML·smoke·보존 A·배포 artifact의 독립 대조. 부하/DB 변경 없음."""
from pathlib import Path
from datetime import datetime, timezone
import difflib
import hashlib
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path.cwd()
OWNED=Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS=OWNED/'agent-runs'
OUT=RUNS/'phase15-C-3-20260922-final-audit-r3'
REG=RUNS/'phase15-C-3-20260922-regression-r3'
CAND=RUNS/'phase15-C-3-20260922-candidate-r3'
ENTRY=RUNS/'phase15-C-3-20260922-entry'
CAP,FLOOR,EXTERNAL=38654705664,31123978717,2891607341


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(name,args):
    result=subprocess.run(args,capture_output=True,encoding='utf-8',errors='replace',env=os.environ)
    (OUT/(name+'.log')).write_text(result.stdout+result.stderr,encoding='utf-8')
    (OUT/(name+'-exit.txt')).write_text(str(result.returncode)+'\n',encoding='utf-8')
    assert result.returncode==0,(name,result.returncode)
    return result.stdout


def git(name,*args):
    return run(name,['git','-c','core.quotepath=false','-c','core.safecrlf=false',*args])


owned=sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
charge=max(FLOOR,owned+EXTERNAL)
assert charge+64*1024**2<=CAP and shutil.disk_usage('E:/').free-64*1024**2>=5*1024**3
OUT.mkdir()
os.environ['TMP']=str(OWNED/'tmp')
os.environ['TEMP']=str(OWNED/'tmp')
os.environ['PYTHONDONTWRITEBYTECODE']='1'
report={'savedAt':datetime.now(timezone.utc).isoformat()}
candidate=read(CAND/'candidate.json')
hashes=candidate['executionInputs']
assert len(hashes)==320
assert all(sha(ROOT/name)==digest==sha(CAND/'source'/name) for name,digest in hashes.items())
reg_inputs={Path(row['Path']).relative_to(ROOT).as_posix():row['Sha256'].lower() for row in read(REG/'execution-input.json')}
assert reg_inputs==hashes
report['candidate']={'count':len(hashes),'identity':candidate['identitySha256'],'allCurrentAndSnapshotsMatch':True}

for filename in ['gradle-exit-code.txt','runner-exit-code.txt','storage-verification-exit.txt']:
    assert int((REG/filename).read_text(encoding='utf-8-sig'))==0
raw_log=(REG/'gradle.log').read_text(encoding='utf-16')
for task in ['test','redisIntegrationTest','mysqlIntegrationTest','bootJar']:
    assert any(line.strip()=='> Task :'+task for line in raw_log.splitlines()),task
assert '11 actionable tasks: 11 executed' in raw_log
totals={}
for task,expected in [('test',1194),('redisIntegrationTest',2),('mysqlIntegrationTest',38)]:
    saved=list((REG/task).glob('*.xml'))
    assert {p.name for p in saved}=={p.name for p in (OWNED/'build/test-results'/task).glob('*.xml')}
    tests=0
    for path in saved:
        raw=OWNED/'build/test-results'/task/path.name
        digest=read(path.with_name(path.name+'.hash.json'))
        assert sha(path)==digest['SavedSha256'].lower() and sha(raw)==digest['RawSha256'].lower()
        suite=ET.parse(path).getroot()
        cases=suite.findall('testcase')
        assert int(suite.get('tests'))==len(cases)>0
        assert all(int(suite.get(k))==0 for k in ['failures','errors','skipped'])
        assert not any(case.find(k) is not None for case in cases for k in ['failure','error','skipped'])
        started=datetime.fromisoformat((REG/'started-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
        assert raw.stat().st_mtime>=started
        tests+=len(cases)
    assert tests==expected
    totals[task]={'suites':len(saved),'tests':tests,'testcases':tests,'failures':0,'errors':0,'skipped':0,'rawAndSavedHashesMatch':True}
report['regression']=totals

run('python-contract',[sys.executable,'scripts/test-phase15-analysis.py'])
run('matrix',[sys.executable,'scripts/summarize-phase15.py','--validate-matrix','scripts/phase15-c-matrix.json'])
run('smoke-analysis',[sys.executable,'scripts/summarize-phase15.py','--family','smoke','--session','c3r320260922','--output',str(OUT/'smoke-analysis.json')])
run('a21-analysis',[sys.executable,'scripts/summarize-phase15.py','--family','A','--session','e20260918','--output',str(OUT/'a21-analysis.json')])
smokes=[]
for path in sorted(RUNS.glob('phase15-C-3-r*/manifest.json')):
    manifest=read(path)
    if manifest['session']!='c3r320260922': continue
    for name,digest in manifest['sourceIdentity'].items():
        assert hashes[name]==digest.lower()
    recovery=read(path.parent/'recovery-check.json')
    runtime=read(path.parent/'runtime.json')
    assert recovery['schemaSeedUsersReexecuted'] is False and recovery['newJvmPid']!=runtime['pid']
    for file in ['redis-cleanup.json','redis-recovery-cleanup.json']:
        assert (path.parent/file).is_file()
    smokes.append({'evidence':str(path.parent),'mode':manifest['writeMode'],'trialPid':runtime['pid'],
                   'recoveryPid':recovery['newJvmPid'],'walCounts':read(path.parent/'server-metrics.json')['walCounts']})
assert len(smokes)==2
report['smokes']=smokes
jar=OWNED/'build/libs/pixel-place-0.0.1-SNAPSHOT.jar'
with zipfile.ZipFile(jar) as archive:
    names=archive.namelist()
    prohibited=[n for n in names if 'dev/cgt/benchmark/' in n or any(t in n.lower() for t in ['junit','mockito','devtools'])]
    test_classes={p.stem+'.class' for p in (ROOT/'src/test/java').rglob('*.java')}
    prohibited += [n for n in names if n.startswith('BOOT-INF/classes/') and Path(n).name in test_classes]
    assert not prohibited,prohibited
report['bootJar']={'path':str(jar),'sha256':sha(jar),'entries':len(names),'prohibited':[]}

baseline=read(ENTRY/'baseline-input.json')
before={row['Path']:row['Sha256'].lower() for row in baseline}
assert all(sha(ENTRY/'source'/name)==digest for name,digest in before.items())
changed=[name for name,digest in before.items() if sha(ROOT/name)!=digest]
added=sorted(set(hashes)-set(before))
diff=[]
for name in sorted(set(changed+added)):
    old=(ENTRY/'source'/name).read_text(encoding='utf-8-sig').splitlines(True) if name in before else []
    new=(ROOT/name).read_text(encoding='utf-8-sig').splitlines(True)
    diff.extend(difflib.unified_diff(old,new,fromfile='C2/'+name,tofile='C3/'+name))
    assert (ROOT/name).read_bytes().endswith(b'\n'),name
    assert not any(line.startswith(('<<<<<<< ','=======','>>>>>>> ')) for line in new),name
(OUT/'c3-only.diff').write_text(''.join(diff),encoding='utf-8')
report['changedFromC2']=sorted(changed)
report['addedExecutionInputs']=added
report['unchangedBaselineFiles']=len(before)-len(changed)
git('diff-check','diff','--check')
git('cached-diff-check','diff','--cached','--check')
assert not git('cached-names','diff','--cached','--name-only').strip()
git('full-diff','diff','--')
git('status','status','--short','--untracked-files=all')
report['branch']=git('branch','branch','--show-current').strip()
report['head']=git('head','rev-parse','HEAD').strip()
assert report['branch']=='main' and report['head']=='cb3a5ddadd494881668343325ac6e4f32097aea7'
git('worktrees','worktree','list')
peaks=[charge,read(REG/'storage-budget.json')['TotalCharged']]
for p in RUNS.glob('phase15-C-3-invoke*/*-budget-after.json'):
    peaks.append(read(p)['ChargedBytes'])
actual=sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())+EXTERNAL
high=max(FLOOR,actual,*peaks)
report['budget']={'actualCharge':actual,'conservativeCharge':high,'cap':CAP,'remaining':CAP-high,'freeBytes':shutil.disk_usage('E:/').free,'reservedFinalEvidenceBytes':1048576}
assert high+1048576<=CAP and report['budget']['freeBytes']>=5*1024**3
report['helperSha256']=sha(Path(__file__))
(OUT/'audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ['changedFromC2','addedExecutionInputs','smokes']},ensure_ascii=False))
print('CHANGED '+json.dumps(report['changedFromC2']+report['addedExecutionInputs'],ensure_ascii=False))
