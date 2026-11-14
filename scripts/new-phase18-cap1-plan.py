"""등록 CAP-1 profile을 기존 현장 Plan 생성기에 연결. 서비스 기동 없음."""
import argparse,json,sys,time,subprocess,hashlib
from pathlib import Path
from pixel_place_paths import historical, evidence_path, plain_absolute
from phase18_cap1 import validate_profile

def read(path):return json.loads(historical(path).read_text(encoding='utf-8-sig'))
def sha(path):
    with historical(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def apply_profile(p,policy,profile,issued):
    p['bounds'].update(writeTerminals=profile['requests'],readTerminals=1,users=profile['users'],
        wsEvents=profile['events'],walBytes=profile['storage']['limits']['walBytes'])
    p.update(schemaVersion=4 if profile['kind']=='cap1-causal-memory-v2' else 3,phase='CAP',stage='CAP-1')
    p['bounds'].update(totalSeconds=profile['totalSeconds'],samples=profile['limits']['samples'],buildBytes=profile['limits']['workspaceBytes'],
        outputBytes=profile['limits']['runBytes'],databaseBytes=profile['storage']['limits']['databaseBytes'],binlogBytes=profile['storage']['limits']['binlogBytes'])
    c=p['cases'][0];c.update(caseId='cap1-w-spread',role=profile['role'],kind='write')
    c['workload'].update(writeRate=profile['writeRate'],readRate=0,wsConnections=100,seed=15,
        warmupSeconds=30,measurementSeconds=profile['measurementSeconds'],writeInFlight=128,readInFlight=4,
        requestMillis=10000,drainMillis=180000,inputTolerancePpm=1000,inputWindowSeconds=10,
        userMode='reuse',userPool=profile['users']-1,bootstrap=True,simultaneousSeconds=0,
        schedule=dict(kind='fixed-rate',activeUsers=0,writePeriodSeconds=200,readPeriodSeconds=50,
            issuedEpochMillis=issued['issuedEpochMillis'],expiresEpochMillis=issued['expiresEpochMillis']))
    policy.update(maxHeapBytes=1610612736,maxBacklogRecords=min(200000,profile['writeRate']*10+128),fixture='none',
        maxSampleBytes=profile['limits']['sampleBytes'],maxTraceBytes=profile['limits']['traceBytes'],
        maxTraceEntries=profile['limits']['traceEntries'])
    return p,policy

def main():
    a=argparse.ArgumentParser()
    for key in ('environment','predecessor','issued','run-id'):a.add_argument('--'+key,required=True)
    args=a.parse_args();repo=Path(__file__).resolve().parent.parent
    predecessor=read(args.predecessor);profile=validate_profile(predecessor['cap1Profile'])
    issued=read(args.issued);env=read(args.environment);out=plain_absolute(args.environment).parent
    if out!=evidence_path(env["sessionId"]):raise ValueError("CAP1 evidence session ownership")
    if not issued['issuedEpochMillis']<=time.time()*1000<issued['expiresEpochMillis']:raise ValueError('CAP1 original expiry')
    if issued['expiresEpochMillis']-issued['issuedEpochMillis']!=profile['totalSeconds']*1000:raise ValueError('CAP1 frozen duration')
    if issued['Stage']!='CAP-1' or issued['Policy']['sha256']!=sha(args.predecessor):raise ValueError('CAP1 issuance binding')
    if env['evidenceBytes']!=profile['limits']['evidenceBytes']:raise ValueError('CAP1 environment evidence binding')
    template=historical(repo/'build/agent-runs/phase18-cf-smoke-20261003-1540')
    p=read(template/'plan-cf-smoke.json');policy=read(template/'policy-cf-smoke.json')
    p,policy=apply_profile(p,policy,profile,issued)
    for name,data in [('cap-template.json',p),('cap-policy-template.json',policy)]:
        with (out/name).open('x',encoding='utf-8') as f:json.dump(data,f,indent=2)
    return subprocess.call([sys.executable,'-B',str(repo/'scripts/new-phase18-plan.py'),'--template',str(out/'cap-template.json'),
        '--environment',args.environment,'--predecessor',args.predecessor,'--run-id',args.run_id,
        '--label','cap','--policy-template',str(out/'cap-policy-template.json')])
if __name__=='__main__':raise SystemExit(main())
