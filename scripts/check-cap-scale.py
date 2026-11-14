"""서비스 없는 CAP raw/수신/정확한 분석 규모 검증. synthetic이며 서버 성능 결과가 아니다."""
import sys, json, hashlib, struct, sqlite3, ctypes, time, shutil
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import phase18_cap as cap
import importlib.util
spec=importlib.util.spec_from_file_location('a',Path(__file__).with_name('analyze-phase18.py'));a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
# k6의 compact canonical/bitset/epoch 파일과 20분 cohort를 같은 규모에서 검증
if '--k6' in sys.argv:
    import phase18_k6 as k
    root=Path(sys.argv[1]);temp=Path(sys.argv[2]);root.mkdir();temp.mkdir()
    began=time.monotonic();cap.set_deadline(int(time.time()*1000)+600000);cap.memory_limit(536870912)
    w=dict(writeRate=105,readRate=0,wsConnections=100,bootstrap=True,warmupSeconds=30,measurementSeconds=1200,inputWindowSeconds=10,schedule=dict(kind='fixed-rate'))
    c=dict(workload=w,role='cap-target',runId='scale',caseId='scale');p=dict(ownership=dict(temp=str(temp)))
    anchors=dict(bootstrap=9990,warmup=10000,measurement=40000);reserved=129150;accepted={};rows=cap.Rows(temp/'rows.sqlite')
    with (root/'k6-ws-canonical.jsonl').open('x') as canonical:
        for i in range(reserved+1):
            phase='bootstrap' if i==0 else 'warmup' if i<=3150 else 'measurement'
            ordinal=0 if i==0 else i-1 if phase=='warmup' else i-3151
            at=anchors[phase]+(0 if i==0 else cap.offset(w,'write',ordinal,phase)//1000000)
            event=dict(eventSeq=(i+1)*7,tileVersion=i+1,x=0,y=0,color=i%16)
            row=dict(type='http',kind='write',phase=phase,ordinal=ordinal,outcome='ACCEPTED',httpStatus=200,startedEpochMillis=at,bodyEpochMillis=at+1,k6DurationMillis=1)
            rows.append(row);accepted[event['eventSeq']]=dict(event=event,sent=at,body=at+1,validated=at+2,phase=phase)
            if i:canonical.write(json.dumps(event,separators=(',',':'))+'\n')
    with (root/'k6-ws-times.bin').open('xb') as file:
        for connection in range(100):
            for first in range(0,reserved,4096):
                values=[10000+i*1000//105+2 for i in range(first,min(reserved,first+4096))]
                file.write(struct.pack('>'+str(len(values))+'q',*values))
    words=(reserved+63)//64
    with (root/'k6-ws-receipts.bin').open('xb') as file:
        receipt=b''.join(struct.pack('>Q',(1<<min(64,reserved-i*64))-1) for i in range(words))
        for _ in range(100):file.write(receipt)
    producer=dict(ws=dict(duplicates=[0]*100,missing=[0]*100,extra=[0]*100))
    inputs=k.fulfillment(c,rows,anchors);ws=k.ws_metrics(a,p,c,root,accepted,producer,1)
    assert inputs['write']['P']==126000 and inputs['write']['inputMet'] and ws['overall']['count']==12600000
    assert ws['overall']['p95Millis']==ws['overall']['p99Millis']==2
    # 별도 복제본 없이 테스트 전용 fixture의 마지막 bit를 바꾸어 정확한 원인으로 거부 확인
    with (root/'k6-ws-receipts.bin').open('r+b') as file:
        file.seek((100*words-1)*8);last=struct.unpack('>Q',file.read(8))[0];file.seek((100*words-1)*8);file.write(struct.pack('>Q',last&~(1<<((reserved-1)%64))))
    try:k.ws_metrics(a,p,c,root,accepted,producer,1);raise AssertionError('missing receipt accepted')
    except a.Invalid as error:assert str(error)=='WS connection exact set'
    memory=cap.memory_usage();assert max(memory.values())<=536870912
    result=dict(synthetic=True,servicesStarted=0,rows=len(rows),maximumOrdinal=125999,wsReceiptEvents=12915000,measurementLatencies=12600000,missingLastDetected=True,
        seconds=time.monotonic()-began,**memory,limitBytes=536870912,bytes=sum(f.stat().st_size for folder in (root,temp) for f in folder.rglob('*') if f.is_file()),
        meaning='Native k6 format support/finite memory only. No services, DB/WAL recovery or CAP performance PASS.')
    rows.close();(root/'scale-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));raise SystemExit(0)

root=Path(sys.argv[1]);temp=Path(sys.argv[2]);root.mkdir();temp.mkdir();run=root/'initial';run.mkdir();(root/'recovery').mkdir()
def put(path,data):
    with path.open('x',encoding='utf-8') as f:json.dump(data,f,ensure_ascii=False)
def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
N=10**9;ph='c'*64;clock='synthetic/scale';w={'writeRate':105,'readRate':0,'wsConnections':100,'bootstrap':True,'warmupSeconds':30,'measurementSeconds':1200,'inputWindowSeconds':10,'schedule':{'kind':'fixed-rate'}}
c={'workload':w,'role':'cap-target','runId':'scale','caseId':'scale'};p={'ownership':{'temp':str(temp)}};start=31*N;end=start+1200*N
rows=cap.Rows(temp/'rows.sqlite');events=129150;pool=20393;began=time.monotonic()
anchors={'bootstrap':{'nanos':N-10_000_000,'endNanos':N-10_000_000},'warmup':{'nanos':N,'endNanos':start},'measurement':{'nanos':start,'endNanos':end}}
counts={f'{kind}/{phase}':cap.count(w,kind,phase) for phase in ('bootstrap','warmup','measurement') for kind in ('write','read')}
with (run/'raw.jsonl').open('x') as raw,(run/'cap-http.jsonl').open('x') as http,(run/'ws-canonical.jsonl').open('x') as canonical:
    for global_i in range(events+1):
        phase='bootstrap' if global_i==0 else 'warmup' if global_i<=3150 else 'measurement'
        ordinal=0 if global_i==0 else global_i-1 if phase=='warmup' else global_i-3151
        offset=0 if global_i==0 else cap.offset(w,'write',ordinal)
        sent=anchors[phase]['nanos']+offset;body=sent+1_000_000
        user=0 if global_i==0 else 1+(global_i-1)%pool;attempt=1 if global_i==0 else 1+(global_i-1)//pool
        r=dict(schemaVersion=1,planHash=ph,runId='scale',caseId='scale',requestId=f'scale/scale/write/{phase}/{ordinal}',kind='write',scheduledPhase=phase,ordinal=ordinal,
            clockId=clock,scheduledOffsetNanos=offset,decidedNanos=sent,sentNanos=sent,completedNanos=body,status='accepted',reason='',httpStatus=200,eventSeq=7*(global_i+1),tileVersion=global_i+1,
            sendState='sent',outcome='ACCEPTED',resolution='not_applicable',userOrdinal=user,userAttempt=attempt,x=0,y=0,color=global_i%16,rawSha256=None,wireBytes=None,validationNanos=None)
        rows.append(r);raw.write(json.dumps(r,separators=(',',':'))+'\n')
        obs=dict(planHash=ph,clockId=clock,requestId=r['requestId'],phase=phase,sentNanos=sent,bodyNanos=body,validatedNanos=body+1000,eventSeq=r['eventSeq'],userOrdinal=user,userAttempt=attempt,outcome='ACCEPTED')
        http.write(json.dumps(obs,separators=(',',':'))+'\n')
        if global_i:canonical.write(json.dumps({k:r[k] for k in ('eventSeq','tileVersion','x','y','color')},separators=(',',':'))+'\n')
with (run/'cap-db.jsonl').open('x') as f:
    for second in range(1232):
        q=second*N+500_000_000;completed=min(events,max(0,(q-N-1_001_000)*105//N+1))
        f.write(json.dumps(dict(planHash=ph,clockId=clock,queryStartNanos=q-10000,queryEndNanos=q,checkpoint=(completed+1)*7))+'\n')
with (run/'cap-ws-times.bin').open('xb') as f:
    for connection in range(100):
        for first in range(0,events,4096):
            values=[N+i*N//105+2_000_000+connection for i in range(first,min(events,first+4096))]
            f.write(struct.pack('>'+str(len(values))+'q',*values))
words=(events+63)//64
with (run/'ws-received.bin').open('xb') as f:
    connection=b''.join(struct.pack('>Q',(1<<min(64,events-i*64))-1) for i in range(words))
    for i in range(100):f.write(connection)
put(run/'cap-observer.json',dict(planHash=ph,clockId=clock,connectionClosed=True,queries=1232,queryNanos=12320000,hashes={n:sha(run/n) for n in ('cap-http.jsonl','cap-db.jsonl')}))
hashes={n:sha(run/n) for n in ('cap-http.jsonl','cap-db.jsonl','cap-observer.json','cap-ws-times.bin')}
for folder in (run,root/'recovery'):put(folder/'manifest.json',{'capHashes':hashes})
producer=dict(clockId=clock,anchors=anchors,wsOpenedNanos=[N-1]*100,wsClosedNanos=[end+1]*100,wsCallbacksDrained=True,ws={'duplicates':[0]*100,'missing':[0]*100,'extra':[0]*100})
report=cap.aggregate(c,rows,producer,counts,a.distribution);report.update(status='VALID_NO_TARGET',planHash=ph)
ws=a.ws_evidence(run,c,rows,producer);assert ws['complete'],ws
report['cap']=cap.evaluate(p,c,rows,producer,report,run,a.read,sha,a.distribution,a.require)
assert report['cap']['verdict']=='PASS',report['cap']['reasons']
assert report['cap']['ws']['overall']['count']==12600000
assert report['cap']['input']['write']['P']==126000
# 실제 마지막 ordinal/연결 bit를 하나 지운 별도 사본. 원본을 변조하거나 해시 맞춤 수정하지 않는다.
negative=root/'missing-last';negative.mkdir();shutil.copyfile(run/'ws-canonical.jsonl',negative/'ws-canonical.jsonl');shutil.copyfile(run/'ws-received.bin',negative/'ws-received.bin')
with (negative/'ws-received.bin').open('r+b') as f:
    f.seek((100*words-1)*8);last=struct.unpack('>Q',f.read(8))[0];f.seek((100*words-1)*8);f.write(struct.pack('>Q',last&~(1<<((events-1)%64))))
miss=a.ws_evidence(negative,c,rows,producer);assert not miss['complete']
class Memory(ctypes.Structure):
    _fields_=[('cb',ctypes.c_ulong),('PageFaultCount',ctypes.c_ulong)]+[(n,ctypes.c_size_t) for n in ('PeakWorkingSetSize','WorkingSetSize','QuotaPeakPagedPoolUsage','QuotaPagedPoolUsage','QuotaPeakNonPagedPoolUsage','QuotaNonPagedPoolUsage','PagefileUsage','PeakPagefileUsage','PrivateUsage')]
m=Memory();m.cb=ctypes.sizeof(m)
kernel=ctypes.WinDLL('kernel32',use_last_error=True);psapi=ctypes.WinDLL('psapi',use_last_error=True)
kernel.GetCurrentProcess.restype=ctypes.c_void_p
psapi.GetProcessMemoryInfo.argtypes=[ctypes.c_void_p,ctypes.POINTER(Memory),ctypes.c_ulong];psapi.GetProcessMemoryInfo.restype=ctypes.c_int
if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(),ctypes.byref(m),m.cb):raise ctypes.WinError(ctypes.get_last_error())
result={'synthetic':True,'servicesStarted':0,'rows':len(rows),'maximumGlobalOrdinal':events,'wsReceiptEvents':events*100,'measurementLatencies':12600000,'missingLastDetected':not miss['complete'],
    'analysisVerdict':report['cap']['verdict'],'seconds':time.monotonic()-began,'peakWorkingSetBytes':m.PeakWorkingSetSize,'peakCommitBytes':m.PeakPagefileUsage,
    'limitBytes':536870912,'cBytes':sum(x.stat().st_size for x in root.rglob('*') if x.is_file()),'fBytes':sum(x.stat().st_size for x in temp.rglob('*') if x.is_file()),
    'meaning':'CAP analysis algorithm/receipt verification only; manifests are synthetic, no DB/WAL recovery claim'}
put(root/'analysis.json',report);put(root/'scale-result.json',result);rows.close();print(json.dumps(result));assert 0<max(m.PeakWorkingSetSize,m.PeakPagefileUsage)<=536870912
