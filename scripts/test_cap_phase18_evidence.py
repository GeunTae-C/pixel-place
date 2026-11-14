"""합성 initial/recovery receipt를 정식 analyzer에 연결. 실제 JVM 실행 주장 없음."""
import json, unittest
from pathlib import Path
import test_analyze_phase18 as legacy
a=legacy.a;N=legacy.N

class CapEvidence(unittest.TestCase):
    def test_formal_analyzer_checks_warmup_and_fixed_read_viewport(self):
        f=legacy.ResultsTest();f.setUp()
        try:
            f.case['workload']['readRate']=1
            row=f.row(0,kind='read',x=15*256,y=0)
            w=f.case['workload'];w.update(writeRate=0,readRate=0,seed=15,wsConnections=5,warmupSeconds=30,measurementSeconds=1200,
                schedule=dict(kind='users',activeUsers=2,writePeriodSeconds=200,readPeriodSeconds=60))
            counts=a.planned(f.case);self.assertEqual(3,counts['read/warmup']);self.assertEqual(0,counts['write/warmup'])
            a.validate_row(row,f.case,'',f.producer,counts)
            row['x']+=256
            with self.assertRaisesRegex(a.Invalid,'fixed user viewport'):a.validate_row(row,f.case,'',f.producer,counts)
            w['warmupSeconds']=0
            with self.assertRaisesRegex(a.Invalid,'read warmup/period'):a.planned(f.case)
        finally:f.tearDown()

    def run_case(self,status,unexpected=False):
        f=legacy.ResultsTest();f.setUp()
        try:
            f.plan.update(schemaVersion=3,phase='CAP',stage='CAP-1');f.plan['ownership']['temp']=str(f.root)
            f.case.update(role='cap-target');f.case['workload'].update(writeRate=1,measurementSeconds=60,inputWindowSeconds=10,inputTolerancePpm=1000,schedule={'kind':'fixed-rate'})
            f.producer['anchors']['measurement']['endNanos']=70*N
            f.producer.update(wsOpenedNanos=[],wsClosedNanos=[],wsCallbacksDrained=True,ws={'duplicates':[],'missing':[],'extra':[]})
            rows=[f.row(i,userOrdinal=i,userAttempt=1,x=0,y=0,color=i%16) for i in range(60)]
            rows[20].update(status='server_5xx',httpStatus=503,eventSeq=None,tileVersion=None,outcome='REJECTED' if status=='busy' else 'UNKNOWN',reason='cap_write_busy' if status=='busy' else 'write_unknown',resolution='not_applicable' if status=='busy' else 'unresolved')
            def extra():
                ph=f.producer['planHash'];clock=f.producer['clockId'];run=f.run
                (run/'cap-http.jsonl').write_text(''.join(json.dumps(dict(planHash=ph,clockId=clock,requestId=r['requestId'],phase='measurement',sentNanos=r['sentNanos'],bodyNanos=r['completedNanos'],validatedNanos=r['completedNanos']+1,eventSeq=r['eventSeq'],userOrdinal=r['userOrdinal'],userAttempt=1,outcome=r['outcome']))+'\n' for r in rows))
                (run/'cap-db.jsonl').write_text(''.join(json.dumps(dict(planHash=ph,clockId=clock,queryStartNanos=i*N,queryEndNanos=i*N+1000,checkpoint=10000))+'\n' for i in range(10,70)))
                (run/'cap-ws-times.bin').write_bytes(b'');(run/'ws-canonical.jsonl').write_text('')
                f.put('cap-observer.json',dict(planHash=ph,clockId=clock,connectionClosed=True,queries=60,queryNanos=60000,hashes={n:a.sha(run/n) for n in ('cap-http.jsonl','cap-db.jsonl')}))
                hashes={n:a.sha(run/n) for n in ('cap-http.jsonl','cap-db.jsonl','cap-observer.json','cap-ws-times.bin')}
                for folder in (run,run.parent/'recovery'):
                    manifest=a.read(folder/'manifest.json');manifest['capHashes']=hashes;f.put('manifest.json',manifest,folder);f.rehash(folder)
                if unexpected:
                    value=a.read(run/'consistency.json');value['accepted']+=1;f.put('consistency.json',value);f.rehash(run)
            return f.analyze(rows,extra=extra)
        finally:f.tearDown()
    def test_busy_continues_full_analysis_but_consumes_both_success_and_error_budgets(self):
        r=self.run_case('busy');self.assertEqual('VALID_NO_TARGET',r['status']);self.assertEqual('PERFORMANCE_MISS',r['cap']['verdict']);self.assertTrue(r['integrity']['recoveryComplete'])
        m=r['cap']['input']['write'];self.assertEqual(60,m['P']);self.assertEqual(60,m['S_all']);self.assertEqual(59,m['A']);self.assertEqual(1,m['systemErrorNumerator'])
    def test_unknown_and_unexpected_persistent_count_never_become_performance_miss(self):
        self.assertEqual('INVALID_OR_STOPPED',self.run_case('unknown')['cap']['verdict'])
        self.assertEqual('INVALID_OR_STOPPED',self.run_case('busy',True)['cap']['verdict'])

if __name__=='__main__':unittest.main()
