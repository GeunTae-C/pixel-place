"""k6 v2의 실제 epoch/Trend 입력 어댑터. Java raw/nanoTime 결과를 생성하지 않음."""
import collections
import hashlib
import heapq
import math
import sqlite3
import statistics
import struct
import uuid
from pathlib import Path
import phase18_cap as cap
import phase18_cap1 as cap1


def validate_metrics(api,path,summary):
    """기존 필수 NDJSON 계약의 bounded 표준 파서. 큰 출력의 PowerShell 객체 생성 비용 제거."""
    definitions={}; points=0; digest=hashlib.sha256(); size=0
    def require(ok,reason):api.require(ok,'Required k6 output content: '+reason)
    with path.open('rb') as stream:
        while line:=stream.readline(16386):
            cap.check_deadline();size+=len(line);digest.update(line)
            require(len(line.rstrip(b'\r\n'))<=16384,'metrics line bound')
            try:r=api.decode(line)
            except (ValueError,UnicodeError):raise api.Invalid('Required k6 output content: metrics JSON')
            require(isinstance(r,dict) and isinstance(r.get('metric'),str) and bool(r['metric'].strip()) and isinstance(r.get('data'),dict),'metrics record structure')
            name=r['metric'];data=r['data']
            if r.get('type')=='Metric':
                require(data.get('name')==name and data.get('type') in ('counter','gauge','rate','trend') and data.get('contains') in ('default','time','data') and isinstance(data.get('thresholds'),list),'metrics definition')
                for threshold in data['thresholds']:require(isinstance(threshold,str) and threshold in summary['data']['metrics'].get(name,{}).get('thresholds',{}),'metrics threshold result missing')
                require(len(definitions)<1024 or name in definitions,'metrics definition bound');definitions[name]=data['type']
            elif r.get('type')=='Point':
                from datetime import datetime
                value=data.get('value');stamp=data.get('time');tags=data.get('tags')
                require(name in definitions and type(value) in (int,float) and math.isfinite(value) and isinstance(stamp,str) and isinstance(tags,dict),'metrics point')
                try:datetime.fromisoformat(stamp.replace('Z','+00:00'))
                except ValueError:raise api.Invalid('Required k6 output content: metrics point')
                require(all(isinstance(v,str) for v in tags.values()),'metrics tag');points+=1
            else:raise api.Invalid('Required k6 output content: metrics record type')
    require(points>0,'metrics empty')
    return dict(metricsSha256=digest.hexdigest(),metricsBytes=size,points=points)


def lines(api, path, maximum, width=4096):
    with path.open('rb') as stream:
        for i in range(maximum + 1):
            cap.check_deadline()
            line = stream.readline(width + 1)
            if not line:
                return
            api.require(i < maximum and len(line) <= width, 'k6 bounded record')
            yield api.decode(line)


def quantile(values, q):
    """k6 Trend의 선형 보간. WS에는 적용하지 않음."""
    if not values:
        return None
    data = sorted(values)
    position = (len(data) - 1) * q
    left = math.floor(position)
    return data[left] + (data[math.ceil(position)] - data[left]) * (position - left)


def fulfillment(c, rows, anchors):
    w = c['workload']; start = anchors['measurement']; end = start + w['measurementSeconds'] * 1000
    width = 5000 if c['role'] == 'cap-smoke-s1' else 10000
    scheduled = w.get('schedule', {}).get('kind') in ('users', 'smoke-s2')
    result = {}
    for kind in ('write', 'read'):
        total = cap.count(w, kind, 'measurement'); buckets = math.ceil((end-start)/width)
        planned = [0]*buckets; sent = [0]*buckets; timely = [0]*buckets
        for i in range(total): planned[cap.offset(w, kind, i, 'measurement')//(width*1000000)] += 1
        accepted = errors = completed = 0; durations = []; reasons = collections.Counter(); started = 0
        for r in rows:
            if r.get('type') != 'http' or r['kind'] != kind or r['phase'] != 'measurement': continue
            if r['outcome'] == 'NOT_SENT': reasons[r['reason']] += 1; continue
            started += 1
            if start <= r['startedEpochMillis'] < end: sent[(r['startedEpochMillis']-start)//width] += 1
            due = start+cap.offset(w,kind,r['ordinal'],'measurement')//1000000
            if due <= r['startedEpochMillis'] < min(end,due+max(1,cap.late(w,kind,'measurement')//1000000)):
                timely[(due-start)//width] += 1
            if r['outcome'] == 'ACCEPTED':
                accepted += 1; durations.append(r['k6DurationMillis'])
                completed += start <= r['bodyEpochMillis'] < end
            errors += r['httpStatus'] >= 500 or r['outcome'] == 'UNKNOWN'
        met = sum(sent)*1000 >= total*999 and all(s*1000 >= p*999 for s,p in zip(sent,planned))
        if scheduled: met &= sum(timely)*1000 >= total*999 and all(s*1000 >= p*999 for s,p in zip(timely,planned))
        result[kind] = dict(P=total,S_all=started,S_valid=sum(timely) if scheduled else sum(sent),A=accepted,
            inputMet=met if total else None,successRatio=accepted/started if started else None,
            systemErrorNumerator=errors,systemErrorDenominator=started,systemErrorRate=errors/started if started else None,
            completionAccepted=completed,completionAcceptedPerSecond=completed/w['measurementSeconds'],
            cohortAcceptedPerSecond=accepted/w['measurementSeconds'],notSentReasons=dict(reasons),
            windows=[dict(P=p,D=s,scheduledTimely=t if scheduled else None) for p,s,t in zip(planned,sent,timely)],
            duration=dict(count=accepted,p95Millis=quantile(durations,.95),p99Millis=quantile(durations,.99),
                          meaning='k6 2.3.0 res.timings.duration; linear interpolation; accepted send cohort'))
    return result


def checkpoint_series(samples, accepted, start, end, epsilon):
    """seq 수치 차이가 아닌 확인된 accepted 집합. 정렬+heap으로 O((A+Q) log A)."""
    ordered = sorted((a['validated'],seq,a['body']) for seq,a in accepted.items())
    active = {}; by_seq = []; by_body = []; index = 0; values = []
    covered = [s for s in samples if start <= s['queryEndEpochMillis'] < end]
    for s in covered:
        at = s['queryEndEpochMillis']; checkpoint = s['checkpoint']
        while index < len(ordered) and ordered[index][0] <= at:
            _,seq,body = ordered[index]; index += 1
            if seq > checkpoint:
                active[seq] = body; heapq.heappush(by_seq,seq); heapq.heappush(by_body,(body,seq))
        while by_seq and by_seq[0] <= checkpoint: active.pop(heapq.heappop(by_seq),None)
        while by_body and by_body[0][1] not in active: heapq.heappop(by_body)
        delay = max(0,at-by_body[0][0]) if by_body else 0
        values.append(dict(at=at,pendingCount=len(active),BMillis=delay,
                           queryMillis=at-s['queryStartEpochMillis']))
    times = [start]+[v['at'] for v in values]+[end]
    first = [v['BMillis'] for v in values if v['at'] < start+(end-start)/3]
    last = [v['BMillis'] for v in values if v['at'] >= start+2*(end-start)/3]
    before = statistics.median(first) if first else None; after = statistics.median(last) if last else None
    trend = None if len(first)<10 or len(last)<10 else after-before-max(1000,before*.1)
    return dict(samples=values,maxGapMillis=max(b-a for a,b in zip(times,times[1:])),
                maxBMillis=max((v['BMillis'] for v in values),default=None),firstCount=len(first),lastCount=len(last),
                firstMedianMillis=before,lastMedianMillis=after,trendMarginMillis=trend,uncertaintyMillis=epsilon)


def native_memory_observations(api,path,runner,runtime):
    """유한 native 시계열과 별도로 보존한 runner peak/한도 대조."""
    process_limit=runtime['MaxProcessMemory'];combined_limit=runtime['MaxCombinedMemory']
    owned={v['Phase']:v for v in runner['Processes']};seen=set();count=0;peak_private=peak_resident=0
    api.require(path.stat().st_size<=128*1024**2,'native observation bytes')
    for row in lines(api,path,86432,4096):
        count+=1;items=row['processes'];api.require(type(items) is list and len(items)<=2,'native observation simultaneous processes')
        api.require(type(row['epochMillis']) is int and 0<row['epochMillis']<=runtime['Expires'] and row['failure'] is None,'native observation time/failure')
        phases=set();private=resident=0
        for item in items:
            phase=item['Phase'];api.require(phase in owned and phase not in phases and type(item['Pid']) is int and item['Pid']>0,'native observation phase/PID')
            phases.add(phase);seen.add(phase)
            for field,peak in (('PrivateBytes','PeakPrivateBytes'),('WorkingSetBytes','PeakWorkingSet')):
                api.require(type(item[field]) is int and 0<=item[field]<=process_limit and item[field]<=owned[phase][peak],'native observation individual limit/peak')
            private+=item['PrivateBytes'];resident+=item['WorkingSetBytes']
        api.require(type(row['combinedPrivateBytes']) is int and type(row['combinedWorkingSetBytes']) is int and row['combinedPrivateBytes']==private and row['combinedWorkingSetBytes']==resident,'native observation exact combined')
        api.require(private<=combined_limit and resident<=combined_limit and private<=runner['PeakCombinedPrivate'] and resident<=runner['PeakCombinedResident'],'native observation combined limit/peak')
        peak_private=max(peak_private,private);peak_resident=max(peak_resident,resident)
    required={v for v in owned if v in ('warmup','measurement','ws')}
    api.require(count>0 and required<=seen and peak_private>0 and peak_resident>0,'native observation missing coverage')
    return dict(samples=count,phases=sorted(seen),peakPrivateBytes=peak_private,peakWorkingSetBytes=peak_resident)


def causal_interval(api,bounds,accepted):
    """claim <= 실제 송신 <= result, 이전 ACK <= callback <= 현재 batch 수신. 단일 bridge nanoTime 원점의 인과 경계."""
    lower,upper=bounds;claim=accepted['claimFloorMillis'];result=accepted['resultCeilingMillis']
    api.require(all(type(v) is int and 0<=v<21600000 for v in (lower,upper,claim,result)) and lower<=upper and claim<=result and claim<=upper,'WS causal clock/range/order')
    return max(0,lower-result),upper-claim


def ws_metrics(api,p,c,run,accepted,producer,epsilon):
    w=c['workload']; connections=w['wsConnections']; reserved=sum(cap.count(w,'write',phase) for phase in ('warmup','measurement'))
    canonical=list(lines(api,run/'k6-ws-canonical.jsonl',reserved,512))
    expected={seq:a['event'] for seq,a in accepted.items() if a['phase']!='bootstrap'} if connections else {}
    api.require(len(canonical)==len(expected) and {e['eventSeq']:e for e in canonical}==expected,'WS exact canonical payload set')
    for field in ('duplicates','missing','extra'):
        api.require(producer['ws'][field]==[0]*connections,'WS '+field)
    words=(reserved+63)//64
    api.require((run/'k6-ws-receipts.bin').stat().st_size==connections*words*8,'WS receipt bytes')
    api.require((run/'k6-ws-times.bin').stat().st_size==connections*reserved*8,'WS timing bytes')
    with (run/'k6-ws-receipts.bin').open('rb') as f:
        for _ in range(connections):
            for word in range(words):
                api.require(struct.unpack('>Q',f.read(8))[0]==(1<<max(0,min(64,len(canonical)-word*64)))-1,'WS connection exact set')
    causal=p.get('schemaVersion')==4 and cap1.expanded(p)
    causal_path=run/'k6-ws-causal.bin'
    if causal: api.require(causal_path.stat().st_size==connections*reserved*8,'WS causal bytes')
    path=Path(p['ownership']['temp'])/('k6-latency-'+uuid.uuid4().hex+'.sqlite')
    with path.open('x'): pass
    db=sqlite3.connect(path); db.execute('PRAGMA cache_size=-8192'); db.execute('PRAGMA temp_store=FILE')
    db.execute('CREATE TABLE latency(value INTEGER, lower_bound INTEGER, upper_bound INTEGER)' if causal else 'CREATE TABLE latency(value INTEGER)'); total=0; per_connection=[]
    try:
        with (run/'k6-ws-times.bin').open('rb') as f, (causal_path if causal else run/'k6-ws-times.bin').open('rb') as bounds:
            for connection in range(connections):
                cap.check_deadline(); f.seek(connection*reserved*8); bounds.seek(connection*reserved*8); durations=[]; intervals=[]
                for event in canonical:
                    received=struct.unpack('>q',f.read(8))[0]; a=accepted[event['eventSeq']]
                    api.require(received>0 and (causal or received-a['sent']>=-epsilon),'WS missing/clock-invalid reception')
                    interval=causal_interval(api,struct.unpack('>II',bounds.read(8)),a) if causal else (None,None)
                    if a['phase']=='measurement': durations.append(received-a['sent']); intervals.append(interval)
                if causal:
                    unused=(reserved-len(canonical))*8
                    api.require(f.read(unused)==bytes(unused) and bounds.read(unused)==bytes(unused),'WS unused receipt timing/bounds')
                values=dict(count=len(durations))
                for label,q in (('p95Millis',95),('p99Millis',99)): values[label]=api.percentile(durations,q)
                if causal:
                    values['causal']={label:dict(lowerMillis=api.percentile([x[0] for x in intervals],q),upperMillis=api.percentile([x[1] for x in intervals],q)) for label,q in (('p95Millis',95),('p99Millis',99))}
                per_connection.append(values); total+=len(durations)
                db.executemany('INSERT INTO latency VALUES (?,?,?)' if causal else 'INSERT INTO latency VALUES (?)',((n,*v) if causal else (n,) for n,v in zip(durations,intervals))); db.commit()
        db.execute('CREATE INDEX ordered_latency ON latency(value)'); overall=dict(count=total)
        for label,q in (('p95Millis',95),('p99Millis',99)):
            overall[label]=db.execute('SELECT value FROM latency ORDER BY value LIMIT 1 OFFSET ?',(math.ceil(total*q/100)-1,)).fetchone()[0] if total else None
        if causal:
            overall['causal']={label:{} for label in ('p95Millis','p99Millis')}
            for column,target in (('lower_bound','lowerMillis'),('upper_bound','upperMillis')):
                db.execute('CREATE INDEX '+column+'_sorted ON latency('+column+')')
                for label,q in (('p95Millis',95),('p99Millis',99)):
                    overall['causal'][label][target]=db.execute('SELECT '+column+' FROM latency ORDER BY '+column+' LIMIT 1 OFFSET ?',(math.ceil(total*q/100)-1,)).fetchone()[0] if total else None
        return dict(clockMode='bridge-causal-brackets-v1' if causal else 'epoch-bracket-v1',overall=overall,connections=per_connection,sqliteBytes=path.stat().st_size,uncertaintyMillis=epsilon,
                    meaning='HTTP send to complete WS body; nearest-rank; HTTP-leading/WS-leading and reordered callbacks retained')
    finally: db.close()


def classify(c,inputs,ws,db,invalid,performance_reasons,tool_reasons):
    input_reasons=[kind+' input below 99.9%' for kind,v in inputs.items() if v['inputMet'] is False]
    for kind,v in inputs.items():
        if not v['P']: continue
        if not v['S_all']: input_reasons.append(kind+' zero sent')
        elif v['successRatio']<.999 or v['systemErrorRate']>.001: performance_reasons.append(kind+' success/error ratio')
    v=inputs.get('write',{})
    if v.get('P') and v.get('S_all'):
        if not v['A']: performance_reasons.append('write zero accepted; HTTP latency unavailable')
        elif v['duration']['p95Millis']>100 or v['duration']['p99Millis']>250: performance_reasons.append('accepted HTTP duration')
    if ws:
        eps=ws['uncertaintyMillis']
        for i,v in enumerate([ws['overall']]+ws['connections']):
            if not v['count']:
                if inputs['write']['A'] and c['workload']['wsConnections']: tool_reasons.append('WS empty accepted sample')
                continue
            for label,limit in (('p95Millis',250),('p99Millis',500)):
                value=v[label]
                lower,upper=(v['causal'][label]['lowerMillis'],v['causal'][label]['upperMillis']) if ws.get('clockMode')=='bridge-causal-brackets-v1' else (value-eps,value+eps)
                if lower>limit: performance_reasons.append(f'WS {i} {label}')
                elif upper>limit: tool_reasons.append(f'WS {i} {label} clock uncertainty')
    if db:
        eps=db['uncertaintyMillis']; value=db['maxBMillis']; margin=db['trendMarginMillis']
        if db['maxGapMillis']+2*eps>3000 or value is None: tool_reasons.append('DB observation gap/empty')
        if value is not None:
            if value-eps>10000: performance_reasons.append('DB B(t) exceeds 10 seconds')
            elif value+eps>10000: tool_reasons.append('DB B(t) clock uncertainty')
        if c['role']!='cap-smoke-s1':
            if margin is None: tool_reasons.append('DB thirds require ten samples each')
            elif margin>2.1*eps: performance_reasons.append('DB median trend')
            elif margin>-2.1*eps: tool_reasons.append('DB median trend clock uncertainty')
    verdict='INVALID_OR_STOPPED' if invalid else 'TOOL_LIMIT' if tool_reasons else 'INPUT_LIMIT' if input_reasons else 'PERFORMANCE_MISS' if performance_reasons else 'PASS'
    return dict(verdict=verdict,reasons=invalid+tool_reasons+input_reasons+performance_reasons,
                validityMet=not invalid,performanceCriteriaMet=not performance_reasons,inputCriteriaMet=(not input_reasons) if inputs else None,inputEvaluation="assessed" if inputs else "unavailable",
                observationLimit=bool(tool_reasons),inputLimitCause='undetermined' if input_reasons else None,
                failureCategories=dict(executionOrConsistency=invalid,tool=tool_reasons,input=input_reasons,performance=performance_reasons),
                smokeVerification=('PASS' if verdict=='PASS' else 'FAIL') if c['role'].startswith('cap-smoke') else None,
                capacityPoint=not c['role'].startswith('cap-smoke') and verdict in ('PASS','PERFORMANCE_MISS'))


def required_native_output(result,process,metrics_bytes,process_limit):
    return (result['RequiredOutputComplete'] is True and result['ActualExit']==process['ActualExit']
        and result['MetricsBytes']==metrics_bytes and 0<result['PeakWorkingSet']<=process_limit
        and 0<result['PeakPrivateBytes']<=process_limit)


def analyze(api,p,c,plan_hash,run):
    api.require(p.get('phase')=='CAP' or p['stage']=='A-2','k6 current CAP/A-2 analysis scope')
    check=api.EvidenceCheck(run); require=api.require; w=c['workload']
    producer=check.artifact('k6-producer.json'); source=check.artifact('k6-input.json'); runner=check.artifact('k6-runner.json')
    analysis_limit=source.get('limits',{}).get('analysisMemoryBytes',536870912)
    expected_memory=cap1.ANALYSIS_MEMORY if cap1.expanded(p) and 'analysisMemoryBytes' in source.get('limits',{}) else 536870912
    api.require(analysis_limit==expected_memory,'k6 frozen analysis memory reservation')
    cap.memory_limit(analysis_limit)
    if p.get('schemaVersion')==4:
        ce=source['clockEvidence']
        require(ce['kind']=='bridge-causal-brackets-v1' and ce['source']=='Windows HotSpot System.nanoTime/QPC' and type(ce['qpcFrequency']) is int and ce['qpcFrequency']>=10000 and ce['outwardGuardMillis']==1 and ce['maximumElapsedMillis']==21600000 and ce['epochOffsetUsed'] is False,'required causal clock evidence')
    check.check('k6 source identity',lambda:source['schema']=='phase18-k6-v2' and source['planHash']==plan_hash and source['plan']==p)
    runtime=check.artifact('k6-runtime.json')
    check.check('k6 actual binary/script',lambda:runtime['BinarySha256'].lower()==source['k6BinarySha256'] and runtime['ScriptSha256'].lower()==source['scriptSha256'] and 'v2.3.0 ' in runtime['Version'])
    combined_limit=1342177280 if source.get('limits',{}).get('combinedMemoryBytes') is not None and p['stage']=='CAP-1' and p.get('bounds',{}).get('samples',0)>10000 else 536870912
    process_limit=805306368 if combined_limit>536870912 else 536870912
    if p.get("schemaVersion")==4 and cap1.expanded(p): combined_limit,process_limit=1792*cap1.MIB,1280*cap1.MIB
    if source.get('limits',{}).get('combinedMemoryBytes') is not None:
        check.check('k6 frozen native memory reservation',lambda:source['limits']['combinedMemoryBytes']==combined_limit==runtime['MaxCombinedMemory'] and runtime['GoMemoryLimit']=='512MiB' and source['limits']['processMemoryBytes']==runtime['MaxProcessMemory']==process_limit)
    if p.get('schemaVersion')==4:
        check.check('k6 frozen Go GC candidate',lambda:source['limits']['goRuntime']['gcPercent']==runtime['GoGcPercent']==100 and source['limits']['goRuntime']['memoryBytes']==512*cap1.MIB and not runtime.get('DiagnosticOnly',False))
        check.check('k6 native memory observations',lambda:native_memory_observations(api,run/'k6-native-memory.jsonl',runner,runtime))
    check.check('k6 combined memory',lambda:0<runner['PeakCombinedResident']<=combined_limit and 0<runner['PeakCombinedPrivate']<=combined_limit)
    check.check('k6 producer completion',lambda:producer['schema']=='phase18-k6-v2' and producer['planHash']==plan_hash and producer['producerComplete'] is True and producer['firstFailure'] is None and producer['unresolved']==[])
    if source and 'wsTransport' in source.get('limits',{}) and w['wsConnections']>0:
        def transport_complete():
            t=producer['wsTransport']; b=producer['wsBridge']
            return b['transportComplete'] is True and t['failure'] is None and all(t[k]==0 for k in ('pending','pendingBytes','waiting','inFlight','unconfirmedBatches')) and t['received']==t['acknowledged']==b['saved']
        check.check('k6 WS transport exact storage confirmation',transport_complete)
        if 'cacheCount' in source['limits']['wsTransport']:
            def serialization_cache():
                t=producer['wsTransport']; l=source['limits']['wsTransport']
                return all(type(t[k]) is int and t[k]>=0 for k in ('maxCacheEntries','maxCacheBytes','cacheHits','cacheMisses')) and t['maxCacheEntries']<=l['cacheCount'] and t['maxCacheBytes']<=l['cacheBytes'] and t['cacheHits']+t['cacheMisses']==t['received']-2*w['wsConnections']
            check.check('k6 bounded serialization cache',serialization_cache)
    check.check('k6 runner ownership',lambda:runner['Complete'] is True and runner['FirstFailure'] is None and bool(runner['Processes']) and all(r['Released'] is True and r['Failure'] is None and type(r['ActualExit']) is int and r['ActualExit'] in (0,99) for r in runner['Processes']))
    anchors={}; drained={}; eps=1; ws_clock=[]; records=None; inputs={}; accepted={}; ws={}; db={}; phases=[]
    facts=dict(sentIds=set(),acceptedWrites=0,acceptedReads=0); tool=[]; performance=[]; roles={}
    try:
        for boundary in producer['phaseBoundaries']:
            if boundary['event']=='k6-scenario-start':
                require(boundary['phase'] not in anchors,'one actual phase anchor'); anchors[boundary['phase']]=boundary['epochMillis']
            elif boundary['event']=='drained': drained[boundary['phase']]=boundary['epochMillis']
            elif boundary['event']=='ws-clock':
                require(boundary['after']>=boundary['before'],'WS clock bracket')
                ws_clock.append(max(abs(boundary['bridge']-boundary['before']),abs(boundary['bridge']-boundary['after']))+1)
        for phase in ('warmup','measurement'):
            require(phase in anchors or w[phase+'Seconds']==0,'required phase actual clock')
            if phase in anchors: check.check('full '+phase+' period and drain',lambda:drained[phase]>=anchors[phase]+w[phase+'Seconds']*1000)
        require(not w['wsConnections'] or ws_clock,'WS clock observation missing')
        eps=max([eps]+ws_clock)
        counts={k+'/'+ph:cap.count(w,k,ph) for k in ('write','read') for ph in ('bootstrap','initial-read','warmup','measurement')}
        request_limit=source.get('limits',{}).get('requests',140000)
        require(request_limit==(cap1.MAX_TERMINALS if cap1.expanded(p) and 'analysisMemoryBytes' in source.get('limits',{}) else 140000),'k6 frozen terminal reservation')
        require(sum(counts.values())<=request_limit,'k6 terminal allocation bound')
        rows_path=Path(p['ownership']['temp'])/('k6-rows-'+uuid.uuid4().hex+'.sqlite'); records=cap.Rows(rows_path)
        seen=set(); explicit=set(); users=collections.defaultdict(list); opened={}; closed={}; terminal=0
        for r in lines(api,run/'k6-records.jsonl',sum(counts.values())+w['wsConnections']*3):
            records.append(r)
            if r['type']=='ws':
                conn=api.integer(r['connection'],'connection',0,w['wsConnections']-1)
                target=opened if r['event']=='open' else closed
                require(r['event'] in ('open','close') and conn not in target,'WS lifecycle unique')
                if r['event']=='close': require(r['code']==1000,'WS close code')
                target[conn]=r['epochMillis']; continue
            key=r['kind']+'/'+r['phase']; ordinal=api.integer(r['ordinal'],'ordinal',0,counts[key]-1)
            request=f"{c['runId']}/{c['caseId']}/{key}/{ordinal}"
            require(r['id']==request and request not in seen,'exact original request ID'); seen.add(request)
            offset=0 if r['phase']=='bootstrap' else cap.offset(w,r['kind'],ordinal,r['phase'])//1000000
            if r['type']=='unstarted':
                require(w['schedule']['kind'] in ('users','smoke-s2') and r['dueOffsetMillis']==offset and 'startedEpochMillis' not in r,'unstarted original schedule')
                explicit.add(request); continue
            require(r['type']=='http' and r['schema']=='phase18-k6-v2','HTTP schema'); terminal+=1
            due=anchors[r['phase']]+offset; require(r['dueEpochMillis']==due,'original due')
            if r['outcome']=='NOT_SENT':
                require('startedEpochMillis' not in r and 'event' not in r and r['reason'] in ('arrival_late','processing_capacity','user_not_ready'),'not sent evidence'); continue
            start=api.integer(r['startedEpochMillis'],'send'); body=api.integer(r['bodyEpochMillis'],'body'); valid=api.integer(r['validationEpochMillis'],'validation')
            require(valid>=body>=start,'HTTP actual time order'); facts['sentIds'].add(request)
            a,b,t=(r[k] for k in ('bridgeBeforeEpochMillis','bridgeAfterEpochMillis','bridgeEpochMillis'))
            require(b>=a and b<=start,'HTTP clock bracket'); eps=max(eps,abs(t-a)+1,abs(t-b)+1)
            duration=r['k6DurationMillis']; require(type(duration) in (int,float) and math.isfinite(duration) and duration>=0,'k6 duration')
            check.check('UNKNOWN/non-allowlisted response '+request,lambda:r['outcome']=='ACCEPTED' and r['httpStatus']==200 or r['outcome']=='REJECTED' and r['kind']=='write' and r['phase']!='bootstrap' and (r['httpStatus'],r['reason']) in ((503,'cap_write_busy'),(429,'cap_cooldown')) and 'event' not in r)
            if r['kind']=='write':
                users[r['userOrdinal']].append((due,start,valid,r['outcome'],r['attempt']))
                if r['outcome']=='ACCEPTED':
                    e=r['event']; seq=api.integer(e['eventSeq'],'seq',1); require(seq not in accepted,'duplicate accepted seq')
                    accepted[seq]=dict(event=e,sent=start,body=body,validated=valid,phase=r['phase']); facts['acceptedWrites']+=1
                    if p.get('schemaVersion')==4: accepted[seq].update(claimFloorMillis=r['claimFloorMillis'],resultCeilingMillis=r['resultCeilingMillis'])
            elif r['outcome']=='ACCEPTED':
                require(r['decodedLength']==65536 and int(r['tileVersion'])>=0,'validated read'); facts['acceptedReads']+=1
        require(terminal==producer['terminal'] and len(facts['sentIds'])==producer['started'],'exact producer row correspondence')
        if w['schedule']['kind'] in ('users','smoke-s2'): require(len(seen)==sum(counts.values()),'scheduled exact original set')
        for attempts in users.values():
            attempts.sort()
            for before,after in zip(attempts,attempts[1:]): require(after[0]>=before[2]+181000 and after[1]>=before[2]+181000 and after[4]==before[4]+1,'original due user readiness')
        if w['schedule']['kind']=='users' and not all(len(users[i])>=2 and all(a[3]=='ACCEPTED' for a in users[i][:2]) for i in range(w['schedule']['activeUsers'])): performance.append('CAP-3 repeated user successes')
        if w['schedule']['kind']=='smoke-s2' and not (set(users)=={0,1} and all(len(v)==2 and all(a[3]=='ACCEPTED' for a in v) for v in users.values())):
            performance.append('S2 two users two successes')
        start=anchors['measurement']; end=start+w['measurementSeconds']*1000
        require(set(opened)==set(closed)==set(range(w['wsConnections'])) and all(opened[i]<=start and closed[i]>=end for i in opened),'maintained WS lifetime')
        inputs=fulfillment(c,records,anchors)
        for process in runner['Processes']:
            phase=process['Phase']; require(phase not in phases,'unique k6 process phase'); phases.append(phase)
            result=check.artifact('k6-'+phase+'-exit.json'); summary=check.artifact('k6-'+phase+'-summary.json')
            check.check('k6 '+phase+' required output',lambda:required_native_output(result,process,(run/('k6-'+phase+'-metrics.json')).stat().st_size,process_limit))
            check.hash_matches('k6-'+phase+'-metrics.json',result.get('MetricsSha256')); check.hash_matches('k6-'+phase+'-summary.json',result.get('SummarySha256'))
            require(summary['schema']=='phase18-k6-summary-v1' and summary['k6Version']=='2.3.0' and summary['phase']==phase,'k6 summary identity')
            if phase=='measurement' and counts['write/measurement']:
                trend=summary['data']['metrics']['accepted_http_duration']; values=trend['values']; wanted=inputs['write']['duration']
                require(values['count']==wanted['count'],'accepted Trend count')
                if wanted['count']:
                    for label,name in (('p95Millis','p(95)'),('p99Millis','p(99)')): require(math.isclose(values[name],wanted[label],rel_tol=1e-9,abs_tol=1e-6),'accepted-only k6 Trend')
                if result['ActualExit']==99: performance.extend(result['FailedThresholds'])
        expected_phases={'measurement'}|({'warmup'} if w['warmupSeconds'] else set())|({'ws'} if w['wsConnections'] else set())|({'bootstrap'} if w['bootstrap'] else set())|({'initial-read'} if w['schedule']['kind']=='users' else set())
        require(set(phases)==expected_phases,'required k6 phase processes')
        ws=ws_metrics(api,p,c,run,accepted,producer,eps)
        if p.get('phase')=='CAP':
            observer=check.artifact('cap-observer.json')
            require(observer['schemaVersion']==3 and observer['clockId']=='phase18-k6-epoch-millis' and observer['planHash']==plan_hash and observer['connectionClosed'] is True,'DB observer identity/close')
            for name,digest in observer['hashes'].items(): check.hash_matches(name,digest)
            samples=list(lines(api,run/'cap-db.jsonl',p['bounds']['totalSeconds']+2 if p.get('stage')=='CAP-1' and p.get('bounds',{}).get('samples',0)>10000 else 3602)); last=None
            for sample in samples:
                require(sample['planHash']==plan_hash and sample['clockId']==observer['clockId'] and sample['queryEndEpochMillis']>=sample['queryStartEpochMillis'] and sample['checkpoint']>=0,'DB query shape')
                if last: require(sample['queryStartEpochMillis']>=last['queryEndEpochMillis'] and sample['checkpoint']>=last['checkpoint'],'DB clock/checkpoint order')
                last=sample
            require(len(samples)==observer['queries'],'DB query count')
            db=checkpoint_series(samples,accepted,start,end,eps)
    except api.EVIDENCE_ERRORS+(struct.error,sqlite3.Error) as error:
        check.mismatch.append('k6 consumption: '+str(error))
    finally:
        if records is not None: records.close()
    for role in ('initial','recovery'):
        directory=run if role=='initial' else run.parent/'recovery'
        roles[role]=api.role_evidence(directory,plan_hash,c,[],producer,role,p['stage']!='A-2',p,facts)
        m=roles[role]['manifest']
        check.check(role+' k6 manifest',lambda:m['inputKind']=='phase18-k6-v2' and bool(m['k6Hashes']))
        required={'k6-runtime.json','k6-input.json','k6-producer.json','k6-runner.json','k6-records.jsonl','k6-ws-canonical.jsonl','k6-ws-receipts.bin','k6-ws-times.bin'}
        if p.get('phase')=='CAP': required.update(('cap-observer.json','cap-db.jsonl','cap-http.jsonl'))
        if p.get('schemaVersion')==4: required.update(('k6-ws-causal.bin','k6-native-memory.jsonl'))
        required.update('k6-'+phase+suffix for phase in phases for suffix in ('-exit.json','-summary.json'))
        check.check(role+' required hash set',lambda:required<=m['k6Hashes'].keys())
        for name,digest in m.get('k6Hashes',{}).items():
            check.check('flat k6 hash path',lambda:Path(name).name==name and '/' not in name and '\\' not in name)
            if Path(name).name==name and '/' not in name and '\\' not in name: check.hash_matches(name,digest)
    a,b=(roles[r]['manifest'] for r in ('initial','recovery'))
    check.check('same initial recovered by new JVM',lambda:all(a[k]==b[k] for k in ('planHash','recordsHash','inputHash','runtimeReceiptHash','environmentIdentity','catalog','wal','mode','segmentBytes','checkpoint','k6Hashes','users')) and (a['pid'],a['startUtc'])!=(b['pid'],b['startUtc']) and b['consistencyRecoveryConnected'] is True)
    invalid=check.missing+check.mismatch+[str(f) for f in check.failures]
    for role,value in roles.items():
        if not value['complete']: invalid.append(role+' consistency/recovery/ownership evidence: '+str(value['missing']+value['mismatch']+value['failures']))
    if not inputs: invalid.append('input aggregation unavailable')
    memory=cap.memory_usage();cap.memory_limit(None)
    if max(memory.values())>analysis_limit: invalid.append('analysis memory TOOL_LIMIT')
    result=classify(c,inputs,ws,db,invalid,performance,tool)
    result.update(input=inputs,ws=ws,db=db,contractVersion=3,actualExits=runner.get('Processes',[]),
        failureDetails=dict(producerFirstFailure=producer.get('firstFailure'),runnerFirstFailure=runner.get('FirstFailure'),
            unresolvedRequests=producer.get('unresolved',[]),processOwnership=[r for r in runner.get('Processes',[]) if r.get('Released') is not True or r.get('Failure')],
            requiredOutputOrInput=check.report(),initialConsistencyShutdown=roles['initial'],recoveryConsistencyShutdown=roles['recovery']))
    return dict(schemaVersion=2,inputKind='phase18-k6-v2',planHash=plan_hash,status='EXECUTION_FAILED' if invalid else 'COMPLETE',
                cap=result,analysisMemory=memory,evidence=dict(complete=not invalid,roles=roles,consumption=check.report()),
                integrity=dict(complete=not invalid,initialComplete=roles['initial']['complete'],recoveryComplete=roles['recovery']['complete'] and not check.mismatch,drainComplete=roles['initial']['drainComplete'],wsComplete=bool(ws) and not invalid),
                firstFailure=producer.get('firstFailure') or runner.get('FirstFailure'),
                meaning='k6 native records; no synthesized Java raw, send timestamps or successful checks; service-free fixtures are not CAP performance evidence')


if __name__=='__main__':
    import argparse
    import importlib.util
    import json
    parser=argparse.ArgumentParser()
    parser.add_argument('--validate-metrics',required=True,type=Path)
    parser.add_argument('--summary',required=True,type=Path)
    parser.add_argument('--expires',required=True,type=int)
    args=parser.parse_args()
    spec=importlib.util.spec_from_file_location('analysis',Path(__file__).with_name('analyze-phase18.py'))
    api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
    try:
        cap.set_deadline(args.expires);cap.memory_limit(536870912)
        result=validate_metrics(api,args.validate_metrics,api.read(args.summary,1048576))
        cap.check_deadline();result.update(valid=True,memory=cap.memory_usage());code=0
    except (OSError,ValueError,KeyError,TypeError) as error:result=dict(valid=False,reason=str(error));code=2
    print(json.dumps(result));raise SystemExit(code)
