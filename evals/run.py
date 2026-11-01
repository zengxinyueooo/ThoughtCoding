"""Local task calibration; never reports reference repairs as Agent results."""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def command(argv, cwd, log, timeout=900):
    start = time.monotonic()
    with log.open("wb") as output:
        p = subprocess.Popen(argv, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                             start_new_session=os.name != "nt")
        try:
            code = p.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"], capture_output=True)
            else:
                os.killpg(p.pid, signal.SIGKILL)
            p.wait()
            return {"exit_code": None, "timed_out": True, "seconds": time.monotonic() - start}
    return {"exit_code": code, "timed_out": False, "seconds": time.monotonic() - start}


def snapshot(commit, destination):
    archive = subprocess.check_output(["git", "archive", "--format=zip", commit], cwd=ROOT)
    destination.mkdir(parents=True)
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        for entry in bundle.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError("Archive path escapes workspace")
        bundle.extractall(destination)


def check(task, commit, destination, maven, patch=None):
    snapshot(commit, destination)
    seed_public_files(task, destination)
    if patch:
        subprocess.run(["git", "init", "-q"], cwd=destination, check=True, capture_output=True)
        changed = subprocess.check_output(["git", "apply", "--numstat", "-z", str(patch)], cwd=destination)
        paths = []
        for item in changed.split(b"\0"):
            if item:
                fields = item.decode("utf-8").split("\t", 2)
                if len(fields) != 3 or not fields[2]:
                    raise ValueError("Renames and malformed patch entries are not allowed")
                paths.append(fields[2])
        if not paths or not set(paths).issubset(set(task["allowed_paths"])):
            raise ValueError("Patch changes files outside the task contract or is empty")
        subprocess.run(["git", "apply", "--check", str(patch)], cwd=destination, check=True, capture_output=True)
        subprocess.run(["git", "apply", str(patch)], cwd=destination, check=True, capture_output=True)
    regression = command([maven, "-B", "-ntp", "test"], destination,
                         destination.parent / (destination.name + "-regression.log"), task["timeout_seconds"])
    regression["status"] = "passed" if regression["exit_code"] == 0 else "failed"
    regression_reports = list((destination / "target/surefire-reports").glob("TEST-*.xml"))
    regression["tests"] = sum(int(ET.parse(p).getroot().get("tests", 0)) for p in regression_reports)
    if regression["tests"] == 0:
        regression["status"] = "no_tests"
    acceptance = destination / "src/test/java/com/thoughtcoding/eval" / task["acceptance_file"]
    acceptance.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "evals/acceptance" / task["acceptance_file"], acceptance)
    outcome = command([maven, "-B", "-ntp", "-Dtest=" + task["acceptance_class"],
                       "-DfailIfNoTests=true", "test"], destination,
                      destination.parent / (destination.name + ".log"), task["timeout_seconds"])
    report = destination / "target/surefire-reports" / ("TEST-" + task["acceptance_class"] + ".xml")
    outcome["commit"] = commit
    outcome["regression"] = regression
    if report.exists() and not outcome["timed_out"]:
        suite = ET.parse(report).getroot()
        outcome["tests"] = int(suite.get("tests", 0))
        outcome["failures"] = int(suite.get("failures", 0))
        outcome["errors"] = int(suite.get("errors", 0))
        outcome["skipped"] = int(suite.get("skipped", 0))
        outcome["status"] = "acceptance_failed"
        if outcome["tests"] == 0 or outcome["skipped"]:
            outcome["status"] = "invalid_test_run"
        elif outcome["exit_code"] == 0 and outcome["failures"] == outcome["errors"] == 0:
            outcome["status"] = "passed"
    else:
        outcome["status"] = "timeout" if outcome["timed_out"] else "environment_or_build_error"
    return outcome


def seed_public_files(task, destination):
    """Versioned public diagnostic fixtures, equally visible to both arms."""
    for relative, source in task.get("public_files", {}).items():
        target = (destination / relative).resolve()
        if not target.is_relative_to((destination / "src/test/java").resolve()):
            raise ValueError("Public fixture must stay under task test sources")
        origin = (ROOT / source).resolve()
        if not origin.is_relative_to((ROOT / "evals/public_checks").resolve()):
            raise ValueError("Public fixture source must be under evals/public_checks")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["calibrate", "score"])
    parser.add_argument("task", choices=[p.stem for p in (ROOT / "evals/tasks").glob("*.json")])
    parser.add_argument("--patch", type=Path, help="Patch to verify; only for score")
    args = parser.parse_args()
    if (args.action == "score") != (args.patch is not None):
        parser.error("score requires --patch; calibrate does not accept --patch")
    maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
    if not maven:
        parser.error("Maven is not on PATH")
    task = json.loads((ROOT / "evals/tasks" / (args.task + ".json")).read_text(encoding="utf-8"))
    output = ROOT / "evals/results" / (args.task + "-" + uuid.uuid4().hex[:10])
    result = {"kind": "task_calibration", "task_id": task["id"], "agent_runs": 0}
    output.mkdir(parents=True)
    if args.action == "score":
        result["kind"] = "patch_verification"
        shutil.copyfile(args.patch.resolve(), output / "submission.patch")
        try:
            result["verification"] = check(task, task["base_commit"], output / "verification", maven,
                                           (output / "submission.patch").resolve())
            result["passed"] = (result["verification"]["status"] == "passed"
                                and result["verification"]["regression"]["status"] == "passed")
        except (ValueError, subprocess.CalledProcessError) as error:
            result["passed"] = False
            result["error"] = str(error)
        (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(output / "result.json")
        return 0 if result["passed"] else 1
    for label, key in [("base", "base_commit"), ("reference", "reference_commit")]:
        reference_patch = ROOT / "evals" / task["reference_patch"] if label == "reference" and task.get("reference_patch") else None
        commit = task["base_commit"] if reference_patch else task[key]
        result[label] = check(task, commit, output / label, maven, reference_patch)
        if reference_patch:
            shutil.copyfile(reference_patch, output / "reference.patch")
            result[label]["reference_note"] = task["reference_note"]
        print(label + ": " + result[label]["status"], flush=True)
    result["calibrated"] = (result["base"]["status"] == "acceptance_failed"
                            and result["base"].get("failures", 0) > 0
                            and result["base"].get("errors", 0) == 0
                            and result["reference"]["status"] == "passed")
    result["ready"] = (result["calibrated"] and all(result[label]["regression"]["status"] == "passed"
                                                   for label in ("base", "reference")))
    (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(output / "result.json")
    return 0 if result["ready"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
