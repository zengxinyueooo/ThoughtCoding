"""Real Agent task acceptance + Langfuse metrics; no simulated model results."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

from agent_run import run_task
from smoke import model_config
from run import ROOT
import langfuse_metrics as metrics

TASKS = ["edit-crlf", "glob-recursive", "dispatcher-failure", "registry-invalid", "bash-timeout"]


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def credentials():
    base = os.environ["LANGFUSE_BASE_URL"].rstrip("/")
    token = base64.b64encode((os.environ["LANGFUSE_PUBLIC_KEY"] + ":" +
                             os.environ["LANGFUSE_SECRET_KEY"]).encode()).decode()
    return base, {"Authorization": "Basic " + token}


def score_payload(batch, run, trace_id):
    return {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, batch.name + ":" + run["session_id"])),
            "traceId": trace_id, "name": "task_success", "dataType": "BOOLEAN",
            "value": float(run["passed"]),
            "comment": f"Independent acceptance + existing regression; {run['task_id']}; "
                       f"repetition={run['repetition']}; status={run['status']}; batch={batch.name}"}


def collect(batch, manifest, upload):
    base, headers = credentials()
    start = metrics.timestamp(manifest["started_at"]) - timedelta(minutes=1)
    # One bounded query, restricted locally to the frozen sessions in this batch.
    rows = metrics.fetch_observations(base, headers, start, datetime.now(timezone.utc) + timedelta(minutes=1))
    records, acceptance, missing, uploads = [], {}, [], []
    for run in manifest["runs"]:
        selected = [r for r in rows if r.get("sessionId") == run["session_id"]]
        evidence_file = batch / (run["task_id"] + "-" + str(run["repetition"])) / "spans.jsonl"
        local = [json.loads(line) for line in evidence_file.read_text(encoding="utf-8").splitlines()] \
            if evidence_file.exists() else []
        trace_ids = {r["traceId"] for r in selected if r.get("name") == "task"}
        if not trace_ids:
            trace_ids = {r["traceId"] for r in local if r.get("name") == "task"}
        if len(trace_ids) != 1:
            missing.append(run["session_id"])
            continue
        trace_id = trace_ids.pop()
        verdict = {"success": bool(run["passed"]), "method": "tests",
                   "reason": f"{run['task_id']} #{run['repetition']}: {run['status']}; "
                             "requires independent acceptance and original regression"}
        acceptance[trace_id] = verdict
        cloud = [r for r in selected if r["traceId"] == trace_id]
        record = metrics.metric_for_trace(cloud or local, verdict, base)
        if not cloud:
            record.update(complete=False, task_success=None, data_source="local_backup_cloud_absent",
                data_quality_issues=["Cloud observations absent; original local span backup exists"])
        record.update(case_id=run["task_id"], repetition=run["repetition"])
        records.append(record)
        if upload:
            body = json.dumps([score_payload(batch, run, trace_id)]).encode()
            request = urllib.request.Request(base + "/api/public/scores", data=body,
                headers=dict(headers, **{"Content-Type": "application/json"}), method="POST")
            with urllib.request.urlopen(request, timeout=30) as response:
                result = json.load(response)
                if result.get("errors"):
                    raise RuntimeError("Langfuse rejected scores (details omitted)")
            uploads.append(trace_id)
    report = {"kind": "real_agent_standard_eval", "generated_at": datetime.now(timezone.utc).isoformat(),
              "summary": metrics.aggregate(records), "tasks": records,
              "scheduled_runs": manifest["scheduled_runs"], "executed_runs": len(manifest["runs"]),
              "missing_sessions": missing, "scores_uploaded": uploads,
              "independent_acceptance": {"successes": sum(r["passed"] for r in manifest["runs"]),
                  "denominator": len(manifest["runs"]),
                  "task_success_rate": sum(r["passed"] for r in manifest["runs"]) / len(manifest["runs"])
                      if manifest["runs"] else None}}
    save(batch / "acceptance.json", acceptance)
    save(batch / "metrics.json", report)
    text = metrics.markdown(report)
    prefix = ("本批为真实模型 + 当前 AgentLoop + 历史 Bug 修复，非模拟实验。\n\n"
              f"计划 {manifest['scheduled_runs']} 次；已执行 {len(manifest['runs'])} 次；"
              f"缺少 Trace 的运行 {len(missing)} 次。所有运行的验收分母见 metrics.json independent_acceptance。\n\n"
              "未启用 MCP / SubAgent / Memory；SubAgent=0 是配置范围，不是能力评测。"
              "Langfuse 未识别价格则 Cost 未知。代理历史费率仅作预算记账，不能作为实际费用。\n\n")
    (batch / "metrics.md").write_text(prefix + text, encoding="utf-8")
    print(json.dumps({"acceptance": report["independent_acceptance"], "telemetry": report["summary"],
                      "missing_traces": len(missing), "scores_uploaded": len(uploads)}, ensure_ascii=False), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--tasks", nargs="+", choices=TASKS, default=TASKS)
    parser.add_argument("--budget-cny", type=float, default=2.0,
                        help="Historical-rate proxy admission budget, NOT an actual billing guarantee")
    parser.add_argument("--collect", type=Path, help="Collect an existing batch without rerunning models")
    parser.add_argument("--upload-scores", action="store_true")
    args = parser.parse_args()
    credentials()  # Fail before any paid model execution if tracing configuration is absent.
    if args.collect:
        batch = args.collect.resolve()
        collect(batch, json.loads((batch / "manifest.json").read_text(encoding="utf-8")), args.upload_scores)
        return
    if args.repetitions < 1 or args.budget_cny <= 0:
        parser.error("Positive repetitions and budget required")
    config, model = model_config()
    if model["name"] != "Qwen/Qwen3-Coder-30B-A3B-Instruct":
        parser.error("Proxy admission accounting requires the frozen model")
    os.environ["THOUGHTCODING_TRACING_ENABLED"] = "true"
    os.environ["THOUGHTCODING_ENVIRONMENT"] = "offline-eval"
    # Never execute a stale adapter after a failed build.
    subprocess.run([os.sys.executable, str(ROOT / "evals/build.py")], cwd=ROOT, check=True)
    batch = ROOT / "evals/results" / ("standard-real-" + uuid.uuid4().hex[:10])
    batch.mkdir(parents=True)
    sources = [ROOT / "pom.xml", Path(__file__).resolve(), ROOT / "evals/agent_run.py",
               ROOT / "evals/java/EvalMain.java", ROOT / "evals/langfuse_metrics.py"]
    sources += list((ROOT / "src/main/java").rglob("*.java"))
    sources += [ROOT / "evals/tasks" / (t + ".json") for t in args.tasks]
    sources += list((ROOT / "evals/acceptance").glob("*.java"))
    manifest = {"kind": "real_agent_standard_eval", "started_at": datetime.now(timezone.utc).isoformat(),
                "scheduled_runs": len(args.tasks) * args.repetitions,
                "tasks": args.tasks, "repetitions": args.repetitions, "model": model["name"],
                "agent_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
                "budget_accounting": "historical input .70/output 2.80 CNY per million; not current price or bill",
                "budget_cny": args.budget_cny, "runs": []}
    save(batch / "manifest.json", manifest)
    print("BATCH " + str(batch), flush=True)
    spent = 0
    for task_id in args.tasks:
        task = json.loads((ROOT / "evals/tasks" / (task_id + ".json")).read_text(encoding="utf-8"))
        for repetition in range(1, args.repetitions + 1):
            if spent >= args.budget_cny:
                print("Admission budget exhausted; remaining cases stay unexecuted", flush=True)
                break
            run = run_task(task, config, model, batch, repetition, args.budget_cny - spent, "baseline", traced=True)
            # This runner never treats the proxy's historical cost estimate as actual cost.
            run["historical_proxy_cost_estimate_cny"] = run.pop("cost_cny", None)
            run["cost_basis"] = "historical_estimate_only; actual cost from matched Langfuse pricing or unknown"
            spent += run["budget_accounted_cny"]
            manifest["runs"].append(run)
            save(batch / (task_id + "-" + str(repetition)) / "result.json", run)
            save(batch / "manifest.json", manifest)
    # Shutdown flush is synchronous in the child; allow ingestion to become queryable.
    for attempt in range(4):
        report = collect(batch, manifest, False)
        if not report["missing_sessions"] and all(r["complete"] for r in report["tasks"]):
            break
        if attempt < 3:
            time.sleep(5)
    if args.upload_scores:
        collect(batch, manifest, True)
    print("REPORT " + str(batch / "metrics.md"), flush=True)


if __name__ == "__main__":
    main()
