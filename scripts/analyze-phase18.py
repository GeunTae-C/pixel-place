"""18 정식 분석기: 동결 예정 집합과 최초 terminal을 대조하는 유일한 집계 구현."""
import argparse
import collections
import hashlib
import json
import math
import struct
import uuid
import os
from pixel_place_paths import historical, plain_absolute, EVIDENCE
import phase18_cap as cap
from pathlib import Path

NANO = 1_000_000_000
PHASES = ("bootstrap", "warmup", "measurement")
KINDS = ("write", "read")
STATUSES = {"accepted", "rejected_429", "rejected_4xx", "server_5xx", "timeout",
            "network_error", "malformed_200", "unexpected_status", "client_not_sent"}
SYSTEM_ERRORS = {"server_5xx", "timeout", "network_error", "malformed_200"}


class Invalid(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise Invalid(reason)


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def decode(text):
    return json.loads(text, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(Invalid("nonfinite JSON")))


def read(path, limit=16_777_216):
    with historical(path).open("rb") as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit, "JSON byte bound")
    result=decode(data.decode("utf-8-sig"))
    cap.check_deadline()
    return result


def sha(path):
    digest = hashlib.sha256()
    with historical(path).open("rb") as stream:
        for data in iter(lambda: stream.read(65536), b""):
            digest.update(data)
    return digest.hexdigest()


def integer(value, name, low=0, high=2**63-1):
    require(type(value) is int and low <= value <= high, "integer range: " + name)
    return value


def percentile(values, percent):
    return None if not values else sorted(values)[math.ceil(len(values)*percent/100)-1]


def distribution(nanos):
    return {"count": len(nanos), "p50Millis": percentile(nanos, 50)/1e6 if nanos else None,
            "p95Millis": percentile(nanos, 95)/1e6 if nanos else None,
            "p99Millis": percentile(nanos, 99)/1e6 if nanos else None,
            "maxMillis": max(nanos)/1e6 if nanos else None}


def planned(c,p=None):
    w = c["workload"]
    require(type(w["bootstrap"]) is bool and c["role"] in {"pilot", "target", "diagnostic", "soak", "cap-target", "cap-diagnostic-ab", "cap-smoke-s1", "cap-smoke-s2"}, "workload role/bootstrap")
    if c["role"]=="cap-diagnostic-ab":
        require(p is not None and p.get("schemaVersion")==4 and p.get("phase")=="CAP" and p.get("stage")=="CAP-1"
            and (w["writeRate"],w["measurementSeconds"]) in ((105,120),(105,1200),(500,60))
            and w["readRate"]==0 and w["wsConnections"]==100 and w["warmupSeconds"]==30,"bounded diagnostic analysis role")
    integer(w["measurementSeconds"], "measurementSeconds", 1, 86400)
    integer(w["warmupSeconds"], "warmupSeconds", 0, 86400)
    if w.get('schedule',{}).get('kind')=='users':
        require(w['warmupSeconds']==30 and w['schedule']['readPeriodSeconds']==max(60,(w['wsConnections']+99)//100),'CAP-3 read warmup/period contract')
    result = {}
    for kind in KINDS:
        rate = integer(w[kind+"Rate"], "rate", 0, 10000)
        for phase in PHASES+(("initial-read",) if w.get("schedule",{}).get("kind")=="users" else ()):
            count = cap.count(w,kind,phase)
            result[kind+"/"+phase] = count
        require(sum(v for k, v in result.items() if k.startswith(kind+"/")) <= (320000 if p is not None and __import__("phase18_cap1").expanded(p) else 200000), "terminal allocation bound")
    return result


def validate_row(r, c, plan_hash, producer, counts):
    require(type(r["schemaVersion"]) is int and r["schemaVersion"] == 1 and r["planHash"] == plan_hash
            and r["runId"] == c["runId"] and r["caseId"] == c["caseId"], "raw identity")
    kind, phase, ordinal = r["kind"], r["scheduledPhase"], r["ordinal"]
    require(kind in KINDS and kind+"/"+phase in counts, "raw namespace")
    integer(ordinal, "ordinal", 0, counts[kind+"/"+phase]-1)
    require(r["requestId"] == f'{c["runId"]}/{c["caseId"]}/{kind}/{phase}/{ordinal}', "planned ID")
    rate = c["workload"][kind+"Rate"]
    offset = 0 if phase == "bootstrap" else cap.offset(c["workload"],kind,ordinal,phase)
    require(r["scheduledOffsetNanos"] == offset and r["clockId"] == producer["clockId"], "raw schedule/clock")
    if kind=='read' and c['workload'].get('schedule',{}).get('kind')=='users' and r['sendState']=='sent':
        require((r['x'],r['y'])==cap.read_pixel(c['workload'],phase,ordinal),'CAP-3 fixed user viewport')
    require(r["status"] in STATUSES and r["sendState"] in {"sent", "not_sent", "unknown"}
            and r["outcome"] in {"ACCEPTED", "REJECTED", "UNKNOWN", "NOT_SENT"}, "terminal status")
    decided = integer(r["decidedNanos"], "decision", -(2**63))
    anchor = producer.get("anchors", {}).get(phase)
    due = None if anchor is None else anchor["nanos"]+offset
    if r["status"] == "client_not_sent":
        require(r["sendState"] == "not_sent" and r["sentNanos"] is None and r["completedNanos"] is None
                and r["outcome"] == "NOT_SENT" and r["httpStatus"] is None and r["reason"], "not_sent evidence")
        if due is None or decided < due:
            require(r["reason"] in {"stopped", "producer_failure"}
                    and (producer.get("stopped") or producer.get("runStatus") == "INCOMPLETE"
                         or producer.get("control", {}).get("state") == "STOPPED"), "early not_sent without interruption")
    else:
        require(due is not None and r["sendState"] != "not_sent", "missing sent clock")
        sent = integer(r["sentNanos"], "sent", -(2**63))
        completed = integer(r["completedNanos"], "completed", -(2**63))
        require(completed >= sent >= due and decided <= completed, "sent/completion time order")
        code = r["httpStatus"]
        if r["status"] == "accepted":
            require(r["outcome"] == "ACCEPTED" and r["sendState"] == "sent" and code == 200, "accepted contract")
            integer(r["tileVersion"], "version")
            if kind == "write":
                integer(r["eventSeq"], "eventSeq", 1)
        elif r["status"] in {"timeout", "network_error", "malformed_200", "unexpected_status"}:
            require(r["outcome"] == "UNKNOWN", "unknown outcome contract")
        if r["status"] in {"timeout", "network_error"}:
            require(code is None, "transport failure HTTP status")
        if r["status"] == "malformed_200":
            require(code == 200, "malformed response status")
        if r["status"] == "server_5xx":
            require(type(code) is int and 500 <= code < 600 and r["outcome"] in {"UNKNOWN", "REJECTED"}, "5xx status")
        if r["status"] == "unexpected_status":
            require(type(code) is int and code != 200 and not 400 <= code < 600, "unexpected HTTP status")
        if r["status"] in {"rejected_429", "rejected_4xx"}:
            require(type(code) is int and 400 <= code < 500 and r["outcome"] == "REJECTED", "4xx status")
        if r["status"] == "rejected_429":
            require(code == 429, "429 status")
    require(r["resolution"] == ("unresolved" if r["outcome"] == "UNKNOWN" else "not_applicable"), "original resolution must not be rewritten")
    return due


def input_fulfillment(c, rows, start, end):
    w = c["workload"]
    window = integer(w["inputWindowSeconds"], "inputWindowSeconds", 1, w["measurementSeconds"])*NANO
    ppm = integer(w["inputTolerancePpm"], "inputTolerancePpm", 0, 1000000)
    buckets = collections.Counter((r["kind"], (r["sentNanos"]-start)//window) for r in rows
                                  if r["sendState"] == "sent" and r["sentNanos"] is not None and start <= r["sentNanos"] < end)
    intervals = []
    for ordinal, left in enumerate(range(start, end, window)):
        right = min(end, left+window)
        entry = {"startOffsetNanos": left-start, "endOffsetNanos": right-start}
        for kind in KINDS:
            count = buckets[kind, ordinal]
            expected = w[kind+"Rate"]*(right-left)/NANO
            entry[kind] = {"sent": count, "expected": expected,
                           "state": "NOT_APPLICABLE" if expected == 0 else "MET" if abs(count-expected)*1000000 <= expected*ppm else "MISSED"}
        intervals.append(entry)
    simultaneous, longest = 0, 0
    for entry in intervals:
        if all(entry[k]["state"] == "MET" for k in KINDS):
            simultaneous += entry["endOffsetNanos"]-entry["startOffsetNanos"]
            longest = max(longest, simultaneous)
        else:
            simultaneous = 0
    states = {k: "NOT_APPLICABLE" if w[k+"Rate"] == 0 else
              "MET" if all(i[k]["state"] == "MET" for i in intervals) else "MISSED" for k in KINDS}
    return {"perKind": states, "windows": intervals, "longestConcurrentInputSeconds": longest/NANO,
            "requiredConcurrentSeconds": w["simultaneousSeconds"],
            "met": "MISSED" not in states.values() and longest >= w["simultaneousSeconds"]*NANO,
            "notSentReasons": dict(collections.Counter(r["reason"] for r in rows if r["status"] == "client_not_sent" and r["scheduledPhase"] == "measurement")),
            "cause": "arrival_late indicates generator delay; processing_capacity alone cannot distinguish server saturation from callback/generator cost"}


def aggregate(c, rows, producer, counts):
    if c['role'].startswith('cap-'):return cap.aggregate(c,rows,producer,counts,distribution)
    anchors = producer.get("anchors", {})
    cohorts = {}
    for kind in KINDS:
        for phase in PHASES:
            subset = [r for r in rows if r["kind"] == kind and r["scheduledPhase"] == phase]
            cohorts[kind+"/"+phase] = {"planned": counts[kind+"/"+phase], "terminal": len(subset),
                "statuses": dict(collections.Counter(r["status"] for r in subset)),
                "sendStates": dict(collections.Counter(r["sendState"] for r in subset))}
    measurement = anchors.get("measurement")
    if measurement is None:
        return {"cohorts": cohorts, "measurement": None, "inputFulfillment": {"met": None, "state": "UNKNOWN"}}
    start, end = measurement["nanos"], measurement["endNanos"]
    require(end-start == c["workload"]["measurementSeconds"]*NANO, "measurement clock duration")
    seconds = (end-start)/NANO
    metrics = {}
    for kind in KINDS:
        subset = [r for r in rows if r["kind"] == kind]
        sent = [r for r in subset if r["sendState"] == "sent" and r["sentNanos"] is not None and start <= r["sentNanos"] < end]
        accepted = [r for r in sent if r["status"] == "accepted"]
        completion = [r for r in subset if r["status"] == "accepted" and start <= r["completedNanos"] < end]
        rtt = [r["completedNanos"]-r["sentNanos"] for r in accepted]
        delay = [r["sentNanos"]-anchors[r["scheduledPhase"]]["nanos"]-r["scheduledOffsetNanos"] for r in sent]
        scheduled = [r["completedNanos"]-anchors[r["scheduledPhase"]]["nanos"]-r["scheduledOffsetNanos"] for r in accepted]
        not_sent = sum(r["status"] == "client_not_sent" and r["scheduledPhase"] == "measurement" for r in subset)
        metrics[kind] = {"completionAccepted": len(completion), "completionAcceptedPerSecond": len(completion)/seconds,
            "sent": len(sent), "sentCohortAccepted": len(accepted), "sentCohortAcceptedPerSecond": len(accepted)/seconds,
            "statuses": dict(collections.Counter(r["status"] for r in sent)), "acceptedRtt": distribution(rtt),
            "sendDelay": distribution(delay), "scheduledToAcceptedResponse": distribution(scheduled),
            "systemErrorNumerator": sum(r["status"] in SYSTEM_ERRORS for r in sent),
            "systemErrorDenominator": len(sent),
            "systemErrorRate": sum(r["status"] in SYSTEM_ERRORS for r in sent)/len(sent) if sent else None,
            "clientNotSent": not_sent, "planned": counts[kind+"/measurement"],
            "clientNotSentRate": not_sent/counts[kind+"/measurement"] if counts[kind+"/measurement"] else None}
        if kind == "read":
            wire = [r.get("wireBytes") for r in accepted]
            metrics[kind]["gzipBytes"] = {"raw": len(accepted)*65536,
                "wire": sum(wire) if all(type(n) is int and n >= 0 for n in wire) else None,
                "meaning": "accepted response bytes; gzip CPU is not inferred from compression ratio"}
    return {"cohorts": cohorts, "measurement": {"startNanos": start, "endNanos": end, "seconds": seconds, "metrics": metrics},
            "inputFulfillment": input_fulfillment(c, rows, start, end)}


def ws_evidence(run, c, rows, producer):
    connections = c["workload"]["wsConnections"]
    if connections == 0:
        return {"complete": True, "reason": "disabled", "mismatch": []}
    problems = []
    expected = {r["eventSeq"]: {k: r[k] for k in ("eventSeq", "tileVersion", "x", "y", "color")}
                for r in rows if r["kind"] == "write" and r["scheduledPhase"] != "bootstrap" and r["status"] == "accepted"}
    canonical = []
    try:
        with (run/"ws-canonical.jsonl").open("rb") as stream:
            while line := stream.readline(2049):
                require(len(line) <= 2048 and len(canonical) < 200000, "WS bound")
                canonical.append(decode(line))
        actual = {r["eventSeq"]: r for r in canonical}
        if len(actual) != len(canonical) or actual != expected:
            problems.append("WS canonical payload/set mismatch")
        count = cap.count(c["workload"],"write","warmup")+cap.count(c["workload"],"write","measurement")
        words = (count+63)//64
        path = run/"ws-received.bin"
        require(path.stat().st_size == connections*words*8, "WS bitset bytes")
        data = path.read_bytes()
        for connection in range(connections):
            for word in range(words):
                actual_word = struct.unpack_from(">Q", data, (connection*words+word)*8)[0]
                bits = max(0, min(64, len(canonical)-word*64))
                if actual_word != (1 << bits)-1:
                    problems.append("WS connection bitset mismatch")
                    break
        if not producer.get("wsCallbacksDrained"):
            problems.append("WS close callbacks incomplete")
    except (OSError, Invalid, KeyError, ValueError):
        problems.append("WS raw evidence missing/invalid")
    return {"complete": not problems, "mismatch": problems, "expectedUnique": len(expected),
            "observedUnique": len(canonical), "duplicates": producer.get("ws", {}).get("duplicates"),
            "meaning": "per-connection observed set; no new global order/exactly-once guarantee"}


EVIDENCE_ERRORS = (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError)


class EvidenceCheck:
    """필수 파일별 실패 수집. 뒤의 손상/누락이 앞서 얻은 유효 지표와 최초 실패를 지우지 않도록 분리."""
    def __init__(self, root):
        self.root = root
        self.missing, self.mismatch, self.failures, self.hashes = [], [], [], {}

    def check(self, reason, predicate):
        try:
            require(predicate(), reason)
            return True
        except EVIDENCE_ERRORS as error:
            self.mismatch.append(reason + (": " + type(error).__name__ if not isinstance(error, Invalid) else ""))
            return False

    def artifact(self, name, shape=dict, limit=16_777_216):
        path = self.root/name
        if not path.is_file():
            self.missing.append(name)
            return shape()
        try:
            self.hashes[name] = sha(path)
            value = read(path, limit)
            require(isinstance(value, shape), "artifact shape")
            return value
        except EVIDENCE_ERRORS as error:
            self.mismatch.append(name + ": " + type(error).__name__)
            return shape()

    def hash_matches(self, name, declared):
        def match():
            self.hashes[name] = sha(self.root/name)
            return declared == self.hashes[name]
        return self.check("hash mismatch: " + name, match)

    def process(self, directory, expected, parent_pid=None):
        identity = self.artifact(directory + "/identity.json")
        exited = self.artifact(directory + "/exit.json")
        # 실제 종료 실패/미해결은 성공 receipt와 모순돼도 실행 실패로 보존
        if ((type(exited.get("exit")) is int and exited["exit"] != 0)
                or exited.get("unresolved") is True
                or isinstance(exited.get("failureType"), str) and bool(exited["failureType"])):
            self.failures.append({"artifact": directory + "/exit.json", "evidence": exited})
        self.check(directory + " identity", lambda: integer(identity["pid"], "pid", 1)
                   and bool(identity["startUtc"]) and all(identity[k] == exited[k] == expected[k] for k in ("pid", "startUtc"))
                   and (parent_pid is None or identity["parentPid"] == parent_pid))
        self.check(directory + " termination", lambda: type(exited.get("exit")) is int and exited["exit"] == 0
                   and exited.get("unresolved") is False and isinstance(exited.get("failureType"), str)
                   and not exited["failureType"])
        return identity

    def report(self):
        return {"complete": not (self.missing or self.mismatch or self.failures), "missing": self.missing,
                "mismatch": self.mismatch, "failures": self.failures, "hashes": self.hashes}


def role_evidence(run, plan_hash, c, rows, producer, role, observation_required, plan, k6=None):
    check = EvidenceCheck(run)
    # 서버의 예정 HTTP별 유한 활동 증거. SOAK도 전역16MiB 제한 때문에 정상 자료를 잃지 않도록 요청량에 예약
    activity_limit = 1_048_576 + (sum(planned(c, plan).values()) + 4) * 1024
    manifest = check.artifact("manifest.json")
    consistency = check.artifact("consistency.json")
    drains = check.artifact("drain.json", list, activity_limit)
    server = check.artifact("server-closed.json", limit=activity_limit)
    runner = check.artifact("runner-result.json")
    runtime = check.artifact("runtime.json")
    check.process("../" + role + "-process", manifest)
    if type(runner.get("exit")) is int and runner["exit"] != 0 or runner.get("success") is False:
        check.failures.append({"artifact": "runner-result.json", "evidence": runner})
    check.check("runner receipt", lambda: runner.get("success") is True and type(runner.get("exit")) is int
                and runner["exit"] == 0 and all(runner[k] == manifest[k] for k in ("pid", "startUtc")))
    check.hash_matches("manifest.json", runner.get("manifestHash"))
    check.check("manifest identity/completion", lambda: manifest["schemaVersion"] == (2 if k6 is not None else 1)
                and manifest["planHash"] == plan_hash and manifest["initialAction"] == role
                and manifest["complete"] is True and manifest["normalShutdown"] is True)
    check.check("frozen input identity", lambda: manifest["inputHash"] == hashlib.sha256(
                json.dumps(plan["inputs"], ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()
                and all(manifest[k] == plan["ownership"][k] for k in ("wal", "catalog"))
                and manifest["environmentIdentity"] == plan["ownership"]["dbInstance"]
                and all(manifest[k] == c["runtime"][k] for k in ("mode", "segmentBytes")))
    check.check("runtime identity", lambda: runtime["schemaVersion"] == 1
                and all(runtime[k] == manifest[k] for k in ("pid", "startUtc", "planHash", "wal", "catalog", "mode", "segmentBytes")))
    for name, field in ((("k6-records.jsonl" if role == "initial" else "../initial/k6-records.jsonl") if k6 is not None else ("raw.jsonl" if role == "initial" else "../initial/raw.jsonl"), "recordsHash" if k6 is not None else "rawHash"),
                        ("consistency.json", "consistencyHash"), ("drain.json", "drainHash")):
        check.hash_matches(name, manifest.get(field))
    check.check("consistency counts/completion", lambda: consistency["complete"] is True
                and consistency["accepted"] == (k6["acceptedWrites"] if k6 is not None else sum(r["kind"] == "write" and r["status"] == "accepted" for r in rows))
                and consistency["reads"] == (k6["acceptedReads"] if k6 is not None else sum(r["kind"] == "read" and r["status"] == "accepted" for r in rows))
                and consistency["checkpoint"] == manifest["checkpoint"])
    drain_complete = check.check("drain state", lambda: bool(drains) and all(d["ready"] is True and d["pending"] is False
                and d["executor"]["state"] == "RUNNING"
                and all(d["executor"][k] == 0 for k in ("outstanding", "queued", "claimedUnsettled", "workerInFlight", "unpublishedTerminal", "activeCount"))
                and d["capture"]["records"] == 0 and d["capture"]["checkpoint"] == d["capture"]["tail"]
                and d["capture"]["observedNanos"] >= d["idleSince"] for d in drains)
                and drains[-1]["capture"]["checkpoint"] == consistency["checkpoint"])
    sent_ids = (k6["sentIds"] if k6 is not None else {r["requestId"] for r in rows if r["sendState"] == "sent"}) if role == "initial" else set()
    closed = check.check("server termination", lambda: server["schedulerTerminated"] is True
                and sent_ids <= server["requests"].keys()
                and all(v["completed"] >= v["started"] > 0 for v in server["requests"].values()))
    if role == "initial" and k6 is None:
        identity = check.process("producer-process", producer.get("runtime", {}), manifest.get("pid"))
        check.check("producer clock", lambda: producer["clockId"] == f'{identity["pid"]}/{identity["startUtc"]}')
    # 최초 원인 파일은 삭제/성공 플래그에 덮이지 않고 실제 내용과 해시로 함께 보존
    for name in ("failure.json", "producer-failure.json", "producer-failure-final.json", "stop.json", "upstream-stop.json"):
        if (run/name).is_file():
            check.failures.append({"artifact": name, "evidence": check.artifact(name)})
    observation = observation_summary(run, plan_hash, observation_required, role, c, producer, k6, plan)
    result = check.report()
    result.update(manifest=manifest, drainComplete=drain_complete and closed, observation=observation,
                  readSamples=consistency.get("readSamples"))
    result["complete"] = result["complete"] and observation["complete"] is not False
    return result


def evidence(run, plan_hash, c, rows, producer, plan):
    observation_required = plan["stage"] != "A-2"
    initial = role_evidence(run, plan_hash, c, rows, producer, "initial", observation_required, plan)
    recovery = role_evidence(run.parent/"recovery", plan_hash, c, rows, producer, "recovery", observation_required, plan)
    linkage = EvidenceCheck(run)
    linkage.check("same initial/recovery identity", lambda: all(initial["manifest"][k] == recovery["manifest"][k]
        for k in ("planHash", "rawHash", "inputHash", "runtimeReceiptHash", "catalog", "wal", "mode", "segmentBytes", "environmentIdentity", "checkpoint")))
    if observation_required:
        linkage.check("same observation policy", lambda: initial["manifest"]["observationPolicyHash"] == recovery["manifest"]["observationPolicyHash"])
    try:
        ws = ws_evidence(run, c, rows, producer)
    except EVIDENCE_ERRORS as error:
        # WS 입력 형태 실패도 완료된 HTTP 하위 지표를 폐기하는 분석 예외로 바꾸지 않음
        ws = {"complete": False, "mismatch": ["WS evidence shape: " + type(error).__name__]}
    result = {"complete": False, "initialComplete": initial["complete"],
              "recoveryComplete": recovery["complete"] and linkage.report()["complete"],
              "drainComplete": initial["drainComplete"], "wsComplete": ws["complete"], "ws": ws,
              "roles": {"initial": initial, "recovery": recovery}, "missing": [], "mismatch": linkage.mismatch,
              "failures": [], "hashes": {}, "readSamples": initial["readSamples"], "allReadBytesVerified": None,
              "readMeaning": "gzip/length validated by producer; version/content only reported samples, never infer full coverage from hashes"}
    for role, part in result["roles"].items():
        for section in (part, part["observation"]):
            for key in ("missing", "mismatch"):
                result[key].extend(role + "/" + item for item in section.get(key, []))
            result["failures"].extend(dict(item, role=role) for item in section.get("failures", []))
            result["hashes"].update({role + "/" + key: value for key, value in section.get("hashes", {}).items()})
    result["complete"] = bool(initial["complete"] and result["recoveryComplete"] and ws["complete"]
                               and not (result["missing"] or result["mismatch"] or result["failures"]))
    return result


def observation_summary(run, plan_hash, required, role="initial", c=None, producer=None, k6=None, plan=None):
    if not required and not (run/"collector.json").is_file():
        return {"complete": None, "reason": "A-2 has no A-3 observation contract", "missing": []}
    check = EvidenceCheck(run)
    collector = check.artifact("collector.json")
    clocks = check.artifact("phase-clocks.json")
    manifest = check.artifact("manifest.json")
    cap1 = bool(plan and plan.get('phase')=='CAP' and plan.get('stage')=='CAP-1'
                    and plan.get('bounds',{}).get('samples',0)>10000)
    expanded = cap1 or bool(plan and plan.get('phase')=='CAP' and c and c.get('role')=='cap-smoke-s2'
                    and plan.get('bounds',{}).get('samples',0)>10000)
    cap1_limits = None
    if cap1:
        from phase18_cap1 import profile
        cap1_limits=profile(c['workload']['writeRate'],c['workload']['measurementSeconds'])['limits']
        require(plan['bounds']['samples']==cap1_limits['samples'] and plan['bounds']['outputBytes']==cap1_limits['runBytes'],'CAP1 frozen analysis allocation')
    observation_limit = 16_777_216
    if expanded:
        # 커지는 trace 파일에만 검증된 원본 policy 한도 적용. self-declaration으로 parser 확대 금지.
        policy_path=run.parent.parent/'policy-cap.json'
        try:
            frozen=read(policy_path)
            require(sha(policy_path)==manifest['observationPolicyHash'],'frozen observation policy hash')
            require(frozen['planHash']==plan_hash and frozen['caseId']==c['caseId'],'frozen observation policy identity')
            require(type(frozen['maxTraceBytes']) is int and frozen['maxTraceBytes']==(cap1_limits['traceBytes'] if cap1 else 52_428_800),'supported frozen trace allocation')
            observation_limit=frozen['maxTraceBytes']+1048576
        except EVIDENCE_ERRORS:
            check.mismatch.append('frozen observation policy')
    observation = check.artifact("observation.json",limit=observation_limit)
    trace = observation.get("trace", {})
    result = {"collector": collector}
    check.check("observation identity", lambda: collector["schemaVersion"] == observation["schemaVersion"] == 1
                and collector["planHash"] == observation["policy"]["planHash"] == plan_hash
                and observation["policy"]["schemaVersion"] == 1
                and observation["policy"]["caseId"] == c["caseId"]
                and observation["policyHash"] == manifest["observationPolicyHash"])
    check.hash_matches("collector.json", manifest.get("collectorHash"))
    check.hash_matches("observation.json", manifest.get("observationHash"))
    check.check("collector completion", lambda: collector["complete"] is True
                and type(collector["observerFailures"]) is int and collector["observerFailures"] == 0 and collector["failure"] is None)
    if (isinstance(collector.get("failure"), str) and bool(collector["failure"])
            or type(collector.get("observerFailures")) is int and collector["observerFailures"] > 0):
        check.failures.append({"artifact": "collector.json", "evidence": {
            "failure": collector.get("failure"), "observerFailures": collector.get("observerFailures")}})
    check.check("trace completion", lambda: trace["schemaVersion"] == 1 and trace["complete"] is True and trace["loss"] == 0)
    if expanded:
        check.check('trace allocation',lambda: type(trace['chargedBytes']) is int and 0<=trace['chargedBytes']<=observation['policy']['maxTraceBytes']
                    and len(trace['journal'])<=observation['policy']['maxTraceEntries']
                    and len(trace['totals'])<=observation['policy']['maxTraceEntries'])
    server_clock = f'{manifest.get("pid")}/{manifest.get("startUtc")}'
    check.check("observation clock", lambda: trace["clockId"] == clocks["clockId"] == server_clock)
    samples_by_role = {}
    receipts = [("server", collector, "resource-samples.jsonl", server_clock)]
    if role == "initial" and k6 is None:
        client = check.artifact("client-collector.json")
        check.hash_matches("client-collector.json", manifest.get("clientCollectorHash"))
        check.check("client collector identity/completion", lambda: client["schemaVersion"] == 1
                    and client["planHash"] == plan_hash and client["complete"] is True)
        receipts.append(("client", client, "client-resources.jsonl", producer.get("clockId")))
    for kind, receipt, filename, clock_id in receipts:
        try:
            policy = observation["policy"]
            # receipt 자체의 선언으로 파일 한도를 늘리지 않음. frozen policy 원본과 manifest까지 대조
            if plan and "bounds" in plan:
                limit = policy["maxSampleBytes"] if kind == "server" else plan["bounds"]["outputBytes"] // 4
                require(type(limit) is int and 4096 <= limit <= plan["bounds"]["outputBytes"], "resource policy bound")
                require(observation["policyHash"] == manifest["observationPolicyHash"], "resource policy hash")
                policy_path = run.parent.parent/"policy-cap.json" if plan.get("phase") == "CAP" else None
                if policy_path is not None:
                    require(sha(policy_path) == observation["policyHash"] and read(policy_path) == policy, "frozen resource policy")
                sample_limit = plan['bounds']['samples'] if expanded else min(10000, plan['bounds']['samples'])
                require(not expanded or sample_limit==(cap1_limits['samples'] if cap1 else 50000),'supported frozen sample allocation')
            else:
                limit, sample_limit = 16_777_216, 10000
            samples = read_samples(run/filename, plan_hash, limit, sample_limit, cap1=bool(cap1))
            samples_by_role[kind] = samples
            check.check(kind + " samples count/bytes/clock", lambda: len(samples) == receipt["samples"]
                        and (run/filename).stat().st_size == receipt["bytes"]
                        and all(s["clockId"] == clock_id for s in samples))
            check.hash_matches(filename, receipt.get("samplesHash"))
        except EVIDENCE_ERRORS as error:
            check.mismatch.append(filename + ": " + type(error).__name__)
    def metrics():
        samples = samples_by_role["server"]
        result["samples"] = len(samples)
        result["captureStates"] = dict(collections.Counter(s["safety"]["captureStatus"] for s in samples))
        result["heapPeakSampleBytes"] = max(s["heapUsed"] for s in samples)
        result["afterGc"] = [samples[-1]["afterGc"]]
        for sample in samples:
            if sample["safety"]["stop"]:
                check.failures.append({"artifact": "resource-samples.jsonl", "evidence": {
                    "nanoTime": sample["nanoTime"], "stop": sample["safety"]["stop"]}})
        if role == "initial" and k6 is None:
            client_samples = samples_by_role["client"]
            result["clientSamples"] = len(client_samples)
            result["clientHeapPeakSampleBytes"] = max(s["heapUsed"] for s in client_samples)
            result["afterGc"].append(client_samples[-1]["afterGc"])
        return True
    check.check("resource sample metrics", metrics)
    def transactions():
        result["transactions"] = []
        for event in trace["journal"]:
            require(event["completedNanos"] >= event["startedNanos"], "operation clock order")
            if event["operation"] == "execute":
                detail = dict(part.split("=", 1) for part in event["result"].split(";") if "=" in part)
                result["transactions"].append({"phase": event["phase"], "outcome": detail.get("outcome", "THREW"),
                    "records": int(detail["records"]) if "records" in detail else None,
                    "tiles": int(detail["tiles"]) if "tiles" in detail else None,
                    "startedNanos": event["startedNanos"], "completedNanos": event["completedNanos"],
                    "elapsedMillis": (event["completedNanos"]-event["startedNanos"])/1e6})
        result["committedEvents"] = sum(t["records"] for t in result["transactions"] if t["outcome"] == "COMMITTED")
        result["traceLoss"] = trace["loss"]
        return True
    check.check("trace transactions", transactions)
    def envelopes():
        require(clocks["schemaVersion"] == 1 and isinstance(clocks["messages"], list), "phase clock shape")
        for message in clocks["messages"]:
            require(message["clockId"] == clocks["clockId"] and message["direction"] in {"send", "receive"}
                    and message["afterNanos"] >= message["beforeNanos"], "phase clock identity/order")
        result["clockEnvelopes"] = clock_envelopes(clocks)
        if role == "initial" and k6 is None:
            for phase in ("warmup", "measurement"):
                if c["workload"][phase+"Seconds"] > 0:
                    envelope = result["clockEnvelopes"][phase]
                    require(envelope["parentLatestNanos"] >= envelope["parentEarliestNanos"], "phase envelope order")
        return True
    check.check("phase clocks", envelopes)
    # 역할별 필수 파일·해시·시계·상태의 마지막 검사 뒤에만 완료 확정
    result.update(check.report())
    return result


def read_samples(path, plan_hash, byte_limit=16_777_216, sample_limit=10000, cap1=False):
    require(type(byte_limit) is int and byte_limit > 0 and type(sample_limit) is int and 0 < sample_limit <= (250000 if cap1 else 50000), "resource limits")
    require(path.stat().st_size <= byte_limit, "resource bytes bound")
    result = []
    consumed = 0
    with path.open("rb") as stream:
        while line := stream.readline(65537):
            cap.check_deadline()
            consumed += len(line)
            require(consumed <= byte_limit, "resource bytes bound")
            require(len(line) <= 65536 and len(result) < sample_limit, "resource row bound")
            row = decode(line)
            require(row["planHash"] == plan_hash and row["schemaVersion"] == 1, "resource identity")
            if result:
                require(row["clockId"] == result[-1]["clockId"] and row["nanoTime"] > result[-1]["nanoTime"], "resource clock order")
            result.append(row)
    require(result, "empty resource evidence")
    return result


def clock_envelopes(clocks):
    result = {}
    for phase, sent, done in (("warmup", "WARMUP", "WARMUP_DONE"), ("measurement", "MEASURE", "SEND_DONE")):
        sends = [r for r in clocks["messages"] if r.get("direction") == "send" and r["message"] == sent]
        receives = [r for r in clocks["messages"] if r.get("direction") == "receive" and r["message"].startswith(done)]
        if sends and receives:
            result[phase] = {"parentEarliestNanos": sends[0]["beforeNanos"], "parentLatestNanos": receives[0]["afterNanos"],
                             "uncertaintyUpperMillis": (receives[0]["afterNanos"]-sends[0]["beforeNanos"])/1e6}
    result["meaning"] = "client anchor lies within parent control envelope; conservative bound, no subtraction of independent nanoTime origins"
    return result


def host_disks(path, devices):
    previous, totals = {}, {}
    missing, count = 0, 0
    require(path.stat().st_size <= 16_777_216, "host evidence bytes")
    with path.open("rb") as stream:
        while line := stream.readline(65537):
            require(len(line) <= 65536 and count < 10000, "host sample bound")
            count += 1
            sample = decode(line)
            if sample.get("status") != "observed":
                missing += 1
                previous.clear()
                continue
            for disk in sample["disks"]:
                name = disk["Name"]
                old = previous.get(name)
                previous[name] = disk
                value = totals.setdefault(name, {"seconds": 0, "readBytes": 0, "writeBytes": 0,
                       "reads": 0, "writes": 0, "readSeconds": 0, "writeSeconds": 0, "queueMaxSample": 0, "invalidIntervals": 0})
                value["queueMaxSample"] = max(value["queueMaxSample"], disk["CurrentDiskQueueLength"])
                if old is None:
                    continue
                frequency = disk["Frequency_PerfTime"]
                elapsed = disk["Timestamp_PerfTime"]-old["Timestamp_PerfTime"]
                fields = {"readBytes": "DiskReadBytesPersec", "writeBytes": "DiskWriteBytesPersec",
                          "reads": "AvgDisksecPerRead_Base", "writes": "AvgDisksecPerWrite_Base",
                          "readSeconds": "AvgDisksecPerRead", "writeSeconds": "AvgDisksecPerWrite"}
                differences = {key: disk[field]-old[field] for key, field in fields.items()}
                if frequency <= 0 or frequency != old["Frequency_PerfTime"] or elapsed <= 0 or any(n < 0 for n in differences.values()):
                    value["invalidIntervals"] += 1
                    continue
                value["seconds"] += elapsed/frequency
                for key, number in differences.items():
                    value[key] += number/frequency if key.endswith("Seconds") else number
    for value in totals.values():
        value["readMeanMillis"] = value["readSeconds"]*1000/value["reads"] if value["reads"] else None
        value["writeMeanMillis"] = value["writeSeconds"]*1000/value["writes"] if value["writes"] else None
    return {"samples": count, "missingSamples": missing, "devices": read(devices) if devices else None, "disks": totals,
            "scope": "entire host interval, physical disk all processes; _Total overlaps individual disks; no WAL-only attribution",
            "latencyMeaning": "delta average-timer ticks / frequency / delta base operations; reset/wrap intervals unavailable",
            "hash": sha(path)}


def analyze(plan_file, run, case_id=None):
    run = historical(run)
    p = read(plan_file, 1_048_576)
    temp = plain_absolute(p["ownership"].get("temp") or os.environ.get("TEMP", ""))
    require(temp.is_relative_to(Path("F:/pixel-place-work")), "analysis temporary files require F work root")
    os.environ["TEMP"] = os.environ["TMP"] = os.environ["SQLITE_TMPDIR"] = str(temp)
    is_cap = (p.get('schemaVersion') == 3 or p.get('schemaVersion') == 4 and p.get('stage') == 'CAP-1') and p.get('phase') == 'CAP' and p.get('stage') in {'CAP-0','CAP-1','CAP-2','CAP-3'}
    require(is_cap or type(p.get("schemaVersion")) is int and p["schemaVersion"] == 2 and p["stage"] in {"A-2", "A-3", "A-4", "B", "C", "D"}, "unsupported plan schema/stage")
    candidates = [c for c in p["cases"] if case_id is None or c["caseId"] == case_id]
    require(len(candidates) == 1, "select one case")
    c = candidates[0]
    require(is_cap == c['role'].startswith('cap-'),'CAP role/schema isolation')
    counts = planned(c,p); plan_hash = sha(plan_file)
    if (run/"k6-input.json").is_file():
        import phase18_k6
        import types
        report=phase18_k6.analyze(types.SimpleNamespace(**globals()), p, c, plan_hash, run)
        if c["role"]=="cap-diagnostic-ab":
            report["cap"].update(capacityPoint=False,diagnosticComparison=True,role=c["role"],
                meaning="bounded diagnostic A/B comparison; not CAP target certification")
        return report
    producer_file = next((run/n for n in ("producer-failure-final.json", "producer-failure.json", "producer.json") if (run/n).is_file()), None)
    require(producer_file is not None, "producer clock/evidence missing")
    producer = read(producer_file)
    require(producer.get("schemaVersion") == 1 and producer.get("planHash") == plan_hash, "producer identity")
    for phase, anchor in producer.get("anchors", {}).items():
        require(phase in PHASES or is_cap and phase=="initial-read" and c["workload"]["schedule"]["kind"]=="users", "phase anchor")
        begin = integer(anchor["nanos"], "anchor", -(2**63))
        end = integer(anchor["endNanos"], "end", -(2**63))
        require(end-begin == (0 if phase == "bootstrap" else ((c["workload"]["wsConnections"]+99)//100)*NANO if phase=="initial-read" else c["workload"][phase+"Seconds"]*NANO), "phase clock duration")
    failed = any((run/n).exists() for n in ("failure.json", "producer-failure.json", "producer-failure-final.json", "stop.json", "upstream-stop.json")) or producer.get("stopped", False)
    rows, seen, problems, unresolved = [], set(), [], []
    if is_cap:
        require(sum(counts.values())<=140000,'CAP analysis allocation TOOL_LIMIT')
        rows=cap.Rows(Path(p['ownership']['temp'])/('cap-rows-'+uuid.uuid4().hex+'.sqlite'))
    expected = {f'{c["runId"]}/{c["caseId"]}/{key}/{i}' for key, count in counts.items() for i in range(count)}
    raw = run/"raw.jsonl"
    require(raw.stat().st_size <= sum(counts.values())*2048+2048, "raw byte bound")
    with raw.open("rb") as stream:
        for number in range(sum(counts.values())+2):
            line = stream.readline(2049)
            if not line:
                break
            require(len(line) <= 2048, "raw line bound")
            r = decode(line)
            try:
                validate_row(r, c, plan_hash, producer, counts)
                require(r["requestId"] not in seen, "duplicate terminal")
                seen.add(r["requestId"]); rows.append(r)
            except (Invalid, KeyError, TypeError) as error:
                problems.append({"line": number+1, "reason": str(error) if isinstance(error, Invalid) else "raw shape"})
    known_unresolved = {}
    for value in producer.get("unresolved", []):
        require(value["requestId"] in expected and value["requestId"] not in known_unresolved
                and value["sendState"] in {"sent", "unknown"}, "producer unresolved identity")
        known_unresolved[value["requestId"]] = value
    for rid in sorted(expected-seen):
        unresolved.append(known_unresolved.get(rid, {"requestId": rid, "sendState": "unknown", "reason": "terminal_missing"}))
    for row in rows:
        if row["outcome"] == "UNKNOWN" or row["sendState"] == "unknown":
            unresolved.append(known_unresolved.get(row["requestId"], {"requestId": row["requestId"], "sendState": row["sendState"], "reason": row["status"]}))
    report = aggregate(c, rows, producer, counts)
    ev = evidence(run, plan_hash, c, rows, producer, p)
    ev["unresolved"] = unresolved
    report.update(schemaVersion=1, planHash=plan_hash, caseId=c["caseId"], runId=c["runId"],
                  originalPlanned=sum(counts.values()), observedTerminals=len(rows), problems=problems,
                  integrity=ev, rawHash=sha(raw), producerHash=sha(producer_file),
                  units={"timestamps": "producer clock nanoTime; never subtract server nanoTime", "latency": "milliseconds nearest-rank", "throughput": "accepted completion in [start,end) / measurement seconds"})
    collection = ev["roles"]["initial"]["observation"]
    report["observation"] = collection
    m = report["measurement"]
    contaminated = m is not None and any(r["scheduledPhase"] != "measurement" and r["completedNanos"] is not None and r["completedNanos"] >= m["startNanos"] for r in rows)
    if contaminated:
        problems.append({"reason": "pre-measurement work not drained"})
    write = None if m is None else m["metrics"]["write"]
    slo = None if write is None or not write["sent"] else (
        write["completionAcceptedPerSecond"] >= 100 and write["acceptedRtt"]["p95Millis"] is not None
        and write["acceptedRtt"]["p95Millis"] <= 100 and write["acceptedRtt"]["p99Millis"] <= 250 and write["systemErrorRate"] <= .001)
    report["writeSloMet"] = slo if c["role"] in {"target", "soak"} else None
    unexpected = any(r["status"] in {"unexpected_status", "rejected_429", "rejected_4xx", "malformed_200"} and not(is_cap and cap.rejected(r)) for r in rows)
    failed = failed or bool(ev["failures"])
    # 원시 완전성/실행 실패까지 판정한 후 최종 complete 확정. 늦은 누락이 true 아래 숨지 않음
    ev["complete"] = bool(ev["complete"] and not (failed or problems or unresolved) and m is not None)
    if failed:
        status = "EXECUTION_FAILED"
    elif problems or unresolved or not ev["complete"] or ev["missing"] or m is None:
        status = "MEASUREMENT_INVALID"
    elif c["role"] not in {"target", "soak"}:
        status = "VALID_NO_TARGET" if not unexpected else "MEASUREMENT_INVALID"
    else:
        status = "VALID_TARGET_MET" if slo and report["inputFulfillment"]["met"] and not unexpected else "VALID_TARGET_MISSED"
    report["status"] = status
    if is_cap:
        report['cap']=cap.evaluate(p,c,rows,producer,report,run,read,sha,distribution,require)
        report['capTemporaryRows']={'path':str(rows.path),'bytes':rows.path.stat().st_size}
        rows.close()
    report["limits"] = ["Historical plan is read as immutable evidence, not approved for replay with current source.",
                         "Performance, input fulfillment and consistency are independent fields.",
                         "UNKNOWN is unresolved unless separately proven; no terminal rewriting."]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True, type=Path)
    parser.add_argument("--run-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--case")
    parser.add_argument("--execution-expiry",type=int,help="live CAP controller original expiry; omit for historical analysis")
    parser.add_argument("--host-disk", type=Path)
    parser.add_argument("--devices", type=Path)
    args = parser.parse_args()
    plain_absolute(args.output)
    require(args.output.is_relative_to(EVIDENCE), "new analysis output requires F evidence root")
    require(not args.output.exists(), "fresh analysis output required")
    try:
        if args.execution_expiry is not None:
            frozen=read(args.plan)
            require(frozen['phase']=='CAP' and args.execution_expiry==frozen['cases'][0]['workload']['schedule']['expiresEpochMillis'],'CAP analysis original expiry')
            cap.set_deadline(args.execution_expiry)
        report = analyze(args.plan, args.run_dir, args.case)
        cap.check_deadline()
        if args.host_disk:
            report["hostDisk"] = host_disks(args.host_disk, args.devices)
        code = 0
    except (Invalid, ValueError, KeyError, TypeError, OSError) as error:
        report = {"schemaVersion": 1, "status": "INPUT_REJECTED", "reason": str(error) if isinstance(error, Invalid) else type(error).__name__}
        code = 2
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2, allow_nan=False)
    print(json.dumps({"analysisExit": code, "runStatus": report["status"], "output": str(args.output)}))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
