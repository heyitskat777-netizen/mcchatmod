#!/usr/bin/env python3
"""Bounded real-client smoke runs. Upload evidence/, never profiles/ or game data."""
from __future__ import annotations

import argparse
import ctypes
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
BASE = PROJECT / "fabric/build/client-smoke"
COMMON_STAGES = {"startup", "local-world", "keybind-open", "search-widget", "layout-save", "import-ui", "batch-workflow", "dialogue-scroll", "roleplay-filter",
                 "backup", "journal-close", "keybind-rebind", "world-leave", "shutdown"}
GRAPHICS_ERRORS = ("Failed to create window", "Failed to initialize GLFW", "GLFW error 65542",
                   "GLFW error 65543", "GLXBadFBConfig", "No X11 DISPLAY")


def required_stages(phase: str) -> set[str]:
    if phase == "preflight":
        return {"startup", "shutdown"}
    if phase not in {"write", "read"}:
        raise ValueError(f"Unknown phase: {phase}")
    return COMMON_STAGES | ({"initial-defaults"} if phase == "write" else {"restart-persistence", "import-persistence"})


def validate_report(report: dict | None, shutdown: dict | None, expected: dict) -> list[str]:
    """Fail closed on absent, stale, incomplete, or zero-assertion evidence."""
    if not isinstance(report, dict):
        return ["Missing or unreadable test report"]
    errors = []
    for key in ("runId", "mode", "phase", "profile"):
        if report.get(key) != expected[key]:
            errors.append(f"Report {key} does not match this process")
    if type(report.get("processId")) is not int or report["processId"] <= 0:
        errors.append("Missing Minecraft process ID")
    for key in ("complete", "bodyCompleted", "shutdownAcknowledged"):
        if report.get(key) is not True:
            errors.append(f"Missing successful {key}")
    if report.get("testCount") != 1:
        errors.append("Exactly one registered client suite must execute")
    if report.get("failures") != []:
        errors.append("Client reported failures")
    stages = report.get("stages", [])
    if not isinstance(stages, list) or not all(isinstance(s, dict) for s in stages):
        stages = []
        errors.append("Malformed stage list")
    names = [s.get("name") for s in stages]
    if len(set(str(n) for n in names)) != len(names):
        errors.append("Duplicate stage evidence")
    missing = required_stages(expected["phase"]) - set(str(n) for n in names)
    if missing:
        errors.append(f"Missing assertion stages: {sorted(missing)}")
    if report.get("assertionStages") != len(stages):
        errors.append("Assertion stage count mismatch")
    for stage in stages:
        if stage.get("passed") is not True:
            errors.append(f"Stage failed: {stage.get('name')}")
        elapsed, budget = stage.get("durationMs"), stage.get("timeoutMs")
        if (type(elapsed) is not int or type(budget) is not int or elapsed < 0 or budget <= 0 or elapsed > budget):
            errors.append(f"Invalid or exceeded stage budget: {stage.get('name')}")
    if not isinstance(shutdown, dict):
        errors.append("Missing process shutdown witness")
    else:
        for key in ("runId", "mode", "phase", "processId"):
            if shutdown.get(key) != report.get(key):
                errors.append(f"Shutdown witness {key} mismatch")
        if shutdown.get("complete") is not True or shutdown.get("shutdownAcknowledged") is not True:
            errors.append("Process shutdown was not acknowledged")
    try:
        started = datetime.fromisoformat(report["startedAt"].replace("Z", "+00:00")).timestamp()
        if started < expected.get("startedAfter", 0) - 2:
            errors.append("Report predates this process")
    except (KeyError, TypeError, ValueError, AttributeError):
        errors.append("Missing report start timestamp")
    origins = report.get("journalOrigins")
    if expected["phase"] == "preflight":
        if origins != []:
            errors.append("Graphics preflight unexpectedly loaded the journal")
    elif not origins:
        errors.append("Journal mod did not load")
    elif expected["mode"] == "packaged":
        if not all(str(p).endswith(".jar") for p in origins):
            errors.append("Packaged journal origin is not a JAR")
        sources = report.get("codeSources", {})
        if not all(".jar" in str(sources.get(name, "")) for name in ("journal", "sqlite")):
            errors.append("Packaged journal/SQLite classes did not load from JARs")
    screenshots = report.get("screenshots")
    if not isinstance(screenshots, list) or not screenshots:
        errors.append("Missing screenshot evidence")
    else:
        profile = Path(expected["profile"]).resolve()
        for name in screenshots:
            path = Path(str(name)).resolve()
            if (not path.is_relative_to(profile) or path.suffix != ".png" or not path.is_file() or
                    path.stat().st_size <= 8 or path.stat().st_mtime < expected.get("startedAfter", 0) - 2 or
                    path.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n"):
                errors.append("Screenshot must be a fresh PNG inside this isolated profile")
    return errors


def validate_launch(launch: dict | None, expected: dict, report: dict | None) -> list[str]:
    """Production-launch evidence proves no project output or standalone SQLite masked the bundle."""
    if not isinstance(launch, dict):
        return ["Missing production-launch classpath evidence"]
    errors = []
    for key in ("runId", "mode", "phase"):
        if launch.get(key) != expected[key]:
            errors.append(f"Launch witness {key} mismatch")
    include_journal = expected["phase"] != "preflight"
    if launch.get("production") is not True or launch.get("journalIncluded") is not include_journal:
        errors.append("Unexpected production launch/mod selection")
    paths = launch.get("classpath")
    if not isinstance(paths, list) or not paths:
        errors.append("Missing production runtime classpath")
    else:
        for name in paths:
            path = Path(str(name)).resolve()
            if (path.suffix != ".jar" or path.is_relative_to(PROJECT) or
                    path.name.startswith(("application-", "domain-", "storage-", "sqlite-jdbc"))):
                errors.append(f"Forbidden production classpath entry: {path}")
    mods = launch.get("mods", [])
    if not isinstance(mods, list) or len(mods) != (5 if include_journal else 4):
        errors.append("Unexpected installed mod count")
        mods = []
    for required in ("fabric-client-gametest-api-v1-", "fabric-gametest-api-v1-"):
        if sum(Path(str(p)).name.startswith(required) for p in mods) != 1:
            errors.append(f"Missing or duplicate required test module: {required}")
    if any(not str(p).endswith(".jar") or Path(str(p)).name.startswith("sqlite-jdbc") for p in mods):
        errors.append("Production mods must be JARs without standalone SQLite")
    journals = [str(Path(str(p)).resolve()) for p in mods if Path(str(p)).name.startswith("lotc-character-journal-") and "smoke-harness" not in Path(str(p)).name]
    if include_journal:
        if len(journals) != 1 or not report or report.get("journalOrigins") != journals:
            errors.append("Loaded journal does not match the installed remapped JAR")
    elif journals:
        errors.append("Graphics preflight installed a journal JAR")
    return errors


def classify_result(returncode: int, timed_out: bool, report: dict | None,
                    shutdown: dict | None, expected: dict, log: str) -> tuple[str, list[str]]:
    errors = validate_report(report, shutdown, expected)
    if timed_out:
        return "TIMEOUT", ["Hard process timeout; execution was terminated", *errors]
    bound = isinstance(report, dict) and all(report.get(k) == expected[k] for k in ("runId", "mode", "phase"))
    # An assertion failure outranks incidental graphics warnings in the same log.
    stages = report.get("stages") if bound and isinstance(report.get("stages"), list) else []
    if bound and (report.get("failures") or any(isinstance(s, dict) and s.get("passed") is False for s in stages)):
        return ("GRAPHICS_FAILURE" if expected["phase"] == "preflight" else "APPLICATION_FAILURE"), errors
    if returncode != 0:
        if any(marker in log for marker in GRAPHICS_ERRORS):
            return "ENVIRONMENT_FAILURE", [f"Process exit {returncode}", *errors]
        return "STARTUP_FAILURE", [f"Process exit {returncode}", *errors]
    if errors:
        return "EVIDENCE_FAILURE", errors
    for marker in ("LOTC_SMOKE_BODY_COMPLETE", "LOTC_SMOKE_SHUTDOWN_COMPLETE"):
        if f"{marker} runId={expected['runId']} phase={expected['phase']} pid={report['processId']}" not in log:
            return "EVIDENCE_FAILURE", [f"Missing fresh {marker} process marker"]
    return "PASSED", []


def _process_table() -> dict[int, tuple[int, str, str]]:
    table = {}
    for entry in Path("/proc").iterdir():
        if entry.name.isdigit():
            try:
                fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
                table[int(entry.name)] = (int(fields[1]), fields[19], fields[0])
            except (OSError, IndexError, ValueError):
                continue
    return table


def _descendants(parent: int, table: dict) -> set[int]:
    result = set()
    parents = {parent}
    while parents:
        children = {pid for pid, (ppid, _, _) in table.items() if ppid in parents} - result
        result |= children
        parents = children
    return result


def _cleanup_children(baseline: set[int], wrapper_pid: int, log) -> bool:
    """Subreaper adoption contains Gradle's detached daemon as well as Minecraft."""
    deadline = time.monotonic() + 15
    killed = False
    while True:
        table = _process_table()
        owned = _descendants(os.getpid(), table) - baseline
        for pid in owned - {wrapper_pid}:
            try:
                os.waitpid(pid, os.WNOHANG)
            except (ChildProcessError, ProcessLookupError):
                pass
        live = {pid: table[pid] for pid in owned if table[pid][2] != "Z"}
        if not live:
            return True
        sig = signal.SIGKILL if time.monotonic() >= deadline else signal.SIGTERM
        for pid, identity in live.items():
            # Recheck the start-time identity immediately before signalling; a
            # reused PID must never make an unrelated process a cleanup target.
            current = _process_table().get(pid)
            if current is not None and current[1] == identity[1]:
                try:
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
        if sig == signal.SIGKILL:
            if killed:
                log.write("OWNED_PROCESS_CLEANUP_FAILED\n")
                return False
            killed = True
            deadline = time.monotonic() + 5
        time.sleep(0.1)


def run_process(command: list[str], log_path: Path, timeout: int, env: dict) -> tuple[int, bool, float]:
    started = time.monotonic()
    timed_out = False
    with log_path.open("w") as log:
        # Gradle's single-use daemon creates a new session, so killpg(wrapper)
        # alone is insufficient. Linux subreaper adoption keeps every detached
        # descendant scoped to this dedicated runner, with no global java/pkill.
        if sys.platform != "linux" or ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
            log.write("ENVIRONMENT_FAILURE: Linux child-subreaper support is required\n")
            return 125, False, time.monotonic() - started
        baseline = _descendants(os.getpid(), _process_table())
        try:
            process = subprocess.Popen(command, cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=True)
        except OSError as failure:
            log.write(str(failure))
            return 127, False, time.monotonic() - started
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
        clean = _cleanup_children(baseline, process.pid, log)
        process.wait(timeout=5)
        code = process.returncode if clean else 125
    return code, timed_out, time.monotonic() - started


def read_json(path: Path) -> dict | None:
    try:
        value = json.loads(path.read_text())
        return value if isinstance(value, dict) else None
    except (OSError, ValueError):
        return None


def write_junit(outcomes: list[dict], path: Path) -> None:
    failed = sum(o["status"] not in {"PASSED", "SKIPPED"} for o in outcomes)
    suite = ET.Element("testsuite", name="minecraft-client-smoke", tests=str(len(outcomes)), failures=str(failed),
                       skipped=str(sum(o["status"] == "SKIPPED" for o in outcomes)))
    for outcome in outcomes:
        case = ET.SubElement(suite, "testcase", name=outcome["name"], classname="client.smoke",
                             time=str(round(outcome.get("duration", 0), 3)))
        if outcome["status"] == "SKIPPED":
            ET.SubElement(case, "skipped", message="Blocked by an earlier failed prerequisite")
        elif outcome["status"] != "PASSED":
            ET.SubElement(case, "failure", type=outcome["status"], message=outcome["status"]).text = "\n".join(outcome.get("errors", []))
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--eula-accepted", action="store_true", help="Caller explicitly accepts Minecraft EULA for these isolated test runtimes")
    parser.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--prepared", action="store_true", help="Reuse prepareClientSmoke outputs from an earlier successful invocation")
    parser.add_argument("--mode", choices=("all", "development", "packaged"), default="all", help="Select affected client checks; graphics preflight still runs first")
    parser.add_argument("--run-id", default=str(uuid.uuid4()))
    parser.add_argument("--timeout", type=int, default=300, help="Hard timeout in seconds for each Minecraft process")
    args = parser.parse_args()
    if not args.eula_accepted:
        parser.error("Explicit --eula-accepted is required before launching Minecraft")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.run_id) or args.timeout <= 0:
        parser.error("run-id must be a safe unique identifier and timeout must be positive")
    evidence = BASE / "evidence" / args.run_id
    profiles = BASE / "profiles" / args.run_id
    # Never reuse evidence or a profile from an older run, or touch the real game.
    evidence.mkdir(parents=True, exist_ok=False)
    profiles.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    env.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    env.setdefault("ALSOFT_DRIVERS", "null")
    launcher = ["bash", "./gradlew"]
    gradle_flags = ["--no-daemon", "--console=plain"]
    outcomes = []
    planned = [("graphics", "preflight", ":fabric:runGraphicsPreflight")]
    if not args.preflight_only:
        modes = ("development", "packaged") if args.mode == "all" else (args.mode,)
        planned += [(mode, phase, ":fabric:runClientGameTest" if mode == "development" else ":fabric:runPackagedSmoke")
                    for mode in modes for phase in ("write", "read")]
    blocked = False
    if not args.prepared:
        rc, timed_out, duration = run_process(launcher + [":fabric:prepareClientSmoke", *gradle_flags],
                                               evidence / "prepare.log", 600, env)
        status = "TIMEOUT" if timed_out else ("PASSED" if rc == 0 else "BUILD_FAILURE")
        outcomes.append(dict(name="prepare", status=status, duration=duration, returncode=rc,
                             errors=[] if rc == 0 and not timed_out else ["Preparation failed; see prepare.log"]))
        blocked = status != "PASSED"
    process_ids = set()
    for mode, phase, task in planned:
        name = f"{mode}-{phase}"
        if blocked:
            outcomes.append(dict(name=name, status="SKIPPED", errors=["Earlier prerequisite failed"]))
            continue
        profile = profiles / mode
        if not profile.exists():
            profile.mkdir()
            (profile / "eula.txt").write_text("# Explicitly accepted by the caller for this synthetic test runtime\neula=true\n")
            (profile / "options.txt").write_text("renderDistance:2\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\npauseOnLostFocus:false\nsoundCategory_master:0\n")
        result = evidence / f"{name}.json"
        expected = dict(runId=args.run_id, mode=mode, phase=phase, profile=str(profile.resolve()), startedAfter=time.time())
        properties = {"Mode": mode, "Phase": phase, "RunId": args.run_id, "Result": str(result), "Profile": str(profile)}
        command = launcher + [task, *[f"-PjournalSmoke{k}={v}" for k, v in properties.items()], *gradle_flags]
        log_path = evidence / f"{name}.log"
        rc, timed_out, duration = run_process(command, log_path, args.timeout, env)
        report = read_json(result)
        status, errors = classify_result(rc, timed_out, report, read_json(Path(str(result) + ".shutdown.json")), expected, log_path.read_text(errors="replace"))
        if status == "PASSED" and mode in {"graphics", "packaged"}:
            errors = validate_launch(read_json(Path(str(result) + ".launch.json")), expected, report)
            if errors:
                status = "EVIDENCE_FAILURE"
        if status == "PASSED":
            pid = report["processId"]
            if pid in process_ids:
                status, errors = "EVIDENCE_FAILURE", ["Minecraft PID reused; separate fresh processes not established"]
            process_ids.add(pid)
        # Preserve synthetic failure screenshots too, but never copy arbitrary
        # paths from a malformed report or upload a database/world directory.
        if report and all(report.get(k) == expected[k] for k in ("runId", "mode", "phase")):
            screenshots = report.get("screenshots", [])
            for index, screenshot in enumerate(screenshots if isinstance(screenshots, list) else []):
                image = Path(str(screenshot)).resolve()
                if (image.is_relative_to(profile.resolve()) and image.suffix == ".png" and image.is_file() and
                        image.stat().st_mtime >= expected["startedAfter"] - 2 and image.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"):
                    shutil.copyfile(image, evidence / f"{name}-{index}.png")
        latest = profile / "logs/latest.log"
        if latest.is_file():
            shutil.copyfile(latest, evidence / f"{name}-minecraft.log")
        outcomes.append(dict(name=name, status=status, duration=duration, returncode=rc, timedOut=timed_out,
                             processId=report.get("processId") if report else None, errors=errors))
        print(f"{name}: {status} (exit={rc}, {duration:.1f}s)", flush=True)
        if errors:
            print("\n".join(errors), flush=True)
        blocked = status != "PASSED"
    summary = dict(runId=args.run_id, outcomes=outcomes, passed=not blocked,
                   scope="graphics-only" if args.preflight_only else f"graphics + {args.mode} write/read")
    (evidence / "summary.json").write_text(json.dumps(summary, indent=2))
    write_junit(outcomes, evidence / "junit.xml")
    print(f"Evidence: {evidence}", flush=True)
    return 0 if not blocked else 1


if __name__ == "__main__":
    raise SystemExit(main())
