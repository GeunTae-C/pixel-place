"""T18-11 계산·완전성·중단 경계. 작은 알려진 표본의 기대값을 직접 고정."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("phase18_analysis", Path(__file__).with_name("analyze-phase18.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
N = a.NANO


class ResourceBoundsTest(unittest.TestCase):
    def test_role_limit_rows_clock_identity_and_legacy_limit(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'samples.jsonl'
            row=dict(schemaVersion=1,planHash='f'*64,clockId='clock',nanoTime=1,padding='x'*2000)
            with p.open('wb') as stream:
                for i in range(9350):
                    row['nanoTime']=i;stream.write((json.dumps(row)+'\n').encode())
            self.assertGreater(p.stat().st_size,16777216)
            with self.assertRaisesRegex(a.Invalid,'resource bytes bound'):a.read_samples(p,'f'*64)
            self.assertEqual(9350,len(a.read_samples(p,'f'*64,67108864)))
            with self.assertRaisesRegex(a.Invalid,'resource row bound'):a.read_samples(p,'f'*64,67108864,9349)
            with self.assertRaisesRegex(a.Invalid,'resource identity'):a.read_samples(p,'0'*64,67108864)
            with self.assertRaisesRegex(a.Invalid,'resource bytes bound'):a.read_samples(p,'f'*64,p.stat().st_size-1)
            p.write_text(json.dumps(row)+'\n'+json.dumps(row)+'\n')
            with self.assertRaisesRegex(a.Invalid,'resource clock order'):a.read_samples(p,'f'*64)
            p.write_text('x'*65537)
            with self.assertRaisesRegex(a.Invalid,'resource row bound'):a.read_samples(p,'f'*64)

    def test_expanded_sample_count_keeps_legacy_default_and_frozen_edge(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'samples.jsonl'
            with path.open('w') as stream:
                for i in range(10322):stream.write(json.dumps(dict(schemaVersion=1,planHash='f'*64,clockId='clock',nanoTime=i))+'\n')
            self.assertEqual(10322,len(a.read_samples(path,'f'*64,335544320,50000)))
            with self.assertRaisesRegex(a.Invalid,'resource row bound'):a.read_samples(path,'f'*64)
            with self.assertRaisesRegex(a.Invalid,'resource limits'):a.read_samples(path,'f'*64,335544320,50001)

    def test_cap1_sample_allocation_is_explicit_and_keeps_legacy_ceiling(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'samples.jsonl'
            with path.open('w') as stream:
                for i in range(250000):stream.write(json.dumps(dict(schemaVersion=1,planHash='f'*64,clockId='clock',nanoTime=i))+'\n')
            self.assertEqual(250000,len(a.read_samples(path,'f'*64,67108864,250000,cap1=True)))
            with self.assertRaisesRegex(a.Invalid,'resource limits'):a.read_samples(path,'f'*64,67108864,250000)
            with self.assertRaisesRegex(a.Invalid,'resource limits'):a.read_samples(path,'f'*64,67108864,250001,cap1=True)
            with path.open('a') as stream:stream.write(json.dumps(dict(schemaVersion=1,planHash='f'*64,clockId='clock',nanoTime=250000))+'\n')
            with self.assertRaisesRegex(a.Invalid,'resource row bound'):a.read_samples(path,'f'*64,67108864,250000,cap1=True)

class ResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.run = self.root/"run"/"initial"
        self.run.mkdir(parents=True)
        self.case = {"caseId": "test", "runId": "run", "role": "diagnostic", "kind": "write",
                     "workload": {"writeRate": 3, "readRate": 0, "warmupSeconds": 0, "measurementSeconds": 1,
                                  "bootstrap": False, "inputWindowSeconds": 1, "inputTolerancePpm": 0,
                                  "simultaneousSeconds": 0, "wsConnections": 0}}
        self.plan = {"schemaVersion": 2, "stage": "A-2", "cases": [self.case]}
        self.plan.update(inputs=[], ownership={"catalog": "fixture", "wal": "fixture.wal", "dbInstance": "fixture-db"})
        self.case["runtime"] = {"mode": "group", "segmentBytes": 33554432}
        self.producer = {"schemaVersion": 1, "clockId": "2/client-start", "anchors": {"measurement": {"nanos": 10*N, "endNanos": 11*N}},
                         "runtime": {"pid": 2, "startUtc": "client-start"}, "control": {"state": "OPEN"}}

    def tearDown(self):
        self.temp.cleanup()

    def put(self, name, value, folder=None):
        (folder or self.run).joinpath(name).write_text(json.dumps(value), encoding="utf-8")

    def row(self, i, completed=None, kind="write", **updates):
        rate = self.case["workload"][kind+"Rate"]
        offset = i*N//rate
        sent = 10*N+offset
        r = dict(schemaVersion=1, planHash="", runId="run", caseId="test", requestId=f"run/test/{kind}/measurement/{i}",
                 kind=kind, scheduledPhase="measurement", ordinal=i, clockId="2/client-start", scheduledOffsetNanos=offset,
                 decidedNanos=sent, sentNanos=sent, completedNanos=sent+10_000_000 if completed is None else completed,
                 status="accepted", reason="", httpStatus=200, eventSeq=i+1 if kind == "write" else None, tileVersion=i+1,
                 sendState="sent", outcome="ACCEPTED", resolution="not_applicable")
        r.update(updates)
        return r

    def analyze(self, rows, failure=False, extra=None):
        plan_file = self.root/"plan.json"
        plan_file.write_text(json.dumps(self.plan), encoding="utf-8")
        ph = a.sha(plan_file)
        for r in rows:
            r["planHash"] = ph
        self.producer["planHash"] = ph
        self.put("producer.json", self.producer)
        (self.run/"raw.jsonl").write_text("".join(json.dumps(r)+"\n" for r in rows), encoding="utf-8")
        self.put("consistency.json", {"complete": True, "checkpoint": 10000, "accepted": sum(r["kind"] == "write" and r["status"] == "accepted" for r in rows),
                 "reads": sum(r["kind"] == "read" and r["status"] == "accepted" for r in rows), "readSamples": 0})
        self.put("drain.json", [{"ready": True, "pending": False, "executor": {"state": "RUNNING", "outstanding": 0, "queued": 0,
                 "claimedUnsettled": 0, "workerInFlight": 0, "unpublishedTerminal": 0, "activeCount": 0},
                 "capture": {"records": 0, "checkpoint": 10000, "tail": 10000, "observedNanos": 12*N}, "idleSince": 12*N}])
        self.put("manifest.json", {"schemaVersion": 1, "initialAction": "initial", "pid": 1, "startUtc": "initial-start",
                 "checkpoint": 10000, "inputHash": a.hashlib.sha256(b"[]").hexdigest(), "runtimeReceiptHash": "2"*64,
                 "catalog": "fixture", "wal": "fixture.wal", "mode": "group", "segmentBytes": 33554432,
                 "environmentIdentity": "fixture-db", "planHash": ph, "complete": True, "normalShutdown": True,
                 "rawHash": a.sha(self.run/"raw.jsonl"), "consistencyHash": a.sha(self.run/"consistency.json"), "drainHash": a.sha(self.run/"drain.json")})
        self.process(self.run.parent/"initial-process", 1, "initial-start")
        self.process(self.run/"producer-process", 2, "client-start", 1)
        self.put("server-closed.json", {"schedulerTerminated": True, "requests": {r["requestId"]: {"started": 1, "completed": 2} for r in rows if r["sendState"] == "sent"}})
        recovery = self.run.parent/"recovery"
        recovery.mkdir(exist_ok=True)
        manifest = dict(a.read(self.run/"manifest.json"), initialAction="recovery", pid=3, startUtc="recovery-start")
        self.put("manifest.json", manifest, recovery)
        self.put("runtime.json", a.read(self.run/"manifest.json"))
        self.put("runtime.json", manifest, recovery)
        for name in ("consistency.json", "drain.json"):
            self.put(name, a.read(self.run/name), recovery)
        self.put("server-closed.json", {"schedulerTerminated": True, "requests": {}}, recovery)
        self.process(self.run.parent/"recovery-process", 3, "recovery-start")
        if self.plan["stage"] != "A-2":
            self.observation(self.run, "initial")
            self.observation(recovery, "recovery")
        self.rehash(self.run)
        self.rehash(recovery)
        if failure:
            self.put("failure.json", {"nextTaskAllowed": False})
        if extra:
            extra()
        return a.analyze(plan_file, self.run)

    def process(self, folder, pid, start, parent=99):
        folder.mkdir(exist_ok=True)
        self.put("identity.json", {"pid": pid, "startUtc": start, "parentPid": parent}, folder)
        self.put("exit.json", {"pid": pid, "startUtc": start, "exit": 0, "unresolved": False, "failureType": ""}, folder)

    def rehash(self, folder):
        manifest = a.read(folder/"manifest.json")
        for name, field in (("consistency.json", "consistencyHash"), ("drain.json", "drainHash"),
                            ("collector.json", "collectorHash"), ("observation.json", "observationHash"),
                            ("client-collector.json", "clientCollectorHash")):
            if (folder/name).exists():
                manifest[field] = a.sha(folder/name)
        self.put("manifest.json", manifest, folder)
        self.put("runner-result.json", {"success": True, "exit": 0, "pid": manifest["pid"], "startUtc": manifest["startUtc"],
                 "manifestHash": a.sha(folder/"manifest.json")}, folder)

    def observation(self, folder, role):
        manifest = a.read(folder/"manifest.json")
        clock = f'{manifest["pid"]}/{manifest["startUtc"]}'
        ph = self.producer["planHash"]
        policy = {"schemaVersion": 1, "planHash": ph, "caseId": self.case["caseId"]}
        self.put("observation.json", {"schemaVersion": 1, "policy": policy, "policyHash": "3"*64,
            "trace": {"schemaVersion": 1, "clockId": clock, "complete": True, "loss": 0, "journal": []}}, folder)
        manifest["observationPolicyHash"] = "3"*64
        self.put("manifest.json", manifest, folder)
        for client in ([False, True] if role == "initial" else [False]):
            filename = "client-resources.jsonl" if client else "resource-samples.jsonl"
            sample = {"schemaVersion": 1, "planHash": ph, "clockId": self.producer["clockId"] if client else clock,
                      "nanoTime": 10*N, "heapUsed": 100, "afterGc": None, "safety": {"captureStatus": "fresh", "stop": ""}}
            (folder/filename).write_text(json.dumps(sample)+"\n", encoding="utf-8")
            self.put("client-collector.json" if client else "collector.json", {"schemaVersion": 1, "planHash": ph,
                "complete": True, "samples": 1, "bytes": (folder/filename).stat().st_size,
                "samplesHash": a.sha(folder/filename), "observerFailures": 0, "failure": None}, folder)
        messages = [{"clockId": clock, "direction": direction, "message": message, "beforeNanos": time, "afterNanos": time+1}
                    for direction, message, time in (("send", "MEASURE", 10*N), ("receive", "SEND_DONE 3", 11*N))] if role == "initial" else []
        self.put("phase-clocks.json", {"schemaVersion": 1, "clockId": clock, "messages": messages}, folder)

    def change(self, relative, updates, rehash=False):
        path = self.run/relative
        value = a.read(path); value.update(updates)
        self.put(path.name, value, path.parent)
        if rehash:
            self.rehash(path.parent)

    def test_completion_window_excludes_end_but_sent_rtt_includes_drain(self):
        r = self.analyze([self.row(0, 10*N), self.row(1, 11*N), self.row(2, 12*N)])
        m = r["measurement"]["metrics"]["write"]
        self.assertEqual(1, m["completionAcceptedPerSecond"])
        self.assertEqual(3, m["sentCohortAcceptedPerSecond"])
        self.assertAlmostEqual(1333.333334, m["acceptedRtt"]["p95Millis"])
        self.assertEqual("VALID_NO_TARGET", r["status"])

    def test_sent_zero_has_null_error_and_latency_denominators(self):
        rows = [self.row(i, status="client_not_sent", reason="processing_capacity", sentNanos=None, completedNanos=None,
                         sendState="not_sent", outcome="NOT_SENT", httpStatus=None, eventSeq=None, tileVersion=None) for i in range(3)]
        r = self.analyze(rows)
        m = r["measurement"]["metrics"]["write"]
        self.assertIsNone(m["systemErrorRate"])
        self.assertIsNone(m["acceptedRtt"]["p99Millis"])
        self.assertEqual(1, m["clientNotSentRate"])
        self.assertFalse(r["inputFulfillment"]["met"])

    def test_duplicate_missing_and_extra_id_cannot_reduce_planned(self):
        for rows in ([self.row(0), self.row(0), self.row(2)], [self.row(0), self.row(1)],
                     [self.row(0), self.row(1), self.row(2, requestId="outside")]):
            with self.subTest(rows=len(rows)):
                r = self.analyze(rows)
                self.assertEqual(3, r["originalPlanned"])
                self.assertEqual("MEASUREMENT_INVALID", r["status"])
                self.assertTrue(r["integrity"]["unresolved"])

    def test_interrupted_future_not_sent_keeps_actual_decision_and_original_plan(self):
        self.producer["stopped"] = True
        rows = [self.row(0)] + [self.row(i, status="client_not_sent", reason="stopped", decidedNanos=10*N+1,
                sentNanos=None, completedNanos=None, sendState="not_sent", outcome="NOT_SENT", httpStatus=None) for i in (1, 2)]
        r = self.analyze(rows, failure=True)
        self.assertEqual("EXECUTION_FAILED", r["status"])
        self.assertEqual([], r["problems"])
        self.assertEqual(3, r["observedTerminals"])
        self.assertEqual(10*N+1, rows[2]["decidedNanos"])

    def test_early_not_sent_without_stop_and_cross_clock_rejected(self):
        rows = [self.row(0), self.row(1), self.row(2, clockId="server-clock")]
        r = self.analyze(rows)
        self.assertTrue(r["problems"])
        rows[2] = self.row(2, status="client_not_sent", reason="arrival_late", decidedNanos=9*N,
                           sentNanos=None, completedNanos=None, sendState="not_sent", outcome="NOT_SENT", httpStatus=None)
        self.assertEqual("MEASUREMENT_INVALID", self.analyze(rows)["status"])

    def test_unknown_and_unexpected_status_remain_visible_after_late_completion(self):
        rows = [self.row(0), self.row(1, status="timeout", httpStatus=None, outcome="UNKNOWN", resolution="unresolved"),
                self.row(2, status="unexpected_status", httpStatus=302, outcome="UNKNOWN", resolution="unresolved")]
        r = self.analyze(rows, failure=True)
        self.assertEqual("EXECUTION_FAILED", r["status"])
        self.assertEqual(2, len(r["integrity"]["unresolved"]))
        self.assertAlmostEqual(1/3, r["measurement"]["metrics"]["write"]["systemErrorRate"])
        self.assertEqual(1, r["measurement"]["metrics"]["write"]["statuses"]["unexpected_status"])

    def test_read_input_shortfall_preserves_valid_write_slo_without_mixed_pass(self):
        self.case.update(role="target", kind="mixed")
        self.case["workload"].update(writeRate=105, readRate=100, simultaneousSeconds=1)
        writes = [self.row(i) for i in range(105)]
        reads = [self.row(i, kind="read") if i < 10 else self.row(i, kind="read", status="client_not_sent", reason="processing_capacity",
                 sentNanos=None, completedNanos=None, httpStatus=None, outcome="NOT_SENT", sendState="not_sent") for i in range(100)]
        r = self.analyze(writes+reads)
        self.assertTrue(r["writeSloMet"])
        self.assertEqual("MISSED", r["inputFulfillment"]["perKind"]["read"])
        self.assertEqual("VALID_TARGET_MISSED", r["status"])
        self.assertEqual(10, r["measurement"]["metrics"]["read"]["sent"])
        self.assertIn("cannot distinguish", r["inputFulfillment"]["cause"])
        for row in reads[10:]:
            row["reason"] = "arrival_late"
        r = self.analyze(writes+reads)
        self.assertEqual("VALID_TARGET_MISSED", r["status"])
        self.assertEqual(90, r["inputFulfillment"]["notSentReasons"]["arrival_late"])

    def test_nearest_rank_and_not_average_of_percentiles(self):
        self.assertEqual(95, a.percentile(list(range(1, 101)), 95))
        self.assertEqual(99, a.percentile(list(range(1, 101)), 99))
        self.assertIsNone(a.percentile([], 99))

    def test_missing_required_collector_never_passes_A3(self):
        self.plan["stage"] = "A-3"
        r = self.analyze([self.row(i) for i in range(3)], extra=lambda: (self.run/"collector.json").unlink())
        self.assertEqual("MEASUREMENT_INVALID", r["status"])
        self.assertFalse(r["integrity"]["complete"])

    def test_manifest_hash_corruption_and_failed_executor_prevent_completion(self):
        r = self.analyze([self.row(i) for i in range(3)], extra=lambda: self.put("consistency.json", {"complete": True, "accepted": 0, "reads": 0}))
        self.assertEqual("MEASUREMENT_INVALID", r["status"])
        self.assertTrue(r["integrity"]["mismatch"])

    def test_actual_exit_and_missing_server_request_override_success_flags(self):
        r = self.analyze([self.row(i) for i in range(3)], extra=lambda: self.put("exit.json", {"exit": 7, "unresolved": False}, self.run.parent/"initial-process"))
        self.assertEqual("EXECUTION_FAILED", r["status"])
        self.assertFalse(r["integrity"]["complete"])
        r = self.analyze([self.row(i) for i in range(3)], extra=lambda: self.put("server-closed.json", {"schedulerTerminated": True, "requests": {}}))
        self.assertFalse(r["integrity"]["drainComplete"])

    def test_known_sent_missing_terminal_preserves_producer_unresolved(self):
        self.producer["unresolved"] = [{"requestId": "run/test/write/measurement/2", "sendState": "sent", "reason": "raw_write_failure", "lastObservedNanos": 12*N}]
        r = self.analyze([self.row(0), self.row(1)], failure=True)
        self.assertEqual(self.producer["unresolved"], r["integrity"]["unresolved"])

    def test_spoofed_collector_success_without_raw_evidence_is_invalid(self):
        self.plan["stage"] = "A-3"
        r = self.analyze([self.row(i) for i in range(3)], extra=lambda: self.put("collector.json", {"schemaVersion": 1, "complete": True, "planHash": self.producer["planHash"]}))
        self.assertEqual("MEASUREMENT_INVALID", r["status"])
        self.assertFalse(r["observation"]["complete"])

    def test_no_target_4xx_never_introduces_a_write_target(self):
        rows = [self.row(0), self.row(1), self.row(2, status="rejected_429", httpStatus=429, outcome="REJECTED")]
        r = self.analyze(rows)
        self.assertEqual("MEASUREMENT_INVALID", r["status"])
        self.assertIsNone(r["writeSloMet"])

    def test_host_counter_deltas_keep_idle_latency_null_and_reset_missing(self):
        raw = {"Name": "disk", "Timestamp_PerfTime": 10, "Frequency_PerfTime": 1000,
               "DiskReadBytesPersec": 100, "DiskWriteBytesPersec": 200, "AvgDisksecPerRead_Base": 1,
               "AvgDisksecPerWrite_Base": 2, "AvgDisksecPerRead": 2, "AvgDisksecPerWrite": 5, "CurrentDiskQueueLength": 1}
        later = dict(raw, Timestamp_PerfTime=1010, DiskWriteBytesPersec=1200, AvgDisksecPerWrite_Base=4, AvgDisksecPerWrite=15)
        path = self.root/"host.jsonl"
        path.write_text("\n".join(json.dumps({"status": "observed", "disks": [d]}) for d in [raw, later, raw]))
        value = a.host_disks(path, None)["disks"]["disk"]
        self.assertEqual(1000, value["writeBytes"])
        self.assertEqual(5, value["writeMeanMillis"])
        self.assertIsNone(value["readMeanMillis"])
        self.assertEqual(1, value["invalidIntervals"])

    def test_A3_required_evidence_regressions_keep_metrics_and_reject_false_completion(self):
        self.plan["stage"] = "A-3"
        cases = [("phase-clocks.json", None, "MEASUREMENT_INVALID", "observation"),
                 ("producer-process/exit.json", {"exit": 1, "unresolved": True}, "EXECUTION_FAILED", "initial"),
                 ("../recovery/consistency.json", {"complete": False}, "MEASUREMENT_INVALID", "recovery"),
                 ("../recovery/collector.json", {"complete": False}, "MEASUREMENT_INVALID", "recovery")]
        for name, change, status, section in cases:
            with self.subTest(name=name):
                mutate = (lambda: (self.run/name).unlink()) if change is None else (lambda: self.change(name, change))
                report = self.analyze([self.row(i) for i in range(3)], extra=mutate)
                self.assertEqual(status, report["status"])
                self.assertFalse(report["integrity"]["complete"])
                self.assertEqual(3, report["measurement"]["metrics"]["write"]["sent"])
                if section == "observation":
                    self.assertFalse(report["observation"]["complete"])
                    self.assertIn("phase-clocks.json", report["observation"]["missing"])
                else:
                    self.assertFalse(report["integrity"][section+"Complete"])
                if status == "EXECUTION_FAILED":
                    failures = report["integrity"]["failures"]
                    self.assertTrue(any(f["artifact"] == "producer-process/exit.json" and f["evidence"]["exit"] == 1 for f in failures))

    def test_rehashed_recovery_failure_and_missing_raw_are_not_success_declarations(self):
        self.plan["stage"] = "A-3"
        for name, updates in (("consistency.json", {"complete": False}), ("collector.json", {"complete": False}),
                              ("consistency.json", {"reads": 7}), ("collector.json", {"samplesHash": "0"*64})):
            with self.subTest(name=name, updates=updates):
                report = self.analyze([self.row(i) for i in range(3)],
                    extra=lambda: self.change("../recovery/"+name, updates, rehash=True))
                self.assertEqual("MEASUREMENT_INVALID", report["status"])
                self.assertFalse(report["integrity"]["recoveryComplete"])
                self.assertFalse(report["integrity"]["complete"])
        for name in ("drain.json", "resource-samples.jsonl", "observation.json", "server-closed.json", "phase-clocks.json"):
            with self.subTest(missing=name):
                report = self.analyze([self.row(i) for i in range(3)], extra=lambda: (self.run.parent/"recovery"/name).unlink())
                self.assertEqual("MEASUREMENT_INVALID", report["status"])
                self.assertFalse(report["integrity"]["recoveryComplete"])

    def test_late_shape_errors_leave_completion_false_and_preserve_original_failure(self):
        self.plan["stage"] = "A-3"
        for name in ("phase-clocks.json", "../recovery/collector.json", "../recovery/consistency.json"):
            with self.subTest(name=name):
                r = self.analyze([self.row(i) for i in range(3)], failure=True,
                    extra=lambda: (self.run/name).write_text('{bad json', encoding="utf-8"))
                self.assertEqual("EXECUTION_FAILED", r["status"])
                self.assertFalse(r["integrity"]["complete"])
                self.assertTrue(any(f["artifact"] == "failure.json" for f in r["integrity"]["failures"]))
                self.assertEqual(3, r["measurement"]["metrics"]["write"]["sent"])

    def test_identity_mismatch_and_process_failure_in_each_role_reject_success(self):
        for folder in ("producer-process", "../initial-process", "../recovery-process"):
            with self.subTest(folder=folder):
                r = self.analyze([self.row(i) for i in range(3)], extra=lambda: self.change(folder+"/exit.json", {"pid": 123}))
                self.assertEqual("MEASUREMENT_INVALID", r["status"])
                self.assertFalse(r["integrity"]["complete"])
                r = self.analyze([self.row(i) for i in range(3)], extra=lambda: self.change(folder+"/exit.json", {"exit": 5}))
                self.assertEqual("EXECUTION_FAILED", r["status"])
                self.assertFalse(r["integrity"]["complete"])

    def test_valid_A2_A3_and_official_pilot_require_only_role_specific_observation(self):
        for stage in ("A-2", "A-3", "A-4"):
            with self.subTest(stage=stage):
                self.plan["stage"] = stage
                r = self.analyze([self.row(i) for i in range(3)])
                self.assertEqual("VALID_NO_TARGET", r["status"])
                self.assertTrue(r["integrity"]["complete"])
                self.assertTrue(r["integrity"]["recoveryComplete"])
                self.assertEqual(None if stage == "A-2" else True, r["observation"]["complete"])

    def test_official_pilot_missing_client_observation_cannot_complete(self):
        self.plan["stage"] = "A-4"
        self.case["role"] = "pilot"
        r = self.analyze([self.row(i) for i in range(3)], extra=lambda: (self.run/"client-collector.json").unlink())
        self.assertEqual("MEASUREMENT_INVALID", r["status"])
        self.assertFalse(r["integrity"]["complete"])
        self.assertFalse(r["observation"]["complete"])

    def test_activity_artifact_honors_explicit_byte_reservation(self):
        self.put("activity.json", {"requests": {"known": {"started": 1, "completed": 2}}})
        path = self.run/"activity.json"
        good = a.EvidenceCheck(self.run)
        self.assertIn("known", good.artifact("activity.json", limit=path.stat().st_size)["requests"])
        short = a.EvidenceCheck(self.run)
        self.assertEqual({}, short.artifact("activity.json", limit=path.stat().st_size-1))
        self.assertFalse(short.report()["complete"])

    def test_WS_shape_failure_keeps_HTTP_metrics_and_invalidates_completeness(self):
        self.case["workload"]["wsConnections"] = 1
        r = self.analyze([self.row(i) for i in range(3)])
        self.assertEqual("MEASUREMENT_INVALID", r["status"])
        self.assertFalse(r["integrity"]["complete"])
        self.assertFalse(r["integrity"]["wsComplete"])
        self.assertEqual(3, r["measurement"]["metrics"]["write"]["sent"])

    def test_invalid_failure_field_is_evidence_corruption_not_confirmed_execution_failure(self):
        for malformed in (["wrong-type"], [], False, None):
            with self.subTest(failureType=malformed):
                r = self.analyze([self.row(i) for i in range(3)],
                    extra=lambda: self.change("producer-process/exit.json", {"failureType": malformed}))
                self.assertEqual("MEASUREMENT_INVALID", r["status"])
                self.assertFalse(r["integrity"]["complete"])
                self.assertEqual([], r["integrity"]["failures"])
        self.plan["stage"] = "A-3"
        r = self.analyze([self.row(i) for i in range(3)],
            extra=lambda: self.change("collector.json", {"observerFailures": "unknown"}, rehash=True))
        self.assertEqual("MEASUREMENT_INVALID", r["status"])
        self.assertFalse(r["observation"]["complete"])
        self.assertEqual([], r["integrity"]["failures"])


if __name__ == "__main__":
    unittest.main()
