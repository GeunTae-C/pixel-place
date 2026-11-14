"""CAP-1의 동결 시간·출력 산정. 서비스 실행과 기존 이력 변경 책임 없음."""
import math
from pixel_place_paths import historical, control_path

MIB=1024**2
MAX_INTEGER=2**63-1
MAX_TERMINALS=320000
MAX_EVENTS=320000
MAX_DELIVERIES=32000000
ANALYSIS_MEMORY=1024*MIB

def expanded(p):
    return p.get("schemaVersion") in (3,4) and p.get("phase")=="CAP" and p.get("stage")=="CAP-1" and p.get("bounds",{}).get("samples",0)>10000
def require(ok,message):
    if not ok: raise ValueError(message)

def expand(limit,usage):
    require(type(limit) is int and type(usage) is int and 0<limit<=MAX_INTEGER and 0<=usage<=MAX_INTEGER//2,'CAP1 finite expansion inputs')
    while 2*usage>=limit:
        require(limit<=MAX_INTEGER//5,'CAP1 expansion overflow')
        limit*=5
    return limit

def profile(rate,seconds,kind="cap1-output-50pct-v1",role="cap-target"):
    require(role in ("cap-target","cap-diagnostic-ab"),"CAP1 supported role")
    if role=="cap-diagnostic-ab":
        require(kind=="cap1-causal-memory-v2" and (rate,seconds) in ((105,120),(105,1200),(500,60)),"CAP1 explicit A/B combinations only")
    require(kind in ("cap1-output-50pct-v1","cap1-causal-memory-v2"),"CAP1 known tool profile")
    revised=kind=="cap1-causal-memory-v2"
    require(type(rate) is int and 105<=rate<=10000 and type(seconds) is int and seconds in (60,120,1200),'CAP1 supported input')
    events=rate*(30+seconds)
    requests=events+1
    deliveries=events*100
    users=min(events,rate*193+128)+1
    parts=dict(environment=300,build=300,fixture=300+math.ceil(users/50),readiness=270,
        warmup=30,measurement=seconds,drains=9*180,control=30,stopAck=2,
        recovery=300,analysis=max(120,math.ceil(deliveries/25000)),shutdown=10,diagnostic=5,mysqlStop=180,closure=180,margin=300)
    total=sum(parts.values())
    lifetime=total+180
    samples=2+lifetime*4
    trace_entries=4*lifetime+2048
    trace_bytes=4*lifetime*512+2048*2048+512*1024
    # 검증된 기존 k6 NDJSON 상한, canonical·bitset·파일 정렬, 두 JVM의 관측 수명 포함.
    deliveries=events*100
    metrics=96*MIB+requests*65536+deliveries*128
    bitsets=((events+63)//64)*8*100
    disk=2*MIB+requests*2560+events*256+bitsets+100*1024+samples*1024+deliveries*(216 if revised else 72)
    run=metrics+disk+requests*4096+16*MIB+2*(samples*6144+trace_bytes+MIB)+32*MIB
    usage=dict(workspaceBytes=2048*MIB+requests*4096+deliveries*(216 if revised else 72),samples=samples,sampleBytes=samples*6144,traceEntries=trace_entries,traceBytes=trace_bytes,
        metricsBytes=metrics,runBytes=run,evidenceBytes=run+64*MIB)
    bases=dict(workspaceBytes=2048*MIB,samples=10000,sampleBytes=64*MIB,traceEntries=6000,traceBytes=2*MIB,
        metricsBytes=96*MIB,runBytes=256*MIB,evidenceBytes=320*MIB)
    limits={k:expand(bases[k],v) for k,v in usage.items()}
    storage_usage=dict(walBytes=MIB+requests*512,
        databaseBytes=128*MIB+users*4096+requests*4096+min(events,1024)*131072,
        binlogBytes=128*MIB+users*4096+requests*(131072+4096))
    storage_usage['mysqlBytes']=storage_usage['databaseBytes']+storage_usage['binlogBytes']+1024*MIB
    storage_limits={key:expand(base,storage_usage[key]) for key,base in dict(walBytes=4*MIB,
        databaseBytes=1024*MIB,binlogBytes=1024*MIB,mysqlBytes=2048*MIB).items()}
    return dict(kind=kind,stage='CAP-1',role=role,writeRate=rate,
        measurementSeconds=seconds,readRate=0,wsConnections=100,seed=15,bootstrap=True,
        requests=requests,events=events,deliveries=deliveries,users=users,writeVus=128,readVus=4,
        storage=dict(usage=storage_usage,limits=storage_limits),parts=parts,totalSeconds=total,observationSeconds=lifetime,usage=usage,limits=limits,
        appHeapBytes=2048*MIB,appSafetyHeapBytes=1536*MIB,k6MemoryBytes=(1792 if revised else 1280)*MIB,k6ProcessMemoryBytes=(1280 if revised else 768)*MIB,k6GoMemoryBytes=512*MIB,analysisMemoryBytes=ANALYSIS_MEMORY,**(dict(k6GoGcPercent=100) if revised else {}))

def validate_profile(value):
    require(value==profile(value['writeRate'],value['measurementSeconds'],value['kind'],value.get('role','cap-target')),'CAP1 frozen profile mismatch')
    return value

def structural_support(value):
    p=validate_profile(value)
    failures=[]
    for condition,reason in ((p['requests']<=MAX_TERMINALS,'CAP1 HTTP retention 320000'),
            (p['events']<=MAX_EVENTS and p['deliveries']<=MAX_DELIVERIES,'CAP1 WS retention 320000/32000000'),
            (p['totalSeconds']<21570,'measurement token lifetime'),
            (p['users']<=200000,'user allocation 200000'),
            (p['limits']['metricsBytes']<=60*1024*MIB,'CAP1 k6 streamed metrics limit 60GiB'),
            (p['limits']['evidenceBytes']<=200*1024*MIB,'CAP1 MySQL evidence parameter 200GiB'),
            (p['storage']['limits']['mysqlBytes']<=250*1024*MIB,'CAP1 MySQL growth parameter 250GiB')):
        if not condition:failures.append(reason)
    return failures

def require_closed_preload_allocation_failure(close,plan,requests):
    """실제 run 미시작·계획 할당 오류만 재개 가능. 성능/정합성 실패를 제외 처리하지 않음."""
    require(close['Status']=='FAILED' and close['RunExit'] is None and close['AnalysisExit'] is None,
        'CAP1 preload failure only')
    require(close['OwnedResourcesClosed'] and close['NormalMysqlStop'] and close['BudgetAfterVerified']
        and close['OwnedJava']==[] and close['Remaining3307']==0 and close['RemainingRunLock'] is False,
        'CAP1 preload ownership unresolved')
    require(close['Budget']['gateMet'] and close['Budget']['transition']['closed'], 'CAP1 preload budget unresolved')
    workers=close['OwnedWorkers']
    failed=[x for x in workers if x['ActualExit']!=0 or x['FirstFailure']]
    require(len(failed)==1 and failed[0]['ActualExit']==1 and failed[0]['Request'].endswith('-plan.request.json'),
        'CAP1 preload exact failed step')
    require(all(x['Joined'] and x['Released'] and x['DbUsersClosed'] and not x['CleanupFailure'] for x in workers),
        'CAP1 preload workers unresolved')
    require(len(requests)==len(workers) and all('-run.request.json' not in x['Request'] and '-analysis.request.json' not in x['Request'] for x in workers),
        'CAP1 preload no load issued')
    for request in requests:
        require(request.get('Parameters',{}).get('Action') not in ('Run','ProduceK6','Pilot','VerifyTransport','VerifyObservation'),
            'CAP1 preload service load action found')
        require('ProduceK6' not in request.get('Arguments',[]) and 'Run' not in request.get('Arguments',[]),
            'CAP1 preload native load action found')
    require(plan['stage']=='CAP-1' and plan['cases'][0]['role']=='cap-target'
        and plan['bounds']['users']==64 and plan['bounds']['writeTerminals']==64
        and plan['cases'][0]['workload']['userPool']>64,'CAP1 exact old template allocation defect')

def require_closed_storage_failure(close,failure,analysis,worker_requests):
    require(close['Status']=='FAILED' and close['RunExit']==1 and close['AnalysisExit']==0,
        'CAP1 exact storage execution failure')
    require(close['OwnedResourcesClosed'] and close['NormalMysqlStop'] and close['BudgetAfterVerified']
        and close['OwnedJava']==[] and close['Remaining3307']==0 and close['RemainingRunLock'] is False
        and close['Budget']['gateMet'] and close['Budget']['transition']['closed'],'CAP1 storage failure ownership')
    require(failure==dict(normalShutdown=True,kind='java.lang.IllegalArgumentException',nextTaskAllowed=False,
        failures=['java.lang.IllegalArgumentException: Phase18 rejected: actual catalog/binlog budget']),
        'CAP1 storage failure exact cause; other integrity/recovery failure cannot resume')
    workers=close['OwnedWorkers'];failed=[x for x in workers if x['ActualExit']!=0 or x['FirstFailure']]
    require(len(failed)==1 and failed[0]['ActualExit']==1 and failed[0]['Request'].endswith('-run.request.json'),
        'CAP1 storage failure exact worker')
    require(all(x['Joined'] and x['Released'] and x['DbUsersClosed'] and not x['CleanupFailure'] for x in workers),
        'CAP1 storage failure workers unresolved')
    require(analysis['status']=='EXECUTION_FAILED' and analysis['cap']['verdict']=='INVALID_OR_STOPPED'
        and analysis['cap']['inputCriteriaMet'] and analysis['cap']['performanceCriteriaMet'],
        'CAP1 no valid input/performance failure override')
    require(len(worker_requests)==len(workers),'CAP1 storage original request linkage')

def require_closed_collector_failure(close,failure,analysis,replay):
    require(close['Status']=='FAILED' and close['RunExit']==1 and close['AnalysisExit']==0
        and close['OwnedResourcesClosed'] and close['NormalMysqlStop'] and close['BudgetAfterVerified']
        and close['OwnedJava']==[] and close['Remaining3307']==0 and close['RemainingRunLock'] is False
        and close['Budget']['gateMet'] and close['Budget']['transition']['closed'],'CAP1 collector STOP closed ownership')
    require(failure['normalShutdown'] and failure['nextTaskAllowed'] is False
        and failure['kind']=='java.lang.IllegalStateException' and set(failure['failures'])=={
            'java.lang.IllegalStateException: app_control_stop','java.io.IOException: Process exit 1',
            'java.lang.IllegalStateException: Safety stop: capture-safety-evidence-lost'},'CAP1 exact collector counter STOP')
    workers=close['OwnedWorkers'];failed=[x for x in workers if x['ActualExit']!=0 or x['FirstFailure']]
    require(len(failed)==1 and failed[0]['ActualExit']==1 and failed[0]['Request'].endswith('-run.request.json')
        and all(x['Joined'] and x['Released'] and x['DbUsersClosed'] and not x['CleanupFailure'] for x in workers),
        'CAP1 collector STOP worker closure')
    require(analysis['status']=='EXECUTION_FAILED' and analysis['cap']['verdict']=='INVALID_OR_STOPPED'
        and analysis['cap']['inputCriteriaMet'] is None,'CAP1 interrupted input is not valid INPUT_LIMIT')
    require(replay['originalStops']==1 and replay['newStops']==0 and replay['oldUpper']>1178
        and 0<=replay['newUpper']<=1178 and replay['servicesStarted']==0 and replay['originalResultPreserved'],
        'CAP1 actual collector counter replay')

def diagnosed_ws_case(session):
    """실제로 별도 진단한 두 원본만 허용. 이름/boolean 선언만으로 다른 실패 통과 금지."""
    cases={
        'phase18-cap1-w250-20261013-200125':dict(kind='cap1-w250-ws-diagnostic-v1',
            plan='3cb2d83cea3c8624ef83c37840237a0b3110df0b34c564f5b8ac64115c700556',
            receipt='9c57bd1e762875229bbbf1918ca0d881d071669f4543768c490d7b09b7c67871',
            accepted=60,durable=62,http=62,mysqlFiles=747,mysqlBytes=97458345153,
            effects={(59,61,61,2864,7215,60),(60,62,62,5240,1715,61)}),
        'phase18-cap1-w500-20261014-011605':dict(kind='cap1-w500-ws-memory-diagnostic-v1',
            plan='9c65c1986e8f9d898d9ab2d17630a512897e4ac89a9ff11b63df29106fc8a3a3',
            receipt='4d0f990ba095bc58d1f3e5d937da908770c90f2d0dec02d6c40ec0ca8140a0be',
            accepted=12649,durable=12651,http=12650,mysqlFiles=778,mysqlBytes=107979617137,
            effects={(12648,12650,12650,153,454,105),(12649,12651,12651,8170,5707,106)})
    }
    require(session in cases,'CAP1 no separate diagnosis for this WS failure')
    return cases[session]


def require_ws_diagnostic_facts(close,analysis,diagnostic,before,after,preservation,owner,mysql,budget,session='phase18-cap1-w250-20261013-200125'):
    """과거의 특정 도구 중단만 별도 진단. 과거 HTTP 응답/PASS나 새 UNKNOWN을 생성하지 않음."""
    known=diagnosed_ws_case(session)
    require(close['SessionId']==session and close['Status']=='FAILED' and close['RunExit']==1 and close['AnalysisExit']==0
        and close['OwnedResourcesClosed'] and close['NormalMysqlStop'] and close['BudgetAfterVerified']
        and close['OwnedJava']==[] and close['Remaining3307']==0 and close['RemainingRunLock'] is False,
        'CAP1 WS original ownership/exit')
    require(analysis['status']=='EXECUTION_FAILED' and analysis['cap']['verdict']=='INVALID_OR_STOPPED'
        and analysis['cap']['inputCriteriaMet'] is None,'CAP1 WS exception is not a performance/input miss')
    require(diagnostic['diagnosticOnly'] is True and diagnostic['originalClassification']=='INVALID_OR_STOPPED'
        and diagnostic['historicalHttpResponse']=='UNRESOLVED' and diagnostic['confirmedAccepted']==known['accepted']
        and diagnostic['serverDurableEvents']==known['durable'] and diagnostic['unexplainedEffects']==0
        and diagnostic['normalContextClose'] is True and diagnostic['ddlOrReseed'] is False
        and diagnostic['newJvmPid']>0 and diagnostic['consistency']['complete'] is True,
        'CAP1 WS complete separate durable/recovery diagnosis')
    expected=known['effects'];actual=set()
    for item in diagnostic['requests']:
        require(item['state']=='identified_effect_exists' and item['historicalHttpResponse']=='UNRESOLVED'
            and item['uniqueAmongAllPlannedWrites'] is True,'CAP1 WS unresolved historical response must remain')
        v=item['wal'];actual.add((int(item['requestId'].rsplit('/',1)[1]),v['eventSeq'],v['userId'],v['x'],v['y'],v['color']))
    require(len(diagnostic['requests'])==2 and actual==expected,'CAP1 WS exact two identified effects')
    for state in (before,after):
        require(state['checkpoint']==state['events']==known['durable'] and state['tiles']==1024 and state['exactWalPrefix']
            and state['tileBytesAndVersions'],'CAP1 WS original clone before/after consistency')
    require(before['eventPayloads']==after['eventPayloads'],'CAP1 WS no generated recovery events')
    require(preservation['originalMysqlFiles']==known['mysqlFiles'] and preservation['originalMysqlBytes']==known['mysqlBytes']
        and preservation['allSourceHashesUnchanged'] is True and preservation['allOriginalWalHashesUnchanged'] is True,
        'CAP1 WS original storage preservation')
    require(owner['Released'] and owner['Failure'] is None and len(owner['Workers'])==5
        and all(w['Joined'] and w['ActualExit']==0 and w['CleanupFailure'] is None and w['FirstFailure'] is None for w in owner['Workers']),
        'CAP1 WS diagnostic workers not closed')
    require(mysql['NormalShutdown'] and mysql['ShutdownObserved'] and mysql['SqlExit']==0 and not mysql['Failures'],
        'CAP1 WS diagnostic SQL shutdown')
    require(budget['gateMet'] and budget['transition']['closed'] and budget['trialBudget']['violation'] is None,
        'CAP1 WS diagnostic ledger unresolved')

def require_ws_failure_resolution(close,plan,resolution,repo,bound,sha,current_rate=None,profile_kind=None):
    import json
    from pathlib import Path
    import xml.etree.ElementTree as ET
    known=diagnosed_ws_case(close['SessionId']);original_hash=known['plan']
    require(resolution['kind']==known['kind'] and resolution['oldPlan']['sha256']==original_hash,
        'CAP1 WS only the diagnosed original run')
    analysis=bound(resolution['analysis']);binding=bound(resolution['diagnostic'])
    require(binding['originalReceipt']['sha256']==known['receipt'],
        'CAP1 WS original runtime receipt identity')
    runtime=bound(binding['originalReceipt'])
    for item in runtime['runtime']:require(sha(item['path'])==item['sha256'],'CAP1 WS original runtime changed')
    for item in binding['diagnosticClasses']+[binding['diagnosticSource']]:
        require(sha(item['path'])==item['sha256'],'CAP1 WS executed diagnostic changed')
    facts={k:bound(binding[k]) for k in ('recovery','before','after','preservation','closure','mysqlStop','budgetAfter','recoveryRequest')}
    require(facts['recovery']['originalPlanHash']==original_hash,'CAP1 WS diagnosis Plan mismatch')
    require_ws_diagnostic_facts(close,analysis,facts['recovery'],facts['before'],facts['after'],facts['preservation'],facts['closure'],facts['mysqlStop'],facts['budgetAfter'],session=close['SessionId'])
    request=facts['recoveryRequest'];args=request['Arguments'];cp=args[args.index('-cp')+1]
    old=historical(Path(resolution['oldPlan']['path']).parent)
    require(cp.split(';',1)[1]==(old/'classpath.txt').read_text().strip(),'CAP1 WS diagnosis must use original production runtime')
    initial=old/close['RunId']/'initial';producer=json.loads((initial/'k6-producer.json').read_text())
    require(producer['firstFailure']=='controller_stop' and producer['producerComplete'] is False
        and {v['id'] for v in producer['unresolved']}=={v['requestId'] for v in facts['recovery']['requests']}
        and len(producer['unresolved'])==2,'CAP1 WS exact preserved unresolved requests')
    rows=[json.loads(line) for line in (initial/'k6-records.jsonl').read_text().splitlines()]
    http=[v for v in rows if v['type']=='http']
    require(len(http)==known['http'] and sum(v['outcome']=='ACCEPTED' for v in http)==known['accepted']
        and all(v['outcome']=='ACCEPTED' or v['outcome']=='NOT_SENT' and v['reason']=='stopped' for v in http),
        'CAP1 WS no other unresolved/unknown HTTP result')
    if known['accepted']==60:
        require('test aborted: WS evidence queue limit' in (initial/'k6-ws.stderr.log').read_text(),
            'CAP1 WS original queue failure evidence')
    else:
        original_runner=json.loads((initial/'k6-runner.json').read_text(encoding='utf-8-sig'))
        require(original_runner['FirstFailure']=='k6_memory_limit'
            and any(x['Phase']=='ws' and x['Failure']=='k6_memory_limit' and x['ActualExit']==108 for x in original_runner['Processes'])
            and all(x['Released'] for x in original_runner['Processes']),'CAP1 exact W500 memory failure and final native closure')
    support=bound(resolution['support']);receipt=bound(support['receipt'])
    for name in ('scripts/phase18-k6.js','scripts/run-phase18-k6.ps1','scripts/phase18-k6-process.ps1',
            'src/benchmark/java/dev/cgt/benchmark/Phase18K6Session.java','src/benchmark/java/dev/cgt/benchmark/Phase18K6Results.java'):
        require(sha(repo/name)==next(v['sha256'] for v in receipt['inputs'] if v['path']==name),'CAP1 WS selected transport source changed: '+name)
    for item in receipt['runtime']:require(sha(item['path'])==item['sha256'],'CAP1 WS selected runtime changed')
    require({Path(v['path']).name for v in support['xml']}=={'TEST-dev.cgt.benchmark.Phase18WsTransportTest.xml','TEST-dev.cgt.benchmark.Phase18WsBridgeTest.xml'},'CAP1 WS full-scale and bridge tests required')
    for item in support['xml']:
        require(sha(item['path'])==item['sha256'],'CAP1 WS selected XML changed')
        xml=ET.parse(historical(item['path'])).getroot()
        require(int(xml.get('tests','0'))>0 and all(xml.get(k,'0')=='0' for k in ('failures','errors','skipped')),'CAP1 WS selected tests incomplete')
    selected=bound(support['closure']);require(selected['Released'] and selected['Failure'] is None and bool(selected['Workers'])
        and all(w['Joined'] and w['ActualExit']==0 and not w['CleanupFailure'] for w in selected['Workers']),
        'CAP1 WS support owner closure')
    budget=bound(support['budget']);require(budget['gateMet'] and budget['transition']['closed'],'CAP1 WS support ledger closure')
    input=bound(support['input']);w=input['plan']['cases'][0]['workload']
    require((w['writeRate'],w['warmupSeconds'],w['measurementSeconds'],w['wsConnections'])==(250,30,60,100),
        'CAP1 WS full selected fixture scale')
    runner=bound(support['runner']);producer=bound(support['producer']);transport=producer['wsTransport'];bridge=producer['wsBridge']
    require(runner['Complete'] and runner['FirstFailure'] is None
        and all(v['Released'] and v['ActualExit']==0 and v['Failure'] is None for v in runner['Processes']),
        'CAP1 WS actual k6 failure/ownership')
    require(producer['producerComplete'] and producer['firstFailure'] is None and not producer['unresolved']
        and len(producer['opened'])==len(producer['closed'])==100 and bridge['transportComplete']
        and transport['failure'] is None and transport['received']==transport['acknowledged']==bridge['saved']==2250200
        and all(transport[k]==0 for k in ('pending','pendingBytes','waiting','inFlight','unconfirmedBatches')),
        'CAP1 WS full delivery/ack/close support')
    runtime=bound(support['k6Runtime'])
    require(runtime['ScriptSha256'].lower()==input['scriptSha256']==sha(repo/'scripts/phase18-k6.js')
        and runtime['BinarySha256'].lower()==input['k6BinarySha256']==sha(support['binary']['path']),
        'CAP1 WS actual pinned k6/script binding')
    limited=support.get('transportScope')
    if limited is not None:
        for name in ('src/test/java/dev/cgt/benchmark/Phase18K6FixtureServer.java','src/test/java/dev/cgt/benchmark/Phase18WsTransportTest.java'):
            require(sha(repo/name)==next(v['sha256'] for v in receipt['inputs'] if v['path']==name),'CAP1 bounded fixture source')
        names={case.get('name') for item in support['xml'] for case in ET.parse(historical(item['path'])).getroot().findall('testcase')}
        require('fullW250Ws100ConnectionMajorBurstsRetainEveryDeliveryAndDrain()' in names,'CAP1 bounded burst test required')
        require_ws_bounded_scope(support,current_rate,repo,bound,sha,profile_kind)
    if known['accepted']!=60 and limited is None:
        for name in ('src/test/java/dev/cgt/benchmark/Phase18K6FixtureServer.java','src/test/java/dev/cgt/benchmark/Phase18WsTransportTest.java'):
            require(sha(repo/name)==next(v['sha256'] for v in receipt['inputs'] if v['path']==name),'CAP1 actual connection-burst fixture source')
        names={case.get('name') for item in support['xml'] for case in ET.parse(historical(item['path'])).getroot().findall('testcase')}
        require('fullW500Ws100ConnectionMajorBurstsRetainEveryDeliveryAndDrain()' in names,'CAP1 W500 connection-major full-scale test required')
        for key in ('w500','w500Burst'):
            more=bound(support[key]);inp=bound(more['input']);prod=bound(more['producer']);run=bound(more['runner']);native=bound(more['k6Runtime'])
            ws=inp['plan']['cases'][0]['workload'];t=prod['wsTransport'];b=prod['wsBridge']
            rate=501 if key=='w500' else 500
            require((ws['writeRate'],ws['warmupSeconds'],ws['measurementSeconds'],ws['wsConnections'])==(rate,30,60,100),'CAP1 W500 required full scale')
            source_rows=bound(more['httpCounts'])
            raw=historical(more['records']['path']);require(sha(raw)==more['records']['sha256'],'CAP1 W500 actual HTTP evidence')
            counts={'ACCEPTED':0,'NOT_SENT':0}
            with raw.open(encoding='utf-8') as file:
                for line in file:
                    row=json.loads(line)
                    if row['type']!='http':continue
                    require(row['outcome'] in counts and (row['outcome']=='ACCEPTED' or row['reason']=='arrival_late'),'CAP1 W500 fixture input boundary only')
                    counts[row['outcome']]+=1
            require(counts==source_rows and sum(counts.values())==rate*90+1 and counts['ACCEPTED']>=45001
                and counts['ACCEPTED']-1>=rate*90*0.999,'CAP1 full W500 volume including actual input shortfall')
            deliveries=(counts['ACCEPTED']-1)*100+200
            require(inp['limits']['wsTransport']['cacheCount']==16384 and inp['limits']['wsTransport']['cacheBytes']==16777216
                and 0<=t['maxCacheEntries']<=16384 and 0<=t['maxCacheBytes']<=16777216,'CAP1 W500 bounded cache support')
            require(prod['producerComplete'] and prod['firstFailure'] is None and not prod['unresolved'] and len(prod['opened'])==len(prod['closed'])==100
                and b['transportComplete'] and t['failure'] is None and t['received']==t['acknowledged']==b['saved']==deliveries
                and all(t[k]==0 for k in ('pending','pendingBytes','waiting','inFlight','unconfirmedBatches')),'CAP1 W500 exact full delivery and closure')
            require(run['Complete'] and run['FirstFailure'] is None and all(x['Released'] and x['ActualExit']==0 and x['Failure'] is None
                and x['PeakPrivateBytes']<=805306368 and x['PeakWorkingSet']<=805306368 for x in run['Processes']),'CAP1 W500 native memory/owner support')
            require(native['ScriptSha256'].lower()==inp['scriptSha256']==sha(repo/'scripts/phase18-k6.js')
                and native['BinarySha256'].lower()==inp['k6BinarySha256']==sha(support['binary']['path']),'CAP1 W500 current binary/script')
    # 추가 실패·STOP·시각 회귀의 실제 종료/선택 결과는 full-scale 결과와 별도로 묶음
    regression=bound(resolution['regression'])
    latest=bound(regression['receipt'])
    analyzer_before=next(v['sha256'] for v in latest['inputs'] if v['path']=='scripts/phase18_k6.py')
    if sha(repo/'scripts/phase18_k6.py')!=analyzer_before:
        repair=bound(resolution['offlineAnalysisRepair']);old_plan=bound(repair['oldPlan'])
        require(next(v['sha256'] for v in old_plan['inputs'] if v['path']=='scripts/phase18_k6.py')==analyzer_before,'CAP1 original tested analyzer')
        require_completed_native_analysis(bound(repair['closure']),old_plan,repair,repo,bound,sha)
        selected=bound(resolution.get('currentAnalyzerTests',repair['selectedTests']))
        require(selected['testsRun']>=22 and selected['failures']==selected['errors']==0
            and selected['analyzer']['sha256']==sha(repo/'scripts/phase18_k6.py')
            and selected['tests']['sha256']==sha(repo/'scripts/test_k6_phase18.py'),'CAP1 current offline analyzer selected tests')
    require({Path(v['path']).name for v in regression['xml']}=={'TEST-dev.cgt.benchmark.Phase18WsFailureTest.xml','TEST-dev.cgt.benchmark.Phase18WsBridgeTest.xml','TEST-dev.cgt.benchmark.Phase18K6BoundaryTest.xml','TEST-dev.cgt.benchmark.Phase18K6Test.xml','TEST-dev.cgt.benchmark.Phase18TransportTest.xml'},'CAP1 WS required direct regression cases')
    for ref in regression['xml']:
        require(sha(ref['path'])==ref['sha256'],'CAP1 WS regression XML changed')
        xml=ET.parse(historical(ref['path'])).getroot();require(int(xml.get('tests','0'))>0 and all(xml.get(k,'0')=='0' for k in ('failures','errors','skipped')),'CAP1 WS regression failure')
    owner=bound(regression['closure']);require(owner['Released'] and owner['Failure'] is None and bool(owner['Workers'])
        and all(v['ActualExit']==0 and v['Joined'] for v in owner['Workers']),'CAP1 WS regression ownership')



def require_ws_bounded_scope(support,current_rate,repo,bound,sha,profile_kind=None):
    """현재 250/s 정상·burst와 실제 500/s native 한계를 연결한 새 낮은 시리즈만 허용."""
    scope=support['transportScope']
    if scope.get('kind')=='cap1-f-ws-tested-range-v2':
        return require_ws_revised_scope(support,current_rate,repo,bound,sha,profile_kind)
    require(scope=={'kind':'cap1-f-ws-tested-range-v1','maximumWriteRate':250}
        and type(current_rate) is int and 105<=current_rate<=250,'CAP1 tested WS transport range')
    more=bound(support['w250Burst']);inp=bound(more['input']);prod=bound(more['producer']);run=bound(more['runner']);native=bound(more['k6Runtime'])
    w=inp['plan']['cases'][0]['workload'];t=prod['wsTransport'];b=prod['wsBridge']
    require((w['writeRate'],w['warmupSeconds'],w['measurementSeconds'],w['wsConnections'])==(250,30,60,100),'CAP1 bounded burst full scale')
    require(prod['producerComplete'] and prod['firstFailure'] is None and not prod['unresolved']
        and len(prod['opened'])==len(prod['closed'])==100 and b['transportComplete']
        and t['failure'] is None and t['received']==t['acknowledged']==b['saved']==2250200
        and all(t[k]==0 for k in ('pending','pendingBytes','waiting','inFlight','unconfirmedBatches')),'CAP1 bounded burst full delivery and closure')
    require(run['Complete'] and run['FirstFailure'] is None and all(x['Released'] and x['ActualExit']==0 and x['Failure'] is None for x in run['Processes']),'CAP1 bounded burst native closure')
    require(native['ScriptSha256'].lower()==inp['scriptSha256']==sha(repo/'scripts/phase18-k6.js')
        and native['BinarySha256'].lower()==inp['k6BinarySha256']==sha(support['binary']['path']),'CAP1 bounded current native source')
    limit=bound(support['higherToolLimit']);owner=bound(limit['closure']);budget=bound(limit['budget'])
    require(limit['kind']=='cap1-isolated-w500-native-memory-limit-v1' and limit['servicesStarted']==0
        and owner['Failure']=='CAP child exit 1' and owner['Released']
        and all(x['Joined'] and x['DbUsersClosed'] and not x['CleanupFailure'] and not x['Services'] for x in owner['Workers'])
        and sum(x['ActualExit']==1 and x['FirstFailure']=='CAP child exit 1' for x in owner['Workers'])==1
        and all(x['ActualExit'] in (0,1) for x in owner['Workers'])
        and budget['gateMet'] and budget['transition']['closed'] and budget['trialBudget']['violation'] is None,'CAP1 isolated higher limit owner closure')
    require(set(limit['cases'])=={'normal','burst'},'CAP1 both higher native cases required')
    for name,ref in limit['cases'].items():
        case=bound(ref);failed=bound(case['runner']);source=bound(case['input']);native=bound(case['k6Runtime']);producer=bound(case['producer'])
        w=source['plan']['cases'][0]['workload']
        require((w['writeRate'],w['warmupSeconds'],w['measurementSeconds'],w['wsConnections'])==((501 if name=='normal' else 500),30,60,100),'CAP1 exact higher fixture scale')
        require(not failed['Complete'] and failed['FirstFailure']=='k6_memory_limit'
            and all(x['Released'] for x in failed['Processes'])
            and any(x['Phase']=='ws' and x['Failure']=='k6_memory_limit' and x['ActualExit']==108
                and max(x['PeakPrivateBytes'],x['PeakWorkingSet'])>805306368 for x in failed['Processes'])
            and producer['firstFailure']=='k6_memory_limit' and not producer['producerComplete'],'CAP1 actual higher native memory limit')
        require(native['ScriptSha256'].lower()==source['scriptSha256']==sha(repo/'scripts/phase18-k6.js')
            and native['BinarySha256'].lower()==source['k6BinarySha256']==sha(support['binary']['path'])
            and native['MaxProcessMemory']==805306368 and native['MaxCombinedMemory']==1342177280,'CAP1 higher limit same pinned execution')


def require_ws_revised_scope(support,current_rate,repo,bound,sha,profile_kind):
    """현재 v2에서 실제 검증한 정상/burst 범위만 연결. 과거 실패의 진단 계약 유지."""
    import json,xml.etree.ElementTree as ET
    from pathlib import Path
    maximum=support['transportScope'].get('maximumWriteRate')
    require(type(maximum) is int and maximum in (250,500) and support['transportScope']==dict(kind='cap1-f-ws-tested-range-v2',maximumWriteRate=maximum,profileKind='cap1-causal-memory-v2')
        and profile_kind=='cap1-causal-memory-v2' and type(current_rate) is int and 105<=current_rate<=maximum,'CAP1 revised tested WS range/profile')
    receipt=bound(support['receipt'])
    owner=bound(support['closure']);budget=bound(support['budget'])
    require(owner['Released'] and owner['Failure'] is None and owner['Workers'] and all(v['Joined'] and v['ActualExit']==0 and not v['FirstFailure'] and not v['CleanupFailure'] and v['DbUsersClosed'] for v in owner['Workers']),'CAP1 revised selected owner unresolved')
    require(budget['gateMet'] and budget['transition']['closed'] and budget['trialBudget']['violation'] is None,'CAP1 revised selected budget unresolved')
    selected_root=historical(Path(support['closure']['path']).parent).resolve()
    for name in ('src/benchmark/java/dev/cgt/benchmark/Phase18K6Causal.java','src/benchmark/java/dev/cgt/benchmark/Phase18Events.java'):
        require(sha(repo/name)==next(v['sha256'] for v in receipt['inputs'] if v['path']==name),'CAP1 revised causal source')
    names={case.get('name') for ref in support['xml'] for case in ET.parse(historical(ref['path'])).getroot().findall('testcase')}
    rates=(250,500) if maximum==500 else (250,)
    require(all(f'fullW{rate}Ws100Warmup30AndMeasurement60RetainsEveryDeliveryAndDrains()' in names and f'fullW{rate}Ws100ConnectionMajorBurstsRetainEveryDeliveryAndDrain()' in names for rate in rates),'CAP1 exact normal/burst execution')
    retention=bound(support['changedRetention'])
    require(retention['syntheticFixture'] and retention['servicesStarted']==0 and retention['httpTerminals']==307501 and retention['events']==307500 and retention['deliveries']==30750000 and retention['measurementLatencies']==30000000 and retention['all100Connections'],'CAP1 revised full retention proof')
    require(retention['memoryLimit']==ANALYSIS_MEMORY and max(retention['memory'].values())<=ANALYSIS_MEMORY and 0<retention['analysisSeconds']<retention['reservedAnalysisSeconds']==profile(250,1200,'cap1-causal-memory-v2')['parts']['analysis'],'CAP1 revised retention resource proof')
    require(retention['analyzer']['sha256']==sha(repo/'scripts/phase18_k6.py'),'CAP1 revised retention analyzer changed')
    for ref in retention['sourceRefs']:require(sha(ref['path'])==ref['sha256'],'CAP1 revised retention raw input changed')
    analysis=bound(support['nativeAnalysis']);cases={v['case']:v for v in analysis['cases']}
    expected={f'ws{rate}-{suffix}' for rate in rates for suffix in ('full','burst')}
    require(set(cases)==expected and analysis['servicesStarted']==0,'CAP1 revised all native cases')
    backlog=bound(support['nativeBacklog']);lags={v['case']:v for v in backlog['cases']}
    require(set(lags)==expected and backlog['servicesStarted']==0,'CAP1 revised native callback backlog evidence')
    selections=[('ws250-full',None,250),('ws250-burst','w250Burst',250)]
    if maximum==500:selections.extend([('ws500-full','w500',500),('ws500-burst','w500Burst',500)])
    for label,key,rate in selections:
        more=bound(support[key]) if key else support
        require(historical(Path(more['input']['path']).parent).resolve()==selected_root/label,'CAP1 revised native case owner linkage')
        inp=bound(more['input']);prod=bound(more['producer']);run=bound(more['runner']);native=bound(more['k6Runtime']);w=inp['plan']['cases'][0]['workload']
        require(inp['plan']['schemaVersion']==4 and (w['writeRate'],w['warmupSeconds'],w['measurementSeconds'],w['wsConnections'])==(rate,30,60,100),'CAP1 revised exact workload')
        ce=inp['clockEvidence']
        require(ce['kind']=='bridge-causal-brackets-v1' and ce['source']=='Windows HotSpot System.nanoTime/QPC' and type(ce['qpcFrequency']) is int
            and ce['qpcFrequency']>=10000 and ce['outwardGuardMillis']==1 and ce['maximumElapsedMillis']==21600000 and ce['epochOffsetUsed'] is False
            and isinstance(ce['clockId'],str) and bool(ce['clockId']),'CAP1 revised actual clock evidence')
        require(cases[label]['clockEvidence']==ce,'CAP1 revised clock analysis linkage')
        t=prod['wsTransport'];b=prod['wsBridge'];n=rate*9000+200
        require(prod['producerComplete'] and prod['firstFailure'] is None and not prod['unresolved'] and set(prod['opened'])==set(prod['closed'])==set(range(100))
            and b['transportComplete'] and t['failure'] is None and t['received']==t['acknowledged']==b['saved']==n
            and all(t[k]==0 for k in ('pending','pendingBytes','waiting','inFlight','unconfirmedBatches')),'CAP1 revised full delivery/closure')
        require(run['Complete'] and run['FirstFailure'] is None and run['PeakCombinedPrivate']<=1792*MIB and run['PeakCombinedResident']<=1792*MIB
            and all(v['Released'] and v['ActualExit']==0 and v['Failure'] is None and max(v['PeakPrivateBytes'],v['PeakWorkingSet'])<=1280*MIB for v in run['Processes']),'CAP1 revised native closure/limits')
        require(native['MaxProcessMemory']==inp['limits']['processMemoryBytes']==1280*MIB and native['MaxCombinedMemory']==inp['limits']['combinedMemoryBytes']==1792*MIB
            and native['GoMemoryLimit']=='512MiB' and native['GoGcPercent']==inp['limits']['goRuntime']['gcPercent']==100 and inp['limits']['goRuntime']['memoryBytes']==512*MIB and not native.get('DiagnosticOnly',False) and native['ScriptSha256'].lower()==inp['scriptSha256']==sha(repo/'scripts/phase18-k6.js')
            and native['BinarySha256'].lower()==inp['k6BinarySha256']==sha(support['binary']['path']),'CAP1 revised frozen native binding')
        observed=cases[label]
        for ref in observed['sourceRefs']:require(sha(ref['path'])==ref['sha256'],'CAP1 revised analyzed raw input changed')
        indexed={Path(ref['path']).name:ref for ref in observed['sourceRefs']}
        require(all(indexed[name]==more[key] for name,key in (('k6-input.json','input'),('k6-producer.json','producer'),('k6-runner.json','runner'),('k6-runtime.json','k6Runtime'))),'CAP1 revised analysis case linkage')
        require(observed['events']==rate*90 and observed['delivery']==rate*9000 and observed['counts']==dict(bootstrap=1,warmup=rate*30,measurement=rate*60)
            and observed['ws']['clockMode']=='bridge-causal-brackets-v1' and observed['ws']['overall']['count']==rate*60*100
            and len(observed['ws']['connections'])==100 and all(v['count']==rate*60 for v in observed['ws']['connections']),'CAP1 revised exact analyzed samples')
        lag=lags[label]
        require(lag['deliveries']==rate*9000 and lag['curve'] and lag['curve'][-1]['nativeReceived']==lag['curve'][-1]['callbacks']==rate*9000,'CAP1 revised native/callback full totals')
        for ref in lag['sourceRefs']:require(sha(ref['path'])==ref['sha256'],'CAP1 revised backlog raw input changed')
        lag_inputs={Path(ref['path']).name:ref for ref in lag['sourceRefs']}
        require(all(lag_inputs[name]==indexed[name] for name in ('k6-input.json','k6-runtime.json','k6-producer.json','k6-ws-times.bin')),'CAP1 revised backlog case linkage')
        if label.endswith('-full'):
            # 정상 fixture에서도 전달 계층이 지연 예산을 소진하거나 native 적체가 지속되면 지원 불가.
            rows=[observed['ws']['overall']]+observed['ws']['connections']
            require(all(row['causal']['p95Millis']['upperMillis']<=250 and row['causal']['p99Millis']['upperMillis']<=500 for row in rows),'CAP1 revised normal callback latency support')
            windows=[v for v in lag['windows'] if 60<=v['fromWarmupSeconds']<90]
            require(len(windows)==3 and all(v['medianQueuedLower']<=rate*100*0.25 for v in windows),'CAP1 revised sustained native callback backlog')
        counts={}
        with historical(indexed['k6-records.jsonl']['path']).open(encoding='utf-8') as stream:
            for line in stream:
                row=json.loads(line)
                if row['type']=='http':
                    require(row['outcome']=='ACCEPTED','CAP1 revised fixture unresolved/input loss');counts[row['phase']]=counts.get(row['phase'],0)+1
        require(counts==observed['counts'],'CAP1 revised raw exact HTTP count')
        # 요약 성공 플래그로 실제 native 관측을 대체하지 않도록 유한 원시 자료 재검증
        from phase18_k6 import native_memory_observations
        class Api:
            Invalid=ValueError
            require=staticmethod(require)
            decode=staticmethod(json.loads)
        native_memory_observations(Api,historical(indexed['k6-native-memory.jsonl']['path']),run,native)
    return True


def require_completed_native_analysis(close,plan,resolution,repo,bound,sha):
    """이미 완주한 특정 실행의 오프라인 guard 오류만 분리. 원래 실패/통계는 보존한다."""
    from pathlib import Path
    require(close['SessionId']=='phase18-cap1-w500-20261014-022838'
        and resolution['oldPlan']['sha256']=='e0db465a9fa62b9aa6d9fc3b5e7d919d50cbcc777c02448b1b9ed34a0f9bb02e', 'CAP1 exact completed native analysis case')
    require(close['Status']=='FAILED' and close['RunExit']==close['AnalysisExit']==0
        and close['OwnedResourcesClosed'] and close['NormalMysqlStop'] and close['BudgetAfterVerified']
        and close['OwnedJava']==[] and close['Remaining3307']==0 and close['RemainingRunLock'] is False
        and close['Budget']['gateMet'] and close['Budget']['transition']['closed'], 'CAP1 completed analysis ownership')
    require(bool(close['OwnedWorkers']) and all(v['ActualExit']==0 and v['Joined'] and v['Released']
        and v['DbUsersClosed'] and not v['FirstFailure'] and not v['CleanupFailure'] for v in close['OwnedWorkers']), 'CAP1 completed analysis worker failure')
    old=bound(resolution['analysis']);corr=bound(resolution['correspondence']);new=bound(corr['reanalysis'])
    require(corr['originalPlan']==resolution['oldPlan'] and corr['originalClosure']==resolution['closure']
        and corr['originalAnalysis']==resolution['analysis'] and corr['originalEvidenceIndex']==close['EvidenceIndex']
        and corr['servicesStarted']==0 and corr['originalExecutionExpiryUnchanged'], 'CAP1 offline original binding')
    index=bound(close['EvidenceIndex'])
    require(corr['files']==len(index['Files']) and all(sha(v['path'])==v['sha256'].lower() for v in index['Files']), 'CAP1 offline original evidence changed')
    require_executed_analysis_sources(corr,resolution,repo,sha)
    require(old['cap']['verdict']=='INVALID_OR_STOPPED'
        and old['cap']['failureCategories']['executionOrConsistency']==['k6 ws required output'], 'CAP1 offline isolated guard mismatch')
    require(all(new['integrity'].values()) and new['cap']['verdict']=='TOOL_LIMIT'
        and new['cap']['failureCategories']['executionOrConsistency']==[], 'CAP1 offline full integrity required')
    for key in ('tool','input','performance'):
        require(new['cap']['failureCategories'][key]==old['cap']['failureCategories'][key], 'CAP1 offline original classification facts')
    for key in ('input','ws','db'):
        require(new['cap'].get(key)==old['cap'].get(key), 'CAP1 offline original measurement facts')
    root=historical(Path(resolution['correspondence']['path']).parent)
    owner=bound(resolution['offlineClosure']);budget=bound(resolution['offlineBudget'])
    require(owner['Released'] and owner['Failure'] is None and bool(owner['Workers'])
        and all(v['ActualExit']==0 and v['Joined'] and not v['CleanupFailure'] for v in owner['Workers'])
        and budget['gateMet'] and budget['transition']['closed'], 'CAP1 offline analysis closure')
    return new



def require_executed_analysis_sources(corr,resolution,repo,sha):
    """과거 실행의 source hash는 보존 사본으로 검증하며 현재 구현으로 소급 교체하지 않는다."""
    from pathlib import Path
    known=dict(analyzerSource='dc876ab132440362e23f4aa7361c8e494212703e0dbb61314ff2b98e4c1e7cab',
        entrySource='d3df669272905747d290da6fe662e5d19635a10b6e811542c1faccc4c1403ea2',
        helperSource='32d75ec22ca5edb4e4ad79b64ff27606f737eeaf638f154fcf83db72a5d1ced9')
    require(Path(corr['analyzerSource']['path']).resolve()==(repo/'scripts/phase18_k6.py').resolve(),
        'CAP1 original offline analyzer identity')
    snapshots=resolution.get('executedSources',{})
    require(not snapshots or set(snapshots)==set(known),'CAP1 complete executed source snapshots')
    for key,digest in known.items():
        ref=corr[key];require(ref['sha256']==digest,'CAP1 exact historical executed source')
        if snapshots:
            saved=snapshots[key]
            require(saved['original']==ref and saved['preserved']['sha256']==digest
                and sha(saved['preserved']['path'])==digest,'CAP1 preserved executed source mismatch')
        else:require(sha(ref['path'])==digest,'CAP1 offline analyzer source changed')


def offline_conditions_source(p,repo,bound,sha):
    """동작 불변인 해당 오프라인 수정에 한해서 기존 유효 105/250을 재사용한다."""
    if p.get('seriesLayout')=='f-output-c-graph-v2':return p['sourceBinding']
    if 'offlineAnalysisRepair' not in p:return p['sourceBinding']
    resolution=bound(p['offlineAnalysisRepair']);plan=bound(resolution['oldPlan']);close=bound(resolution['closure'])
    require_completed_native_analysis(close,plan,resolution,repo,bound,sha)
    before={v['path']:v['sha256'] for v in plan['inputs']};after={v['path']:v['sha256'] for v in p['sourceBinding']}
    require(before.keys()==after.keys(),'CAP1 offline source set')
    changed={key for key in before if before[key]!=after[key]}
    require(changed and changed<= {'scripts/phase18_k6.py','scripts/phase18_cap1.py','scripts/test_k6_phase18.py'},
        'CAP1 offline repair cannot reuse changed execution conditions')
    # Java Plan 목록은 대소문자 무시 순서, 원래 receipt/조건 해시는 경로의 ordinal 순서다.
    return sorted(plan['inputs'],key=lambda v:v['path'])


def verify_policy(path,repo,ledger):
    """기존 원본 증거와 현재 CAP-1 후보의 호출 경계 검증. boolean 요약만 신뢰하지 않음."""
    import json,hashlib
    from pathlib import Path
    from phase18_cap_budget import load_chain
    repo,ledger=Path(repo),control_path(ledger)
    def sha(p):
        with historical(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
    def read(p):return json.loads(historical(p).read_text(encoding='utf-8-sig'))
    def bound(ref):
        require(sha(ref['path'])==ref['sha256'].lower(),'CAP1 evidence hash mismatch: '+ref['path'])
        return read(ref['path'])
    p=read(path)
    if p.get('role')=='cap-diagnostic-ab':
        from phase18_cap1_comparison import verify_policy as verify_comparison
        return verify_comparison(path,repo,ledger)
    prof=validate_profile(p['cap1Profile'])
    require(p['schemaVersion']==1 and p['stage']=='CAP-1' and p['role']=='cap-target','CAP1 policy scope')
    require(not structural_support(prof),'CAP1 combination needs support preparation')
    require(sha(p['instruction']['path'])==p['instruction']['sha256'],'CAP1 instruction changed')
    for ref in p['sourceBinding']:
        require(sha(repo/ref['path'])==ref['sha256'],'CAP1 current source changed: '+ref['path'])
    inventory={str(q.relative_to(repo)).replace('\\','/') for folder in ('src','scripts') for q in (repo/folder).rglob('*')
        if q.is_file() and q.suffix.lower() not in ('.zip','.pyc')}
    inventory.update(('build.gradle','settings.gradle','gradlew.bat','pixel_place.sql','docs/phase18-tools.md'))
    require(inventory=={r['path'].replace('\\','/') for r in p['sourceBinding']},'CAP1 source file set changed')
    for ref in (p['k6']['binary'],p['k6']['script']):
        require(sha(ref['path'])==ref['sha256'].lower(),'CAP1 k6 binding')
    budget=bound(p['budgetReservation'])
    state,tip=load_chain(ledger)
    require(state['gateMet'] and ('transition' not in state or state['transition']['closed']),'CAP1 previous budget unresolved')
    require(tip==p['ledgerTip'] and budget['stage']=='CAP-1','CAP1 current ledger binding')
    require(budget['additionalPeak']['evidence']>=prof['usage']['evidenceBytes'],'CAP1 evidence peak reservation')
    require(len(p['cap0'])==2,'CAP1 needs original S1/S2')
    smokes=set();cap0_service=0;cap0_floor=0
    for item in p['cap0']:
        close=bound(item['closure']);analysis=bound(item['analysis']);index=bound(close['EvidenceIndex'])
        require(close['Status']=='PASS' and close['OwnedResourcesClosed'] and close['NormalMysqlStop']
            and close['RunExit']==close['AnalysisExit']==0 and close['BudgetAfterVerified'],'CAP1 CAP0 closure')
        require(analysis['cap']['smokeVerification']=='PASS' and analysis['cap']['capacityPoint'] is False
            and all(analysis['integrity'].values()),'CAP1 CAP0 integrity/recovery')
        require(close['Remaining3307']==0 and close['RemainingRunLock'] is False,'CAP1 CAP0 ownership')
        smokes.add(close['Smoke'])
        cap0_service=max(cap0_service,close['ServiceMillisCumulative'])
        cap0_floor=max(cap0_floor,close['RecordedServiceMillisCumulativeFloor'])
        require(bool(close['OwnedWorkers']) and all(x['Joined'] and x['Released'] and x['ActualExit']==0 and not x['FirstFailure'] for x in close['OwnedWorkers']),'CAP1 CAP0 worker closure')
        if close['OwnedJava'] == {}:
            supplement=bound(p['s1Supplement'])
            require(supplement['Closure']['sha256'].lower()==item['closure']['sha256'].lower(),'CAP1 original S1 supplement')
            probe=bound(supplement['Probe'])
            require(probe['OwnedJava']==[],'CAP1 S1 empty array proof')
        else:require(close['OwnedJava']==[],'CAP1 remaining Java ownership')
        for ref in index['Files']:
            require(sha(ref['path'])==ref['sha256'].lower(),'CAP1 CAP0 execution evidence changed')
    require(smokes=={'S1','S2'},'CAP1 distinct original S1 and S2 required')
    conditions=dict(stage='CAP-1',pattern='W-SPREAD',seed=15,ws=100,readRate=0,warmup=30,vu=128,readVu=4,
        appHeap=2147483648,k6Heap=prof['k6GoMemoryBytes'],k6ProcessMemory=prof['k6ProcessMemoryBytes'],k6CombinedMemory=prof['k6MemoryBytes'],analysisHeap=ANALYSIS_MEMORY,volumeLayout=state.get("volumeLayout"),source=offline_conditions_source(p,repo,bound,sha))
    if prof['kind']=='cap1-causal-memory-v2':conditions['k6GoGcPercent']=prof['k6GoGcPercent']
    require(p.get('seriesLayout')==state.get('volumeLayout')=='f-output-c-graph-v2','CAP1 current storage series identity')
    expected_conditions=hashlib.sha256(json.dumps(conditions,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    require(p['conditionsHash']==expected_conditions,'CAP1 derived conditions identity')
    history=[];sessions=set();service=cap0_service;floor=cap0_floor
    actual_attempts=list(ledger.glob('cap1-attempt-*.json'))
    require(len(p['history'])==len(actual_attempts),'CAP1 complete attempt history required')
    for item in p['history']:
        close=bound(item['closure']);plan=bound(item['plan'])
        require(plan['cases'][0]['role']!='cap-diagnostic-ab','Diagnostic comparison is not a CAP target candidate')
        require(close['SessionId'] not in sessions,'CAP1 duplicate history')
        sessions.add(close['SessionId']);service+=close['ServiceMillis'];floor+=close['ServiceMillis']
        require(close['Stage']=='CAP-1' and close['OwnedResourcesClosed'] and close['NormalMysqlStop']
            and close['OwnedJava']==[] and close['Remaining3307']==0 and close['RemainingRunLock'] is False,
            'CAP1 prior ownership unresolved')
        index=bound(close['EvidenceIndex'])
        for ref in index['Files']:
            require(sha(ref['path'])==ref['sha256'].lower(),'CAP1 prior execution evidence changed')
        if close['Status']=='FAILED':
            resolution=bound(item['resolution'])
            require(resolution['kind'] in ('cap1-preload-plan-allocation-v1','cap1-postload-storage-reservation-v1','cap1-collector-counter-history-v1','cap1-w250-ws-diagnostic-v1','cap1-w500-ws-memory-diagnostic-v1','cap1-completed-native-analysis-v1')
                and resolution['closure']==item['closure'] and resolution['oldPlan']==item['plan'],
                'CAP1 exact failure resolution binding')
            requests=[read(w['Request']) for w in close['OwnedWorkers']]
            indexed={ref['path'].replace('\\','/').lower() for ref in index['Files']}
            require(all(w['Request'].replace('\\','/').lower() in indexed for w in close['OwnedWorkers']),
                'CAP1 preload indexed worker requests')
            if resolution['kind']=='cap1-completed-native-analysis-v1':
                analysis=require_completed_native_analysis(close,plan,resolution,repo,bound,sha)
                require(item['resolution']==p['offlineAnalysisRepair'],'CAP1 same verified offline repair')
                w=plan['cases'][0]['workload']
                history.append(dict(value=w['writeRate'],seconds=w['measurementSeconds'],verdict=analysis['cap']['verdict'],closed=True,
                    conditionsHash=close['ConditionsHash'],evidenceVerified=True,planHash=item['plan']['sha256'],
                    analysisHash=bound(resolution['correspondence'])['reanalysis']['sha256'],closureHash=item['closure']['sha256'],
                    sessionId=close['SessionId'],runId=close['RunId']))
                continue
            if resolution['kind']=='cap1-preload-plan-allocation-v1':
                require_closed_preload_allocation_failure(close,plan,requests)
            elif resolution['kind']=='cap1-postload-storage-reservation-v1':
                failure=bound(resolution['failure']);old_analysis=bound(resolution['analysis'])
                require_closed_storage_failure(close,failure,old_analysis,requests)
                root=historical(Path(item['closure']['path']).parent)/close['RunId']
                require(not (root/'recovery').exists() and not (root/'recovery-process').exists(),
                    'CAP1 recovery attempted failure cannot use storage exception')
                prior_source=resolution['originalBenchmarkSource']
                require(sha(prior_source['path'])==prior_source['sha256']==next(x['sha256'] for x in plan['inputs']
                    if x['path']=='src/benchmark/java/dev/cgt/benchmark/Phase18BenchmarkMain.java'),
                    'CAP1 original post-consistency guard source')
                original=historical(prior_source['path']).read_text(encoding='utf-8-sig')
                require(original.index('consistency=Phase18Consistency.verify(fixture,context,users,k6Results.accepted()')
                    <original.index('"actual catalog/binlog budget"'),'CAP1 original consistency before storage guard')
                measured=bound(resolution['storageMeasurement'])
                require(measured['binlogGrowthLowerBoundBytes']>plan['bounds']['binlogBytes']
                    and prof['storage']['usage']['binlogBytes']>=measured['binlogGrowthLowerBoundBytes'],
                    'CAP1 binlog reservation repair measured lower bound')
            elif resolution['kind']=='cap1-collector-counter-history-v1':
                failure=bound(resolution['failure']);old_analysis=bound(resolution['analysis']);replay=bound(resolution['replay'])
                require_closed_collector_failure(close,failure,old_analysis,replay)
                root=historical(Path(item['closure']['path']).parent)/close['RunId']
                require(not (root/'recovery').exists() and not (root/'recovery-process').exists(),
                    'CAP1 collector resolution cannot override recovery failure')
                raw=root/'initial/resource-samples.jsonl'
                require(sha(raw)==replay['rawHash'],'CAP1 replay original samples hash')
                old_source=resolution['originalCollectorSource']
                require(sha(old_source['path'])==old_source['sha256']==next(x['sha256'] for x in plan['inputs']
                    if x['path']=='src/benchmark/java/dev/cgt/benchmark/Phase18Collector.java'),'CAP1 original collector source')
                with (root/'initial/k6-records.jsonl').open(encoding='utf-8') as f:
                    for line in f:
                        row=json.loads(line)
                        require(row['type']!='http' or (row['outcome']=='ACCEPTED' and row['httpStatus']==200)
                            or (row['outcome']=='NOT_SENT' and row['reason']=='stopped'),
                            'CAP1 collector repair cannot override UNKNOWN/HTTP failure')
            else:
                require_ws_failure_resolution(close,plan,resolution,repo,bound,sha,prof['writeRate'],prof['kind'])
            proof=bound(resolution['proof']);replacement=bound(proof['plan'])
            receipt=bound(proof['runtimeReceipt']);output=bound(proof['stdout'])
            require(proof['actualExit']==0 and output['executionAvailable'] and output['stage']=='CAP-1'
                and output['planHash']==proof['plan']['sha256'],'CAP1 repaired Java Plan proof')
            require(replacement['inputs']==p['sourceBinding'] and receipt['inputs']==p['sourceBinding'],
                'CAP1 repaired proof current source binding')
            require(replacement['bounds']['users']>=replacement['cases'][0]['workload']['userPool']+1
                and replacement['bounds']['writeTerminals']>=plan['cases'][0]['workload']['writeRate']*(30+plan['cases'][0]['workload']['measurementSeconds'])+1,'CAP1 repaired allocation')
            continue
        analysis=bound(item['analysis'])
        require(close['RunExit']==close['AnalysisExit']==0 and all(analysis['integrity'].values()),'CAP1 prior integrity unresolved')
        require(close['Status']==analysis['cap']['verdict'] and close['Status'] in ('PASS','PERFORMANCE_MISS','INPUT_LIMIT','TOOL_LIMIT'),'CAP1 prior valid closure')
        index=bound(close['EvidenceIndex'])
        for ref in index['Files']:
            require(sha(ref['path'])==ref['sha256'].lower(),'CAP1 prior execution evidence changed')
        w=plan['cases'][0]['workload']
        history.append(dict(value=w['writeRate'],seconds=w['measurementSeconds'],verdict=close['Status'],closed=True,
            conditionsHash=close['ConditionsHash'],evidenceVerified=True,planHash=item['plan']['sha256'],
            analysisHash=item['analysis']['sha256'],closureHash=item['closure']['sha256'],
            sessionId=close['SessionId'],runId=close['RunId']))
    require(sessions=={read(a)['SessionId'] for a in actual_attempts},'CAP1 full issued history linkage')
    require(p['serviceMillisBefore']==service and p['recordedServiceMillisFloorBefore']==floor,'CAP1 preserved service consumption')
    from phase18_cap import selection_gate
    if not any(t['conditionsHash']==p['conditionsHash'] for t in history):require((prof['writeRate'],prof['measurementSeconds'])==(105,120),'CAP1 first baseline required')
    if prof['measurementSeconds']==1200:
        require(selection_gate('confirm-20min',history,prof['writeRate'],p['conditionsHash']),'CAP1 three distinct equivalent passes required')
    require(not any(t['value']==prof['writeRate'] and t['conditionsHash']==p['conditionsHash']
        and t['verdict'] in ('PERFORMANCE_MISS','INPUT_LIMIT') and t['seconds'] in (120,1200) for t in history),
        'CAP1 valid failed candidate cannot collect replacement passes')
    return dict(valid=True,stage='CAP-1',profile=prof,priorTrials=len(history),ledgerTip=tip)

def verify_storage_start(path,repo,ledger,session):
    """등록·실제 발급·현재 예약에 연결된 CAP-1 Start의 물리 U만 반환."""
    import json,hashlib,time
    from pathlib import Path
    from pixel_place_paths import plain_absolute,evidence_path
    from phase18_cap_budget import load_chain,C_GRAPH_LAYOUT
    repo=Path(repo);ledger=control_path(ledger);policy_path=plain_absolute(path)
    def read(value):return json.loads(plain_absolute(value).read_text(encoding='utf-8-sig'))
    def digest(value):
        with plain_absolute(value).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
    ref=dict(path=str(policy_path),sha256=digest(policy_path));p=read(policy_path)
    registration=read(ledger/'cap1-registration.json')
    require(Path(registration['path'])==policy_path and registration['sha256'].lower()==ref['sha256'],'CAP1 storage registered policy')
    require(p['schemaVersion']==1 and p['stage']=='CAP-1' and p['role'] in ('cap-target','cap-diagnostic-ab'),'CAP1 storage stage/role')
    prof=validate_profile(p['cap1Profile']);require(not structural_support(prof),'CAP1 storage supported profile')
    issued=read(evidence_path(session)/'cap-issued.json');attempt=read(ledger/f"cap1-attempt-{p['attempt']}.json")
    require(issued['SessionId']==attempt['SessionId']==session and issued['RunId']==attempt['RunId']
        and issued['Stage']==attempt['Stage']=='CAP-1' and issued['Role']==p['role']==prof['role'],'CAP1 storage issued identity')
    require(issued['Policy']==attempt['Policy']==registration and issued['issuedEpochMillis']==attempt['IssuedEpochMillis']
        and issued['expiresEpochMillis']==attempt['ExpiresEpochMillis']
        and issued['expiresEpochMillis']-issued['issuedEpochMillis']==prof['totalSeconds']*1000
        and issued['issuedEpochMillis']<=time.time()*1000<issued['expiresEpochMillis'],'CAP1 storage original issuance/expiry')
    if p['role']=='cap-diagnostic-ab':
        from phase18_cap1_comparison import verify_issued
        verify_issued(p,issued,attempt,ledger,read,digest)
    state,tip=load_chain(ledger);budget=read(p['budgetReservation']['path'])
    require(digest(p['budgetReservation']['path'])==p['budgetReservation']['sha256'],'CAP1 storage reservation hash')
    require(state.get('volumeLayout')==C_GRAPH_LAYOUT and state['gateMet'] and state['transition']['reservation']==budget
        and not state['transition']['closed'] and state['trialBudget']['violation'] is None,'CAP1 storage active reservation')
    for key,needed in dict(evidence=prof['usage']['evidenceBytes'],mysql=prof['storage']['usage']['mysqlBytes'],
                           wal=prof['storage']['usage']['walBytes'],work=prof['usage']['workspaceBytes']).items():
        require(budget['additionalPeak'][key]>=needed,'CAP1 storage physical peak coverage: '+key)
    for item in p['sourceBinding']:require(digest(repo/item['path'])==item['sha256'],'CAP1 storage current source binding')
    return dict(kind='cap1-physical-usage-v1',SessionId=session,Policy=registration,ExpiresEpochMillis=issued['expiresEpochMillis'],
        MysqlUsageBytes=prof['storage']['usage']['mysqlBytes'],EvidenceUsageBytes=prof['usage']['evidenceBytes'],
        WorkspaceUsageBytes=prof['usage']['workspaceBytes'],WalUsageBytes=prof['storage']['usage']['walBytes'],
        NextReservationBytes=state['nextReservation']['F'],MysqlLimitBytes=prof['storage']['limits']['mysqlBytes'],
        EvidenceLimitBytes=prof['limits']['evidenceBytes'])


if __name__=='__main__':
    import argparse,json
    a=argparse.ArgumentParser();choice=a.add_mutually_exclusive_group(required=True)
    choice.add_argument('--verify-policy');choice.add_argument('--verify-storage-start')
    a.add_argument('--session');a.add_argument('--repo',required=True);a.add_argument('--ledger-root',required=True)
    args=a.parse_args()
    if args.verify_storage_start:
        require(args.session is not None,'CAP1 storage session required')
        result=verify_storage_start(args.verify_storage_start,args.repo,args.ledger_root,args.session)
    else:result=verify_policy(args.verify_policy,args.repo,args.ledger_root)
    print(json.dumps(result))
