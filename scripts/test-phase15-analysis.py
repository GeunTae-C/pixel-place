"""증거 분석기의 합성 입력·누락/오염 거부 검증. 실제 부하나 DB 변경 없음."""
import copy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('analysis', Path(__file__).with_name('summarize-phase15.py'))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


class AnalysisContract(unittest.TestCase):
    def metrics(self, on=False):
        return dict(enabled=on, incomplete=False, observerFailures=0, samplerFailure=None,
                    performanceCollection='collected' if on else 'disabled: operation timers and batch-size distribution',
                    timers=[dict(operation=o, outcome='success', count=n) for o,n in
                            [('record_force',2),('command',4),('storage_append',2),('group_request',4),
                             ('queue_wait',4),('batch_core_call',2),('batch_core_inside',2)]] if on else [],
                    walCounts=dict(singleCalls=0,batchCalls=2,batchRecords=4,completedRecords=4,
                                   recordForces=2,coveredRecords=4,emptyForces=1,failedForces=0,rotations=1,
                                   batchSizes={'1':1,'3':1} if on else None))

    def test_disabled_keeps_actual_safety_and_rejects_zero_or_borrowed_performance(self):
        value=self.metrics()
        self.assertTrue(all(analysis.coverage_checks(value,'group',4,False).values()))
        for replacement in ({}, {'1':4}, 0):
            changed=copy.deepcopy(value);changed['walCounts']['batchSizes']=replacement
            self.assertFalse(all(analysis.coverage_checks(changed,'group',4,False).values()))
        value['timers']=[dict(operation='record_force',outcome='success',count=2)]
        self.assertFalse(all(analysis.coverage_checks(value,'group',4,False).values()))

    def test_collection_failure_and_missing_safety_are_not_disabled(self):
        for on in (True,False):
            for mutation in ('missing','failure','observer','enabled'):
                value=self.metrics(on)
                if mutation=='missing': del value['walCounts']['coveredRecords']
                elif mutation=='failure': value['incomplete']=True
                elif mutation=='observer': value['observerFailures']=1
                else: value['enabled']=not on
                self.assertFalse(all(analysis.coverage_checks(value,'group',4,on).values()))

    def test_force_less_than_events_never_substitutes_for_exact_record_coverage(self):
        self.assertTrue(all(analysis.coverage_checks(self.metrics(True),'group',4,True).values()))
        for field,replacement in [('coveredRecords',3),('batchRecords',3),('completedRecords',3),('failedForces',1),('rotations',0)]:
            value=self.metrics();value['walCounts'][field]=replacement
            self.assertFalse(all(analysis.coverage_checks(value,'group',4,False).values()))
        self.assertFalse(all(analysis.coverage_checks(self.metrics(),'single',4,False).values()))

    def test_nearest_rank_uses_real_samples_and_empty_is_na(self):
        self.assertEqual(4,analysis.percentile([4,1,2,3],.95))
        self.assertIsNone(analysis.percentile([], .99))

    def test_optional_cycle_and_broadcast_samples_are_explicitly_disabled_not_zero(self):
        metrics={'disabledFields':{k:'instrumentation off' for k in ('timers','batchSizes','lastCycle','broadcastFailures')}}
        row=dict(lastCycle=None,broadcastFailures=None,timers=[])
        self.assertTrue(analysis.optional_samples(metrics,[row],False))
        for key in ('lastCycle','broadcastFailures'):
            for value in (0,{},False):
                broken=dict(row);broken[key]=value
                self.assertFalse(analysis.optional_samples(metrics,[broken],False))
            broken=dict(row);del broken[key]
            self.assertFalse(analysis.optional_samples(metrics,[broken],False))
        self.assertFalse(analysis.optional_samples({},[row],False))
        self.assertFalse(analysis.optional_samples(metrics,[],False))
        self.assertTrue(analysis.optional_samples({'disabledFields':{}},[dict(lastCycle=None,broadcastFailures=0)],True))

    def test_queue_claim_execution_unpublished_and_failed_closed_reject_drain(self):
        value=dict(state='RUNNING',queued=0,claimedUnsettled=0,workerInFlight=0,outstanding=0,unpublishedTerminal=0,activeCount=0,workerAlive=True)
        self.assertTrue(analysis.executor_idle(value))
        for field in ('queued','claimedUnsettled','workerInFlight','outstanding','unpublishedTerminal','activeCount'):
            changed=dict(value);changed[field]=1;self.assertFalse(analysis.executor_idle(changed))
        for state in ('FAILED','STOPPED','STOPPING'):
            changed=dict(value,state=state,workerAlive=False);self.assertFalse(analysis.executor_idle(changed))

    def test_fresh_capture_and_pending_safety_are_required_independent_of_timers(self):
        row=dict(executor=dict(state='RUNNING',queued=0,claimedUnsettled=0,workerInFlight=0,outstanding=0,unpublishedTerminal=0,activeCount=0),
                 ready=True,commandActive=0,servletActive=0,pending=False,servletStarted=5,servletCompleted=5,expectedServlets=5,
                 capture=dict(records=0,observedNanos=20,checkpoint=7,tail=7),idleSince=10,checkpoint=7,acceptedTail=7)
        self.assertTrue(analysis.drain_checks(dict(complete=True,observations=[row]*3)))
        for key in ('pending','servletActive','commandActive','executor'):
            broken=copy.deepcopy(row);del broken[key]
            self.assertFalse(analysis.drain_checks(dict(complete=True,observations=[broken]*3)))
        # 세 필드 모두 없을 때 None == None으로 정상 exact count를 흉내 내면 안 됨
        for replacement in ('missing',None,False,-1):
            broken=copy.deepcopy(row)
            for key in ('servletStarted','servletCompleted','expectedServlets'):
                if replacement=='missing': del broken[key]
                else: broken[key]=replacement
            self.assertFalse(analysis.drain_checks(dict(complete=True,observations=[broken]*3)))
        row['capture']['observedNanos']=9
        self.assertFalse(analysis.drain_checks(dict(complete=True,observations=[row]*3)))

    def test_manifest_conditions_reject_changed_source_mode_and_targets(self):
        entry=analysis.fixed_matrix()['entries'][2]
        s=analysis.read(Path(__file__).with_name('phase15-c4-input.json'))
        manifest=dict(stage='C-4',spec=s,caseIndex=entry['caseIndex'],writeSettings=dict(mode=entry['mode'],maxBatchSize=16,
                     maxOutstanding=128,queueTimeoutMillis=1000,shutdownGraceMillis=10000,collectionWaitMillis=0))
        self.assertTrue(analysis.fixed_conditions(manifest,entry))
        # C-4 승인 exact 값만 허용. 과거 C-3의 36GiB 입력과 혼합 금지
        for rejected in (38654705664, 79456894975, 79456894977, 2**63-1):
            manifest['spec']['maxTotalBytes']=rejected
            self.assertFalse(analysis.fixed_conditions(manifest,entry))
        manifest['spec']['maxTotalBytes']=79456894976
        manifest['spec']['targetP95Millis']=200
        self.assertFalse(analysis.fixed_conditions(manifest,entry))
        manifest['spec']['targetP95Millis']=100;manifest['writeSettings']['mode']='group'
        self.assertFalse(analysis.fixed_conditions(manifest,entry))

    def test_fifteen_order_missing_duplicate_and_a21_smoke_mixing(self):
        values=[e['id'] for e in analysis.fixed_matrix()['entries']]
        self.assertEqual(15,len(values));self.assertTrue(analysis.exact_entries(values,'C'))
        for changed in (values[:-1],values+[values[0]],values[::-1],values+['A']*6,['smoke-single','smoke-group']):
            self.assertFalse(analysis.exact_entries(changed,'C'))
        self.assertTrue(analysis.exact_entries(['smoke-single','smoke-group'],'smoke'))

    def test_historical_and_c4_impact_smokes_require_their_exact_frozen_approval(self):
        entry=analysis.fixed_matrix()['smokes'][0]
        s=analysis.read(Path(__file__).with_name('phase15-c4-input.json'))
        s.update(purpose='exploratory',warmupSeconds=2,measurementSeconds=5,repetitions=1,
                 cases=[dict(role='probe',offeredRate=2,webSocketSessions=2,pattern='spread-tiles')])
        manifest=dict(stage='C-3',spec=s,caseIndex=0,writeSettings=dict(mode='single',maxBatchSize=16,
            maxOutstanding=128,queueTimeoutMillis=1000,shutdownGraceMillis=10000,collectionWaitMillis=0))
        for cap,floor in ((38654705664,31123978717),(79456894976,36960813040)):
            s['maxTotalBytes']=cap;manifest['budgetApproval']=dict(maximumBytes=cap,carriedChargeBytes=floor)
            self.assertTrue(analysis.fixed_conditions(manifest,entry))
            manifest['budgetApproval']['carriedChargeBytes']=floor-1
            self.assertFalse(analysis.fixed_conditions(manifest,entry))
        del manifest['budgetApproval']
        self.assertFalse(analysis.fixed_conditions(manifest,entry))

    def test_evidence_source_is_independent_of_current_source_and_rejects_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'source.java';p.write_text('frozen source',encoding='utf-8')
            identity={'source.java':hashlib.sha256(p.read_bytes()).hexdigest()}
            self.assertTrue(analysis.source_matches(identity,directory))
            p.write_text('different candidate',encoding='utf-8')
            self.assertFalse(analysis.source_matches(identity,directory))
            self.assertFalse(analysis.source_matches({'../source.java':'bad'},directory))


if __name__ == '__main__':
    unittest.main()
