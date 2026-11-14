"""CAP 분모·일정 경계의 요구사항 기반 작은 사례. 실제 서비스 실적과 구분."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import phase18_cap as cap
import unittest
import tempfile

N=10**9
class CapInputs(unittest.TestCase):
    def test_original_due_readiness_does_not_use_late_dispatch_time(self):
        validated=19*N;due=200*N
        self.assertFalse(cap.user_ready_at_due(due-1,validated,'ACCEPTED'))
        self.assertTrue(cap.user_ready_at_due(due,validated,'ACCEPTED'))
        self.assertTrue(cap.user_ready_at_due(due+1,validated,'ACCEPTED'))
        self.assertFalse(cap.user_ready_at_due(due,validated+1,'ACCEPTED'))
        self.assertFalse(cap.user_ready_at_due(due+1000*N,validated,'UNKNOWN'))
    def case(self,rate=1,seconds=20):return {'workload':{'writeRate':rate,'readRate':0,'measurementSeconds':seconds,'warmupSeconds':0,'bootstrap':False,'inputWindowSeconds':10,'schedule':{'kind':'fixed-rate'}}}
    def row(self,i,sent=None,status='accepted'):
        return {'requestId':str(i),'kind':'write','scheduledPhase':'measurement','scheduledOffsetNanos':i*N,'sendState':'sent','sentNanos':i*N if sent is None else sent,
                'completedNanos':(i*N if sent is None else sent)+1000,'status':status,'reason':'','outcome':'ACCEPTED'}
    def test_p_zero_and_s_zero_are_not_fake_success(self):
        m,problems=cap.inputs(self.case(),[],0,20*N)
        self.assertIsNone(m['read']['inputMet']);self.assertIsNone(m['read']['successRatio']);self.assertFalse(m['write']['inputMet']);self.assertIsNone(m['write']['successRatio'])
    def test_exact_deadline_is_invalid_and_late_window_is_not_offset(self):
        rows=[self.row(i) for i in range(20)];rows[9]=self.row(9,10*N)
        m,problems=cap.inputs(self.case(),rows,0,20*N)
        self.assertEqual(['9'],problems);self.assertEqual(20,m['write']['S_all']);self.assertEqual(19,m['write']['S_valid']);self.assertFalse(m['write']['inputMet'])
    def test_last_valid_timestamp_is_before_exact_integer_deadline(self):
        c=self.case(3,20);rows=[]
        for i in range(60):
            offset=i*N//3;row=self.row(i);row['scheduledOffsetNanos']=offset;row['sentNanos']=offset;row['completedNanos']=offset+1000;rows.append(row)
        # 마지막 허용 시각 직전은 같은 예정 cohort; actual dispatch는 별도 집계
        rows[29]['sentNanos']=10*N-2;rows[29]['completedNanos']=10*N+1000
        m,p=cap.inputs(c,rows,0,20*N);self.assertFalse(p);self.assertTrue(m['write']['inputMet']);self.assertEqual(30,m['write']['windows'][0]['dispatchWindowD'])
    def test_sparse_valid_send_in_later_window_cannot_repay_earlier_input(self):
        c=self.case(0,200);c['workload'].update(wsConnections=0,schedule={'kind':'users','activeUsers':3,'writePeriodSeconds':200,'readPeriodSeconds':50})
        rows=[]
        for i in range(3):
            due=cap.offset(c['workload'],'write',i);r=self.row(i);r.update(scheduledOffsetNanos=due,sentNanos=due,completedNanos=due+1000);rows.append(r)
        rows[1].update(sentNanos=70*N,completedNanos=70*N+1000)
        m,p=cap.inputs(c,rows,0,200*N)
        self.assertFalse(p);self.assertEqual(3,m['write']['S_valid']);self.assertFalse(m['write']['inputMet'])
        self.assertEqual(1,m['write']['windows'][6]['scheduledCohortSvalid']);self.assertEqual(0,m['write']['windows'][6]['dispatchWindowD']);self.assertEqual(1,m['write']['windows'][7]['dispatchWindowD'])
    def test_zero_accepted_preserves_rejection_error_denominator(self):
        rows=[self.row(i,status='server_5xx') for i in range(20)]
        m,_=cap.inputs(self.case(),rows,0,20*N)
        self.assertEqual(0,m['write']['A']);self.assertEqual(20,m['write']['systemErrorNumerator']);self.assertEqual(1,m['write']['systemErrorRate']);self.assertEqual([],m['write']['rttNanos'])
    def test_s2_exact_sparse_schedule(self):
        w={'schedule':{'kind':'smoke-s2'},'bootstrap':False,'measurementSeconds':220}
        self.assertEqual([0,N,200*N,201*N],[cap.offset(w,'write',i) for i in range(4)])
        self.assertEqual([10*N,60*N,110*N,160*N],[cap.offset(w,'read',i) for i in range(4)])
        self.assertEqual(N,cap.late(w,'write'));self.assertEqual(4,cap.count(w,'read','measurement'))
    def test_fractional_users_schedule_does_not_round_rate(self):
        w={'schedule':{'kind':'users','activeUsers':3,'writePeriodSeconds':200,'readPeriodSeconds':50},'wsConnections':5,'bootstrap':False,'measurementSeconds':401}
        self.assertEqual(7,cap.count(w,'write','measurement'));self.assertEqual(200*N//3,cap.offset(w,'write',1));self.assertEqual(400*N,cap.offset(w,'write',6))
    def test_sqlite_rows_preserve_high_ordinal_and_duplicate_values(self):
        with tempfile.TemporaryDirectory() as d:
            rows=cap.Rows(Path(d)/'rows.sqlite')
            for i in (0,63,64,65535,129150):rows.append({'ordinal':i,'samePayload':'same'})
            self.assertEqual([0,63,64,65535,129150],[r['ordinal'] for r in rows]);self.assertEqual(5,len(rows));rows.close()

class CapContracts(unittest.TestCase):
    def test_db_uses_actual_gap_set_and_validation_cutoff(self):
        samples=[{'queryStartNanos':5*N-1,'queryEndNanos':5*N,'checkpoint':7}]
        r=cap.checkpoint_series(samples,{7:(N,N),999:(4*N,2*N),1000:(6*N,3*N)},0,10*N)
        self.assertEqual(1,r['samples'][0]['pendingCount']);self.assertEqual(3*N,r['maxBnanos']);self.assertEqual(5*N,r['maxGapNanos']);self.assertIsNone(r['trendMet'])
    def test_db_trend_and_gap_are_separate(self):
        samples=[{'queryStartNanos':i*N-100,'queryEndNanos':i*N,'checkpoint':0} for i in range(1,61)]
        r=cap.checkpoint_series(samples,{999:(N,N)},0,61*N)
        self.assertFalse(r['trendMet']);self.assertEqual(N,r['maxGapNanos']);self.assertGreaterEqual(r['firstCount'],10);self.assertGreaterEqual(r['lastCount'],10)
        gap=cap.checkpoint_series(samples[:2]+samples[6:],{},0,61*N);self.assertTrue(gap['trendMet']);self.assertEqual(5*N,gap['maxGapNanos'])
    def test_selection_never_promotes_input_limit_or_two_passes(self):
        h=[dict(closed=True,verdict='PASS',value=105,sameConditions=True,seconds=120)]
        self.assertTrue(cap.selection_gate('continue-ascent',h,250));self.assertFalse(cap.selection_gate('confirm-20min',h*2,105));self.assertFalse(cap.selection_gate('confirm-20min',h*3,105))
        limited=[dict(closed=False,verdict='INPUT_LIMIT',value=250)]
        self.assertFalse(cap.selection_gate('input-descent',limited,200));limited[0]['closed']=True;self.assertTrue(cap.selection_gate('input-descent',limited,200));self.assertFalse(cap.selection_gate('continue-ascent',limited,500))
        ws=[dict(closed=True,verdict='PASS',value=100,sameConditions=True),dict(closed=True,verdict='PERFORMANCE_MISS',value=200)]
        self.assertTrue(cap.selection_gate('ws-first-miss-fallback',ws,100));ws[0]['sameConditions']=False;self.assertFalse(cap.selection_gate('ws-first-miss-fallback',ws,100))
    def test_formal_initial_get_has_ten_ms_deadline(self):
        w={'schedule':{'kind':'users','activeUsers':3,'writePeriodSeconds':200,'readPeriodSeconds':50},'wsConnections':5,'bootstrap':False,'measurementSeconds':401}
        self.assertEqual(5,cap.count(w,'read','initial-read'));self.assertEqual(40_000_000,cap.offset(w,'read',4,'initial-read'));self.assertEqual(10_000_000,cap.late(w,'read','initial-read'))
    def test_live_deadline_expires_but_historical_read_is_explicit(self):
        import time
        try:
            with self.assertRaises(ValueError):cap.set_deadline(int(time.time()*1000)-1)
        finally:cap.set_deadline(None)

    def test_twenty_minutes_requires_three_distinct_bound_results(self):
        import copy,hashlib
        digest=lambda s:hashlib.sha256(s.encode()).hexdigest()
        condition=digest('same frozen performance conditions')
        h=[dict(closed=True,verdict='PASS',value=105,seconds=120,sessionId='s'+str(i),runId='r'+str(i),
                evidenceVerified=True,conditionsHash=condition,planHash=digest('plan'+str(i)),
                analysisHash=digest('analysis'+str(i)),closureHash=digest('closure'+str(i))) for i in range(3)]
        self.assertTrue(cap.selection_gate('confirm-20min',h,105,condition))
        self.assertFalse(cap.selection_gate('confirm-20min',h[:2],105,condition))
        self.assertFalse(cap.selection_gate('confirm-20min',[copy.deepcopy(h[0]) for _ in range(3)],105,condition))
        for field in ('sessionId','runId','analysisHash','closureHash'):
            changed=copy.deepcopy(h);changed[2][field]=changed[0][field]
            self.assertFalse(cap.selection_gate('confirm-20min',changed,105,condition),field)
        for field,value in [('conditionsHash',digest('different')),('closed',False),('evidenceVerified',False),('planHash',''),('verdict','PERFORMANCE_MISS')]:
            changed=copy.deepcopy(h);changed[2][field]=value
            self.assertFalse(cap.selection_gate('confirm-20min',changed,105,condition),field)

    def test_cap3_read_only_warmup_and_fixed_seeded_viewport(self):
        w=dict(schedule=dict(kind='users',activeUsers=2,writePeriodSeconds=200,readPeriodSeconds=60),
               wsConnections=5,seed=15,bootstrap=False,warmupSeconds=30,measurementSeconds=1200)
        self.assertEqual(0,cap.count(w,'write','warmup'))
        self.assertEqual(3,cap.count(w,'read','warmup')) # offsets 0,12,24; 36 is outside.
        self.assertEqual(100,cap.count(w,'read','measurement'))
        self.assertEqual([0,12*N,24*N],[cap.offset(w,'read',i,'warmup') for i in range(3)])
        self.assertEqual((15*256,0),cap.read_pixel(w,'initial-read',0))
        for user in range(5):
            self.assertEqual(cap.read_pixel(w,'initial-read',user),cap.read_pixel(w,'measurement',user+95))
        self.assertEqual(cap.read_pixel(w,'initial-read',2),cap.read_pixel(w,'warmup',2))

if __name__=='__main__':unittest.main()
