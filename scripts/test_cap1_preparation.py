"""CAP1 유한 예약·보존 장부·동등 실패 거부의 선택 검증."""
import copy,hashlib,unittest
import phase18_cap1 as c
import phase18_cap as cap
import phase18_cap_budget as b
from test_cap_budget import seed,smoke_reservation

class Cap1Preparation(unittest.TestCase):
    def test_baseline_http_ws_users_and_independent_output_limits(self):
        p=c.profile(105,120)
        self.assertEqual((15751,1575000,15751,128),(p['requests'],p['deliveries'],p['users'],p['writeVus']))
        self.assertEqual(4383,p['totalSeconds']);self.assertEqual(1882741920,p['usage']['runBytes'])
        for key in p['usage']:self.assertLess(2*p['usage'][key],p['limits'][key])
        self.assertEqual([],c.structural_support(p))
        for key,usage in p['storage']['usage'].items():self.assertLess(2*usage,p['storage']['limits'][key])
        self.assertEqual(5*b.GIB,p['storage']['limits']['binlogBytes'])
        self.assertEqual(10*b.GIB,p['storage']['limits']['mysqlBytes'])
    def test_new_native_profile_preserves_old_and_rejects_partial_override(self):
        old=c.profile(500,60);new=c.profile(500,60,'cap1-causal-memory-v2')
        self.assertEqual((768,1280),(old['k6ProcessMemoryBytes']//c.MIB,old['k6MemoryBytes']//c.MIB))
        self.assertEqual((1280,1792),(new['k6ProcessMemoryBytes']//c.MIB,new['k6MemoryBytes']//c.MIB))
        self.assertEqual((512,512,100),(old['k6GoMemoryBytes']//c.MIB,new['k6GoMemoryBytes']//c.MIB,new['k6GoGcPercent']))
        self.assertEqual(old['usage']['workspaceBytes']+4500000*144,new['usage']['workspaceBytes'])
        self.assertEqual(new,c.validate_profile(new));self.assertEqual(old,c.validate_profile(old))
        new['k6ProcessMemoryBytes']=old['k6ProcessMemoryBytes']
        with self.assertRaisesRegex(ValueError,'profile mismatch'):c.validate_profile(new)

    def test_actual_generator_replaces_small_template_allocation(self):
        import importlib.util,json
        from pathlib import Path
        script=Path(__file__).with_name('new-phase18-cap1-plan.py')
        spec=importlib.util.spec_from_file_location('cap1_generator',script)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        template=script.parent.parent/'build/agent-runs/phase18-cf-smoke-20261003-1540/plan-cf-smoke.json'
        from pixel_place_paths import historical
        p=json.loads(historical(template).read_text(encoding='utf-8-sig'))
        self.assertEqual(64,p['bounds']['users'])
        profile=c.profile(105,120)
        p,_=module.apply_profile(p,{},profile,dict(issuedEpochMillis=1,expiresEpochMillis=4383001))
        self.assertEqual(15751,p['bounds']['users'])
        self.assertEqual(15751,p['bounds']['writeTerminals'])
        self.assertEqual(15750,p['bounds']['wsEvents'])
        self.assertGreaterEqual(p['bounds']['users'],p['cases'][0]['workload']['userPool']+1)
    def test_all_candidate_combinations_are_calculated_not_claimed_as_pass(self):
        for rate in (105,250,500,1000,2000,4000,8000,10000):
            for seconds in (60,120,1200):
                p=c.profile(rate,seconds);self.assertEqual(rate*(30+seconds)*100,p['deliveries'])
                self.assertGreater(p['totalSeconds'],seconds+30)
        self.assertEqual([],c.structural_support(c.profile(105,1200)))
        self.assertEqual([],c.structural_support(c.profile(500,60)))
        self.assertEqual([],c.structural_support(c.profile(250,60)))
        self.assertEqual([],c.structural_support(c.profile(1000,120)))
        self.assertIn('CAP1 HTTP retention 320000',c.structural_support(c.profile(4000,60)))
        self.assertEqual([],c.structural_support(c.profile(250,1200)))
    def test_integer_boundaries_and_frozen_profile_reject_tampering(self):
        self.assertEqual(50,c.expand(10,5));self.assertEqual(250,c.expand(10,25))
        for a,bad in ((0,1),(1,-1),(1,None),(True,0),(2**63-1,2**62)):
            with self.assertRaises(ValueError):c.expand(a,bad)
        p=c.profile(105,120);p['totalSeconds']+=1
        with self.assertRaisesRegex(ValueError,'profile mismatch'):c.validate_profile(p)
    def test_cap1_storage_uses_existing_chain_without_s2_policy_impersonation(self):
        prior=b.seed_state(seed(),'a'*64);r=smoke_reservation();r['stage']='CAP-1'
        r['storagePolicy']=b.storage_expansion(prior,prior['observed'],'b'*64,r)
        r['registeredTrialBytes']=r['storagePolicy']['limits']['trial']
        self.assertEqual('cap1-storage-50pct-v1',r['storagePolicy']['kind'])
        actual=b.reserve(prior,prior['observed'],dict(C=40*b.GIB,F=400*b.GIB),'b'*64,r)
        self.assertTrue(actual['gateMet']);self.assertGreaterEqual(actual['charged']['C'],prior['charged']['C'])
        broken=copy.deepcopy(r);broken['storagePolicy']['kind']='s2-storage-50pct-v1'
        with self.assertRaisesRegex(ValueError,'stage storage'):b.reserve(prior,prior['observed'],actual['free'],'b'*64,broken)
    def test_preload_repair_does_not_reclassify_valid_or_unclosed_runs(self):
        worker=dict(ActualExit=1,FirstFailure='CAP child exit 1',Request='step-012-plan.request.json',
            Joined=True,Released=True,DbUsersClosed=True,CleanupFailure=None)
        close=dict(Status='FAILED',RunExit=None,AnalysisExit=None,OwnedResourcesClosed=True,
            NormalMysqlStop=True,BudgetAfterVerified=True,OwnedJava=[],Remaining3307=0,RemainingRunLock=False,
            Budget=dict(gateMet=True,transition=dict(closed=True)),OwnedWorkers=[worker])
        plan=dict(stage='CAP-1',bounds=dict(users=64,writeTerminals=64),cases=[dict(role='cap-target',workload=dict(userPool=15750))])
        requests=[dict(Parameters=dict(Action='Plan'))]
        c.require_closed_preload_allocation_failure(close,plan,requests)
        for key,value in [('RunExit',0),('AnalysisExit',0),('Status','INPUT_LIMIT'),('OwnedResourcesClosed',False),('NormalMysqlStop',False)]:
            with self.assertRaises(ValueError):c.require_closed_preload_allocation_failure(dict(close,**{key:value}),plan,requests)
        with self.assertRaises(ValueError):c.require_closed_preload_allocation_failure(close,plan,[dict(Parameters=dict(Action='ProduceK6'))])
    def test_postload_storage_resume_never_allows_other_integrity_failure(self):
        close=dict(Status='FAILED',RunExit=1,AnalysisExit=0,OwnedResourcesClosed=True,NormalMysqlStop=True,
            BudgetAfterVerified=True,OwnedJava=[],Remaining3307=0,RemainingRunLock=False,
            Budget=dict(gateMet=True,transition=dict(closed=True)),OwnedWorkers=[dict(ActualExit=1,
            FirstFailure='CAP child exit 1',Request='step-014-run.request.json',Joined=True,Released=True,
            DbUsersClosed=True,CleanupFailure=None)])
        failure=dict(normalShutdown=True,kind='java.lang.IllegalArgumentException',nextTaskAllowed=False,
            failures=['java.lang.IllegalArgumentException: Phase18 rejected: actual catalog/binlog budget'])
        analysis=dict(status='EXECUTION_FAILED',cap=dict(verdict='INVALID_OR_STOPPED',inputCriteriaMet=True,performanceCriteriaMet=True))
        c.require_closed_storage_failure(close,failure,analysis,[{}])
        for changed in (dict(failure,normalShutdown=False),dict(failure,failures=['DB snapshot bytes/version']),
            dict(failure,failures=failure['failures']+['recovery mismatch'])):
            with self.assertRaises(ValueError):c.require_closed_storage_failure(close,changed,analysis,[{}])
        with self.assertRaises(ValueError):c.require_closed_storage_failure(dict(close,OwnedResourcesClosed=False),failure,analysis,[{}])
        with self.assertRaises(ValueError):c.require_closed_storage_failure(close,failure,dict(analysis,cap=dict(analysis['cap'],inputCriteriaMet=False)),[{}])
    def test_collector_replay_cannot_override_unknown_failure_or_unclosed_owner(self):
        close=dict(Status='FAILED',RunExit=1,AnalysisExit=0,OwnedResourcesClosed=True,NormalMysqlStop=True,
            BudgetAfterVerified=True,OwnedJava=[],Remaining3307=0,RemainingRunLock=False,
            Budget=dict(gateMet=True,transition=dict(closed=True)),OwnedWorkers=[dict(ActualExit=1,
            FirstFailure='CAP child exit 1',Request='step-014-run.request.json',Joined=True,Released=True,
            DbUsersClosed=True,CleanupFailure=None)])
        failure=dict(normalShutdown=True,nextTaskAllowed=False,kind='java.lang.IllegalStateException',failures=[
            'java.lang.IllegalStateException: app_control_stop','java.io.IOException: Process exit 1',
            'java.lang.IllegalStateException: Safety stop: capture-safety-evidence-lost'])
        analysis=dict(status='EXECUTION_FAILED',cap=dict(verdict='INVALID_OR_STOPPED',inputCriteriaMet=None))
        replay=dict(originalStops=1,newStops=0,oldUpper=15079,newUpper=300,servicesStarted=0,originalResultPreserved=True)
        c.require_closed_collector_failure(close,failure,analysis,replay)
        for changed in (dict(replay,newStops=1),dict(replay,newUpper=15079)):
            with self.assertRaises(ValueError):c.require_closed_collector_failure(close,failure,analysis,changed)
        with self.assertRaises(ValueError):c.require_closed_collector_failure(dict(close,OwnedResourcesClosed=False),failure,analysis,replay)
        with self.assertRaises(ValueError):c.require_closed_collector_failure(close,dict(failure,failures=failure['failures']+['UNKNOWN']),analysis,replay)
    def test_three_passes_do_not_hide_equivalent_valid_failure(self):
        digest=lambda s:hashlib.sha256(s.encode()).hexdigest()
        condition=digest('series')
        h=[dict(closed=True,verdict='PASS',value=105,seconds=120,sessionId='s'+str(i),runId='r'+str(i),
            evidenceVerified=True,conditionsHash=condition,planHash=digest('p'+str(i)),
            analysisHash=digest('a'+str(i)),closureHash=digest('c'+str(i))) for i in range(3)]
        self.assertTrue(cap.selection_gate('confirm-20min',h,105,condition))
        for result in ('PERFORMANCE_MISS','INPUT_LIMIT'):
            self.assertFalse(cap.selection_gate('confirm-20min',h+[dict(h[0],verdict=result)],105,condition))
        self.assertTrue(cap.selection_gate('confirm-20min',h+[dict(h[0],seconds=60,verdict='PERFORMANCE_MISS')],105,condition))
        high=dict(h[0],value=250,verdict='PERFORMANCE_MISS')
        self.assertTrue(cap.selection_gate('confirm-20min',h+[high],105,condition))
        self.assertFalse(cap.selection_gate('confirm-20min',h[:2]+[high],105,condition))

if __name__=='__main__':unittest.main()
