"""k6 실제 기록 스키마의 무서비스 합성 검사. DB/JVM 복구 성공 증거가 아님."""
import copy
import json
import struct
import unittest
import phase18_k6 as k
import phase18_cap as cap
import test_analyze_phase18 as legacy

a=legacy.a

class K6Results(unittest.TestCase):
    def test_missing_input_aggregation_is_unassessed_without_promoting_failure(self):
        result=k.classify({'workload':{},'role':'cap-smoke-s2'},{},{},{},['execution failed'],[],[])
        self.assertIsNone(result['inputCriteriaMet']);self.assertEqual('unavailable',result['inputEvaluation'])
        self.assertEqual('INVALID_OR_STOPPED',result['verdict'])

    def fixture(self,mode='normal',mutate=None):
        f=legacy.ResultsTest(); f.setUp()
        try:
            f.plan.update(schemaVersion=3,phase='CAP',stage='CAP-1'); f.plan['ownership']['temp']=str(f.root)
            f.case['role']='cap-target'; w=f.case['workload']; w.update(writeRate=1,measurementSeconds=60,inputWindowSeconds=10,inputTolerancePpm=1000,schedule={'kind':'fixed-rate'})
            f.producer['anchors']['measurement']['endNanos']=70*legacy.N
            old=[f.row(i,userOrdinal=i,userAttempt=1) for i in range(60)]
            if mode=='input': old.pop(5)
            if mode in ('busy','empty'):
                for r in (old if mode=='empty' else [old[5]]): r.update(status='server_5xx',httpStatus=503,eventSeq=None,outcome='REJECTED',reason='cap_write_busy')
            if mode=='s2-busy':
                w['readRate']=1
                old=[f.row(i,userOrdinal=i%2,userAttempt=i//2+1) for i in range(4)]+[f.row(i,kind='read') for i in range(4)]
                old[2].update(status='server_5xx',httpStatus=503,eventSeq=None,outcome='REJECTED',reason='cap_write_busy')
                w.update(writeRate=0,readRate=0,measurementSeconds=220,schedule=dict(kind='smoke-s2',activeUsers=2))
                f.case['role']='cap-smoke-s2'
            def extra():
                ph=f.producer['planHash']; run=f.run; raw=[]
                for r in old:
                    at=10000+cap.offset(w,r['kind'],r['ordinal'],'measurement')//1000000
                    value=dict(type='http',schema='phase18-k6-v2',id=r['requestId'],kind=r['kind'],phase='measurement',ordinal=r['ordinal'],dueEpochMillis=at,
                        startedEpochMillis=at,bodyEpochMillis=at+10,validationEpochMillis=at+10,bridgeBeforeEpochMillis=at,bridgeAfterEpochMillis=at,bridgeEpochMillis=at,
                        httpStatus=r['httpStatus'],outcome=r['outcome'],reason=r['reason'],k6DurationMillis=300 if mode=='perf' else 10,userOrdinal=r.get('userOrdinal',0),attempt=r.get('userAttempt',1))
                    if r['outcome']=='ACCEPTED' and r['kind']=='write':value['event']=dict(eventSeq=r['ordinal']*7+1,tileVersion=r['ordinal']+1,x=0,y=0,color=1)
                    if r['kind']=='read':value.update(decodedLength=65536,tileVersion='0')
                    raw.append(value)
                f.put('k6-input.json',dict(schema='phase18-k6-v2',planHash=ph,plan=f.plan,k6BinarySha256='a'*64,scriptSha256='b'*64))
                f.put('k6-runtime.json',dict(BinarySha256='a'*64,ScriptSha256='b'*64,Version='k6 v2.3.0 fixture'))
                f.put('k6-producer.json',dict(schema='phase18-k6-v2',planHash=ph,producerComplete=True,firstFailure=None,unresolved=[],started=len(raw),terminal=len(raw),
                    phaseBoundaries=[dict(event='drained',phase='warmup',epochMillis=10000),dict(event='k6-scenario-start',phase='measurement',epochMillis=10000),dict(event='drained',phase='measurement',epochMillis=10000+w['measurementSeconds']*1000)],
                    ws=dict(duplicates=[],missing=[],extra=[])))
                code=99 if mode=='perf' else 0
                f.put('k6-runner.json',dict(Complete=True,FirstFailure=None,PeakCombinedResident=100,PeakCombinedPrivate=100,Processes=[dict(Phase='measurement',ActualExit=code,Released=True,Failure=None)]))
                (run/'k6-records.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in raw))
                for name in ('k6-ws-canonical.jsonl','k6-ws-receipts.bin','k6-ws-times.bin','cap-http.jsonl'): (run/name).write_bytes(b'')
                (run/'cap-db.jsonl').write_text(''.join(json.dumps(dict(planHash=ph,clockId='phase18-k6-epoch-millis',queryStartEpochMillis=t,queryEndEpochMillis=t,checkpoint=10000))+'\n' for t in range(10000,10000+w['measurementSeconds']*1000,1000)))
                f.put('cap-observer.json',dict(schemaVersion=3,planHash=ph,clockId='phase18-k6-epoch-millis',connectionClosed=True,queries=w['measurementSeconds'],hashes={n:a.sha(run/n) for n in ('cap-http.jsonl','cap-db.jsonl')}))
                durations=[r['k6DurationMillis'] for r in raw if r['outcome']=='ACCEPTED' and r['kind']=='write']
                f.put('k6-measurement-summary.json',dict(schema='phase18-k6-summary-v1',phase='measurement',k6Version='2.3.0',data=dict(metrics={'accepted_http_duration':dict(values=dict(count=len(durations),**{'p(95)':k.quantile(durations,.95),'p(99)':k.quantile(durations,.99)}))})))
                (run/'k6-measurement-metrics.json').write_text('{"synthetic":true}\n')
                f.put('k6-measurement-exit.json',dict(RequiredOutputComplete=True,ActualExit=code,FailedThresholds=['accepted_http_duration:p(95)<=100'] if code else [],
                    MetricsBytes=(run/'k6-measurement-metrics.json').stat().st_size,PeakWorkingSet=100,PeakPrivateBytes=100,
                    MetricsSha256=a.sha(run/'k6-measurement-metrics.json'),SummarySha256=a.sha(run/'k6-measurement-summary.json')))
                hashes={path.name:a.sha(path) for path in run.iterdir() if path.name.startswith(('k6-','cap-')) and path.name!='k6-measurement-metrics.json'}
                for folder in (run,run.parent/'recovery'):
                    m=a.read(folder/'manifest.json');m.update(schemaVersion=2,inputKind='phase18-k6-v2',recordsHash=a.sha(run/'k6-records.jsonl'),k6Hashes=hashes,users=list(range(60)),consistencyRecoveryConnected=folder!=run)
                    f.put('manifest.json',m,folder);f.rehash(folder)
                if mutate:mutate(f)
            return f.analyze(old,extra=extra)
        finally:f.tearDown()

    def test_normal_and_threshold_miss_keep_full_recovery_evidence(self):
        for mode,wanted in [('normal','PASS'),('perf','PERFORMANCE_MISS'),('input','INPUT_LIMIT'),('busy','PERFORMANCE_MISS'),('empty','PERFORMANCE_MISS')]:
            with self.subTest(mode=mode):
                r=self.fixture(mode); self.assertEqual(wanted,r['cap']['verdict'],r['cap']['reasons'])
                self.assertTrue(r['integrity']['complete']);self.assertTrue(r['integrity']['recoveryComplete'])
                self.assertEqual(99 if mode=='perf' else 0,r['cap']['actualExits'][0]['ActualExit'])
                self.assertEqual(60,r['cap']['input']['write']['P'])


    def test_actual_native_output_peak_and_legacy_reservation_boundaries(self):
        # 보존된 완주 W500의 실제 phase receipt로 새/기존 한도 경계를 구별
        result=dict(RequiredOutputComplete=True,ActualExit=0,MetricsBytes=809664140,PeakWorkingSet=586981376,PeakPrivateBytes=617091072)
        process=dict(ActualExit=0);size=result['MetricsBytes']
        self.assertTrue(k.required_native_output(result,process,size,805306368))
        self.assertFalse(k.required_native_output(result,process,size,536870912))
        for field,value in [('PeakWorkingSet',805306369),('PeakPrivateBytes',805306369),('PeakPrivateBytes',0),('RequiredOutputComplete',False)]:
            altered=dict(result,**{field:value})
            self.assertFalse(k.required_native_output(altered,process,size,805306368),field)
        self.assertFalse(k.required_native_output(result,process,size+1,805306368))
        self.assertFalse(k.required_native_output(result,dict(ActualExit=1),size,805306368))
        edge=dict(result,PeakWorkingSet=536870912,PeakPrivateBytes=536870912)
        self.assertTrue(k.required_native_output(edge,process,size,536870912))

    def test_s2_allowlisted_rejection_keeps_valid_closure_and_smoke_failure(self):
        r=self.fixture('s2-busy')
        self.assertEqual('PERFORMANCE_MISS',r['cap']['verdict'],r['cap']['reasons'])
        self.assertIn('S2 two users two successes',r['cap']['failureCategories']['performance'])
        self.assertTrue(r['integrity']['recoveryComplete']);self.assertEqual('FAIL',r['cap']['smokeVerification'])
        self.assertEqual(4,r['cap']['input']['write']['P']);self.assertEqual(3,r['cap']['input']['write']['A'])

    def test_exit_zero_cannot_hide_missing_duplicate_extra_unknown_or_ownership(self):
        def records(f,change):
            path=f.run/'k6-records.jsonl';rows=[json.loads(x) for x in path.read_text().splitlines()];change(rows);path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        changes=[lambda f:records(f,lambda rows:rows.pop()),lambda f:records(f,lambda rows:rows.__setitem__(1,rows[0])),
                 lambda f:records(f,lambda rows:rows[0].update(id='outside')),
                 lambda f:records(f,lambda rows:rows[0].update(outcome='UNKNOWN',httpStatus=500)),
                 lambda f:(f.run/'k6-measurement-metrics.json').unlink(),
                 lambda f:f.change('../recovery/manifest.json',dict(normalShutdown=False),rehash=True),
                 lambda f:f.change('../recovery-process/exit.json',dict(unresolved=True)),
                 lambda f:f.change('k6-runner.json',dict(Complete=False,FirstFailure='original_expiry'))]
        expected=['exact producer row correspondence','exact original request ID','exact original request ID','UNKNOWN/non-allowlisted response',
                  'k6-measurement-metrics.json','manifest identity/completion','recovery-process/exit.json','k6 runner ownership']
        for change,reason in zip(changes,expected):
            with self.subTest(change=change):
                r=self.fixture(mutate=change);self.assertEqual('INVALID_OR_STOPPED',r['cap']['verdict']);self.assertEqual(0,r['cap']['actualExits'][0]['ActualExit'])
                self.assertIn(reason,json.dumps(r['cap']['reasons']))

    def test_k6_interpolation_and_db_gaps_validation_cutoff(self):
        self.assertEqual(95,k.quantile([0,100],.95));self.assertEqual(100,a.percentile([0,100],95))
        accepted={7:dict(validated=1000,body=900),999:dict(validated=3000,body=950),1001:dict(validated=1000,body=800)}
        samples=[dict(queryStartEpochMillis=2000,queryEndEpochMillis=2000,checkpoint=7),dict(queryStartEpochMillis=4000,queryEndEpochMillis=4000,checkpoint=999)]
        result=k.checkpoint_series(samples,accepted,0,5000,1)
        self.assertEqual([1,1],[v['pendingCount'] for v in result['samples']]);self.assertEqual([1200,3200],[v['BMillis'] for v in result['samples']])

    def test_streaming_metrics_bound_and_large_output(self):
        import tempfile,time
        from pathlib import Path
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'metrics.json';summary=dict(data=dict(metrics={}))
            definition=b'{"type":"Metric","metric":"http_req_duration","data":{"name":"http_req_duration","type":"trend","contains":"time","thresholds":[]}}\n'
            point=b'{"type":"Point","metric":"http_req_duration","data":{"time":"2026-10-11T00:00:00.123456789Z","value":12.5,"tags":{"name":"finite","scenario":"measurement"}}}\n'
            with path.open('wb') as stream:
                stream.write(definition)
                for _ in range(250000):stream.write(point)
            cap.memory_limit(536870912);began=time.monotonic()
            result=k.validate_metrics(a,path,summary)
            self.assertEqual(250000,result['points']);self.assertEqual(a.sha(path),result['metricsSha256'])
            self.assertGreater(result['metricsBytes'],32*1024**2)
            print('K6_METRICS_STREAM '+json.dumps(dict(**result,seconds=time.monotonic()-began,memory=cap.memory_usage())))
            cap.memory_limit(None)
            for raw,reason in [(b' '*16385+b'\n','metrics line bound'),(b'{"type":"Point","type":"Metric"}\n','metrics JSON'),(b'','metrics empty')]:
                path.write_bytes(raw)
                with self.assertRaisesRegex(a.Invalid,reason):k.validate_metrics(a,path,summary)

    def test_clock_uncertainty_zero_denominators_and_all_reasons(self):
        c=dict(role='cap-smoke-s1',workload=dict(wsConnections=1))
        metrics=dict(write=dict(P=1,S_all=1,A=1,inputMet=True,successRatio=1,systemErrorRate=0,duration=dict(p95Millis=1,p99Millis=1)),read=dict(P=0,inputMet=None))
        ws=dict(overall=dict(count=1,p95Millis=250,p99Millis=250),connections=[],uncertaintyMillis=2)
        self.assertEqual('TOOL_LIMIT',k.classify(c,metrics,ws,{},[],[],[])['verdict'])
        metrics['write'].update(S_all=0,A=0,inputMet=False)
        self.assertEqual('INPUT_LIMIT',k.classify(c,metrics,{}, {},[],[],[])['verdict'])
        r=k.classify(c,metrics,ws,{},['ownership'],['HTTP duration'],[])
        self.assertEqual('INVALID_OR_STOPPED',r['verdict']);self.assertGreaterEqual(len(r['reasons']),4)

class CausalClock(unittest.TestCase):
    def test_delay_asymmetry_and_epoch_drift_do_not_narrow_causal_interval(self):
        event=dict(claimFloorMillis=10,resultCeilingMillis=40,sent=1000000)
        self.assertEqual((5,50),k.causal_interval(a,(45,60),event))
        # Delayed result or batch arrival widens bounds, never improves them.
        self.assertEqual((0,90),k.causal_interval(a,(45,100),dict(event,resultCeilingMillis=80)))
        for wall in (-900000,0,9999999999999):
            self.assertEqual((5,50),k.causal_interval(a,(45,60),dict(event,sent=wall)))
        for bounds,entry in [((60,45),event),((0,5),event),((-1,50),event),((0,21600000),event),((0,50),dict(event,resultCeilingMillis=9))]:
            with self.assertRaises(a.Invalid):k.causal_interval(a,bounds,entry)
        with self.assertRaises(KeyError):k.causal_interval(a,(45,60),{})

    def test_causal_nearest_rank_bounds_keep_pass_miss_and_uncertainty_distinct(self):
        c=dict(role='cap-smoke-s1',workload=dict(wsConnections=1))
        metrics=dict(write=dict(P=1,S_all=1,A=1,inputMet=True,successRatio=1,systemErrorRate=0,duration=dict(p95Millis=1,p99Millis=1)))
        for lower,upper,wanted in [(0,249,'PASS'),(251,300,'PERFORMANCE_MISS'),(249,251,'TOOL_LIMIT')]:
            values=dict(count=1,p95Millis=999999,p99Millis=-99999,causal=dict(p95Millis=dict(lowerMillis=lower,upperMillis=upper),p99Millis=dict(lowerMillis=lower,upperMillis=upper)))
            ws=dict(clockMode='bridge-causal-brackets-v1',overall=values,connections=[values],uncertaintyMillis=9999)
            self.assertEqual(wanted,k.classify(c,metrics,ws,{},[],[],[])['verdict'])
            self.assertEqual('INVALID_OR_STOPPED',k.classify(c,metrics,ws,{},['missing receipt'],[],[])['verdict'])

class RevisedEvidence(unittest.TestCase):
    def test_native_series_rejects_missing_false_sum_and_guard_breach(self):
        import tempfile
        from pathlib import Path
        runtime=dict(MaxProcessMemory=10,MaxCombinedMemory=20,Expires=1000)
        runner=dict(Processes=[dict(Phase='ws',PeakPrivateBytes=10,PeakWorkingSet=10)],PeakCombinedPrivate=10,PeakCombinedResident=10)
        row=dict(epochMillis=100,processes=[dict(Phase='ws',Pid=1,PrivateBytes=10,WorkingSetBytes=9)],combinedPrivateBytes=10,combinedWorkingSetBytes=9,failure=None)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'memory.jsonl'
            path.write_text(json.dumps(row)+'\n');self.assertEqual(1,k.native_memory_observations(a,path,runner,runtime)['samples'])
            bad=[None,dict(row,combinedPrivateBytes=9),dict(row,failure='k6_memory_limit'),dict(row,processes=[]),dict(row,epochMillis=1001),dict(row,processes=[dict(row['processes'][0],PrivateBytes=11)],combinedPrivateBytes=11)]
            for case in bad:
                path.write_text('' if case is None else json.dumps(case)+'\n')
                with self.assertRaises(a.Invalid):k.native_memory_observations(a,path,runner,runtime)

    def test_causal_full_set_unused_slot_and_truncation_are_checked(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as folder:
            run=Path(folder);w=dict(writeRate=1,readRate=0,warmupSeconds=0,measurementSeconds=3,wsConnections=2,bootstrap=False)
            p=dict(schemaVersion=4,phase='CAP',stage='CAP-1',bounds=dict(samples=10001),ownership=dict(temp=folder));c=dict(workload=w)
            events=[dict(eventSeq=n,tileVersion=n,x=0,y=0,color=1) for n in (9,7)]
            accepted={v['eventSeq']:dict(event=v,sent=100,phase='measurement',claimFloorMillis=5,resultCeilingMillis=15) for v in events}
            producer=dict(ws=dict(duplicates=[0,0],missing=[0,0],extra=[0,0]))
            (run/'k6-ws-canonical.jsonl').write_text(''.join(json.dumps(v)+'\n' for v in events))
            (run/'k6-ws-receipts.bin').write_bytes(struct.pack('>QQ',3,3))
            (run/'k6-ws-times.bin').write_bytes(struct.pack('>qqqqqq',105,107,0,105,107,0))
            valid=b''.join(struct.pack('>II',10,20) if i%3!=2 else bytes(8) for i in range(6))
            path=run/'k6-ws-causal.bin';path.write_bytes(valid)
            result=k.ws_metrics(a,p,c,run,accepted,producer,999)
            self.assertEqual(4,result['overall']['count']);self.assertEqual(dict(lowerMillis=0,upperMillis=15),result['overall']['causal']['p99Millis'])
            self.assertEqual(7,result['overall']['p99Millis'])
            for bad,why in ((valid[:-1],'WS causal bytes'),(valid[:-8]+struct.pack('>II',1,2),'WS unused receipt timing/bounds'),(struct.pack('>II',21,20)+valid[8:],'WS causal clock/range/order')):
                path.write_bytes(bad)
                with self.assertRaisesRegex(a.Invalid,why):k.ws_metrics(a,p,c,run,accepted,producer,1)

if __name__=='__main__':unittest.main()
