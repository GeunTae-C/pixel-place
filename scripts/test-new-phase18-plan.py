"""환경 이전 때 workload 보존, 새 identity, 과거 plan 재사용 거부 검증."""
import copy
import datetime
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("new_plan", Path(__file__).with_name("new-phase18-plan.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class S2ProfileTest(unittest.TestCase):
    def test_generator_uses_profile_but_keeps_s1_and_java_paths(self):
        import json,time,sys
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location("cap_plan",Path(__file__).with_name("new-phase18-cap-plan.py"))
        cap=importlib.util.module_from_spec(spec);spec.loader.exec_module(cap)
        for smoke,k6,expected_output,expected_sample in [('S1',True,134217728,1048576),('S2',False,33554432,1048576),('S2',True,1342177280,335544320)]:
            with self.subTest(smoke=smoke,k6=k6),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);now=int(time.time()*1000)
                module.write_new(root/'issued.json',dict(issuedEpochMillis=now,expiresEpochMillis=now+2400000))
                # 정식 환경으로 사용하지 않는 생성기 입력 대역; downstream Plan 생성은 호출 기록만 확인
                module.write_new(root/'environment.json',dict(evidenceBytes=1677721600,sessionId="phase18-unit-fixture"))
                module.write_new(root/'profile.json',cap.s2_profile(Path(__file__).resolve().parent.parent))
                module.write_new(root/'predecessor.json',dict(k6=dict(s2Profile=dict(path=str(root/'profile.json'),sha256=cap.sha(root/'profile.json')))))
                argv=['new-phase18-cap-plan.py','--environment',str(root/'environment.json'),'--issued',str(root/'issued.json'),'--predecessor',str(root/'predecessor.json'),'--smoke',smoke,'--run-id','isolated']
                if k6:argv+=['--k6']
                if smoke=='S2' and k6:argv+=['--s2-profile',str(root/'profile.json')]
                with patch.object(cap,'evidence_path',return_value=root),patch.object(sys,'argv',argv),patch.object(cap.subprocess,'call',return_value=0) as downstream:
                    self.assertEqual(0,cap.main());downstream.assert_called_once()
                plan=module.read(root/'cap-template.json');policy=module.read(root/'cap-policy-template.json')
                self.assertEqual(expected_output,plan['bounds']['outputBytes']);self.assertEqual(expected_sample,policy['maxSampleBytes'])
                self.assertEqual(50000 if smoke=='S2' and k6 else 10000,plan['bounds']['samples']);self.assertEqual(250,plan['cases'][0]['observation']['sampleMillis'])
                if smoke=='S2' and k6:
                    module_path=root/'environment.json';module_path.write_text(json.dumps(dict(evidenceBytes=201326592,sessionId="phase18-unit-fixture")))
                    with patch.object(cap,'evidence_path',return_value=root),patch.object(sys,'argv',argv),patch.object(cap.subprocess,'call') as downstream:
                        with self.assertRaisesRegex(ValueError,'environment evidence reservation'):cap.main()
                        downstream.assert_not_called()

    def test_static_profile_matches_old_failure_and_new_reservations(self):
        spec=importlib.util.spec_from_file_location("cap_plan",Path(__file__).with_name("new-phase18-cap-plan.py"))
        cap=importlib.util.module_from_spec(spec);spec.loader.exec_module(cap)
        profile=cap.s2_profile(Path(__file__).resolve().parent.parent)
        self.assertEqual((335544320,1342177280,1677721600,50000,250),tuple(profile[k] for k in ('maxSampleBytes','outputBytes','evidenceBytes','samples','sampleMillis')))
        u=profile['usage']
        self.assertEqual(10322,u['requiredSamples']);self.assertEqual(2580000,u['observationMillis'])
        self.assertGreater(9350*1850,1048576)
        self.assertEqual(12368,u['traceEntries']);self.assertEqual(10002432,u['traceBytes'])
        self.assertEqual(2*(u['sampleBytes']+u['observationBytes'])+u['metricsBytes']+u['otherRunBytes'],u['runBytes'])
        self.assertLess(u['runBytes']*2,profile['outputBytes'])
        self.assertLess(u['evidenceBytes']*2,profile['evidenceBytes'])
        # 한도만 늘려도 예상량은 불변. 실제 출력 증가만 부모 usage에 합산
        altered=dict(profile,maxSampleBytes=profile['maxSampleBytes']*5)
        self.assertEqual(u,altered['usage'])
        larger=cap.s2_usage(cleanup_millis=360000)
        self.assertGreater(larger['runBytes'],u['runBytes'])
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'profile.json';module.write_new(path,profile)
            self.assertEqual(profile,cap.read_s2_profile(path,Path(__file__).resolve().parent.parent))
            profile['maxSampleBytes']=1048576;path.write_text(__import__('json').dumps(profile))
            with self.assertRaisesRegex(ValueError,'profile/template mismatch'):cap.read_s2_profile(path,Path(__file__).resolve().parent.parent)

class NewPlanTest(unittest.TestCase):
    def setUp(self):
        self.template = dict(sessionId="phase18-old-session", state="ready", ownership={}, bounds={"databaseBytes": 512},
            cases=[dict(runId="old-run", workload={"writeRate": 105}, runtime={"mode": "group"})])
        self.environment = dict(sessionId="phase18-new-session", mysqlRoot="F:/MySQL/test", owner="cgt", catalogMode="create-new",
            mysqlUuid="expected", observedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())

    def compose(self):
        return module.compose(self.template, self.environment, Path("C:/repo"), "new-run", 2, "f" * 64)

    def test_new_identity_preserves_workload_bounds_and_template(self):
        original = copy.deepcopy(self.template)
        plan = self.compose()
        self.assertEqual(original, self.template)
        self.assertEqual(plan["bounds"], original["bounds"])
        self.assertEqual(plan["cases"][0]["workload"], original["cases"][0]["workload"])
        self.assertEqual(plan["ownership"]["dbInstance"], "expected@" + "f" * 64)
        self.assertEqual(Path(plan["ownership"]["sessionRoot"]), Path("F:/pixel-place-work/sessions/phase18-new-session"))
        for area, folder in (("build", "build"), ("cache", "project-cache"), ("temp", "temp"), ("jna", "jna")):
            self.assertEqual(Path(plan["ownership"][area]), Path(plan["ownership"]["sessionRoot"]) / folder)
        self.assertEqual(Path(plan["ownership"]["wal"]),
            Path("F:/pixel-place-wal/phase18/phase18-new-session/new-run/pixel-place.wal"))

    def test_old_storage_rejected(self):
        self.environment["mysqlRoot"] = "E:/old/mysql"
        with self.assertRaisesRegex(ValueError, "ownership"):
            self.compose()

    def test_e_repository_cannot_be_used_for_new_execution(self):
        with self.assertRaisesRegex(ValueError, "C repository"):
            module.compose(self.template, self.environment, Path("E:/old/repo"), "new-run", 2, "f" * 64)

    def test_old_session_rejected(self):
        self.environment["sessionId"] = self.template["sessionId"]
        with self.assertRaisesRegex(ValueError, "new session"):
            self.compose()

    def test_stale_environment_rejected(self):
        self.environment["observedUtc"] = "2000-01-01T00:00:00Z"
        with self.assertRaisesRegex(ValueError, "Fresh environment"):
            self.compose()

    def test_multiple_cases_cannot_silently_reuse_run_ids(self):
        self.template["cases"].append(copy.deepcopy(self.template["cases"][0]))
        with self.assertRaisesRegex(ValueError, "One case"):
            self.compose()

    def test_production_redis_rejected(self):
        with self.assertRaisesRegex(ValueError, "Dedicated"):
            module.compose(self.template, self.environment, Path("C:/repo"), "new-run", 0, "f" * 64)

    def test_existing_plan_bytes_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "plan.json"
            module.write_new(target, self.template)
            before = target.read_bytes()
            with self.assertRaises(FileExistsError):
                module.write_new(target, self.compose())
            self.assertEqual(before, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
