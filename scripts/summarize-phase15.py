"""해당 evidence manifest와 보존 source 기반 A21/C15·별도 smoke 독립 재계산. 부하 실행 없음."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
from datetime import datetime, timedelta

ROOT = Path("E:/pixel-place-phase15/phase15-a-20260918")


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def percentile(values, q):
    return sorted(values)[math.ceil(len(values) * q) - 1] if values else None


def resources(rows):
    cpu = [r["cpuCoresUsed"] for r in rows if r.get("cpuCoresUsed") is not None]
    return dict(samples=len(rows), cpuMeanCores=sum(cpu) / len(cpu) if cpu else None,
                cpuMaxCores=max(cpu, default=None), heapPeakBytes=max((r["heapUsed"] for r in rows), default=None),
                gcCount=sum(r.get("gcCountDelta") or 0 for r in rows),
                gcMillis=sum(r.get("gcMillisDelta") or 0 for r in rows))


def utc(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def disk_window(directory, trial_id, client, start, end):
    matches = list(directory.parent.glob("phase15-*-invoke*/" + trial_id + "-disk.json"))
    if len(matches) != 1:
        return dict(available=False, reason="OS disk evidence missing or ambiguous")
    # 같은 child JVM의 UTC/nanoTime 쌍으로만 창 계산. 서로 다른 JVM nanoTime 차감 금지
    anchor = next(t for t in client["controlTransitions"] if t["type"] == "received_MEASURE")
    beginning = utc(anchor["utc"]) + timedelta(microseconds=(start - anchor["nanoTime"]) / 1000)
    ending = beginning + timedelta(microseconds=(end - start) / 1000)
    rows = [r for r in read(matches[0]) if beginning <= utc(r["Utc"]) < ending]
    counters = collections.defaultdict(list)
    invalid = 0
    for row in rows:
        for sample in row["Samples"]:
            if sample["Status"] != 0:
                invalid += 1
            else:
                counters[sample["Counter"].rsplit("\\", 1)[-1]].append(sample["Value"])
    return dict(available=bool(rows), samples=len(rows), invalidCounterSamples=invalid,
                scope="E volume host-wide, one-second interval samples aligned by child UTC; other host work can contribute",
                counters={key:dict(mean=sum(values)/len(values), p95=percentile(values,.95), maximum=max(values)) for key,values in counters.items()},
                evidence=str(matches[0]))


def trial(directory, manifest):
    spec = manifest["spec"]
    case = spec["cases"][manifest["caseIndex"]]
    summary = read(directory / "summary.json")
    consistent = read(directory / "consistency.json")
    recovery = read(directory / "recovery-check.json")
    client = read(directory / "client-summary.json")
    metrics = read(directory / "server-metrics.json")
    samples = read(directory / "resource-samples.json")["server"]
    raw = [json.loads(line) for line in (directory / "client-results.jsonl").read_text(encoding="utf-8").splitlines()]
    measured = summary["measurement"]
    start, end = measured["windowStartNanos"], measured["windowEndNanos"]
    sent = [r for r in raw if r["sentNanos"] is not None and start <= r["sentNanos"] < end]
    successes = [r for r in sent if r["outcome"] == "accepted"]
    completed = [r for r in raw if r["outcome"] == "accepted" and start <= r["completedNanos"] < end]
    errors = [r for r in sent if r["outcome"] in ("server_5xx", "network_error", "timeout", "malformed_200")]
    planned = [r for r in raw if r["phase"] == "measurement"]
    missing = [r for r in planned if r["outcome"] == "client_not_sent"]
    checks = dict(summaryComplete=summary["complete"], shutdown=summary["normalShutdown"],
                  consistency=consistent["complete"] and consistent["mismatches"] == 0 and consistent["unknownUnresolved"] == 0,
                  recovery=recovery["complete"] and recovery["mismatches"] == 0 and not recovery["schemaSeedUsersReexecuted"],
                  client=client["success"] and not client["webSocketFailures"] and client["backgroundFailure"] is None,
                  metrics=not metrics["incomplete"],
                  allTerminalAttempts=len(raw) + 1 == manifest["plan"]["totalAttempts"],
                  sent=len(sent) == measured["sent"], planned=len(planned) == measured["planned"],
                  cohortAccepted=len(successes) == measured["cohortAccepted"],
                  completedAccepted=len(completed) == measured["completedAccepted"],
                  throughput=math.isclose(len(completed) / spec["measurementSeconds"], measured["acceptedPerSecond"]),
                  systemErrorRate=math.isclose(len(errors) / len(sent), measured["systemErrorRate"]) if sent else measured["systemErrorRate"] is None,
                  notSentRate=math.isclose(len(missing) / len(planned), measured["notSentRate"]),
                  wsConnections=len(client["messagesPerConnection"]) == case["webSocketSessions"],
                  wsPerConnection=all(n == consistent["accepted"] - 1 for n in client["messagesPerConnection"]),
                  fixtureFilesOnE=all(Path(p).resolve().is_relative_to(ROOT.resolve()) for p in manifest["fixtureFiles"].values()),
                  realPort=manifest["database"]["port"] == 3307)
    outcomes = collections.Counter(r["outcome"] for r in sent)
    if missing: outcomes["client_not_sent"] += len(missing)
    checks.update(outcomes=dict(outcomes) == measured["outcomes"],
                  windowDuration=end - start == spec["measurementSeconds"] * 1_000_000_000,
                  plannedFromRate=len(planned) == math.ceil(case["offeredRate"] * spec["measurementSeconds"]),
                  drainAccepted=sum(r["completedNanos"] >= end for r in successes) == measured["drainAccepted"],
                  roleUnchanged=summary["role"] == case["role"])
    for key, population, first, last in (
        ("successRtt", successes, "sentNanos", "completedNanos"),
        ("scheduledToResponse", successes, "scheduledNanos", "completedNanos"),
        ("sendDelay", sent, "scheduledNanos", "sentNanos")):
        values = [(r[last] - r[first]) / 1e6 for r in population]
        checks[key + "Samples"] = measured[key]["samples"] == len(values)
        for field, q in (("p50Millis", .5), ("p95Millis", .95), ("p99Millis", .99), ("maxMillis", 1)):
            actual, expected = measured[key][field], percentile(values, q)
            checks[key + field] = actual == expected if actual is None or expected is None else math.isclose(actual, expected, abs_tol=1e-8)
    force_count = sum(t["count"] for t in metrics["timers"] if t["operation"] == "record_force" and t["outcome"] == "success")
    if manifest["instrumentation"]:
        if manifest.get("writeMode", "single") == "single":
            checks["forcePerEvent"] = force_count == consistent["databaseEvents"]
    if manifest.get("stage", "A") != "A":
        checks.update(c_observations(directory, manifest, metrics, consistent))
        force_count = metrics.get("walCounts", {}).get("recordForces")
    phases = collections.Counter(r["phase"] for r in raw if r["outcome"] == "accepted")
    phases["prepare"] += 1
    checks["phaseEvents"] = dict(phases) == consistent["phaseEventCounts"]
    measure_samples = [r for r in samples if r["phase"] == "measurement"]
    captures = [r for r in measure_samples if r["captureStatus"] == "fresh" and r["capture"] is not None]
    cycles = {r["lastCycle"]["startedNanos"]: r["lastCycle"] for r in measure_samples if r["lastCycle"] is not None}
    durations = [(r["startedNanos"] - r["previousStartedNanos"]) / 1e6 for r in cycles.values() if r["previousStartedNanos"] > 0]
    client_samples = [r for r in client["resources"] if start <= r["nanoTime"] < end]
    missed_observations = []
    for attempt in missing:
        following = next((r for r in client["resources"] if r["nanoTime"] >= attempt["completedNanos"]), None)
        missed_observations.append(dict(reason=attempt["reason"], detectionLateMillis=(attempt["completedNanos"] - attempt["scheduledNanos"]) / 1e6,
                                        rawHttpOutstanding=sum(a["sentNanos"] is not None and a["sentNanos"] <= attempt["completedNanos"] < a["completedNanos"] for a in raw),
                                        followingSample=None if following is None else {k:following.get(k) for k in ("inFlight", "cpuCoresUsed", "gcCountDelta", "gcMillisDelta")}))
    outstanding, peak = 0, 0
    timeline = [(a["sentNanos"], 1) for a in raw if a["sentNanos"] is not None] + [(a["completedNanos"], -1) for a in raw if a["sentNanos"] is not None]
    for when, delta in sorted(timeline):
        outstanding += delta
        if start <= when < end: peak = max(peak, outstanding)
    return dict(trial=manifest["trialId"], case=manifest["caseIndex"], repetition=manifest["repetition"],
                role=case["role"], offeredRate=case["offeredRate"], ws=case["webSocketSessions"], pattern=case["pattern"],
                measurement=measured, independentChecks=checks, independentChecksPassed=all(checks.values()),
                writeMode=manifest.get("writeMode", "single"), matrixEntry=manifest.get("matrixEntry"),
                httpStatuses=dict(collections.Counter(str(r["httpStatus"]) for r in sent)),
                serviceUnavailableReasons=dict(collections.Counter(r["reason"] for r in sent if r["httpStatus"] == 503)),
                performanceCollection=metrics.get("performanceCollection", "A legacy timer contract"),
                notSentReasons=dict(collections.Counter(r["reason"] for r in missing)),
                consistency=consistent, recoveryPid=recovery["newJvmPid"], forceCount=force_count,
                clientResources=resources(client_samples), clientSampledMaxInFlight=max((r["inFlight"] for r in client_samples), default=None),
                rawHttpOutstandingPeak=peak,
                notSentObservations=missed_observations, tokenPreparationMillis=client["tokenPreparationNanos"] / 1e6,
                serverResources=resources(measure_samples), wsMessagesPerConnection=client["messagesPerConnection"],
                captureStatuses=dict(collections.Counter(r["captureStatus"] for r in measure_samples)),
                freshCaptureRecords=[r["capture"]["records"] for r in captures],
                captureMaxAgeMillis=max((r["captureAgeNanos"] / 1e6 for r in measure_samples if r["captureAgeNanos"] is not None), default=None),
                cycleStartIntervalMillis=durations if manifest['instrumentation'] else None,
                walMissing=sum(r["wal"]["status"] == "missing" for r in measure_samples),
                pendingSamples=sum(r["pending"] for r in measure_samples),
                disk=disk_window(directory, manifest["trialId"], client, start, end),
                timers=[dict(t, meanMillis=t["totalMillis"] / t["count"]) for t in metrics["timers"] if t["phase"] == "measurement"],
                budgetBefore=read(directory / "budget-before.json"), evidence=str(directory))


def fixed_matrix():
    def entry(i, role, mode, case, repeat, on=True):
        return dict(id=i, role=role, mode=mode, caseIndex=case, repetition=repeat, instrumentation=on)
    rows = [entry('low-' + m, 'low', m, 2, 0) for m in ('single', 'group')]
    for repeat in range(3):
        for case, pattern in enumerate(('same', 'spread')):
            for mode in (('group', 'single') if repeat == 1 else ('single', 'group')):
                name = f'r{repeat+1}-{pattern}-{mode}'
                rows.append(entry(name, 'target', mode, case, repeat))
                if (repeat, case, mode) == (1, 1, 'group'):
                    rows.append(entry(name + '-off', 'instrumentation', mode, case, repeat, False))
    return dict(version=1, entries=rows, smokes=[entry('smoke-'+m, 'smoke', m, 0, 0) for m in ('single', 'group')])


def executor_idle(s):
    return bool(s) and s.get('state') == 'RUNNING' and all(s.get(k) == 0 for k in
        ('queued', 'claimedUnsettled', 'workerInFlight', 'outstanding', 'unpublishedTerminal', 'activeCount'))


def coverage_checks(metrics, mode, events, enabled):
    c = metrics.get('walCounts') or {}
    required = ('singleCalls', 'batchCalls', 'batchRecords', 'completedRecords', 'recordForces',
                'coveredRecords', 'emptyForces', 'failedForces', 'rotations')
    numeric = all(type(c.get(k)) is int and c[k] >= 0 for k in required)
    checks = dict(safetyCountsPresent=numeric, instrumentationMatches=metrics.get('enabled') is enabled,
                  observationComplete=metrics.get('incomplete') is False and metrics.get('observerFailures') == 0
                                      and metrics.get('samplerFailure') is None)
    if not numeric:
        return checks
    checks['forceCoverage'] = (events > 0 and c['completedRecords'] == c['coveredRecords'] == events
        and c['recordForces'] > 0 and c['emptyForces'] == c['rotations'] and c['failedForces'] == 0)
    if mode == 'single':
        checks['singleCoverage'] = c['singleCalls'] == c['recordForces'] == events and c['batchCalls'] == c['batchRecords'] == 0
    elif mode == 'group':
        checks['groupCoverage'] = c['singleCalls'] == 0 and 0 < c['batchCalls'] <= events and c['batchRecords'] == events
    else:
        checks['knownMode'] = False
    sizes = c.get('batchSizes')
    timers = metrics.get('timers')
    if enabled:
        checks['performanceCollected'] = (metrics.get('performanceCollection') == 'collected' and isinstance(sizes, dict)
            and isinstance(timers, list) and len(timers) > 0)
        checks['distribution'] = (isinstance(sizes, dict) and all(1 <= int(k) <= 128 and type(v) is int and v > 0 for k, v in sizes.items())
            and sum(sizes.values()) == c['batchCalls'] and sum(int(k)*v for k, v in sizes.items()) == c['batchRecords'])
        checks['forceTimer'] = isinstance(timers, list) and sum(t['count'] for t in timers
            if t['operation'] == 'record_force' and t['outcome'] == 'success') == c['recordForces']
        expected_timers = dict(command=events, storage_append=c['singleCalls']+c['batchCalls'])
        expected_timers.update(dict(group_request=events,queue_wait=events,batch_core_call=c['batchCalls'],batch_core_inside=c['batchCalls'])
                              if mode=='group' else dict(core_call=events,core_inside=events))
        checks['requiredTimers'] = isinstance(timers,list) and all(sum(t['count'] for t in timers
            if t['operation']==operation and t['outcome']=='success')==count for operation,count in expected_timers.items())
    else:
        checks['explicitDisabled'] = (sizes is None and timers == [] and
            metrics.get('performanceCollection') == 'disabled: operation timers and batch-size distribution')
    return checks


def c_observations(directory, manifest, metrics, consistent):
    checks = coverage_checks(metrics, manifest['writeMode'], consistent['databaseEvents'], manifest['instrumentation'])
    checks['optionalSamples'] = optional_samples(metrics, read(directory / 'resource-samples.json')['server'], manifest['instrumentation'])
    runtime = read(directory / 'runtime.json')
    recovery = read(directory / 'recovery-runtime.json')
    for label, value in [('runtime', runtime), ('recoveryRuntime', recovery)]:
        checks[label] = (value.get('writeMode') == manifest['writeMode'] and value.get('writeSettings') == manifest['writeSettings']
            and value.get('executorBeans') == 1 and value.get('sharedMeasurementVerified') is True
            and value.get('sharedStorageVerified') is True and value.get('instrumentation') is manifest['instrumentation']
            and value.get('executorClass', '').endswith('GroupPixelWriteExecutor' if manifest['writeMode'] == 'group' else 'SinglePixelWriteExecutor'))
    checks['newJvm'] = runtime.get('pid') != recovery.get('pid')
    drains = read(directory / 'drain.json')
    checks['drain'] = drain_checks(drains)
    checks['fixedMatrix'] = manifest.get('matrix') == fixed_matrix()
    allowed = fixed_matrix()['smokes' if manifest['stage'] == 'C-3' else 'entries']
    entry = next((r for r in allowed if r['id'] == manifest.get('matrixEntry')), None)
    checks['matrixIdentity'] = entry is not None and all(manifest.get(k) == entry[v] for k, v in
        [('writeMode', 'mode'), ('caseIndex', 'caseIndex'), ('repetition', 'repetition'), ('instrumentation', 'instrumentation')])
    checks['fixedConditions'] = entry is not None and fixed_conditions(manifest,entry)
    return checks


def optional_samples(metrics, samples, enabled):
    expected={} if enabled else {k:'instrumentation off' for k in ('timers','batchSizes','lastCycle','broadcastFailures')}
    return metrics.get('disabledFields')==expected and bool(samples) and all(
        'lastCycle' in row and 'broadcastFailures' in row
        and (type(row['broadcastFailures']) is int and row['broadcastFailures']>=0 if enabled
             else row['lastCycle'] is None and row['broadcastFailures'] is None and row.get('timers')==[])
        for row in samples)


def drain_checks(drains):
    rows = drains.get('observations', [])
    return drains.get('complete') is True and len(rows) == 3 and all(
        executor_idle(r.get('executor')) and r.get('ready') is True and r.get('commandActive') == 0
        and r.get('pending') is False and r.get('servletActive') == 0
        and all(type(r.get(k)) is int and r[k] >= 0 for k in ('servletStarted','servletCompleted','expectedServlets'))
        and r.get('servletStarted') == r.get('servletCompleted') == r.get('expectedServlets')
        and r['capture']['records'] == 0 and r['capture']['observedNanos'] >= r['idleSince']
        and r['capture']['checkpoint'] == r['capture']['tail'] == r['checkpoint'] >= r['acceptedTail'] for r in rows)


def fixed_conditions(manifest,entry):
    s=manifest['spec'];c=s['cases'][manifest['caseIndex']];smoke=entry['role']=='smoke';low=entry['role']=='low'
    # 보존 C3 smoke와 승인된 C4 후보의 영향 smoke는 서로 다른 동결 승인 쌍으로 식별
    approval=manifest.get('budgetApproval',{})
    smoke_caps={(38654705664,31123978717):38654705664,(79456894976,36960813040):79456894976}
    cap=79456894976 if manifest.get('stage')=='C-4' else smoke_caps.get(
        (approval.get('maximumBytes'),approval.get('carriedChargeBytes')))
    expected=dict(warmupSeconds=2 if smoke else 30,measurementSeconds=5 if smoke else 120,repetitions=1 if smoke else 3,
                  maxInFlight=128,requestTimeoutSeconds=30,maxDrainSeconds=60,readyTimeoutSeconds=120,controlTimeoutSeconds=15,
                  maxAttemptsPerTrial=40000,maxFixtureUsers=40000,maxOfferedRate=200,maxTotalBytes=cap,
                  userMode='once',userPoolSize=None,reuseMarginMillis=1000,seed=15,sampleIntervalMillis=1000,
                  purpose='exploratory' if smoke else 'decision')
    if not smoke: expected.update(targetAcceptedWritesPerSecond=100,targetP95Millis=100,targetP99Millis=250,maxSystemErrorRate=.001)
    settings=dict(mode=entry['mode'],maxBatchSize=16,maxOutstanding=128,queueTimeoutMillis=1000,shutdownGraceMillis=10000,collectionWaitMillis=0)
    return (all(s.get(k)==v for k,v in expected.items()) and len(s['cases'])==(1 if smoke else 3)
            and c==dict(role='probe' if smoke or low else 'target',offeredRate=2 if smoke else 1 if low else 105,
                        webSocketSessions=2 if smoke else 0 if low else 100,
                        pattern='spread-tiles' if smoke or entry['caseIndex']==1 else 'same-pixel')
            and manifest.get('writeSettings')==settings)


def source_matches(identity, snapshot):
    root = Path(snapshot).resolve()
    for name, digest in identity.items():
        path = (root / name).resolve()
        if not path.is_relative_to(root) or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest.lower():
            return False
    return bool(identity)


def exact_entries(actual, family):
    expected = [e['id'] for e in fixed_matrix()['smokes' if family == 'smoke' else 'entries']]
    return actual == expected


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", default="e20260918")
    parser.add_argument("--output")
    parser.add_argument("--family", choices=['A', 'C', 'smoke'], default='A')
    parser.add_argument("--manifest", action='append', default=[])
    parser.add_argument("--a-source", default=str(ROOT / 'agent-runs/phase15-C-1-20260922-entry/source'))
    parser.add_argument("--validate-matrix")
    args = parser.parse_args()
    if args.validate_matrix:
        actual = read(Path(args.validate_matrix))
        if actual != fixed_matrix():
            raise ValueError('Fixed C matrix/order mismatch')
        print(json.dumps(dict(matrixValid=True, comparisonTrials=15, smokeTrials=2, loadExecuted=False)))
        return
    rows, identities, pending = [], [], []
    pattern = 'phase15-A-r*/manifest.json' if args.family == 'A' else 'phase15-C-*-r*/manifest.json'
    paths = [Path(p) for p in args.manifest] if args.manifest else sorted((ROOT / 'agent-runs').glob(pattern))
    sources_valid = []
    for path in paths:
        manifest = read(path)
        if manifest["session"] != args.session:
            continue
        expected_stage = {'A': 'A', 'C': 'C-4', 'smoke': 'C-3'}[args.family]
        if manifest.get('stage', 'A') != expected_stage:
            raise ValueError('A/C/smoke evidence families cannot be mixed')
        if args.family != 'smoke' and manifest['spec']['purpose'] != 'decision':
            continue
        if not (path.parent / "recovery-check.json").exists():
            pending.append(manifest["trialId"])
            continue
        rows.append(trial(path.parent, manifest))
        identities.append(dict(source=manifest["sourceIdentity"], spec=manifest["specSha256"], mysql=manifest["mysqlRuntimeConfigSha256"]))
        sources_valid.append(source_matches(manifest['sourceIdentity'], args.a_source if args.family == 'A' else manifest['sourceSnapshot']))
    pairs = [(r["case"], r["repetition"]) for r in rows]
    if args.family == 'A':
        exact = set(pairs) == {(c, r) for c in range(7) for r in range(3)} and len(pairs) == 21
    else:
        actual = [r['matrixEntry'] for r in rows]
        exact = exact_entries(actual, args.family)
    result = dict(session=args.session, completed=len(rows), pending=pending, trials=rows,
                  family=args.family, exactMatrix=exact,
                  identicalInputs=bool(identities) and all(i == identities[0] for i in identities), evidenceSourceMatches=bool(sources_valid) and all(sources_valid),
                  independentChecksPassed=all(r["independentChecksPassed"] for r in rows), performanceDecision="15-B not performed")
    if args.family == 'A':
        result['exact21Cases'] = exact
    else:
        result['performanceDecision'] = '15-C-4 comparison not decided by this analyzer'
    if args.output:
        output = Path(args.output).resolve()
        if not output.is_relative_to(ROOT.resolve()):
            raise ValueError("Evidence must remain on owned E root")
        with output.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in result.items() if k != "trials"}, ensure_ascii=False))
    for r in rows:
        m = r["measurement"]
        print(f"case={r['case']} repeat={r['repetition']} accepted/s={m['acceptedPerSecond']:.4f} p95={m['successRtt']['p95Millis']} p99={m['successRtt']['p99Millis']} sent={m['sent']}/{m['planned']} errors={m['systemErrorRate']} checks={r['independentChecksPassed']}")
    if pending or not all(result[k] for k in ['exactMatrix', 'identicalInputs', 'evidenceSourceMatches', 'independentChecksPassed']):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
