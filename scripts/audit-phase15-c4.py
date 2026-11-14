"""최종 C-4 코드·fresh 회귀·비교 증거·배포 artifact의 독립 대조. 테스트나 부하를 기동하지 않음."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import difflib
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path.cwd()
OWNED=Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS=OWNED/'agent-runs'
C3=RUNS/'phase15-C-3-20260922-candidate-r3'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--regression',required=True)
    parser.add_argument('--analysis',required=True)
    parser.add_argument('--decision',required=True)
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    reg,analysis,decision,out=[Path(v).resolve() for v in (args.regression,args.analysis,args.decision,args.output)]
    assert all(p.is_relative_to(RUNS.resolve()) for p in (reg,analysis,decision,out))
    report=read(analysis); chosen=read(decision)
    comparison=RUNS/report['candidate']
    assert comparison.resolve().is_relative_to(RUNS.resolve())
    assert report['complete'] and report['completed']==15 and report['independentChecksPassed']
    assert report['targetLoadAdequate'], 'G-01: generator did not apply target load; final completion forbidden'
    assert chosen['adoption'] in ('single','group') and chosen['comparisonComplete']
    frozen=read(comparison/'candidate.json')
    assert report['session']==frozen['measurementSession']
    candidate=frozen['executionInputs']
    prior=read(C3/'candidate.json')['executionInputs']
    for location,inputs in ((comparison,candidate),(C3,prior)):
        assert all(sha(location/'source'/name)==digest for name,digest in inputs.items())
    first=RUNS/'phase15-C-4-20260923-candidate-r1'
    assert all(sha(first/'source'/name)==digest for name,digest in read(first/'candidate.json')['executionInputs'].items())
    current={Path(v['Path']).relative_to(ROOT).as_posix():v['Sha256'].lower() for v in read(reg/'execution-input.json')}
    assert all(sha(ROOT/name)==digest for name,digest in current.items())
    assert read(reg/'input-preservation.json')==dict(Count=len(current),Changed=0)
    for name in ('gradle-exit-code.txt','runner-exit-code.txt','storage-verification-exit.txt'):
        assert int((reg/name).read_text(encoding='utf-8-sig'))==0
    log=(reg/'gradle.log').read_text(encoding='utf-16')
    for task in ('test','redisIntegrationTest','mysqlIntegrationTest','bootJar'):
        assert any(line.strip()=='> Task :'+task for line in log.splitlines()),task
    assert '11 actionable tasks: 11 executed' in log
    started=datetime.fromisoformat((reg/'started-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
    totals={}
    for task in ('test','redisIntegrationTest','mysqlIntegrationTest'):
        saved=list((reg/task).glob('*.xml'))
        assert saved and {p.name for p in saved}=={p.name for p in (OWNED/'build/test-results'/task).glob('*.xml')}
        tests=0
        for p in saved:
            raw=OWNED/'build/test-results'/task/p.name
            hashes=read(p.with_name(p.name+'.hash.json'))
            assert sha(p)==hashes['SavedSha256'].lower() and sha(raw)==hashes['RawSha256'].lower()
            assert raw.stat().st_mtime>=started
            suite=ET.parse(p).getroot(); cases=suite.findall('testcase')
            assert int(suite.get('tests'))==len(cases)>0
            assert all(int(suite.get(k))==0 for k in ('failures','errors','skipped'))
            assert not any(c.find(k) is not None for c in cases for k in ('failure','error','skipped'))
            tests+=len(cases)
        totals[task]=dict(suites=len(saved),tests=tests,failures=0,errors=0,skipped=0)
    changed=sorted(n for n in candidate if n in current and candidate[n]!=current[n])
    deleted=sorted(set(candidate)-set(current))
    added=sorted(set(current)-set(candidate))
    if chosen['adoption']=='single':
        forbidden=('GroupPixelWriteExecutor','BatchWriteGuard','BatchWriteResult','BatchWriteOutcome','PixelWriteUnknownException')
        assert not any((ROOT/'src/main/java/dev/cgt/pixelplace/pixel/application'/f'{n}.java').exists() for n in forbidden)
        for name in ('SinglePixelWriteExecutor.java','PixelCommandService.java','PixelUserWriteGate.java'):
            name='src/main/java/dev/cgt/pixelplace/pixel/application/'+name
            assert current[name]==candidate[name],name
        core='src/main/java/dev/cgt/pixelplace/pixel/application/PixelWriteService.java'
        old=(comparison/'source'/core).read_text(encoding='utf-8')
        new=(ROOT/core).read_text(encoding='utf-8')
        start='    public synchronized PixelWriteResult writePixel('
        assert old[old.index(start):old.index('    /** caller')].strip()==new[new.index(start):new.index('    static void validateWriteRequest(')].strip()
        tail='    static void validateWriteRequest('
        assert old[old.index(tail):]==new[new.index(tail):]
    jar=OWNED/'build/libs/pixel-place-0.0.1-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        names=archive.namelist()
        prohibited=[n for n in names if 'dev/cgt/benchmark/' in n or any(v in n.lower() for v in ('junit','mockito','devtools'))]
        testclasses={p.stem+'.class' for tree in (ROOT/'src/test/java',comparison/'source/src/test/java') for p in tree.rglob('*.java')}
        prohibited += [n for n in names if n.startswith('BOOT-INF/classes/') and Path(n).name in testclasses]
        if chosen['adoption']=='single':
            prohibited += [n for n in names if any('/'+v in n for v in forbidden)]
        assert not prohibited,prohibited
    ledger=read(sorted((RUNS/'phase15-C-4-budget-ledger').glob('*.json'))[-1])
    owned=sum(p.stat().st_size for p in OWNED.rglob('*') if p.is_file())
    charge=ledger['chargedBytes']+max(0,owned-ledger['ownedBytes'])
    reserve=128*1024**2; free=shutil.disk_usage('E:/').free
    assert charge+reserve<=79456894976 and free-reserve>=5*1024**3
    out.mkdir()
    shutil.copyfile(jar,out/'final-boot.jar')
    assert sha(out/'final-boot.jar')==sha(jar)
    for name,digest in current.items():
        target=out/'source'/name;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/name,target);assert sha(target)==digest
    diff=[]
    for name in sorted(set(changed+added+deleted)):
        old=(comparison/'source'/name).read_text(encoding='utf-8-sig').splitlines(True) if name in candidate else []
        new=(ROOT/name).read_text(encoding='utf-8-sig').splitlines(True) if name in current else []
        if new:
            assert (ROOT/name).read_bytes().endswith(b'\n'),name
            assert not any(line.startswith(('<<<<<<< ','=======','>>>>>>> ')) for line in new),name
        diff.extend(difflib.unified_diff(old,new,fromfile='comparison/'+name,tofile='final/'+name))
    (out/'final-only.diff').write_text(''.join(diff),encoding='utf-8')
    for name,command in [('diff-check',['diff','--check']),('cached-check',['diff','--cached','--check']),
            ('full-diff',['diff','--']),('status',['status','--short','--untracked-files=all']),
            ('head',['rev-parse','HEAD']),('branch',['branch','--show-current']),('worktrees',['worktree','list'])]:
        result=subprocess.run(['git','-c','core.quotepath=false','-c','core.safecrlf=false',*command],capture_output=True,encoding='utf-8')
        (out/(name+'.txt')).write_text(result.stdout+result.stderr,encoding='utf-8')
        assert result.returncode==0
    assert (out/'head.txt').read_text().strip()=='cb3a5ddadd494881668343325ac6e4f32097aea7'
    assert (out/'branch.txt').read_text().strip()=='main'
    staged=subprocess.run(['git','diff','--cached','--name-only'],capture_output=True,encoding='utf-8',check=True)
    assert not staged.stdout.strip(), 'Staged user state must remain empty'
    for name in ('report-phase15-c4.py','render-phase15-c4.py','audit-phase15-c4.py','update-phase15-c4-progress.py',
                 'check-phase15-c4-environment.ps1','Phase15C4DatabaseAudit.java','test-phase15-c-budget.ps1',
                 'prepare-phase15-c4.py','seal-phase15-c4.py','save-phase15-c4-results.ps1','inspect-phase15-c4.py',
                 'Phase15C4PacingAudit.java','check-phase15-c4-pacing.ps1','save-phase15-c4-r2-plan.ps1',
                 'decide-phase15-c4.py','finalize-phase15-single.py'):
        shutil.copyfile(ROOT/'scripts'/name,out/name)
    for name in ('docs/pixel-place-specs.md','docs/pixel-place-details.md'):
        saved=out/name;saved.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,saved)
    result=dict(savedAt=datetime.now(timezone.utc).isoformat(),regression=totals,
        regressionEvidence=str(reg),analysisEvidence=str(analysis),analysisSha256=sha(analysis),decision=chosen,
        executionInputs=current,inputCount=len(current),identitySha256=hashlib.sha256(json.dumps(current,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
        comparisonCandidatePreserved=True,c3CandidatePreserved=True,firstC4CandidatePreserved=True,
        singleCoreAndCommandSemanticsPreserved=True,changed=changed,deleted=deleted,added=added,
        bootJar=dict(path=str(jar),sha256=sha(jar),prohibited=[]),
        budget=dict(chargedBytes=charge,maximumBytes=79456894976,freeBytes=free,reservedClosingEvidenceBytes=reserve))
    (out/'audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('executionInputs','changed','deleted','added','decision')},ensure_ascii=False))


if __name__=='__main__':
    main()
