"""실행 종료 시 코드·회귀·측정·Git 상태를 E: 증거에 연결하는 읽기 중심 감사 도구."""
import hashlib
import json
import subprocess
import shutil
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path("E:/pixel-place-phase15/phase15-a-20260918")
OUT = ROOT / "agent-runs/phase15-A-final-audit"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def git(*args):
    result = subprocess.run(["git", "-c", "core.safecrlf=false", *args], capture_output=True, encoding="utf-8", errors="replace")
    return dict(exit=result.returncode, stdout=result.stdout, stderr=result.stderr)


def main():
    OUT.mkdir(exist_ok=False)
    commands = {"inside": git("rev-parse", "--is-inside-work-tree"), "branch": git("branch", "--show-current"),
                "head": git("rev-parse", "HEAD"), "worktrees": git("worktree", "list", "--porcelain"),
                "status": git("status", "--short"), "diffCheck": git("diff", "--check"),
                "cachedDiffCheck": git("diff", "--cached", "--check"), "stagedEmpty": git("diff", "--cached", "--quiet")}
    diff = git("diff", "--")
    (OUT / "final.diff").write_text(diff["stdout"], encoding="utf-8")
    paths = set()
    for root in ("src/main", "src/benchmark", "src/test"):
        paths.update(p for p in Path(root).rglob("*") if p.is_file())
    paths.update(Path(p) for p in ("build.gradle", "pixel_place.sql", ".gitignore", ".gitattributes", "docs/pixel-place-details.md", "docs/pixel-place-specs.md",
                                 "작업기록/phase-15-progress.md", "명령문/015-A_통합_측정입력.json"))
    paths.update(Path("scripts").glob("*phase15*"))
    paths.update(Path("scripts").glob("Phase15*.java"))
    hashes = {p.as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths) if p.is_file()}
    baseline = read(Path("build/agent-runs/phase15-A-20260917-preparation/baseline-input.json"))
    preserved_names = (".gitignore", ".gitattributes", "pixel_place.sql", "src/main/resources/application.yml", "docs/pixel-place-specs.md",
                       "src/main/java/dev/cgt/pixelplace/pixel/application/PixelUserWriteGate.java",
                       "src/main/java/dev/cgt/pixelplace/pixel/websocket/PixelWebSocketSessionRegistry.java",
                       "src/main/java/dev/cgt/pixelplace/pixel/websocket/PixelWebSocketHandler.java",
                       "src/main/java/dev/cgt/pixelplace/pixel/application/PixelEventMessage.java",
                       "src/main/java/dev/cgt/pixelplace/pixel/web/PixelController.java",
                       "src/main/java/dev/cgt/pixelplace/tile/web/TileController.java")
    preservation = {}
    for name in preserved_names:
        before = next((row["Hash"].lower() for row in baseline if row["Path"] == name), None)
        origin = "phase15-A preparation baseline-input.json"
        if before is None:
            for manifest_path in sorted(Path("build/agent-runs").glob("phase15-A-r*/manifest.json")):
                before = read(manifest_path).get("sourceIdentity", {}).get(name)
                if before:
                    before = before.lower()
                    origin = str(manifest_path)
                    break
        preservation[name] = dict(matches=before is not None and hashlib.sha256(Path(name).read_bytes()).hexdigest() == before,
                                  comparisonSource=origin, note="Ignored DDL/main config absent from Git baseline; earliest smoke source identity used" if "manifest" in origin else "")
    checks, preserved_static = [], []
    for p in sorted(paths):
        if p.suffix not in (".java", ".json", ".yml", ".ps1", ".py", ".md", ".gradle") or not p.is_file():
            continue
        text = p.read_text(encoding="utf-8-sig")
        lines = text.splitlines()
        problems = []
        if text and not text.endswith("\n"): problems.append("missing final newline")
        trailing = [(i, line) for i, line in enumerate(lines, 1) if line.rstrip() != line]
        if trailing:
            previous = git("show", "HEAD:" + p.as_posix()) if p.suffix == ".md" else None
            if previous and previous["exit"] == 0 and commands["diffCheck"]["exit"] == 0 and all(line in previous["stdout"].splitlines() for _, line in trailing):
                preserved_static.append(dict(path=p.as_posix(), lines=[i for i, _ in trailing],
                                             finding="Preexisting HEAD whitespace retained; no new diff whitespace error"))
            else:
                problems.append("trailing whitespace")
        if any(line.startswith(("<<<<<<< ", ">>>>>>> ")) or line == "=======" for line in lines): problems.append("conflict marker")
        if problems: checks.append(dict(path=p.as_posix(), problems=problems))
    regression = read(ROOT / "agent-runs/phase15-A-e-final-regression/results.json")
    totals = {}
    for suite in regression["Suites"]:
        t = totals.setdefault(suite["Task"], dict(tests=0, failures=0, errors=0, skipped=0, testcase=0, suites=0))
        for key, source in (("tests", "Tests"), ("failures", "Failures"), ("errors", "Errors"), ("skipped", "Skipped"), ("testcase", "Testcases")):
            t[key] += suite[source]
        t["suites"] += 1
    result = dict(utc=datetime.now(timezone.utc).isoformat(), git=commands, inputHashes=hashes,
                  preexistingPreserved=preservation, staticFindings=checks, preservedStaticFindings=preserved_static,
                  regressionExit=regression["Exit"], regression=totals,
                  regressionStorage=read(ROOT / "agent-runs/phase15-A-e-final-regression/storage-budget.json"),
                  measurement=read(ROOT / "agent-runs/phase15-A-measurement-summary.json"),
                  databaseShutdown=read(ROOT / "mysql/shutdown-verification.json"),
                  scope="15-A only; no 15-B adoption decision; no group commit; no staging/commit/push or branch/worktree changes")
    redis = subprocess.run(["C:/Program Files/Redis/redis-cli.exe", "-h", "127.0.0.1", "-p", "6379", "-n", "2", "DBSIZE"], capture_output=True, text=True)
    result["redisBenchmarkDbAfter"] = dict(database=2, exit=redis.returncode,
                                          keys=int(redis.stdout.strip()) if redis.returncode == 0 and redis.stdout.strip().isdigit() else None)
    owned_bytes = sum(p.stat().st_size for p in ROOT.rglob("*") if p.is_file())
    legacy = read(ROOT / "legacy-budget-charge.json")["chargedBytes"]
    # 기존 Redis의 작은 자동 RDB·source/graph 증가와 이 최종 감사 파일까지 보수적 추가 여유 포함
    ancillary = 64 * 1024 * 1024
    rdb = Path("C:/Program Files/Redis/dump.rdb")
    result["finalStorage"] = dict(ownedRootBytes=owned_bytes, legacyChargeBytes=legacy,
                                  ancillaryConservativeChargeBytes=ancillary, chargedBytes=owned_bytes + legacy + ancillary,
                                  maximumBytes=25 * 1024**3, freeEBytes=shutil.disk_usage(ROOT).free,
                                  minimumFreeEBytes=5 * 1024**3, sharedRedisRdbBytes=rdb.stat().st_size,
                                  sharedRedisPersistence="Existing C:/Program Files/Redis/dump.rdb automatic snapshots; AOF off; config unchanged; observed 42271 bytes during case 1")
    result["verificationPassed"] = (all(command["exit"] == 0 for command in commands.values())
        and all(row["matches"] for row in preservation.values()) and not checks and regression["Exit"] == 0
        and all(t["tests"] > 0 and t["tests"] == t["testcase"] and t["failures"] == t["errors"] == t["skipped"] == 0 for t in totals.values())
        and all(result["measurement"][key] for key in ("exact21Cases", "identicalInputs", "currentSourceMatches", "independentChecksPassed"))
        and not result["databaseShutdown"]["Port3307Listening"] and result["databaseShutdown"]["ShutdownCommandExit"] == 0
        and result["redisBenchmarkDbAfter"]["keys"] == 0
        and result["finalStorage"]["chargedBytes"] <= result["finalStorage"]["maximumBytes"]
        and result["finalStorage"]["freeEBytes"] >= result["finalStorage"]["minimumFreeEBytes"])
    (OUT / "final-audit.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(dict(evidence=str(OUT), gitChecks={k:v["exit"] for k,v in commands.items()},
                         staticFindings=checks, preservation=preservation, tests=totals,
                         finalStorage=result["finalStorage"], verificationPassed=result["verificationPassed"]), ensure_ascii=False))
    if not result["verificationPassed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
