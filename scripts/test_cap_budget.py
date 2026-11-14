import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import phase18_cap_budget as b

def seed():
    return dict(CLimitBytes=20*b.GIB,FLimitBytes=100*b.GIB,MinimumFreeBytes=10*b.GIB,
        CObservedEvidenceGrowthBytes=511214116,CGraphWholeRootConservativeBytes=230744783,
        CSourceAndFinalReportReservationBytes=16777216,CConservativeChargedBytes=758736115,
        FOwnedSessionBytes=804028650,FSharedCacheNetGrowthBytes=1344514,FMySqlNetGrowthBytes=-9514,
        FMySqlCumulativeObservedPeakGrowthBytes=26805366,FPhase18WalGrowthBytes=0,
        FTransientBuildTestAllowanceBytes=1073741824,FConservativeChargedBytes=1905920354)

class StorageExpansion(unittest.TestCase):
    def test_exact_half_repeats_and_invalid_arithmetic(self):
        for usage,expected in [(0,100),(49,100),(50,500),(51,500),(400,2500)]:
            self.assertEqual(expected,b.expand_limit(100,usage))
        for limit,usage in [(0,0),(-1,1),(100,-1),(True,1),(100,float('nan')),(100,float('inf')),(100,None),(2**63-1,2**62),(2**63-1,2**62-1)]:
            # 마지막 값은 50% 직전이므로 그대로 허용
            if usage==2**62-1:self.assertEqual(limit,b.expand_limit(limit,usage))
            else:
                with self.assertRaises(ValueError):b.expand_limit(limit,usage)
        with self.assertRaisesRegex(ValueError,'overflow'):b.expand_limit(2**63//3,2**62-1)

    def test_expanded_trial_and_cumulative_keep_consumption_and_allowance(self):
        prior=b.seed_state(seed(),'a'*64);observed=dict(prior['observed']);free={'C':40*b.GIB,'F':400*b.GIB}
        # 기존 한도에 근접한 과거 소비도 낮추지 않고 새 누적 정책 계산
        prior['charged'].update(C=18*b.GIB,F=90*b.GIB)
        reservation=smoke_reservation();reservation['id']='expanded-isolated'
        reservation['additionalPeak']['evidence']=500*1024**2
        reservation['storagePolicy']=b.storage_expansion(prior,observed,'b'*64,reservation)
        reservation['registeredTrialBytes']=reservation['storagePolicy']['limits']['trial']
        self.assertEqual({'C':100*b.GIB,'F':500*b.GIB,'trial':40*b.GIB},reservation['storagePolicy']['limits'])
        first=b.reserve(prior,observed,free,'b'*64,reservation);self.assertTrue(first['gateMet'])
        prepared=dict(observed,evidence=observed['evidence']+1024**2)
        fresh=b.reserve(prior,prepared,free,'b'*64,reservation)
        self.assertTrue(fresh['gateMet']);self.assertEqual(first['charged']['C']+1024**2,fresh['charged']['C'])
        half=dict(observed,evidence=observed['evidence']+100*b.GIB)
        with self.assertRaisesRegex(ValueError,'preparation growth'):b.reserve(prior,half,free,'b'*64,reservation)
        with self.assertRaisesRegex(ValueError,'predecessor/usage'):b.reserve(prior,observed,free,'0'*64,reservation)
        same=b.reserve(first,observed,free,'c'*64,reservation);self.assertEqual(first['charged'],same['charged'])
        low=dict(free,C=10*b.GIB+same['remainingPeak']['C']+same['nextReservation']['C']-1)
        self.assertFalse(b.reserve(same,observed,low,'d'*64,reservation)['gateMet'])
        grew=dict(observed,cache=observed['cache']+41*b.GIB)
        bad=b.reserve(same,grew,free,'d'*64,reservation)
        closed=b.reserve(bad,observed,free,'e'*64,reservation,True)
        self.assertFalse(closed['gateMet']);self.assertEqual(bad['trialBudget']['violation'],closed['trialBudget']['violation'])
        self.assertEqual(bad['charged'],closed['charged'])
        tampered=copy.deepcopy(reservation);tampered['storagePolicy']['usage']['C']-=1
        with self.assertRaisesRegex(ValueError,'predecessor/usage'):b.reserve(prior,observed,free,'b'*64,tampered)

    def test_expanded_candidate_replays_without_changing_historical_records(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
            b.initialize_chain(root,Path(folder)/'anchor.json')
            prior,h=b.load_chain(root);first=b.advance(prior,prior['observed'],dict(C=40*b.GIB,F=400*b.GIB),h)
            original,_=b.append_record(root,first);original_bytes=original.read_bytes()
            prior,h=b.load_chain(root);reservation=smoke_reservation();reservation['id']='expanded-chain'
            reservation['storagePolicy']=b.storage_expansion(prior,prior['observed'],h,reservation)
            reservation['registeredTrialBytes']=reservation['storagePolicy']['limits']['trial']
            prepared=dict(prior['observed'],evidence=prior['observed']['evidence']+1024)
            value=b.reserve(prior,prepared,first['free'],h,reservation)
            b.append_record(root,value);loaded,_=b.load_chain(root)
            self.assertEqual(value,loaded);self.assertEqual(original_bytes,original.read_bytes())

class CapBudget(unittest.TestCase):
    def test_final_charges_and_reservations_survive_unchanged_observation(self):
        s=b.seed_state(seed(),'a'*64);r=b.advance(s,s['observed'],{'C':40*b.GIB,'F':400*b.GIB},'a'*64)
        self.assertEqual({'C':758736115,'F':1905920354},r['charged']);self.assertTrue(r['gateMet'])
        self.assertEqual(r['charged'],b.advance(r,r['observed'],r['free'],'b'*64)['charged'])
    def test_mysql_shrink_does_not_refund_past_peak_or_allowance(self):
        s=b.seed_state(seed(),'a'*64);o=dict(s['observed'],mysql=-100000)
        r=b.advance(s,o,{'C':40*b.GIB,'F':400*b.GIB},'a'*64)
        self.assertEqual(s['charged'],r['charged'])
        o['mysql']+=5000;r2=b.advance(r,o,r['free'],'b'*64)
        self.assertEqual(1905920354+5000,r2['charged']['F'])
    def test_disjoint_groups_growth_counted_once(self):
        s=b.seed_state(seed(),'a'*64);o=dict(s['observed']);o['graph']+=7;o['wal']+=11
        r=b.advance(s,o,{'C':40*b.GIB,'F':400*b.GIB},'a'*64)
        self.assertEqual(758736122,r['charged']['C']);self.assertEqual(1905920365,r['charged']['F'])
    def test_pre_reservation_and_free_space_reject_boundary(self):
        s=b.seed_state(seed(),'a'*64);s['charged']['C']=20*b.GIB-64*1024**2
        free={'C':11*b.GIB,'F':400*b.GIB}
        self.assertTrue(b.advance(s,s['observed'],free,'a'*64)['gateMet'])
        o=dict(s['observed']);o['evidence']+=1
        self.assertFalse(b.advance(s,o,free,'a'*64)['gateMet'])
        free['F']=15*b.GIB-1;self.assertFalse(b.advance(s,s['observed'],free,'a'*64)['gateMet'])
    def test_restart_hash_chain_and_tampered_decrease(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
            b.initialize_chain(root,Path(d)/'anchor.json')
            s,h=b.load_chain(root);o=dict(s['observed']);o['evidence']+=10
            r=b.advance(s,o,{'C':40*b.GIB,'F':400*b.GIB},h)
            path,_=b.append_record(root,r)
            actual,h=b.load_chain(root);self.assertEqual(r,actual)
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),h)
            r['charged']['C']-=1;path.write_text(json.dumps(r))
            with self.assertRaisesRegex(ValueError,'chain/charge'):b.load_chain(root)
    def test_seed_reconciliation_rejects_missing_charges(self):
        bad=seed();bad['FConservativeChargedBytes']-=bad['FTransientBuildTestAllowanceBytes']
        with self.assertRaisesRegex(ValueError,'F seed'):b.seed_state(bad,'a'*64)

    def test_suffix_or_all_records_lost_never_reverts_to_seed(self):
        for loss in ('last','all','anchor','initialization','commit'):
            with self.subTest(loss=loss),tempfile.TemporaryDirectory() as d:
                root=Path(d)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
                anchor=Path(d)/'anchor.json';b.initialize_chain(root,anchor)
                for i in range(2):
                    s,h=b.load_chain(root);o=dict(s['observed']);o['evidence']+=12345
                    b.append_record(root,b.advance(s,o,{'C':40*b.GIB,'F':400*b.GIB},h))
                if loss=='last':(root/'cap-storage-ledger-000002.json').unlink()
                elif loss=='all':
                    for p in root.glob('cap-storage-ledger-*.json'):p.unlink()
                elif loss=='anchor':anchor.unlink()
                elif loss=='initialization':(root/'cap-storage-initialized.json').unlink()
                else:(root/'cap-storage-commit-000002.json').unlink()
                with self.assertRaises((ValueError,FileNotFoundError)):b.load_chain(root)
                with self.assertRaises(ValueError):b.initialize_chain(root,anchor)

    def test_each_interrupted_write_fails_closed_after_restart(self):
        for stage in ('intent','prepared','record','anchor'):
            with self.subTest(stage=stage),tempfile.TemporaryDirectory() as d:
                root=Path(d)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
                b.initialize_chain(root,Path(d)/'anchor.json');s,h=b.load_chain(root)
                r=b.advance(s,dict(s['observed'],evidence=s['observed']['evidence']+12345),{'C':40*b.GIB,'F':400*b.GIB},h)
                def crash(actual):
                    if actual==stage:raise OSError('simulated interruption')
                with self.assertRaisesRegex(OSError,'interruption'):b.append_record(root,r,crash)
                with self.assertRaises(ValueError):b.load_chain(root)

    def test_missing_marker_is_not_an_implicit_initialization(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'cap-budget.json').write_text(json.dumps(seed()))
            with self.assertRaises(FileNotFoundError):b.load_chain(root)


class Transition(unittest.TestCase):
    def reservation(self):
        return dict(schemaVersion=2,id='isolated',scope='k6-mod3',issuedEpochMillis=1,pastUncertain=['historical temporary writes unknown'],
            additionalPeak=dict(evidence=2*b.GIB,graph=0,work=b.GIB,cache=b.GIB,mysql=2*b.GIB,wal=b.GIB),
            transientAllowance=dict(C=0,F=b.GIB),registeredTrialBytes=8*b.GIB)
    def test_current_free_future_peak_and_no_historical_refund(self):
        s=b.seed_state(seed(),'a'*64);free=dict(C=40*b.GIB,F=400*b.GIB);reservation=self.reservation()
        first=b.reserve(s,s['observed'],free,'a'*64,reservation)
        self.assertEqual(s['charged']['F']+b.GIB,first['charged']['F']);self.assertTrue(first['gateMet'])
        observed=dict(s['observed']);observed['evidence']+=b.GIB
        second=b.reserve(first,observed,dict(free,C=39*b.GIB),'b'*64,reservation)
        self.assertEqual(b.GIB,second['remainingPeak']['C']);self.assertEqual(first['charged']['C']+b.GIB,second['charged']['C'])
        closed=b.reserve(second,s['observed'],free,'c'*64,reservation,True)
        self.assertEqual(second['charged'],closed['charged']);self.assertEqual(dict(C=0,F=0),closed['remainingPeak'])
        low=dict(free,C=12*b.GIB+64*1024**2-1)
        self.assertFalse(b.reserve(s,s['observed'],low,'a'*64,reservation)['gateMet'])
        bad=copy.deepcopy(reservation);bad['registeredTrialBytes']-=1
        with self.assertRaisesRegex(ValueError,'peak limit'):b.reserve(s,s['observed'],free,'a'*64,bad)
    def test_isolated_committed_legacy_to_new_chain_and_no_reversion(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
            b.initialize_chain(root,Path(d)/'anchor.json');s,h=b.load_chain(root);free=dict(C=40*b.GIB,F=400*b.GIB)
            b.append_record(root,b.advance(s,s['observed'],free,h));s,h=b.load_chain(root)
            r=b.reserve(s,s['observed'],free,h,self.reservation());b.append_record(root,r);current,digest=b.load_chain(root)
            self.assertEqual(r,current);self.assertEqual(2,current['sequence'])
            r=b.reserve(current,current['observed'],free,digest,self.reservation(),True);b.append_record(root,r);self.assertEqual(r,b.load_chain(root)[0])

def smoke_reservation():
    return dict(schemaVersion=2,id='mf01-isolated-smoke-compatible',scope='k6-mod3',issuedEpochMillis=1,
        pastUncertain=['isolated fixture: historical temporary costs unknown'],
        additionalPeak=dict(evidence=192*1024**2,graph=0,work=2*b.GIB,cache=b.GIB,mysql=2*b.GIB,wal=64*1024**2),
        transientAllowance=dict(C=0,F=b.GIB),registeredTrialBytes=8*b.GIB)


# S1 최소 예약의 합계 의미와 append/reload 계약 검증. 실제 서비스/대형 파일 생성 없음.
class S2EvidenceBudget(unittest.TestCase):
    def test_320_mib_reservation_preserves_total_allowance_and_sticky_violation(self):
        s=b.seed_state(seed(),'a'*64);free=dict(C=40*b.GIB,F=400*b.GIB)
        reservation=smoke_reservation();reservation['id']='s2-output-isolated'
        reservation['additionalPeak'].update(evidence=335544320,graph=33554432)
        reservation['transientAllowance']['C']=16777216
        first=b.reserve(s,s['observed'],free,'a'*64,reservation)
        self.assertEqual(6895435776,first['trialBudget']['requiredPeakBytes']);self.assertTrue(first['gateMet'])
        same=b.reserve(first,first['observed'],free,'b'*64,reservation)
        self.assertEqual(first['charged'],same['charged'])
        low=dict(free,C=10*b.GIB+64*1024**2+first['remainingPeak']['C']-1)
        self.assertFalse(b.reserve(first,first['observed'],low,'b'*64,reservation)['gateMet'])
        grew=dict(first['observed']);grew['cache']+=9*b.GIB
        failed=b.reserve(first,grew,free,'b'*64,reservation)
        closed=b.reserve(failed,first['observed'],free,'c'*64,reservation,True)
        self.assertFalse(closed['gateMet']);self.assertEqual(failed['trialBudget']['violation'],closed['trialBudget']['violation'])
        self.assertEqual(failed['charged'],closed['charged'])

class TrialBudget(unittest.TestCase):
    def setUp(self):
        self.seed=b.seed_state(seed(),'a'*64)
        self.free=dict(C=40*b.GIB,F=400*b.GIB)
        self.reservation=smoke_reservation()
        self.first=b.reserve(self.seed,self.seed['observed'],self.free,'a'*64,self.reservation)

    def test_total_peak_boundary_and_root_reservation_is_not_a_cap(self):
        # 최초 6.25GiB, cache 2GiB이면 7.25GiB, 2.75GiB이면 정확히 8GiB.
        for growth,expected_peak,met in (
            (0,25*b.GIB//4,True),
            (2*b.GIB,29*b.GIB//4,True),
            (11*b.GIB//4,8*b.GIB,True),
            (11*b.GIB//4+1,8*b.GIB+1,False),
            (9*b.GIB,57*b.GIB//4,False),
        ):
            with self.subTest(growth=growth):
                observed=dict(self.first['observed']);observed['cache']+=growth
                result=b.reserve(self.first,observed,self.free,'b'*64,self.reservation)
                self.assertEqual(met,result['gateMet'])
                self.assertEqual(growth,result['trialBudget']['additionalObservedBytes'])
                self.assertEqual(expected_peak,result['trialBudget']['requiredPeakBytes'])
                self.assertEqual(self.first['transition']['baseObserved'],result['transition']['baseObserved'])
                self.assertEqual(self.seed['charged']['F']+b.GIB+growth,result['charged']['F'])
                if not met:
                    self.assertEqual(dict(reason='registered_trial_limit_exceeded',firstSequence=2,
                        requiredPeakBytes=expected_peak,registeredTrialBytes=8*b.GIB),result['trialBudget']['violation'])
                print(f'MF01 boundary cache={growth} required={expected_peak} gate={result["gateMet"]}')

    def test_32_gib_actual_growth_overrun_and_registration_limit(self):
        r=copy.deepcopy(self.reservation);r['registeredTrialBytes']=32*b.GIB
        first=b.reserve(self.seed,self.seed['observed'],self.free,'a'*64,r)
        observed=dict(first['observed']);observed['cache']+=32*b.GIB+1
        result=b.reserve(first,observed,self.free,'b'*64,r)
        self.assertFalse(result['gateMet'])
        self.assertEqual(149*b.GIB//4+1,result['trialBudget']['requiredPeakBytes'])
        r['registeredTrialBytes']+=1
        with self.assertRaisesRegex(ValueError,'peak limit'):b.reserve(self.seed,self.seed['observed'],self.free,'a'*64,r)

    def test_drives_next_reservations_and_free_space_remain_independent(self):
        for drive,limit,next_bytes,remaining in (('C',20*b.GIB,64*1024**2,192*1024**2),
                                                ('F',100*b.GIB,5*b.GIB,81*b.GIB//16)):
            with self.subTest(drive=drive):
                prior=copy.deepcopy(self.first)
                prior['charged'][drive]=limit-next_bytes-remaining
                at=b.reserve(prior,prior['observed'],self.free,'b'*64,self.reservation)
                self.assertTrue(at['gateMet'])
                self.assertEqual(dict(C=64*1024**2,F=5*b.GIB),at['nextReservation'])
                prior['charged'][drive]+=1
                failed=b.reserve(prior,prior['observed'],self.free,'b'*64,self.reservation)
                self.assertFalse(failed['gateMet']);self.assertIsNone(failed['trialBudget']['violation'])
                free=dict(self.free);free[drive]=10*b.GIB+next_bytes+self.first['remainingPeak'][drive]
                self.assertTrue(b.reserve(self.first,self.first['observed'],free,'b'*64,self.reservation)['gateMet'])
                free[drive]-=1
                failed=b.reserve(self.first,self.first['observed'],free,'b'*64,self.reservation)
                self.assertFalse(failed['gateMet']);self.assertIsNone(failed['trialBudget']['violation'])
                restored=b.reserve(failed,failed['observed'],self.free,'c'*64,self.reservation)
                self.assertTrue(restored['gateMet'])
                self.assertEqual(self.first['charged'],restored['charged'])

    def ledger(self,folder):
        root=Path(folder)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
        b.initialize_chain(root,Path(folder)/'anchor.json')
        return root

    def test_overrun_survives_reload_shrink_close_without_refund(self):
        with tempfile.TemporaryDirectory() as folder:
            root=self.ledger(folder)
            state,digest=b.load_chain(root)
            first=b.reserve(state,state['observed'],self.free,digest,self.reservation)
            b.append_record(root,first);state,digest=b.load_chain(root)
            observed=dict(state['observed']);observed['cache']+=9*b.GIB
            overrun=b.reserve(state,observed,self.free,digest,self.reservation)
            b.append_record(root,overrun)
            for closed in (False,True):
                state,digest=b.load_chain(root)
                result=b.reserve(state,first['observed'],self.free,digest,self.reservation,closed)
                b.append_record(root,result);loaded,_=b.load_chain(root)
                self.assertEqual(result,loaded);self.assertFalse(loaded['gateMet'])
                self.assertEqual(overrun['trialBudget']['violation'],loaded['trialBudget']['violation'])
                self.assertEqual(overrun['charged'],loaded['charged'])
            self.assertEqual(dict(C=0,F=0),loaded['remainingPeak'])
            self.assertEqual(b.GIB,loaded['trialBudget']['requiredPeakBytes'])
            with self.assertRaisesRegex(ValueError,'Immutable live'):b.reserve(loaded,loaded['observed'],self.free,'e'*64,self.reservation)

    def test_normal_close_and_next_trial_charge_allowance_once(self):
        closed=b.reserve(self.first,self.first['observed'],self.free,'b'*64,self.reservation,True)
        self.assertTrue(closed['gateMet']);self.assertEqual(self.first['charged'],closed['charged'])
        next_reservation=copy.deepcopy(self.reservation);next_reservation['id']='isolated-next'
        started=b.reserve(closed,closed['observed'],self.free,'c'*64,next_reservation)
        self.assertTrue(started['gateMet'])
        self.assertEqual(closed['charged']['F']+b.GIB,started['charged']['F'])
        self.assertEqual(started['charged'],b.reserve(started,started['observed'],self.free,'d'*64,next_reservation)['charged'])

    def test_normal_old_schema1_and_schema2_bytes_survive_loading_and_append(self):
        with tempfile.TemporaryDirectory() as folder:
            root=self.ledger(folder)
            state,digest=b.load_chain(root)
            legacy1=b.advance(state,state['observed'],self.free,digest)
            path1,_=b.append_record(root,legacy1);bytes1=path1.read_bytes()
            state,digest=b.load_chain(root)
            legacy2=b.reserve(state,state['observed'],self.free,digest,self.reservation)
            legacy2.pop('trialBudget')
            path2,_=b.append_record(root,legacy2);bytes2=path2.read_bytes()
            state,digest=b.load_chain(root);self.assertEqual(legacy2,state)
            observed=dict(state['observed']);observed['cache']+=2*b.GIB
            current=b.reserve(state,observed,self.free,digest,self.reservation)
            b.append_record(root,current);self.assertEqual(current,b.load_chain(root)[0])
            self.assertTrue(current['gateMet'])
            self.assertEqual(bytes1,path1.read_bytes());self.assertEqual(bytes2,path2.read_bytes())

    def test_old_false_pass_and_erased_violation_are_rejected(self):
        for mode in ('old-false-pass','erased-after-shrink','changed-reason'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as folder:
                root=self.ledger(folder);state,digest=b.load_chain(root)
                b.append_record(root,b.reserve(state,state['observed'],self.free,digest,self.reservation))
                state,digest=b.load_chain(root);observed=dict(state['observed']);observed['cache']+=9*b.GIB
                overrun=b.reserve(state,observed,self.free,digest,self.reservation)
                if mode=='erased-after-shrink':
                    b.append_record(root,overrun);state,digest=b.load_chain(root)
                    record=b.reserve(state,self.first['observed'],self.free,digest,self.reservation,True)
                else:record=overrun
                if mode=='changed-reason':record['trialBudget']['violation']['reason']='other'
                else:record.pop('trialBudget');record['gateMet']=True
                b.append_record(root,record)
                with self.assertRaisesRegex(ValueError,'Historical trial limit|chain/charge'):b.load_chain(root)


if __name__=='__main__':unittest.main()
