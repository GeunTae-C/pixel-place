"""2026-10-15 명령의 6+2회 비교만 기존 CAP 준비/발급에 연결. 정식 후보 gate 책임 없음."""
import json,hashlib,time
from pathlib import Path
from pixel_place_paths import historical,control_path,evidence_path
from phase18_cap1 import require,validate_profile,structural_support,require_ws_revised_scope
from phase18_cap_budget import load_chain

ROLE="cap-diagnostic-ab"
INSTRUCTION="b892240cf15c9cd4ff2f80fe1e86841bcc04680ff877938d2eba010a41cb8859"
ORIGINAL="phase18-cap1-w500-20261015-084327"
PLAN="b43d77dfc460fe28cd06be1f00c7ac8e67c1c99635eb9b612d1f74f04dfdb6a3"
PAIRS=((105,120),(105,1200),(500,60))
CORE="src/main/java/dev/cgt/pixelplace/wal/infra/SegmentedWalStorage.java"

def sha(p):
    with historical(p).open("rb") as f:return hashlib.file_digest(f,"sha256").hexdigest()
def read(p):return json.loads(historical(p).read_text(encoding="utf-8-sig"))
def bound(ref):
    require(sha(ref["path"])==ref["sha256"].lower(),"comparison evidence changed: "+ref["path"])
    return read(ref["path"])
def ref(p):return dict(path=str(p),sha256=sha(p))

def closed(owner):
    require(owner["Released"] and owner["Failure"] is None and owner["Workers"]
        and all(x["Joined"] and x["ActualExit"]==0 and not x["FirstFailure"] and not x["CleanupFailure"]
                and x["DbUsersClosed"] for x in owner["Workers"]),"comparison owned workers unresolved")

def production_changes(source,original):
    require({k for k in source if k.startswith("src/main/")}==set(original),
        "comparison production file set changed")
    return {k for k,v in original.items() if source[k]!=v}

def recovery(contract,repo):
    """원래 45건·전체 효과와 새 JVM·원본/사본 소유 마감을 서로 대조."""
    r=contract["recovery"]
    d=bound(r["diagnosis"]);before=bound(r["before"]);after=bound(r["after"]);preserved=bound(r["preservation"])
    owner=bound(r["owner"]);sql=bound(r["mysqlStop"]);budget=bound(r["budget"]);runtime=bound(r["runtime"])
    classes=bound(r["classes"]);copy=bound(r["copy"]);copy_owner=bound(r["copyOwner"])
    require(d["originalPlanHash"]==PLAN and d["originalClassification"]=="INVALID_OR_STOPPED"
        and d["historicalHttpResponse"]=="UNRESOLVED" and d["diagnosticOnly"] and not d["ddlOrReseed"]
        and d["confirmedAccepted"]==53423 and d["notSent"]==55 and d["notSentEffectAbsence"]
        and d["unexplainedEffects"]==0 and d["serverDurableEvents"]==53468 and d["normalContextClose"],
        "specific W500 stopped-data diagnosis required")
    require(before==dict(checkpoint=53410,events=53410,tiles=1024,exactWalPrefix=True,tileBytesAndVersions=True,walTail=53468)
        and after==dict(checkpoint=53468,events=53468,tiles=1024,exactWalPrefix=True,tileBytesAndVersions=True,walTail=53468),
        "complete pre/post database prefix and tiles")
    consistency=d["consistency"]
    require(consistency["complete"] and consistency["canonicalMemory"]==consistency["databaseTiles"]==1024
        and consistency["checkpoint"]==consistency["accepted"]==consistency["retainedWalRecords"]==53468,"fresh JVM all-state consistency")
    require(preserved["complete"] and preserved["dbFiles"]==909 and preserved["walFiles"]==1
        and preserved["allSourceHashesUnchanged"] and not preserved["originalStarted"],"original preservation")
    closed(owner);closed(copy_owner)
    require(owner["NormalSqlStop"] and sql["NormalShutdown"] and sql["ShutdownObserved"]
        and sql["SqlExit"]==0 and not sql["Failures"] and budget["gateMet"] and budget["transition"]["closed"],"clone SQL and ledger close")
    require(copy["complete"] and copy["sourceFrozen"] and copy["originalUnchanged"] and len(copy["files"])==909
        and all(x["sha256"]==x["sourceAfterSha256"]==x["copySha256"] for x in copy["files"]),"whole frozen physical copy")
    receipt=bound(runtime["originalReceipt"])
    require(runtime["runtimeFilesVerified"]==len(receipt["runtime"])==486 and runtime["productionInputsVerified"]==165
        and not runtime["productionChanged"],"original production/runtime correspondence")
    for item in receipt["runtime"]:require(sha(item["path"])==item["sha256"],"original recovery runtime changed")
    source=runtime["diagnosticSource"]
    require(sha(source["path"])==source["sha256"]==classes["Source"].lower(),"executed diagnostic source")
    require(classes["Classes"] and all(sha(x["Path"])==x["Sha256"].lower() for x in classes["Classes"]),"executed diagnostic classes")
    request=bound(r["request"]);args=request["Arguments"]
    require(args[args.index("-cp")+1].split(";",1)[1]==runtime["classpath"]
        and request["ExpiresEpochMillis"]==owner["OriginalExpires"]
        and any(Path(x["Request"])==Path(r["request"]["path"]) and x["ActualExit"]==0 for x in owner["Workers"]),"fresh original JVM owner linkage")
    original=evidence_path(ORIGINAL);plan=read(original/"plan-cap.json")
    require(sha(original/"plan-cap.json")==PLAN,"original W500 Plan changed")
    initial=original/plan["cases"][0]["runId"]/"initial";producer=read(initial/"k6-producer.json")
    require(producer["firstFailure"]=="app_control_stop" and not producer["producerComplete"] and len(producer["unresolved"])==45,"original HTTP uncertainty preserved")
    requests=d["requests"]
    require(len(requests)==45 and {x["id"] for x in requests}=={x["id"] for x in producer["unresolved"]}
        and len({x["eventSeq"] for x in requests})==45
        and all(x["effect"]=="identified_effect_exists" and x["uniqueAmongAllPlannedWrites"]
            and x["historicalHttpResponse"]=="UNRESOLVED" and x["databaseAfter"] for x in requests),"all 45 individual effects")
    return plan

def slots(p,contract,attempts,now):
    """고정 조합·원래 예약·중복·6+2회 및 총 시간 경계. 실패 발급도 소비."""
    prof=validate_profile(p["cap1Profile"]);entry=p["comparison"]
    require(p["role"]==prof["role"]==ROLE and entry["variant"] in ("A","B"),"comparison role/variant")
    pair=(prof["writeRate"],prof["measurementSeconds"]);require(pair in PAIRS,"comparison allowed pair")
    require(type(entry["slot"]) is int and entry["slot"]==len(attempts)+1<=8,"comparison remaining unique slot")
    budget=bound(contract["taskBudget"])
    require(budget["instructionSha256"]==INSTRUCTION and budget["appSlots"]==8 and budget["mandatorySlots"]==6
        and budget["additionalPairMaximum"]==1 and budget["A_copyMillis"]==7200000
        and budget["A_recoveryMillis"]==3600000 and budget["selectionMillis"]==7200000
        and budget["totalMillis"]==60510000,"frozen whole-task budget")
    used=18000000;counts={};seen_sessions=set()
    for a,policy in attempts:
        old=policy["cap1Profile"];e=policy["comparison"];duration=old["totalSeconds"]*1000
        require(e["contract"]==entry["contract"] and a["SessionId"] not in seen_sessions
            and a["ExpiresEpochMillis"]-a["IssuedEpochMillis"]==duration,"original comparison issuance")
        seen_sessions.add(a["SessionId"]);used+=duration
        k=(e["variant"],old["writeRate"],old["measurementSeconds"]);counts[k]=counts.get(k,0)+1
    key=(entry["variant"],*pair)
    if counts.get(key,0):
        extra=entry.get("additionalPair")
        require(len(attempts)>=4 and extra and len(extra["reason"].strip())>=20
            and tuple(extra["pair"])==pair and counts[key]==1,"one reasoned additional pair only")
        repeated={k[1:] for k,n in counts.items() if n>1}
        require(not repeated or repeated=={pair},"no second additional pair")
    else:require(entry.get("additionalPair") is None,"additional flag requires prior exact combination")
    require(used+prof["totalSeconds"]*1000<=budget["totalMillis"],"comparison total reservation budget")
    whole_start=budget["copyOriginalExpires"]-budget["A_copyMillis"]
    require(whole_start<=now and now+prof["totalSeconds"]*1000<=whole_start+budget["totalMillis"],"comparison remaining absolute whole-task time")
    if entry["slot"]==1:require(key==("A",105,120),"comparison first short baseline")
    if pair!=(105,120):require(counts.get((entry["variant"],105,120),0)>0,"same variant short wiring first")
    if entry["variant"]=="B":require(counts.get(("A",105,1200),0)>0,"long baseline before changed production")

def verify_issued(p,issued,attempt,ledger,reader=read,digest=sha):
    entry=p["comparison"];contract=bound(entry["contract"])
    require(p["instruction"]["sha256"]==INSTRUCTION and issued["Role"]==attempt["Role"]==ROLE,"issued comparison authority")
    attempts=[]
    for path in control_path(ledger).glob("cap1-attempt-*.json"):
        a=reader(path);pol=bound(a["Policy"])
        if pol.get("role")==ROLE and a["SessionId"]!=issued["SessionId"]:attempts.append((a,pol))
    slots(p,contract,attempts,issued["issuedEpochMillis"])
    require(issued["SessionId"].startswith("phase18-cap1-diag-"+entry["variant"].lower()+"-w")
        and issued["expiresEpochMillis"]==attempt["ExpiresEpochMillis"],"issued comparison owner and expiry")

def verify_policy(path,repo,ledger):
    from phase18_cap1 import ANALYSIS_MEMORY
    repo=Path(repo);ledger=control_path(ledger);p=read(path);prof=validate_profile(p["cap1Profile"])
    require(p["schemaVersion"]==1 and p["stage"]=="CAP-1" and p["role"]==ROLE and not structural_support(prof),"bounded comparison profile")
    require(p["instruction"]["sha256"]==INSTRUCTION==sha(p["instruction"]["path"]),"exact comparison instruction")
    contract=bound(p["comparison"]["contract"]);require(contract["instruction"]==p["instruction"],"same comparison contract")
    original_plan=recovery(contract,repo)
    import xml.etree.ElementTree as ET
    selected=bound(contract["selection"]);selected_owner=bound(selected["owner"]);closed(selected_owner)
    selected_receipt=bound(selected["runtimeReceipt"])
    for x in selected_receipt["runtime"]:require(sha(x["path"])==x["sha256"],"selected runtime changed")
    selected_inputs={x["path"]:x["sha256"] for x in selected_receipt["inputs"]}
    require(all(selected_inputs.get(x["path"])==x["sha256"] for x in contract["commonInputs"]),"selected common compiled execution inputs")
    suites=set()
    for x in selected["xml"]:
        require(sha(x["path"])==x["sha256"],"selected XML changed")
        xml=ET.parse(historical(x["path"])).getroot();suites.add(xml.get("name"))
        require(int(xml.get("tests","0"))>0 and all(xml.get(k,"0")=="0" for k in ("failures","errors","skipped")),"selected role failure")
    require(suites=={"dev.cgt.benchmark.Phase18Cap1ComparisonTest","dev.cgt.benchmark.Phase18Cap1Test"},"exact selected Java role suites")
    require(selected["pythonValidator"]["sha256"]==sha(repo/"scripts/phase18_cap1_comparison.py"),"current Python validator selected binding")
    require(len(selected["pythonResults"])==3,"exact Python selection results")
    expected_patterns={"test_cap1*.py","test_k6_phase18.py","test_cap_phase18*.py"};actual_patterns=set()
    for x in selected["pythonResults"]:
        require(x["path"].endswith(".result.json"),"Python worker result path")
        request_path=x["path"][:-len(".result.json")]
        require(any(Path(w["Request"])==Path(request_path) for w in selected_owner["Workers"]),"Python selection owner linkage")
        request=read(request_path);args=request["Arguments"]
        require(request["Kind"]=="native" and "-m" in args and args[args.index("-m")+1]=="unittest"
            and "-p" in args,"actual selected Python request")
        actual_patterns.add(args[args.index("-p")+1])
        result=bound(x);require(result["Exit"]==0 and result["Joined"] and result["OutCompleted"] and result["ErrCompleted"] and not result["FirstFailure"],"selected Python actual result")

    require(actual_patterns==expected_patterns,"exact selected Python suites")

    inventory={q.relative_to(repo).as_posix() for folder in ("src","scripts") for q in (repo/folder).rglob("*") if q.is_file() and q.suffix.lower() not in (".zip",".pyc")}
    inventory.update(("build.gradle","settings.gradle","gradlew.bat","pixel_place.sql","docs/phase18-tools.md"))
    source={x["path"]:x["sha256"] for x in p["sourceBinding"]}
    require(set(source)==inventory and all(sha(repo/k)==v for k,v in source.items()),"complete current comparison source binding")
    common=contract["commonInputs"]
    require(all(source.get(x["path"])==x["sha256"] for x in common)
        and {x["path"] for x in common}=={k for k in source if k.startswith(("src/benchmark/","scripts/")) or k in ("build.gradle","settings.gradle","gradlew.bat","pixel_place.sql")},
        "unchanged common A/B execution tools")
    production={x["path"]:x["sha256"] for x in original_plan["inputs"] if x["path"].startswith("src/main/")}
    changed=production_changes(source,production)
    if p["comparison"]["variant"]=="A":require(not changed,"baseline uses original production")
    else:
        require(changed=={CORE},"one minimal production change")
        candidate=bound(p["comparison"]["candidate"])
        require(candidate["coreSha256"]==source[CORE] and candidate["baselineSha256"]==production[CORE],"changed core binding")
        closed(bound(candidate["owner"]));candidate_tests=bound(candidate["selected"])
        candidate_runtime=bound(candidate["runtimeReceipt"])
        candidate_inputs={x["path"]:x["sha256"] for x in candidate_runtime["inputs"]}
        require(candidate_inputs.get(CORE)==source[CORE]
            and all(candidate_inputs.get(x["path"])==x["sha256"] for x in common),"changed selected runtime source binding")
        for x in candidate_runtime["runtime"]:require(sha(x["path"])==x["sha256"],"changed runtime artifact")
        require(candidate_tests["failures"]==candidate_tests["errors"]==candidate_tests["skipped"]==0
            and candidate_tests["tests"]>0 and candidate_tests["xml"],"changed production required selected tests")
        for x in candidate_tests["xml"]:
            require(sha(x["path"])==x["sha256"],"changed selected XML")
            xml=ET.parse(historical(x["path"])).getroot()
            require(int(xml.get("tests","0"))>0 and all(xml.get(k,"0")=="0" for k in ("failures","errors","skipped")),"changed selected suite failed")
    for item in (p["k6"]["binary"],p["k6"]["script"]):require(sha(item["path"])==item["sha256"],"pinned comparison k6")
    support=bound(contract["wsSupport"])
    support_inputs={x["path"]:x["sha256"] for x in bound(support["receipt"])["inputs"]}
    transport=("Phase18K6Auth.java","Phase18K6Causal.java","Phase18K6Results.java","Phase18K6Session.java")
    transport_paths=["src/benchmark/java/dev/cgt/benchmark/"+x for x in transport]
    transport_paths+=["scripts/phase18-k6.js","scripts/phase18-k6-process.ps1","scripts/run-phase18-k6.ps1","scripts/phase18_k6.py"]
    require(all(source.get(k)==support_inputs.get(k) for k in transport_paths)
        and p["k6"]["binary"]["sha256"]==support["binary"]["sha256"],"unchanged executed WS and analyzer binding")
    require_ws_revised_scope(support,prof["writeRate"],repo,bound,sha,prof["kind"])
    original=evidence_path(ORIGINAL);old_policy=bound(original_plan["predecessor"])
    validation=read(original/"step-001-cap1-validation.request.json.native.stdout")
    original_close=bound(contract["originalClosure"])
    require(validation["valid"] and validation["profile"]==old_policy["cap1Profile"]
        and any(x["Request"].endswith("step-001-cap1-validation.request.json") and x["ActualExit"]==0 and x["Joined"]
            for x in original_close["OwnedWorkers"]),"previous complete historical validation")
    # 기존 26개 이력과 실패 원본을 유지. 검증된 과거 이력의 원본 refs/index가 달라지면 재사용 불가
    require(p["history"][:len(old_policy["history"])]==old_policy["history"],"historical attempts/resolutions not removed or rewritten")
    actual=list(ledger.glob("cap1-attempt-*.json"));require(len(p["history"])==len(actual),"all actual attempts retained")
    history_ids=set();comparisons=[];service=old_policy["serviceMillisBefore"];floor=old_policy["recordedServiceMillisFloorBefore"]
    original_seen=False
    for i,item in enumerate(p["history"]):
        close=bound(item["closure"]);plan=bound(item["plan"]);index=bound(close["EvidenceIndex"])
        require(close["SessionId"] not in history_ids and close["OwnedResourcesClosed"] and close["NormalMysqlStop"]
            and close["OwnedJava"]==[] and close["Remaining3307"]==0 and close["RemainingRunLock"] is False,"all prior ownership closed")
        history_ids.add(close["SessionId"])
        require(all(sha(x["path"])==x["sha256"].lower() for x in index["Files"]),"historical execution evidence changed")
        if i<len(old_policy["history"]):continue
        service+=close["ServiceMillis"];floor+=close["ServiceMillis"]
        if close["SessionId"]==ORIGINAL:
            require(item["closure"]==contract["originalClosure"] and item["plan"]["sha256"]==PLAN
                and close["Status"]=="FAILED","specific original failure remains failed");original_seen=True;continue
        require(plan["cases"][0]["role"]==ROLE and close["Role"]==ROLE,"new history must be this comparison")
        analysis=bound(item["analysis"])
        require(close["Status"]==analysis["cap"]["verdict"] and close["Status"] in ("PASS","PERFORMANCE_MISS","INPUT_LIMIT","TOOL_LIMIT")
            and all(analysis["integrity"].values()) and analysis["cap"]["diagnosticComparison"]
            and not analysis["cap"]["capacityPoint"] and close["RunExit"]==close["AnalysisExit"]==0,"new UNKNOWN/safety/ownership failure blocks comparison")
    for path_ in actual:
        a=read(path_);require(a["SessionId"] in history_ids,"issued attempt omitted")
        pol=bound(a["Policy"])
        if pol.get("role")==ROLE:comparisons.append((a,pol))
    require(original_seen and p["serviceMillisBefore"]==service and p["recordedServiceMillisFloorBefore"]==floor,"preserved service consumption")
    slots(p,contract,comparisons,int(time.time()*1000))
    state,tip=load_chain(ledger);budget=bound(p["budgetReservation"])
    require(state["gateMet"] and state["transition"]["closed"] and tip==p["ledgerTip"] and budget["stage"]=="CAP-1","current comparison storage gate")
    for key,needed in dict(evidence=prof["usage"]["evidenceBytes"],mysql=prof["storage"]["usage"]["mysqlBytes"],wal=prof["storage"]["usage"]["walBytes"],work=prof["usage"]["workspaceBytes"]).items():
        require(budget["additionalPeak"][key]>=needed,"comparison finite peak "+key)
    conditions=dict(stage="CAP-1",role=ROLE,variant=p["comparison"]["variant"],contract=p["comparison"]["contract"]["sha256"],
        pattern="W-SPREAD",seed=15,ws=100,readRate=0,warmup=30,vu=128,readVu=4,appHeap=2147483648,
        k6Heap=prof["k6GoMemoryBytes"],k6GoGcPercent=100,k6ProcessMemory=prof["k6ProcessMemoryBytes"],
        k6CombinedMemory=prof["k6MemoryBytes"],analysisHeap=ANALYSIS_MEMORY,volumeLayout=state["volumeLayout"],source=p["sourceBinding"])
    expected=hashlib.sha256(json.dumps(conditions,sort_keys=True,separators=(",",":")).encode()).hexdigest()
    require(p["conditionsHash"]==expected and p["seriesLayout"]==state["volumeLayout"]=="f-output-c-graph-v2","comparison derived condition binding")
    return dict(valid=True,stage="CAP-1",role=ROLE,profile=prof,slot=p["comparison"]["slot"],capacityPoint=False,ledgerTip=tip)
