"""CAP-0 등록 S1/S2만 새 template과 source-bound plan/policy로 동결. 서비스 실행 없음."""
import argparse
import importlib.util
import json
from pathlib import Path
from pixel_place_paths import historical, evidence_path, plain_absolute
import subprocess
import sys
import time
import hashlib

S2_ROW_BYTES=6144
from phase18_cap_budget import expand_limit

def s2_usage(total_millis=2400000,cleanup_millis=180000):
    # 최초 만료 + 유한 cleanup을 각 JVM에 보수 적용. 순차 initial/recovery의 권리 합산 아님.
    millis=total_millis+cleanup_millis
    seconds=(millis+999)//1000
    samples=2+millis//250
    # 무작업 flush 4/cycle 외 쓰기4건·startup/recovery·close/WAL에 2048개 여유.
    journal=4*seconds+2048
    trace=4*seconds*512+2048*2048+512*1024
    observation=trace+1048576
    # WS state10+clock1/s, 측정4 VU 50ms polling, HTTP/WS/phase 제어 여유256 RPC.
    # 실제 k62.3.0의 RPC당10 point에 4096B, gauge/정의/종료에16MiB 여유.
    rpc=11*seconds+4*((220500+49)//50)+256
    metrics=rpc*4096+16*1024**2
    other=32*1024**2 # run 로그16MiB, 기록/정합성8MiB, manifest/마감8MiB
    run=2*(samples*S2_ROW_BYTES+observation)+metrics+other
    return dict(observationMillis=millis,requiredSamples=samples,sampleBytes=samples*S2_ROW_BYTES,
        traceEntries=journal,traceAggregateEntries=512,traceBytes=trace,observationBytes=observation,
        controlRpcs=rpc,metricsBytes=metrics,otherRunBytes=other,runBytes=run,evidenceBytes=run+64*1024**2)

def sha(path):
    with Path(path).open("rb") as stream:return hashlib.file_digest(stream,"sha256").hexdigest()

def s2_profile(repo):
    folder=historical(repo/"build/agent-runs/phase18-cf-smoke-20261003-1540")
    plan=json.loads((folder/"plan-cf-smoke.json").read_text(encoding="utf-8-sig"))
    policy=json.loads((folder/"policy-cf-smoke.json").read_text(encoding="utf-8-sig"))
    c=plan["cases"][0];w=c["workload"];o=c["observation"]
    if (w['wsConnections']!=2 or w['writeInFlight']!=4 or w['readInFlight']!=4 or c['runtime']['batchMax']!=16
            or o['sampleMillis']!=250 or w['readyMillis']!=90000 or w['controlMillis']!=30000 or o['stopAckMillis']!=2000
            or policy['maxTraceBytes']!=2097152 or policy['maxTraceEntries']!=6000 or plan['bounds']['logBytesPerStream']!=1048576):
        raise ValueError('Unsupported S2 template/profile')
    usage=s2_usage()
    bases=dict(maxSampleBytes=67108864,outputBytes=268435456,evidenceBytes=335544320,samples=10000,
        maxTraceBytes=2097152,maxTraceEntries=6000,metricsBytes=101188608)
    expected=dict(maxSampleBytes=usage['sampleBytes'],outputBytes=usage['runBytes'],evidenceBytes=usage['evidenceBytes'],
        samples=usage['requiredSamples'],maxTraceBytes=usage['traceBytes'],maxTraceEntries=usage['traceEntries'],metricsBytes=usage['metricsBytes'])
    limits={key:expand_limit(value,expected[key]) for key,value in bases.items()}
    return dict(schemaVersion=2,kind="cap0-s2-output-50pct-v1",planTemplate={"path":str(folder/"plan-cf-smoke.json"),"sha256":sha(folder/"plan-cf-smoke.json")},
        policyTemplate={"path":str(folder/"policy-cf-smoke.json"),"sha256":sha(folder/"policy-cf-smoke.json")},
        **limits,usage=usage,baseLimits=bases,sampleMillis=250,rowBytes=S2_ROW_BYTES,totalMillis=2400000,cleanupMillis=180000,
        measurementSeconds=220,drainMillis=180000,readyMillis=w["readyMillis"],controlMillis=w["controlMillis"],stopAckMillis=o["stopAckMillis"])

def read_s2_profile(path,repo):
    profile=json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if profile!=s2_profile(repo):raise ValueError("Frozen S2 profile/template mismatch")
    return profile


def main():
    parser=argparse.ArgumentParser()
    for name in ('environment','predecessor','issued','smoke','run-id'):parser.add_argument('--'+name,required=True)
    parser.add_argument('--k6',action='store_true')
    parser.add_argument('--s2-profile')
    args=parser.parse_args();require=args.smoke in ('S1','S2')
    if not require:raise ValueError('Only CAP-0 S1/S2')
    repo=Path(__file__).resolve().parent.parent
    issued=json.loads(Path(args.issued).read_text(encoding='utf-8-sig'))
    if not issued['issuedEpochMillis']<=time.time()*1000<issued['expiresEpochMillis'] or issued['expiresEpochMillis']-issued['issuedEpochMillis']!=2400000:raise ValueError('Original finite preparation expiry required')
    out=plain_absolute(args.environment).parent
    session=json.loads(Path(args.environment).read_text(encoding="utf-8-sig"))["sessionId"]
    if out!=evidence_path(session):raise ValueError("CAP evidence session ownership")
    plan=json.loads(historical(repo/'build/agent-runs/phase18-cf-smoke-20261003-1540/plan-cf-smoke.json').read_text(encoding='utf-8-sig'))
    policy=json.loads(historical(repo/'build/agent-runs/phase18-cf-smoke-20261003-1540/policy-cf-smoke.json').read_text(encoding='utf-8-sig'))
    plan.update(schemaVersion=3,phase='CAP',stage='CAP-0')
    plan['bounds'].update(totalSeconds=2400,samples=10000,outputBytes=33554432,databaseBytes=1073741824,binlogBytes=1073741824)
    if args.k6: plan['bounds']['outputBytes']=134217728
    c=plan['cases'][0];c.update(caseId='cap-'+args.smoke.lower(),role='cap-smoke-'+args.smoke.lower(),kind='mixed')
    w=c['workload'];w.update(requestMillis=10000,drainMillis=180000,inputTolerancePpm=1000,inputWindowSeconds=5 if args.smoke=='S1' else 10,
        schedule={'kind':'fixed-rate' if args.smoke=='S1' else 'smoke-s2','activeUsers':0 if args.smoke=='S1' else 2,'writePeriodSeconds':200,'readPeriodSeconds':50,
                  'issuedEpochMillis':issued['issuedEpochMillis'],'expiresEpochMillis':issued['expiresEpochMillis']})
    if args.smoke=='S2':w.update(writeRate=0,readRate=0,warmupSeconds=0,measurementSeconds=220,userMode='reuse',userPool=2,bootstrap=False,simultaneousSeconds=0)
    policy.update(maxHeapBytes=1610612736,maxBacklogRecords=64,fixture='none')
    if args.k6 and args.smoke=='S2':
        if not args.s2_profile:raise ValueError('Frozen S2 output profile required')
        profile=read_s2_profile(args.s2_profile,repo)
        binding=json.loads(Path(args.predecessor).read_text(encoding='utf-8-sig'))['k6']['s2Profile']
        if Path(binding['path']).resolve()!=Path(args.s2_profile).resolve() or binding['sha256']!=sha(args.s2_profile):
            raise ValueError('S2 profile predecessor binding')
        environment=json.loads(Path(args.environment).read_text(encoding='utf-8-sig'))
        if environment['evidenceBytes']!=profile['evidenceBytes']:raise ValueError('S2 environment evidence reservation')
        plan['bounds']['outputBytes']=profile['outputBytes']
        plan['bounds']['samples']=profile['samples']
        policy['maxSampleBytes']=profile['maxSampleBytes']
        policy['maxTraceBytes']=profile['maxTraceBytes']
        policy['maxTraceEntries']=profile['maxTraceEntries']
    template=out/'cap-template.json';policy_template=out/'cap-policy-template.json'
    for path,data in ((template,plan),(policy_template,policy)):
        with path.open('x',encoding='utf-8') as stream:json.dump(data,stream,indent=2)
    argv=[sys.executable,str(repo/'scripts/new-phase18-plan.py'),'--template',str(template),'--environment',args.environment,'--predecessor',args.predecessor,
          '--run-id',args.run_id,'--label','cap','--policy-template',str(policy_template)]
    return subprocess.call(argv)
if __name__=='__main__':raise SystemExit(main())
