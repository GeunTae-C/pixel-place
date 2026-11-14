"""고정 진단 조합·슬롯·총 예약이 정식 CAP 조건으로 확장되지 않는 경계 검증."""
import copy,unittest
from unittest.mock import patch
import phase18_cap1 as cap
import phase18_cap1_comparison as ab

class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.now=2_000_000_000_000
        self.budget=dict(instructionSha256=ab.INSTRUCTION,appSlots=8,mandatorySlots=6,additionalPairMaximum=1,
            A_copyMillis=7200000,A_recoveryMillis=3600000,selectionMillis=7200000,totalMillis=60510000,
            copyOriginalExpires=self.now+7200000)
        self.contract=dict(taskBudget={"path":"frozen","sha256":"1"*64})
        self.reference={"path":"comparison","sha256":"2"*64}
    def policy(self,variant="A",pair=(105,120),slot=1):
        return dict(role=ab.ROLE,cap1Profile=cap.profile(*pair,"cap1-causal-memory-v2",ab.ROLE),
            comparison=dict(variant=variant,slot=slot,contract=self.reference,additionalPair=None))
    def attempt(self,p,n):
        return (dict(SessionId="issued-"+str(n),IssuedEpochMillis=self.now,
            ExpiresEpochMillis=self.now+p["cap1Profile"]["totalSeconds"]*1000),p)
    def check(self,p,prior=(),now=None):
        with patch.object(ab,"bound",return_value=self.budget):
            ab.slots(p,self.contract,list(prior),self.now if now is None else now)
    def test_exact_profiles_accept_and_share_existing_resource_contract(self):
        for pair in ab.PAIRS:
            p=cap.profile(*pair,"cap1-causal-memory-v2",ab.ROLE);cap.validate_profile(p)
            target=cap.profile(*pair,"cap1-causal-memory-v2");target["role"]=ab.ROLE
            self.assertEqual(target,p)
    def test_outside_combinations_and_unknown_roles_reject(self):
        for pair in [(500,120),(500,1200),(250,60),(104,120),(1000,60),(105,60),(105,121)]:
            with self.subTest(pair=pair),self.assertRaises(ValueError):cap.profile(*pair,"cap1-causal-memory-v2",ab.ROLE)
        with self.assertRaises(ValueError):cap.profile(105,120,"cap1-causal-memory-v2","diagnostic")
    def test_first_baseline_and_next_long_accept(self):
        a=self.policy();self.check(a)
        self.check(self.policy(pair=(105,1200),slot=2),[self.attempt(a,1)])
    def test_no_long_or_changed_production_before_required_baseline(self):
        for p in [self.policy(pair=(105,1200)),self.policy("B")]:
            with self.assertRaises(ValueError):self.check(p)
    def test_duplicate_unreasoned_slot_and_wrong_sequence_reject(self):
        a=self.policy()
        for p in [self.policy(slot=1),self.policy(slot=2)]:
            with self.assertRaises(ValueError):self.check(p,[self.attempt(a,1)])
    def six(self):
        values=[self.policy(v,pair,i+1) for i,(v,pair) in enumerate((v,pair) for v in ("A","B") for pair in ab.PAIRS)]
        return [self.attempt(p,i+1) for i,p in enumerate(values)]
    def test_one_additional_pair_is_finite_and_reasoned(self):
        prior=self.six();a=self.policy(pair=(105,1200),slot=7)
        a["comparison"]["additionalPair"]=dict(pair=[105,1200],reason="Explicit host interference leaves the long comparison inconclusive.")
        self.check(a,prior);prior.append(self.attempt(a,7))
        b=self.policy("B",(105,1200),8);b["comparison"]["additionalPair"]=a["comparison"]["additionalPair"];self.check(b,prior)
        with self.assertRaises(ValueError):self.check(self.policy("B",(500,60),8),prior)
        prior.append(self.attempt(b,8))
        with self.assertRaises(ValueError):self.check(self.policy(slot=9),prior)
    def test_changed_original_expiry_and_reused_session_reject(self):
        a=self.policy();old=self.attempt(a,1);old[0]["ExpiresEpochMillis"]+=1
        with self.assertRaises(ValueError):self.check(self.policy(pair=(105,1200),slot=2),[old])
        old=self.attempt(a,1)
        with self.assertRaises(ValueError):self.check(self.policy(pair=(500,60),slot=3),[old,old])
    def test_missing_whole_time_and_modified_budget_reject(self):
        with self.assertRaises(ValueError):self.check(self.policy(),now=self.now+60_000_000)
        self.budget["appSlots"]=9
        with self.assertRaises(ValueError):self.check(self.policy())
    def test_diagnostic_analysis_accepts_only_exact_profiles_and_never_selects_candidate(self):
        from test_analyze_phase18 import a
        import phase18_cap
        p=dict(schemaVersion=4,phase="CAP",stage="CAP-1",bounds=dict(samples=50000))
        case=dict(role=ab.ROLE,workload=dict(bootstrap=True,writeRate=105,readRate=0,wsConnections=100,
            warmupSeconds=30,measurementSeconds=120,schedule=dict(kind="fixed-rate")))
        self.assertEqual(12600,a.planned(case,p)["write/measurement"])
        case["workload"]["writeRate"]=500
        with self.assertRaises(ValueError):a.planned(case,p)
        history=[dict(role=ab.ROLE,closed=True,verdict="PASS",value=105)]
        self.assertFalse(phase18_cap.selection_gate("continue-ascent",history,250))
        history[0]["role"]="cap-target"
        self.assertTrue(phase18_cap.selection_gate("continue-ascent",history,250))
    def test_added_or_removed_production_file_cannot_hide_outside_core_change(self):
        original={"src/main/a.java":"a","src/main/b.java":"b"}
        self.assertEqual(set(),ab.production_changes(dict(original),original))
        source=dict(original);source["src/main/a.java"]="changed"
        self.assertEqual({"src/main/a.java"},ab.production_changes(source,original))
        source["src/main/new.java"]="new"
        with self.assertRaisesRegex(ValueError,"production file set"):ab.production_changes(source,original)
        source=dict(original);del source["src/main/b.java"]
        with self.assertRaisesRegex(ValueError,"production file set"):ab.production_changes(source,original)

    def test_formal_cap_profile_remains_distinct(self):
        p=self.policy();p["role"]="cap-target"
        with self.assertRaises(ValueError):self.check(p)
        target=cap.profile(500,120,"cap1-causal-memory-v2")
        self.assertEqual("cap-target",target["role"]);cap.validate_profile(target)

if __name__=="__main__":unittest.main()
