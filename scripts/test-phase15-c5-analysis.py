"""대표 부하·성능 목표·후속 채택의 분리와 사전 matrix 경계 검증."""
from pathlib import Path
import importlib.util
import unittest
import copy

spec=importlib.util.spec_from_file_location('c5',Path(__file__).with_name('report-phase15-c5.py'))
c5=importlib.util.module_from_spec(spec);spec.loader.exec_module(c5)

class C5LoadTest(unittest.TestCase):
    def fixture(self,rate=105):
        entries=[dict(id=f'{mode}{repeat}',mode=mode,role='target') for repeat in range(3) for mode in ('single','group')]
        rows=[dict(matrixEntry=e['id'],writeMode=e['mode'],measurement=dict(sent=rate*120,acceptedPerSecond=1)) for e in entries]
        return rows,entries

    def test_68_per_second_is_hold_despite_zero_errors_improvement_and_clean_recovery(self):
        rows,entries=self.fixture(68)
        gate=c5.load_gate(rows,entries,120)
        self.assertEqual('INSUFFICIENT_GENERATED_LOAD',gate['status'])
        pairs=[dict(p95Improved=True,p99Improved=True,forcePerEventReduced=True)]*3
        self.assertEqual('HOLD',c5.adoption_evidence(gate,pairs,True))

    def test_each_mode_and_repetition_requires_100_boundary_not_average_or_accepted(self):
        rows,entries=self.fixture(100)
        self.assertEqual('PASS',c5.load_gate(rows,entries,120)['status'])
        for i in range(len(rows)):
            changed=copy.deepcopy(rows);changed[i]['measurement']['sent']-=1
            self.assertEqual('INSUFFICIENT_GENERATED_LOAD',c5.load_gate(changed,entries,120)['status'])

    def test_adequate_load_with_p95_target_miss_can_be_eligible_but_safety_is_required(self):
        rows,entries=self.fixture();rows[0]['measurement']['p95Millis']=180
        gate=c5.load_gate(rows,entries,120)
        pairs=[dict(p95Improved=True,p99Improved=True,forcePerEventReduced=True)]*3
        self.assertEqual('EFFECT_EVIDENCE_ELIGIBLE_FINAL_GATES_REQUIRED',c5.adoption_evidence(gate,pairs,True))
        self.assertEqual(180,rows[0]['measurement']['p95Millis'])
        self.assertEqual('HOLD',c5.adoption_evidence(gate,pairs,False))

    def test_smoke_low_off_are_not_targets_and_empty_target_requires_reused_evidence(self):
        entries=[dict(id='smoke',mode='single',role='smoke'),dict(id='low',mode='group',role='probe'),dict(id='off',mode='group',role='off')]
        rows=[dict(matrixEntry=e['id'],writeMode=e['mode'],measurement=dict(sent=120)) for e in entries]
        gate=c5.load_gate(rows,entries,120)
        self.assertEqual('REUSED_REPRESENTATIVE_EVIDENCE_REQUIRED',gate['status'])
        self.assertTrue(all(r['loadAdequate'] is None for r in gate['rows']))

    def test_exact_matrix_rejects_missing_duplicate_order_and_wrong_mode(self):
        rows,entries=self.fixture()
        for bad in (rows[:-1],rows+[rows[0]],rows[::-1]):
            with self.assertRaises(ValueError):c5.load_gate(bad,entries,120)
        rows[0]['writeMode']='group'
        with self.assertRaises(ValueError):c5.load_gate(rows,entries,120)

if __name__=='__main__':unittest.main()
