"""새 현장 환경을 기존 workload template에 연결. 과거 계획/결과 수정과 서비스 실행 없음."""
from pathlib import Path
import argparse
import copy
import datetime
import hashlib
import json
import re
import subprocess
from pixel_place_paths import evidence_path, historical, plain_absolute


def read(path):
    return json.loads(historical(path).read_text(encoding="utf-8-sig"))


def sha(path):
    with historical(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_new(path, value):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def compose(template, environment, repo, run_id, redis_index, environment_hash):
    session = environment["sessionId"]
    if not re.fullmatch(r"phase18-[a-z0-9-]{1,60}", session) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,39}", run_id):
        raise ValueError("Fresh safe session/run identifiers required")
    if len(template["cases"]) != 1 or session == template["sessionId"] or run_id == template["cases"][0]["runId"]:
        raise ValueError("One case and new session/run identities required")
    if environment["mysqlRoot"] != "F:/MySQL/test" or environment["owner"] != "cgt" or environment["catalogMode"] != "create-new":
        raise ValueError("Current F ownership required")
    if not 2 <= redis_index <= 15:
        raise ValueError("Dedicated Redis index required")
    observed = datetime.datetime.fromisoformat(environment["observedUtc"].replace("Z", "+00:00"))
    age = (datetime.datetime.now(datetime.timezone.utc) - observed).total_seconds()
    if not 0 <= age < 86400:
        raise ValueError("Fresh environment required")
    plan = copy.deepcopy(template)
    plan.update(sessionId=session, state="ready")
    if repo.drive.upper() != "C:":
        raise ValueError("Current C repository required")
    large = Path("F:/pixel-place-work/sessions") / session
    plan["ownership"].update(repo=str(repo), owner=environment["owner"],
        java="C:/Program Files/Java/jdk-21.0.10/bin/java.exe", sessionRoot=str(large),
        evidenceRoot=str(evidence_path(session)), build=str(large / "build"),
        cache=str(large / "project-cache"), temp=str(large / "temp"), jna=str(large / "jna"),
        wal=str(Path("F:/pixel-place-wal/phase18") / session / run_id / "pixel-place.wal"),
        dbInstance=environment["mysqlUuid"] + "@" + environment_hash,
        catalog="pixel_place_bench_" + run_id.replace("-", "_"), redisIndex=redis_index)
    plan["cases"][0].update(runId=run_id, plannedStartUtc=datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"))
    return plan


def main():
    parser = argparse.ArgumentParser()
    for key in ("template", "environment", "predecessor", "run-id", "label"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--policy-template")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,39}", args.label):
        raise ValueError("Safe new label required")
    repo = Path(__file__).resolve().parent.parent
    env_path = plain_absolute(args.environment)
    env = read(env_path)
    out = evidence_path(env["sessionId"])
    if env_path.parent != out or not env_path.name.startswith("environment-"):
        raise ValueError("Environment must belong to this new session")
    receipt = env_path.stem.removeprefix("environment-")
    redis = read(out / ("redis-" + receipt + ".json"))
    proof = read(out / ("mysql-" + receipt + ".json"))
    descriptor = read(repo / "scripts/mysql-test-instance.json")
    if (proof["Action"] != "Environment" or proof["RunId"] != env["sessionId"] or proof["SqlExit"] != 0
            or proof["Live"]["Pid"] != env["mysqlPid"] or proof["Live"]["StartUtc"] != env["mysqlStartUtc"]
            or redis["RunId"] != env["redisRunId"] or redis["Pid"] != env["redisPid"] or not redis["Empty"]
            or env["mysqlUuid"] != descriptor["uuid"] or env["mysqlConfigHash"] != sha(descriptor["config"])
            or env["mysqlBinaryHash"] != sha(descriptor["binary"])):
        raise ValueError("Live proof/environment/current instance mismatch")
    plan = compose(read(args.template), env, repo, args.run_id, redis["Database"], sha(env_path))
    plan["sourceBaseline"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    predecessor = Path(args.predecessor).resolve()
    plan["predecessor"] = {"path": str(predecessor), "sha256": sha(predecessor)}
    paths = {p for folder in ("src", "scripts") for p in (repo / folder).rglob("*")
             if p.is_file() and p.suffix.lower() not in (".zip", ".pyc")}
    paths |= {repo / p for p in ("build.gradle", "settings.gradle", "gradlew.bat", "pixel_place.sql", "docs/phase18-tools.md")}
    plan["inputs"] = [{"path": p.relative_to(repo).as_posix(), "sha256": sha(p)} for p in sorted(paths)]
    target = out / ("plan-" + args.label + ".json")
    policy_path = out / ("policy-" + args.label + ".json")
    if target.exists() or (args.policy_template and policy_path.exists()):
        raise FileExistsError("Completed plans/policies must not be overwritten")
    policy = read(args.policy_template) if args.policy_template else None
    write_new(target, plan)
    if policy is not None:
        policy.update(planHash=sha(target), caseId=plan["cases"][0]["caseId"])
        write_new(policy_path, policy)
    print(json.dumps({"plan": str(target), "sha256": sha(target), "mysqlRoot": env["mysqlRoot"], "executed": False}))


if __name__ == "__main__":
    main()
