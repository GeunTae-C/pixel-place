"""과거 장부 replay와 F 동시 peak·재배치 경계의 작은 무서비스 검증."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
import phase18_cap_budget as b
from test_cap_budget import seed,smoke_reservation


class FLayout(unittest.TestCase):
    def prior(self):
        s=b.seed_state(seed(),'a'*64)
        return b.reserve(s,s['observed'],dict(C=40*b.GIB,F=400*b.GIB),'a'*64,smoke_reservation(),True)

    def transitioned(self):
        s=self.prior()
        return b.relocate_layout(s,{k:1000 for k in b.F_GROUPS},s['free'],'b'*64,300,dict(C=7,F=11))

    def test_copies_and_preparation_keep_historical_volume_charges(self):
        old=self.prior();new=self.transitioned()
        self.assertEqual(new['charged']['C'],old['charged']['C']+7)
        self.assertEqual(new['charged']['F'],old['charged']['F']+311)
        self.assertEqual(new['pastAllowances'],old['pastAllowances'])
        self.assertEqual(new['pastUncertain'],old['transition']['reservation']['pastUncertain'])
        self.assertEqual(b.GROUPS['evidence'],'C')
        self.assertEqual(b.groups_for(new)['evidence'],'F')

    def test_cleanup_observation_never_refunds_old_consumption(self):
        s=self.transitioned();shrunk={k:0 for k in s['observed']}
        r=b.advance(s,shrunk,s['free'],'c'*64)
        self.assertEqual(r['charged'],s['charged'])
        self.assertEqual(r['volumeLayout'],'f-output-v1')
        self.assertNotIn('transition',r)

    def test_new_evidence_archive_and_graph_growth_charge_only_f(self):
        s=self.transitioned();observed=dict(s['observed'],evidence=1007,archive=1011,graph=1013)
        r=b.advance(s,observed,s['free'],'c'*64)
        self.assertEqual(r['charged']['C'],s['charged']['C'])
        self.assertEqual(r['charged']['F'],s['charged']['F']+31)

    def reservation(self,s):
        r=smoke_reservation();r['id']='new-f-fixture';r['additionalPeak']={k:0 for k in b.F_GROUPS}
        r['additionalPeak'].update(evidence=2*b.GIB,mysql=2*b.GIB,work=b.GIB)
        r['transientAllowance']={'C':0,'F':0}
        return r

    def test_shared_f_sum_rejects_individually_sufficient_free(self):
        s=self.transitioned();r=self.reservation(s)
        free=dict(C=40*b.GIB,F=10*b.GIB+5*b.GIB+s['nextReservation']['F']-1)
        out=b.reserve(s,s['observed'],free,'c'*64,r)
        self.assertFalse(out['gateMet']);self.assertEqual(out['remainingPeak']['F'],5*b.GIB)
        free['F']+=1
        self.assertTrue(b.reserve(s,s['observed'],free,'c'*64,r)['gateMet'])

    def test_new_reservation_cannot_omit_archive_or_uncertainty(self):
        s=self.transitioned();r=self.reservation(s);del r['additionalPeak']['archive']
        with self.assertRaisesRegex(ValueError,'storage scopes'):b.reserve(s,s['observed'],s['free'],'c'*64,r)
        r=self.reservation(s);r['pastUncertain']=['different']
        with self.assertRaisesRegex(ValueError,'uncertainty'):b.reserve(s,s['observed'],s['free'],'c'*64,r)

    def test_expansion_includes_next_f_evidence_reservation(self):
        s=self.transitioned();r=self.reservation(s)
        value=b.storage_expansion(s,s['observed'],'c'*64,r)
        self.assertEqual(s['charged']['F']+10*b.GIB+64*1024**2,value['usage']['F'])

    def test_cannot_relocate_open_reservation_or_repeat_transition(self):
        old=self.prior();old['transition']['closed']=False
        with self.assertRaisesRegex(ValueError,'Close current'):b.relocate_layout(old,{k:0 for k in b.F_GROUPS},old['free'],'b'*64,0)
        with self.assertRaisesRegex(ValueError,'already adopted'):b.relocate_layout(self.transitioned(),{k:0 for k in b.F_GROUPS},old['free'],'b'*64,0)

    def test_anchor_and_old_bytes_replay_across_relocation_and_observe(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
            b.initialize_chain(root,Path(folder)/'anchor.json')
            old,tip=b.load_chain(root)
            record=b.reserve(old,old['observed'],dict(C=40*b.GIB,F=400*b.GIB),tip,smoke_reservation(),True)
            first,_=b.append_record(root,record);before=first.read_bytes()
            old,tip=b.load_chain(root);new=b.relocate_layout(old,{k:1000 for k in b.F_GROUPS},old['free'],tip,300)
            b.append_record(root,new);actual,tip=b.load_chain(root);self.assertEqual(new,actual)
            observation=b.advance(actual,{k:0 for k in b.F_GROUPS},actual['free'],tip)
            b.append_record(root,observation);self.assertEqual(observation,b.load_chain(root)[0])
            self.assertEqual(before,first.read_bytes())


class CGraphSuccessor(FLayout):
    def successor(self,s=None):
        s=s or self.transitioned()
        observed=dict(s['observed'],graph=311,work=0,evidence=1050)
        known={k:0 for k in b.C_GRAPH_GROUPS};known.update(graph=311,evidence=70,work=23)
        return b.adopt_c_graph(s,observed,s['free'],'c'*64,known,dict(C=13,F=0))

    def test_volume_switch_and_positive_writes_survive_net_shrink_once(self):
        old=self.transitioned();new=self.successor(old)
        self.assertEqual(new['charged'],dict(C=old['charged']['C']+324,F=old['charged']['F']+93))
        self.assertEqual(b.groups_for(old)['graph'],'F');self.assertEqual(b.groups_for(new)['graph'],'C')
        after=b.advance(new,dict(new['observed'],graph=320,archive=1011),new['free'],'d'*64)
        self.assertEqual(after['charged'],dict(C=new['charged']['C']+9,F=new['charged']['F']+11))
        shrink=b.advance(after,{k:0 for k in new['observed']},new['free'],'e'*64)
        self.assertEqual(shrink['charged'],after['charged']);self.assertEqual(new['pastUncertain'],old['pastUncertain'])

    def test_successor_rejects_duplicate_open_unknown_and_unproven_graph(self):
        s=self.transitioned();new=self.successor(s)
        change=new['layoutSuccessor']
        with self.assertRaisesRegex(ValueError,'exactly once'):self.successor(new)
        s['transition']={'closed':False}
        with self.assertRaisesRegex(ValueError,'Close current'):self.successor(s)
        del s['transition'];known=dict(change['knownPositiveGrowth'],graph=310)
        with self.assertRaisesRegex(ValueError,'restored write'):b.adopt_c_graph(s,new['observed'],s['free'],'c'*64,known,dict(C=0,F=0))
        with self.assertRaisesRegex(ValueError,'Unknown storage'):b.groups_for(dict(volumeLayout='made-up'))

    def test_exact_free_and_charge_boundaries_use_new_volume(self):
        s=self.transitioned();new=self.successor(s);change=new['layoutSuccessor'];nxt=new['nextReservation']
        free={d:10*b.GIB+nxt[d] for d in ('C','F')}
        self.assertTrue(b.adopt_c_graph(s,new['observed'],free,'c'*64,change['knownPositiveGrowth'],change['externalPositiveGrowth'])['gateMet'])
        for d in ('C','F'):
            short=dict(free);short[d]-=1
            self.assertFalse(b.adopt_c_graph(s,new['observed'],short,'c'*64,change['knownPositiveGrowth'],change['externalPositiveGrowth'])['gateMet'])
        s['storageLimits']['C']=new['charged']['C']+nxt['C']
        self.assertTrue(self.successor(s)['gateMet']);s['storageLimits']['C']-=1
        self.assertFalse(self.successor(s)['gateMet'])

    def test_observation_roots_are_disjoint_and_keep_historical_link_exception(self):
        from unittest.mock import patch
        from pixel_place_paths import GRAPH,ARCHIVE
        roots=[]
        def observe(path,deadline,**kw):
            roots.append((Path(path),kw));return 7
        with patch.object(b,'directory_bytes',side_effect=observe):
            result,_=b.f_observation(Path('C:/Users/cgt/Desktop/Java/pixel-place'),1)
        self.assertEqual(result['graph'],7);self.assertEqual(result['project'],35)
        self.assertIn(GRAPH,[p for p,_ in roots]);self.assertIn(ARCHIVE,[p for p,_ in roots])
        for i,(p,_) in enumerate(roots):
            for q,_ in roots[i+1:]:self.assertFalse(p.is_relative_to(q) or q.is_relative_to(p))
        self.assertEqual([p for p,kw in roots if kw.get('historical')],[Path('C:/Users/cgt/Desktop/Java/pixel-place/build/agent-runs')])

    def test_successor_replay_preserves_all_old_bytes_and_rejects_interrupted_commit(self):
        for failure in (None,'intent','prepared','record','anchor'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as folder:
                root=Path(folder)/'ledger';root.mkdir();(root/'cap-budget.json').write_text(json.dumps(seed()))
                anchor=Path(folder)/'anchor.json';b.initialize_chain(root,anchor)
                old,tip=b.load_chain(root)
                first=b.reserve(old,old['observed'],dict(C=40*b.GIB,F=400*b.GIB),tip,smoke_reservation(),True);b.append_record(root,first)
                old,tip=b.load_chain(root);b.append_record(root,b.relocate_layout(old,{k:1000 for k in b.F_GROUPS},old['free'],tip,300))
                before={p.name:p.read_bytes() for p in root.glob('cap-storage-*.json')}
                old,tip=b.load_chain(root);new=self.successor(old);new['previousHash']=tip
                def interrupt(step):
                    if step==failure:raise RuntimeError('fixture interruption')
                if failure:
                    with self.assertRaisesRegex(RuntimeError,'fixture interruption'):b.append_record(root,new,interrupt)
                    with self.assertRaises(ValueError):b.load_chain(root)
                else:
                    b.append_record(root,new);self.assertEqual(new,b.load_chain(root)[0])
                    value=json.loads(anchor.read_text());value['sha256']='0'*64;anchor.write_text(json.dumps(value))
                    with self.assertRaisesRegex(ValueError,'tip mismatch'):b.load_chain(root)
                self.assertTrue(all((root/name).read_bytes()==data for name,data in before.items()))


if __name__=='__main__':unittest.main()
