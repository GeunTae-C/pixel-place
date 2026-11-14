"""CAP-1 Start 물리 예약과 과거 분석 사본의 좁은 진입 경계. 서비스 없음."""
import copy,hashlib,json,time,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import phase18_cap1 as c
import phase18_cap_budget as b

class Cap1StorageStart(unittest.TestCase):
    def check(self,change=None):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);ledger=root/'control';ledger.mkdir();out=root/'issued';out.mkdir();repo=root/'repo';repo.mkdir()
            (repo/'current.py').write_text('current source')
            def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
            def put(p,v):p.write_text(json.dumps(v),encoding='utf-8')
            profile=c.profile(250,1200);now=int(time.time()*1000)-1000;sid='cap1-fixture-storage'
            reserve=dict(additionalPeak=dict(evidence=profile['usage']['evidenceBytes'],mysql=profile['storage']['usage']['mysqlBytes'],wal=profile['storage']['usage']['walBytes'],work=profile['usage']['workspaceBytes']))
            reserve_path=root/'reserve.json';put(reserve_path,reserve)
            policy=dict(schemaVersion=1,stage='CAP-1',role='cap-target',attempt=1,cap1Profile=profile,budgetReservation=dict(path=str(reserve_path),sha256=sha(reserve_path)),sourceBinding=[dict(path='current.py',sha256=sha(repo/'current.py'))])
            path=root/'policy.json';put(path,policy);registration=dict(path=str(path),sha256=sha(path))
            issued=dict(SessionId=sid,RunId='run-fixture',Stage='CAP-1',Role='cap-target',Policy=registration,issuedEpochMillis=now,expiresEpochMillis=now+profile['totalSeconds']*1000)
            attempt=dict(SessionId=sid,RunId='run-fixture',Stage='CAP-1',Policy=registration,IssuedEpochMillis=now,ExpiresEpochMillis=issued['expiresEpochMillis'])
            state=dict(volumeLayout=b.C_GRAPH_LAYOUT,gateMet=True,transition=dict(reservation=copy.deepcopy(reserve),closed=False),trialBudget=dict(violation=None),nextReservation=dict(C=64*1024**2,F=5*b.GIB+64*1024**2))
            if change:change(policy,registration,issued,attempt,state,repo)
            put(path,policy)
            if registration['sha256']!='0'*64:registration['sha256']=sha(path)
            put(ledger/'cap1-registration.json',registration);put(out/'cap-issued.json',issued);put(ledger/'cap1-attempt-1.json',attempt)
            # Files and policy hashes are real; only current isolated ledger/evidence lookup is injected.
            with patch.object(c,'control_path',lambda x:Path(x)),patch('pixel_place_paths.evidence_path',lambda x:out),patch.object(b,'load_chain',lambda x:(state,'a'*64)):
                return c.verify_storage_start(path,repo,ledger,sid)
    def test_current_issued_reserved_profile_uses_u_not_l(self):
        got=self.check();p=c.profile(250,1200)
        self.assertEqual(p['storage']['usage']['mysqlBytes'],got['MysqlUsageBytes'])
        self.assertEqual(p['storage']['limits']['mysqlBytes'],got['MysqlLimitBytes'])
        self.assertGreater(got['MysqlLimitBytes'],2*got['MysqlUsageBytes'])
        self.assertEqual(5*b.GIB+64*1024**2,got['NextReservationBytes'])
    def test_wrong_registration_issuance_expiry_source_or_reservation_rejected(self):
        changes=[lambda p,r,i,a,s,d:r.update(sha256='0'*64),lambda p,r,i,a,s,d:i.update(Role='cap-smoke-s2'),
            lambda p,r,i,a,s,d:a.update(SessionId='someone-else'),lambda p,r,i,a,s,d:a.update(ExpiresEpochMillis=a['ExpiresEpochMillis']+1),
            lambda p,r,i,a,s,d:s.update(volumeLayout='f-output-v1'),lambda p,r,i,a,s,d:s['transition'].update(closed=True),
            lambda p,r,i,a,s,d:s['trialBudget'].update(violation='hard limit'),lambda p,r,i,a,s,d:s['transition']['reservation']['additionalPeak'].update(mysql=1),
            lambda p,r,i,a,s,d:(d/'current.py').write_text('changed'),lambda p,r,i,a,s,d:p.update(stage='CAP-0')]
        for change in changes:
            with self.subTest(change=change),self.assertRaises(ValueError):self.check(change)
    def test_historical_snapshots_cannot_replace_the_executed_hash(self):
        repo=Path('C:/Users/cgt/Desktop/Java/pixel-place')
        digests=['dc876ab132440362e23f4aa7361c8e494212703e0dbb61314ff2b98e4c1e7cab','d3df669272905747d290da6fe662e5d19635a10b6e811542c1faccc4c1403ea2','32d75ec22ca5edb4e4ad79b64ff27606f737eeaf638f154fcf83db72a5d1ced9']
        keys=('analyzerSource','entrySource','helperSource');corr={k:dict(path=str(repo/('scripts/phase18_k6.py' if n==0 else k)),sha256=digests[n]) for n,k in enumerate(keys)}
        snapshots={k:dict(original=corr[k],preserved=dict(path='F:/saved/'+k,sha256=digests[n])) for n,k in enumerate(keys)}
        known={v['preserved']['path']:v['preserved']['sha256'] for v in snapshots.values()}
        c.require_executed_analysis_sources(corr,dict(executedSources=snapshots),repo,lambda p:known.get(p,'changed'))
        for key in keys:
            altered=copy.deepcopy(snapshots);altered[key]['preserved']['sha256']='0'*64
            with self.assertRaises(ValueError):c.require_executed_analysis_sources(corr,dict(executedSources=altered),repo,lambda p:known.get(p,'changed'))
        with self.assertRaises(ValueError):c.require_executed_analysis_sources(corr,{},repo,lambda p:'current-is-different')
    def test_bounded_transport_cannot_authorize_higher_or_unspecified_rate(self):
        scope={'transportScope':{'kind':'cap1-f-ws-tested-range-v1','maximumWriteRate':250}}
        for rate in (None,104,251,500,True):
            with self.subTest(rate=rate),self.assertRaisesRegex(ValueError,'tested WS transport range'):
                c.require_ws_bounded_scope(scope,rate,Path('.'),lambda x:self.fail('must reject before evidence'),lambda p:'')
        scope['transportScope']['maximumWriteRate']=500
        with self.assertRaisesRegex(ValueError,'tested WS transport range'):
            c.require_ws_bounded_scope(scope,105,Path('.'),lambda x:None,lambda p:'')

    def test_new_storage_series_uses_current_source_only(self):
        current=[dict(path='current',sha256='a'*64)]
        self.assertIs(current,c.offline_conditions_source(dict(seriesLayout=b.C_GRAPH_LAYOUT,sourceBinding=current,offlineAnalysisRepair={}),Path('.'),lambda r:None,lambda p:None))

if __name__=='__main__':unittest.main()
