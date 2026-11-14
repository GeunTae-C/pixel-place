"""CAP v2 추가 판정. legacy 분류를 보존하며 CAP 전용 일정/분모/정확한 분포를 계산."""
import collections
import json
import math
import sqlite3
import statistics
import struct
import uuid
import time
import os
import ctypes
from pathlib import Path

N=10**9
def user_ready_at_due(due,validated,outcome):
    """실제 dispatch 지연으로 원래 due의 미준비를 보충하지 않음."""
    return outcome!='UNKNOWN' and due>=validated+181*N

def count(w,kind,phase):
    if phase=='initial-read':return w['wsConnections'] if kind=='read' and w.get('schedule',{}).get('kind')=='users' else 0
    if phase=='bootstrap':return int(kind=='write' and w['bootstrap'])
    schedule=w.get('schedule',{'kind':'legacy'})
    if schedule['kind'] not in ('smoke-s2','users'):return w[kind+'Rate']*w[phase+'Seconds']
    if phase=='warmup' and (kind=='write' or schedule['kind']=='smoke-s2'):return 0
    if schedule['kind']=='smoke-s2':return 4
    people=schedule['activeUsers'] if kind=='write' else w['wsConnections']
    period=schedule['writePeriodSeconds'] if kind=='write' else schedule['readPeriodSeconds']
    return (w[phase+'Seconds']*people+period-1)//period if people and period else 0
def read_pixel(w,phase,ordinal):
    if w.get('schedule',{}).get('kind')=='users':tile=(w['seed']%1024+ordinal%w['wsConnections'])%1024
    else:
        n=ordinal+(0 if phase=='initial-read' else count(w,'read','initial-read'))+(count(w,'read','warmup') if phase=='measurement' else 0)
        tile=0 if w['readPattern']=='hot' else n%1024
    return (tile%32*256,tile//32*256)
def offset(w,kind,i,phase=None):
    if phase=='initial-read':return i*10_000_000
    s=w.get('schedule',{'kind':'legacy'})
    if s['kind']=='smoke-s2':return ((0,1,200,201) if kind=='write' else (10,60,110,160))[i]*N
    if s['kind']=='users':return i*(s['writePeriodSeconds'] if kind=='write' else s['readPeriodSeconds'])*N//(s['activeUsers'] if kind=='write' else w['wsConnections'])
    return i*N//w[kind+'Rate']
def late(w,kind,phase=None):
    if phase=='initial-read':return 10_000_000
    s=w.get('schedule',{'kind':'legacy'})
    if s['kind']=='smoke-s2':return N
    if s['kind']=='users':return (s['writePeriodSeconds'] if kind=='write' else s['readPeriodSeconds'])*N//(s['activeUsers'] if kind=='write' else w['wsConnections'])
    return N//w[kind+'Rate']
def rejected(r):
    return r['kind']=='write' and r['scheduledPhase']!='bootstrap' and r['sendState']=='sent' and r['outcome']=='REJECTED' and r['eventSeq'] is None and (
        (r['status'],r['httpStatus'],r['reason']) in (('server_5xx',503,'cap_write_busy'),('rejected_429',429,'cap_cooldown')))

_expires=None
_memory_limit=None
_memory_checked=0
def memory_usage():
    # 기존 Windows 규모 검증의 native working-set/commit 관측 재사용
    class Memory(ctypes.Structure):
        _fields_=[('cb',ctypes.c_ulong),('PageFaultCount',ctypes.c_ulong)]+[(n,ctypes.c_size_t) for n in ('PeakWorkingSetSize','WorkingSetSize','QuotaPeakPagedPoolUsage','QuotaPagedPoolUsage','QuotaPeakNonPagedPoolUsage','QuotaNonPagedPoolUsage','PagefileUsage','PeakPagefileUsage','PrivateUsage')]
    if os.name!='nt':raise ValueError('k6 memory observation requires Windows')
    m=Memory();m.cb=ctypes.sizeof(m)
    kernel=ctypes.WinDLL('kernel32',use_last_error=True);psapi=ctypes.WinDLL('psapi',use_last_error=True)
    kernel.GetCurrentProcess.restype=ctypes.c_void_p
    psapi.GetProcessMemoryInfo.argtypes=[ctypes.c_void_p,ctypes.POINTER(Memory),ctypes.c_ulong]
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(),ctypes.byref(m),m.cb):raise ctypes.WinError(ctypes.get_last_error())
    return dict(peakWorkingSetBytes=m.PeakWorkingSetSize,peakCommitBytes=m.PeakPagefileUsage)
def memory_limit(value):
    global _memory_limit,_memory_checked
    _memory_limit=value;_memory_checked=0
def set_deadline(expires):
    global _expires
    _expires=expires
    check_deadline()
def check_deadline():
    global _memory_checked
    if _expires is not None and time.time()*1000>=_expires:raise ValueError('CAP original analysis expiry exhausted')
    if _memory_limit is not None and time.monotonic()-_memory_checked>=.25:
        _memory_checked=time.monotonic()
        if max(memory_usage().values())>_memory_limit:raise ValueError('k6 analysis memory TOOL_LIMIT')

class Rows:
    """정규화 terminal의 파일 보유. 소비자는 반복 가능한 유한 스트림을 순회."""
    def __init__(self,path):
        self.path=Path(path)
        with self.path.open('x'):pass
        self.db=sqlite3.connect(self.path)
        self.db.execute('PRAGMA cache_size=-8192')
        self.db.execute('CREATE TABLE rows (id INTEGER PRIMARY KEY, value TEXT NOT NULL)')
        self.size=0
    def append(self,row):
        self.db.execute('INSERT INTO rows VALUES (?,?)',(self.size,json.dumps(row,separators=(',',':'))));self.size+=1
        if self.size%1000==0:self.db.commit()
    def __len__(self):return self.size
    def __iter__(self):
        self.db.commit()
        for i,(data,) in enumerate(self.db.execute('SELECT value FROM rows ORDER BY id')):
            if i%1000==0:check_deadline()
            yield json.loads(data)
    def close(self):self.db.commit();self.db.close()

def inputs(c,rows,start,end):
    w=c['workload'];window=w['inputWindowSeconds']*N;result={};violations=[]
    for kind in ('write','read'):
        total=count(w,kind,'measurement');planned=[0]*math.ceil((end-start)/window);cohort=[0]*len(planned);dispatch=[0]*len(planned)
        for i in range(total):planned[offset(w,kind,i)//window]+=1
        sent=valid=accepted=errors=0;reasons=collections.Counter();rtt=[]
        for r in rows:
            if r['kind']!=kind or r['scheduledPhase']!='measurement':continue
            if r['sendState']!='sent':reasons[r['reason']]+=1;continue
            sent+=1;due=start+r['scheduledOffsetNanos'];deadline=min(due+late(w,kind),end)
            if due<=r['sentNanos']<deadline:
                valid+=1;cohort[r['scheduledOffsetNanos']//window]+=1;dispatch[(r['sentNanos']-start)//window]+=1
            else:violations.append(r['requestId'])
            if r['status']=='accepted':accepted+=1;rtt.append(r['completedNanos']-r['sentNanos'])
            errors+=r['status'] in {'server_5xx','timeout','network_error','malformed_200'}
        buckets=[{'ordinal':i,'P':p,'scheduledCohortSvalid':cohort[i],'dispatchWindowD':dispatch[i],
                  'cohortRatio':cohort[i]/p if p else None,'dispatchRatio':dispatch[i]/p if p else None,'excess':max(0,dispatch[i]-p)} for i,p in enumerate(planned)]
        met=all(cohort[i]*1000>=p*999 and dispatch[i]*1000>=p*999 for i,p in enumerate(planned) if p) and valid*1000>=total*999
        result[kind]={'P':total,'S_all':sent,'S_valid':valid,'A':accepted,'notSentReasons':dict(reasons),'windows':buckets,
             'inputMet':met if total else None,'successRatio':accepted/sent if sent else None,'acceptedPerPlanned':accepted/total if total else None,
             'systemErrorNumerator':errors,'systemErrorDenominator':sent,'systemErrorRate':errors/sent if sent else None,
             'rttNanos':rtt}
    return result,violations

def aggregate(c,rows,producer,counts,distribution):
    cohorts={}
    for key,p in counts.items():cohorts[key]={'planned':p,'terminal':0,'statuses':collections.Counter(),'sendStates':collections.Counter()}
    measurement=producer.get('anchors',{}).get('measurement')
    data={k:{'sent':0,'accepted':0,'completion':0,'notSent':0,'errors':0,'statuses':collections.Counter(),'rtt':[],'delay':[],'scheduled':[],'wire':0,'wireValid':True} for k in ('write','read')}
    for r in rows:
        cohort=cohorts[r['kind']+'/'+r['scheduledPhase']];cohort['terminal']+=1;cohort['statuses'][r['status']]+=1;cohort['sendStates'][r['sendState']]+=1
        if measurement is None:continue
        start,end=measurement['nanos'],measurement['endNanos'];d=data[r['kind']]
        if r['status']=='client_not_sent' and r['scheduledPhase']=='measurement':d['notSent']+=1
        if r['status']=='accepted' and start<=r['completedNanos']<end:d['completion']+=1
        if r['sendState']=='sent' and start<=r['sentNanos']<end:
            d['sent']+=1;d['statuses'][r['status']]+=1;d['errors']+=r['status'] in {'server_5xx','timeout','network_error','malformed_200'}
            d['delay'].append(r['sentNanos']-producer['anchors'][r['scheduledPhase']]['nanos']-r['scheduledOffsetNanos'])
            if r['status']=='accepted':
                d['accepted']+=1;d['rtt'].append(r['completedNanos']-r['sentNanos']);d['scheduled'].append(r['completedNanos']-producer['anchors'][r['scheduledPhase']]['nanos']-r['scheduledOffsetNanos'])
                if r['kind']=='read':
                    if type(r.get('wireBytes')) is int and r['wireBytes']>=0:d['wire']+=r['wireBytes']
                    else:d['wireValid']=False
    if measurement is None:return {'cohorts':cohorts,'measurement':None,'inputFulfillment':{'met':None,'state':'UNKNOWN'}}
    metrics={};seconds=c['workload']['measurementSeconds']
    for kind,d in data.items():
        planned=counts[kind+'/measurement']
        metrics[kind]={'completionAccepted':d['completion'],'completionAcceptedPerSecond':d['completion']/seconds,'sent':d['sent'],
            'sentCohortAccepted':d['accepted'],'sentCohortAcceptedPerSecond':d['accepted']/seconds,'statuses':dict(d['statuses']),
            'acceptedRtt':distribution(d['rtt']),'sendDelay':distribution(d['delay']),'scheduledToAcceptedResponse':distribution(d['scheduled']),
            'systemErrorNumerator':d['errors'],'systemErrorDenominator':d['sent'],'systemErrorRate':d['errors']/d['sent'] if d['sent'] else None,
            'clientNotSent':d['notSent'],'planned':planned,'clientNotSentRate':d['notSent']/planned if planned else None}
        if kind=='read':metrics[kind]['gzipBytes']={'raw':d['accepted']*65536,'wire':d['wire'] if d['wireValid'] else None}
    actual,violations=inputs(c,rows,start,end)
    return {'cohorts':cohorts,'measurement':{'startNanos':start,'endNanos':end,'seconds':seconds,'metrics':metrics},
            'inputFulfillment':{'met':not violations and all(x['inputMet'] is not False for x in actual.values()),'state':'CAP_V2'}}

def selection_gate(action,history,candidate,conditions_hash=None):
    """다음 단계용 순수 사전 조건. 실행/재시작 기능 없음; capability/예산은 별도 필수."""
    if any(t.get('role')=='cap-diagnostic-ab' or not t.get('closed') or t['verdict']=='INVALID_OR_STOPPED' for t in history):return False
    if action=='continue-ascent':return bool(history) and history[-1]['verdict']=='PASS' and candidate>history[-1]['value']
    if action=='input-descent':return bool(history) and history[-1]['verdict']=='INPUT_LIMIT' and candidate<history[-1]['value']
    if action=='ws-first-miss-fallback':return bool(history) and history[-1]['value']==200 and history[-1]['verdict'] in ('PERFORMANCE_MISS','TOOL_LIMIT') and candidate==100 and any(t['value']==100 and t['verdict']=='PASS' and t.get('sameConditions') for t in history)
    if action=='confirm-20min':
        # A caller supplies already verified analysis/closure linkage, not three copies of a summary.
        def digest(v):return isinstance(v,str) and len(v)==64 and all(c in '0123456789abcdef' for c in v)
        if not digest(conditions_hash):return False
        if any(t['value']==candidate and t.get('seconds') in (120,1200) and t.get('conditionsHash')==conditions_hash and t['verdict'] in ('PERFORMANCE_MISS','INPUT_LIMIT') for t in history):return False
        sessions=set();runs=set();analyses=set();closures=set();passes=0
        for t in history:
            if not (t['verdict']=='PASS' and t['value']==candidate and t.get('seconds')==120 and t.get('conditionsHash')==conditions_hash):continue
            if t.get('evidenceVerified') is not True or not all(digest(t.get(k)) for k in ('planHash','analysisHash','closureHash')):return False
            if not all(isinstance(t.get(k),str) and t[k] for k in ('sessionId','runId')):return False
            values=(t['sessionId'],t['runId'],t['analysisHash'],t['closureHash'])
            if any(v in seen for v,seen in zip(values,(sessions,runs,analyses,closures))):return False
            for v,seen in zip(values,(sessions,runs,analyses,closures)):seen.add(v)
            passes+=1
        return passes>=3
    return False

def checkpoint_series(samples,http,start,end):
    covered=[s for s in samples if start<=s['queryEndNanos']<end]
    times=[start]+[s['queryEndNanos'] for s in covered]+[end]
    max_gap=max((b-a for a,b in zip(times,times[1:])),default=0)
    b_values=[]
    for sample in covered:
        check_deadline()
        pending=[body for seq,(validated,body) in http.items() if validated<=sample['queryEndNanos'] and seq>sample['checkpoint']]
        b_values.append({'at':sample['queryEndNanos'],'pendingCount':len(pending),'Bnanos':sample['queryEndNanos']-min(pending) if pending else 0,'queryNanos':sample['queryEndNanos']-sample['queryStartNanos']})
    first=[v['Bnanos'] for v in b_values if v['at']<start+(end-start)//3]
    final=[v['Bnanos'] for v in b_values if v['at']>=start+2*(end-start)//3]
    med1=statistics.median(first) if first else None;med2=statistics.median(final) if final else None
    trend=None if len(first)<10 or len(final)<10 else med2-med1<=max(N,med1*.1)
    return {'samples':b_values,'maxGapNanos':max_gap,'maxBnanos':max((v['Bnanos'] for v in b_values),default=None),'firstCount':len(first),'lastCount':len(final),'firstMedianNanos':med1,'lastMedianNanos':med2,'trendMet':trend}

def evaluate(p,c,rows,producer,report,run,read,sha,distribution,require):
    w=c['workload'];clock=producer['clockId'];plan_hash=report['planHash'];issues=[]
    if report['measurement'] is None:return {'verdict':'INVALID_OR_STOPPED','reasons':['measurement clock missing']}
    start,end=report['measurement']['startNanos'],report['measurement']['endNanos']
    metric,violations=inputs(c,rows,start,end);issues.extend('dispatch schedule violation '+i for i in violations)
    for v in metric.values():v['acceptedRtt']=distribution(v.pop('rttNanos'))
    invalid=report['status'] in ('EXECUTION_FAILED','MEASUREMENT_INVALID')
    for r in rows:
        if r['sendState']=='sent' and r['scheduledPhase'] not in ('bootstrap','measurement'):
            anchor=producer['anchors'][r['scheduledPhase']];due=anchor['nanos']+r['scheduledOffsetNanos']
            if not due<=r['sentNanos']<min(due+late(w,r['kind'],r['scheduledPhase']),anchor['endNanos']):issues.append('preparation dispatch schedule violation '+r['requestId'])
        if r['sendState']=='sent' and r['status']!='accepted' and not rejected(r):issues.append('non-allowlisted CAP response '+r['requestId'])
    accepted={r['eventSeq']:(r['sentNanos'],r['completedNanos'],r['scheduledPhase']) for r in rows if r['kind']=='write' and r['status']=='accepted'}
    observer={};db_result={};ws_result={};users={};observation_limit=False;user_success=True
    try:
        observer=read(run/'cap-observer.json');require(observer['planHash']==plan_hash and observer['clockId']==clock and observer['connectionClosed'] is True,'CAP observer identity/close')
        manifest=read(run/'manifest.json');recovery=read(run.parent/'recovery/manifest.json')
        hashes={name:sha(run/name) for name in ('cap-http.jsonl','cap-db.jsonl','cap-observer.json','cap-ws-times.bin')}
        require(manifest['capHashes']==hashes and recovery['capHashes']==hashes,'CAP initial/recovery observation hashes')
        require(all(hashes[k]==v for k,v in observer['hashes'].items()),'CAP observer raw hashes')
        http={};seen=set();http_rows={}
        with (run/'cap-http.jsonl').open() as stream:
            for line in stream:
                r=json.loads(line);require(len(seen)<140000 and r['requestId'] not in seen and r['clockId']==clock and r['planHash']==plan_hash,'CAP HTTP exact set')
                require(r['validatedNanos']>=r['bodyNanos']>=r['sentNanos'],'CAP body/validation order');seen.add(r['requestId']);http_rows[r['requestId']]=r
                if r['eventSeq'] is not None:http[r['eventSeq']]=(r['validatedNanos'],r['bodyNanos'])
                if r['userOrdinal'] is not None:users.setdefault(r['userOrdinal'],[]).append((r['sentNanos'],r['validatedNanos'],r['outcome'],r['userAttempt'],r['requestId']))
        require(seen=={r['requestId'] for r in rows if r['sendState']=='sent'},'CAP HTTP terminal join')
        for row in rows:
            if row['sendState']=='sent':
                obs=http_rows[row['requestId']]
                require(all(obs[k]==row[v] for k,v in [('phase','scheduledPhase'),('sentNanos','sentNanos'),('bodyNanos','completedNanos'),('eventSeq','eventSeq'),('outcome','outcome'),('userOrdinal','userOrdinal'),('userAttempt','userAttempt')]),'CAP HTTP full terminal join')
        require(set(http)==set(accepted),'CAP accepted seq exact join')
        for seq,a in accepted.items():require(http[seq][1]==a[1],'CAP HTTP response time join')
        for row in rows:
            if row['sendState']=='sent':http_rows[row['requestId']]['dueNanos']=producer['anchors'][row['scheduledPhase']]['nanos']+row['scheduledOffsetNanos']
        for ordinal,attempts in users.items():
            attempts.sort()
            for before,after in zip(attempts,attempts[1:]):
                due=http_rows[after[4]]['dueNanos']
                require(user_ready_at_due(due,before[1],before[2]),'CAP original due user readiness/retire')
                require(after[0]>=before[1]+181*N and before[2]!='UNKNOWN','CAP user readiness/retire')
        if w['schedule']['kind']=='users':
            active=w['schedule']['activeUsers']
            for row in rows:
                if row['kind']=='write' and row['scheduledPhase']=='measurement' and row['sendState']=='sent':require(row['userOrdinal']==row['ordinal']%active,'CAP-3 deterministic user/WS mapping')
            initial=[r for r in rows if r['scheduledPhase']=='initial-read']
            require(len(initial)==w['wsConnections'] and {r['ordinal'] for r in initial}==set(range(w['wsConnections'])) and all(r['kind']=='read' and r['status']=='accepted' and r['outcome']=='ACCEPTED' and r['sendState']=='sent' and r['httpStatus']==200 and r['validationNanos'] is not None for r in initial),'CAP-3 initial GET success')
            user_success=all(len(users.get(i,[]))>=2 and all(a[2]=='ACCEPTED' for a in users[i][:2]) for i in range(active))
        if w['schedule']['kind']=='smoke-s2':require(set(users)=={0,1} and all(len(v)==2 and [x[3] for x in v]==[1,2] and all(x[2]=='ACCEPTED' for x in v) for v in users.values()),'S2 two user two success')
        samples=[];last=None
        with (run/'cap-db.jsonl').open() as stream:
            for line in stream:
                sample=json.loads(line);require(len(samples)<3602 and sample['clockId']==clock and sample['planHash']==plan_hash,'CAP DB sample identity')
                left,right,cp=sample['queryStartNanos'],sample['queryEndNanos'],sample['checkpoint'];require(right>=left and cp>=0,'DB query bounds')
                if last is not None:require(left>=last[0] and cp>=last[1],'DB monotonic query/checkpoint')
                samples.append(sample);last=(right,cp)
        db_result=checkpoint_series(samples,http,start,end)
        observation_limit=db_result['maxGapNanos']>3*N or (db_result['trendMet'] is None and c['role']!='cap-smoke-s1')
        db_result.update(shortSmokeTrendNA=c['role']=='cap-smoke-s1',queryNanos=observer['queryNanos'],queries=observer['queries'],meaning='HTTP body to checkpoint observation; same producer clock; query end conservative')
        canonical=[]
        with (run/'ws-canonical.jsonl').open() as stream:
            for line in stream:canonical.append(json.loads(line)['eventSeq'])
        reserved=count(w,'write','warmup')+count(w,'write','measurement');connections=w['wsConnections']
        require((run/'cap-ws-times.bin').stat().st_size==reserved*connections*8,'CAP WS timing bound')
        require(len(producer['wsOpenedNanos'])==connections and len(producer['wsClosedNanos'])==connections and all(t<=start for t in producer['wsOpenedNanos']) and all(t>=end for t in producer['wsClosedNanos']),'CAP WS connection lifetime')
        require(all(v==0 for key in ('duplicates','missing','extra') for v in producer['ws'][key]),'CAP WS exactness counters')
        sqlite_path=Path(p['ownership']['temp'])/('cap-latency-'+c['runId']+'-'+uuid.uuid4().hex+'.sqlite')
        with sqlite_path.open('x'):pass
        db=sqlite3.connect(sqlite_path);db.execute('PRAGMA cache_size=-8192');db.execute('CREATE TABLE latency(value INTEGER)')
        by_connection=[];total=0
        with (run/'cap-ws-times.bin').open('rb') as stream:
            for connection in range(connections):
                check_deadline()
                stream.seek(connection*reserved*8);durations=[]
                for i,seq in enumerate(canonical):
                    received=struct.unpack('>q',stream.read(8))[0];require(received!=0 and seq in accepted and received>=accepted[seq][0],'CAP WS missing/early/extra join')
                    if accepted[seq][2]=='measurement':durations.append(received-accepted[seq][0])
                by_connection.append(distribution(durations));total+=len(durations)
                db.executemany('INSERT INTO latency VALUES (?)',((n,) for n in durations));db.commit()
        db.execute('CREATE INDEX ordered_latency ON latency(value)')
        overall={'count':total}
        for label,q in [('p50Millis',50),('p95Millis',95),('p99Millis',99),('maxMillis',100)]:overall[label]=db.execute('SELECT value FROM latency ORDER BY value LIMIT 1 OFFSET ?',(math.ceil(total*q/100)-1,)).fetchone()[0]/1e6 if total else None
        db.close();ws_result={'overall':overall,'connections':by_connection,'sqliteBytes':sqlite_path.stat().st_size,'timeMeaning':'actual HTTP send to whole WS message reception on producer clock'}
    except (OSError,ValueError,KeyError,TypeError,struct.error) as error:issues.append('CAP observation '+str(error));invalid=True
    invalid=invalid or bool(issues)
    input_met=all(v['inputMet'] is not False for v in metric.values())
    performance=user_success
    for kind,v in metric.items():
        if not v['P']:continue
        performance &= v['S_all']>0 and v['successRatio']>=.999 and v['systemErrorRate']<=.001
        if kind=='write':performance &= v['acceptedRtt']['count']>0 and v['acceptedRtt']['p95Millis']<=100 and v['acceptedRtt']['p99Millis']<=250
    for v in ws_result.get('connections',[]):performance &= bool(v['count']) and v['p95Millis']<=250 and v['p99Millis']<=500
    performance &= db_result.get('maxBnanos') is not None and db_result['maxBnanos']<=10*N and db_result.get('trendMet') is not False
    verdict='INVALID_OR_STOPPED' if invalid else 'TOOL_LIMIT' if observation_limit else 'INPUT_LIMIT' if not input_met else 'PASS' if performance else 'PERFORMANCE_MISS'
    smoke=c['role'].startswith('cap-smoke')
    return {'contractVersion':2,'verdict':None if smoke and not invalid else verdict,'smokeVerification':('PASS' if not invalid and not observation_limit and input_met else 'FAIL') if smoke else None,
        'capacityPoint':not smoke and verdict in ('PASS','PERFORMANCE_MISS'),'input':metric,'db':db_result,'ws':ws_result,'userAttempts':{str(k):len(v) for k,v in users.items()},
        'reasons':issues,'inputLimitCause':'undetermined' if not input_met else None,'performanceCriteriaMet':bool(performance),'observationLimit':observation_limit,
        'meaning':'smoke confirms wiring and lifecycle; never a CAP performance PASS' if smoke else 'local CAP trial criteria, not a production SLA'}
