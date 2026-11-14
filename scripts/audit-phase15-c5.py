"""C5 입력 집합·fresh 회귀·배포 artifact 대조. 실행이나 채택을 대신하지 않는 증거 도구."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path.cwd()
OWNED=Path('E:/pixel-place-phase15/phase15-a-20260918')
RUNS=OWNED/'agent-runs'

def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path,value):
    with Path(path).open('x',encoding='utf-8') as file: json.dump(value,file,ensure_ascii=False,indent=2);file.write('\n')

def inputs():
    # PowerShell 목록과 독립적으로 열거해 양쪽의 입력 누락을 대조
    paths={p.as_posix() for p in Path('src').rglob('*') if p.is_file()}
    paths.update(('build.gradle','settings.gradle','pixel_place.sql','gradlew','gradlew.bat',
        'gradle/wrapper/gradle-wrapper.properties','gradle/wrapper/gradle-wrapper.jar',
        'scripts/test-phase15.ps1','scripts/phase15-inputs.ps1','scripts/Phase15RegressionDatabase.java',
        'scripts/phase15-c-budget.json','scripts/measure-phase15.ps1','scripts/summarize-phase15.py',
        'scripts/test-phase15-analysis.py','scripts/phase15-c-input.json','scripts/phase15-c-matrix.json',
        'scripts/start-phase15-c-mysql.ps1','scripts/stop-phase15-c-mysql.ps1','scripts/Phase15OwnedDatabaseStop.java',
        'scripts/Phase15C4DatabaseAudit.java','scripts/harness-local-secrets.ps1','scripts/phase15-c4-input.json',
        'scripts/phase15-c-budget.ps1','scripts/test-phase15-c-budget.ps1',
        'scripts/report-phase15-c4.py','scripts/render-phase15-c4.py','scripts/decide-phase15-c4.py'))
    paths.update(p.as_posix() for p in Path('scripts').iterdir() if p.is_file() and 'phase15-c5' in p.name
                 and p.suffix in ('.ps1','.py','.json','.java'))
    return {name:sha(name) for name in sorted(paths)}

def snapshot(out,current):
    for name,digest in current.items():
        target=out/'source'/name;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/name,target);assert sha(target)==digest
    write(out/'inputs.json',dict(savedUtc=datetime.now(timezone.utc).isoformat(),files=current,count=len(current),
         identitySha256=hashlib.sha256(json.dumps(current,sort_keys=True,separators=(',',':')).encode()).hexdigest()))

def regression(reg,frozen,out):
    current=inputs(); prior=read(frozen/'inputs.json')['files'];assert current==prior
    for name in ('execution-input.json','execution-input-after.json'):
        rows=read(reg/name)
        actual={Path(r['Path']).relative_to(ROOT).as_posix():r['Sha256'].lower() for r in rows}
        assert len(rows)==len(actual) and actual==current, name
    preserved=read(reg/'input-preservation.json');assert preserved['Exact']
    assert preserved['Count']==preserved['AfterCount']==len(current)
    for name in ('gradle-exit-code.txt','runner-exit-code.txt','storage-verification-exit.txt'):
        assert int((reg/name).read_text(encoding='utf-8-sig'))==0,name
    log=(reg/'gradle.log').read_text(encoding='utf-16')
    for task in ('test','redisIntegrationTest','mysqlIntegrationTest','bootJar'):
        assert any(line.strip()=='> Task :'+task for line in log.splitlines()),task
    assert '11 actionable tasks: 11 executed' in log
    start=datetime.fromisoformat((reg/'started-utc.txt').read_text(encoding='utf-8-sig').strip()).timestamp()
    totals={}; case_index={}
    for task in ('test','redisIntegrationTest','mysqlIntegrationTest'):
        saved=list((reg/task).glob('*.xml'));raw_root=OWNED/'build/test-results'/task
        assert saved and {p.name for p in saved}=={p.name for p in raw_root.glob('*.xml')}
        count=0
        for file in saved:
            raw=raw_root/file.name; hashes=read(file.with_name(file.name+'.hash.json'))
            assert sha(file)==hashes['SavedSha256'].lower() and sha(raw)==hashes['RawSha256'].lower()
            assert raw.stat().st_mtime>=start
            suite=ET.parse(file).getroot();cases=suite.findall('testcase')
            assert int(suite.get('tests'))==len(cases)>0
            assert all(int(suite.get(k))==0 for k in ('failures','errors','skipped'))
            assert all(c.find(k) is None for c in cases for k in ('failure','error','skipped'))
            case_index[suite.get('name')]=[c.get('name') for c in cases];count+=len(cases)
        totals[task]=dict(suites=len(saved),tests=count,failures=0,errors=0,skipped=0)
    jar=OWNED/'build/libs/pixel-place-0.0.1-SNAPSHOT.jar'
    assert jar.stat().st_mtime>=start
    with zipfile.ZipFile(jar) as archive:
        names=archive.namelist();classes=sorted(n for n in names if n.startswith('BOOT-INF/classes/'))
        prohibited=[n for n in names if 'dev/cgt/benchmark/' in n or any(k in n.lower() for k in ('junit','mockito','devtools'))]
        testnames={p.stem+'.class' for p in Path('src/test/java').rglob('*.java')}
        prohibited += [n for n in classes if Path(n).name in testnames]
        assert not prohibited,prohibited
        for name in ('GroupPixelWriteExecutor','BatchWriteGuard','BatchWriteResult','PixelWriteUnknownException','SinglePixelWriteExecutor'):
            assert f'BOOT-INF/classes/dev/cgt/pixelplace/pixel/application/{name}.class' in names
        for name in ('application.yml','application-stub.yml'):
            assert archive.read('BOOT-INF/classes/'+name)==(ROOT/'src/main/resources'/name).read_bytes()
    shutil.copyfile(jar,out/'final-boot.jar');assert sha(jar)==sha(out/'final-boot.jar')
    write(out/'bootjar.json',dict(path=str(jar),sha256=sha(jar),bytes=jar.stat().st_size,classes=classes,prohibited=[]))
    write(out/'testcases.json',case_index)
    write(out/'regression.json',dict(savedUtc=datetime.now(timezone.utc).isoformat(),regression=str(reg),
         inputCount=len(current),inputIdentity=read(frozen/'inputs.json')['identitySha256'],
         totals=totals,actualTasks=11,gradleExit=0,runnerExit=0,storageExit=0,
         exactCurrentFrozenBeforeAfter=True,bootJarSha256=sha(jar)))
    print(json.dumps(dict(inputCount=len(current),totals=totals,bootJarSha256=sha(jar))))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--freeze',action='store_true')
    parser.add_argument('--frozen');parser.add_argument('--regression');parser.add_argument('--output',required=True)
    args=parser.parse_args();out=Path(args.output).resolve()
    assert out.is_relative_to(RUNS.resolve()) and not out.exists()
    out.mkdir()
    if args.freeze:
        snapshot(out,inputs());print(json.dumps(read(out/'inputs.json')|{'files':'saved'}))
    else: regression(Path(args.regression),Path(args.frozen),out)

if __name__=='__main__':main()
