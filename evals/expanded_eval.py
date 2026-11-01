"""Frozen, resumable 10-task x 3 real-model benchmark; at most two concurrent trials."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

from agent_run import run_task
from run import ROOT
from smoke import model_config
from standard_eval import collect, credentials, save

TASKS = ["edit-crlf", "glob-recursive", "dispatcher-failure", "registry-invalid", "bash-timeout",
         "read-binary", "write-limit", "dispatcher-cancel", "session-latest", "registry-owner"]


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sanitized(value):
    if isinstance(value, dict):
        return {k: "REDACTED" if any(k.lower().replace("_", "").endswith(s)
                   for s in ("apikey", "secretkey", "password", "accesstoken", "authorization"))
                else sanitized(v) for k, v in value.items()}
    return [sanitized(v) for v in value] if isinstance(value, list) else value


def wilson(successes, count):
    """Descriptive only: repeated trials and selected cases are not iid population samples."""
    if not count:
        return None
    z = 1.95996398454
    p = successes / count
    center = (p + z*z/(2*count)) / (1+z*z/count)
    margin = z*((p*(1-p)/count+z*z/(4*count*count))**.5)/(1+z*z/count)
    return [center-margin, center+margin]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--workers", type=int, choices=[1, 2], default=2)
    parser.add_argument("--trial-budget-cny", type=float, default=2.0,
                        help="Historical-rate admission allowance per trial, not a bill guarantee")
    args = parser.parse_args()
    credentials()
    os.environ["THOUGHTCODING_TRACING_ENABLED"] = "true"
    os.environ["THOUGHTCODING_ENVIRONMENT"] = "offline-eval"
    config, model = model_config()
    if model["name"] != "Qwen/Qwen3-Coder-30B-A3B-Instruct":
        parser.error("Frozen proxy accounting requires the existing model")
    if args.trial_budget_cny <= 0:
        parser.error("Positive trial allowance required")
    if args.resume:
        batch = args.resume.resolve()
        manifest = json.loads((batch / "manifest.json").read_text(encoding="utf-8"))
        if manifest["workers"] != args.workers or manifest["trial_budget_cny"] != args.trial_budget_cny:
            parser.error("Resume cannot change frozen workers or admission allowance")
        if manifest["configuration"] != sanitized(config):
            parser.error("Resume would change frozen model or harness configuration")
        for relative, expected in manifest["source_sha256"].items():
            if fingerprint(ROOT / relative) != expected:
                parser.error("Source changed since batch freeze: " + relative)
        if (batch / "INVALIDATED.json").exists():
            parser.error("Cannot resume invalidated batch")
    else:
        calibration = json.loads((ROOT / "evals/EXPANDED_CALIBRATION.json").read_text(encoding="utf-8"))
        for task_id in TASKS:
            entry = calibration[task_id]
            if not entry["ready"] or entry["task_sha256"] != fingerprint(ROOT / "evals/tasks" / (task_id+".json")):
                parser.error("Missing or stale calibration: " + task_id)
            contract = json.loads((ROOT / "evals/tasks" / (task_id+".json")).read_text(encoding="utf-8"))
            if entry["acceptance_sha256"] != fingerprint(ROOT / "evals/acceptance" / contract["acceptance_file"]):
                parser.error("Acceptance changed after calibration: " + task_id)
        subprocess.run([sys.executable, str(ROOT / "evals/build.py")], cwd=ROOT, check=True)
        batch = ROOT / "evals/results" / ("expanded-real-" + uuid.uuid4().hex[:10])
        batch.mkdir(parents=True)
        shutil.copytree(ROOT / "target/classes", batch / "runtime/classes")
        shutil.copytree(ROOT / "evals/.build", batch / "runtime/adapter")
        dependencies = (ROOT / "target/eval-classpath.txt").read_text().strip()
        sources = list((ROOT / "src/main/java").rglob("*.java"))
        sources += [ROOT / "pom.xml", ROOT / "evals/java/EvalMain.java", ROOT / "evals/java/EvalTelemetry.java", Path(__file__).resolve(),
                    ROOT / "evals/agent_run.py", ROOT / "evals/run.py", ROOT / "evals/standard_eval.py",
                    ROOT / "evals/langfuse_metrics.py", ROOT / "evals/verify_standard_results.py",
                    ROOT / "evals/expanded_finalize.py", ROOT / "evals/expanded_report.py",
                    ROOT / "evals/build.py", ROOT / "evals/EXPANDED_CALIBRATION.json"]
        sources += [ROOT / "evals/tasks" / (t+".json") for t in TASKS]
        sources += list((ROOT / "evals/acceptance").glob("*.java"))
        for source in sources:
            target = batch / "frozen-sources" / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        manifest = {"kind": "expanded_real_agent_standard_eval", "started_at": datetime.now(timezone.utc).isoformat(),
            "tasks": TASKS, "repetitions": 3, "scheduled_runs": 30, "workers": args.workers,
            "trial_budget_cny": args.trial_budget_cny,
            "budget_accounting": "Historical proxy allowance only; no current pricing or billing claim",
            "configuration": sanitized(config), "model": model["name"],
            "agent_commit": subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip(),
            "source_sha256": {str(p.relative_to(ROOT)): fingerprint(p) for p in sources},
            "runtime_paths": {"classes": str(batch / "runtime/classes"),
                              "adapter": str(batch / "runtime/adapter"), "dependencies": dependencies,
                              "local_span_backup":True},
            "runtime_sha256": {str(p.relative_to(batch)): fingerprint(p) for p in (batch / "runtime").rglob("*.class")},
            "calibration": calibration, "runs": []}
        save(batch / "manifest.json", manifest)
    for relative, expected in manifest["runtime_sha256"].items():
        if fingerprint(batch / relative) != expected:
            parser.error("Frozen compiled runtime was modified: " + relative)
    # Recover completed results after an interrupted parent, never silently rerun an unfinished directory.
    recovered = {(r["task_id"], r["repetition"]): r for r in manifest["runs"]}
    pending = []
    for task_id in TASKS:
        task = json.loads((ROOT / "evals/tasks" / (task_id+".json")).read_text(encoding="utf-8"))
        for repetition in range(1, 4):
            output = batch / (task_id+"-"+str(repetition))
            if (output / "result.json").exists():
                recovered[(task_id,repetition)] = json.loads((output / "result.json").read_text(encoding="utf-8"))
            elif (task_id,repetition) not in recovered:
                if output.exists():
                    parser.error("Unfinished trial retained; audit before resume: " + str(output))
                pending.append((task,repetition))
    manifest["runs"] = list(recovered.values())
    pending.sort(key=lambda pair: hashlib.sha256(f"20261023:{pair[0]['id']}:{pair[1]}".encode()).hexdigest())
    save(batch / "manifest.json", manifest)
    print("BATCH " + str(batch), flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(run_task, task, copy.deepcopy(config), model, batch, repetition,
                    args.trial_budget_cny, "baseline", True, manifest["runtime_paths"]): (task,repetition)
                   for task,repetition in pending}
        for future in as_completed(futures):
            task,repetition = futures[future]
            try:
                result = future.result()
            except Exception as error:
                result = {"task_id":task["id"],"repetition":repetition,"passed":False,
                    "status":"harness_error","error_type":type(error).__name__,
                    "session_id":f"standard-eval-{batch.name}-{task['id']}-{repetition}"}
            result["historical_proxy_cost_estimate_cny"] = result.pop("cost_cny",None)
            result["cost_basis"] = "Historical estimate only; actual cost unknown without valid pricing"
            save(batch / (task["id"]+"-"+str(repetition)) / "result.json", result)
            manifest["runs"].append(result)
            manifest["runs"].sort(key=lambda r:(TASKS.index(r["task_id"]),r["repetition"]))
            save(batch / "manifest.json", manifest)
            print(f"PROGRESS {len(manifest['runs'])}/30; provisional_passed={sum(r['passed'] for r in manifest['runs'])}; final_verdicts_pending", flush=True)
    collect(batch, manifest, False)
    subprocess.run([sys.executable, str(ROOT / "evals/expanded_finalize.py"),str(batch)], check=True)
    subprocess.run([sys.executable, str(ROOT / "evals/expanded_report.py"),str(batch)], check=True)


if __name__ == "__main__":
    main()
