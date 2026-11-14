"""보존된 W250 진단의 직접 선택 검증. 원본 증거를 변경하거나 새 부하를 시작하지 않음."""
import copy,json,unittest
from pathlib import Path
import phase18_cap1 as c

class WsResume(unittest.TestCase):
    def facts(self):
        r=Path(__file__).resolve().parent.parent/'build/agent-runs'
        old=r/'phase18-cap1-w250-20261013-200125';d=r/'cap1-ws-diagnostic-20261013-03'
        paths=[old/'cap-closure.json',old/'analysis.json',d/'recovery-diagnostic.json',d/'database-before-recovery.json',d/'database-after-recovery.json',d/'original-preservation.json',d/'diagnostic-owner-closure.json',d/'mysql-stop.json',d/'budget-after.json']
        return [json.loads(c.historical(p).read_text(encoding='utf-8-sig')) for p in paths]
    def test_complete_specific_diagnosis_preserves_original_unknown(self):
        facts=self.facts();c.require_ws_diagnostic_facts(*facts)
        self.assertEqual('UNRESOLVED',facts[2]['historicalHttpResponse'])
        self.assertEqual('INVALID_OR_STOPPED',facts[1]['cap']['verdict'])
    def test_data_recovery_copy_and_shutdown_uncertainty_block_resume(self):
        changes=[(2,lambda x:x.update(unexplainedEffects=1)),(2,lambda x:x.update(normalContextClose=False)),
            (2,lambda x:x.update(historicalHttpResponse='ACCEPTED')),(2,lambda x:x['requests'][0].update(state='unresolved')),
            (2,lambda x:x['requests'][0]['wal'].update(userId=999)),(3,lambda x:x.update(tileBytesAndVersions=False)),
            (4,lambda x:x.update(checkpoint=61)),(5,lambda x:x.update(allSourceHashesUnchanged=False)),
            (6,lambda x:x['Workers'][0].update(Joined=False)),(7,lambda x:x.update(NormalShutdown=False)),
            (8,lambda x:x['transition'].update(closed=False))]
        for index,change in changes:
            with self.subTest(index=index,change=change):
                facts=self.facts();change(facts[index])
                with self.assertRaises(ValueError):c.require_ws_diagnostic_facts(*facts)
    def test_valid_miss_and_original_owner_failure_cannot_use_exception(self):
        for status in ('PASS','PERFORMANCE_MISS','INPUT_LIMIT','TOOL_LIMIT'):
            facts=self.facts();facts[0]['Status']=status
            with self.assertRaises(ValueError):c.require_ws_diagnostic_facts(*facts)
        facts=self.facts();facts[0]['OwnedResourcesClosed']=False
        with self.assertRaises(ValueError):c.require_ws_diagnostic_facts(*facts)
    def test_missing_required_evidence_never_defaults_to_resolved(self):
        for index,field in [(2,'requests'),(2,'consistency'),(5,'allOriginalWalHashesUnchanged'),(6,'Workers'),(8,'transition')]:
            facts=self.facts();del facts[index][field]
            with self.assertRaises((KeyError,ValueError)):c.require_ws_diagnostic_facts(*facts)

    def test_new_w500_diagnosis_cannot_be_used_as_w250_or_another_run(self):
        r=Path(__file__).resolve().parent.parent/'build/agent-runs'
        old=r/'phase18-cap1-w500-20261014-011605';d=r/'cap1-ws-diagnostic-20261014-01'
        paths=[old/'cap-closure.json',old/'analysis.json',d/'recovery-diagnostic.json',d/'database-before-recovery.json',d/'database-after-recovery.json',d/'original-preservation.json',d/'diagnostic-owner-closure.json',d/'mysql-stop.json',d/'budget-after.json']
        facts=[json.loads(c.historical(p).read_text(encoding='utf-8-sig')) for p in paths]
        c.require_ws_diagnostic_facts(*facts,session=old.name)
        with self.assertRaises(ValueError):c.require_ws_diagnostic_facts(*facts)
        with self.assertRaises(ValueError):c.require_ws_diagnostic_facts(*facts,session='unverified-new-failure')
        for index,change in [(2,lambda x:x.update(confirmedAccepted=12651)),(2,lambda x:x.update(unexplainedEffects=1)),(4,lambda x:x.update(tileBytesAndVersions=False)),(6,lambda x:x.update(Released=False)),(8,lambda x:x['transition'].update(closed=False))]:
            changed=copy.deepcopy(facts);change(changed[index])
            with self.assertRaises(ValueError):c.require_ws_diagnostic_facts(*changed,session=old.name)

if __name__=='__main__':unittest.main()
