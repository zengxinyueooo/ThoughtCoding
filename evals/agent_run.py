"""Run production ThoughtCoding with a restricted local evaluation adapter."""
import argparse
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import yaml
from run import ROOT, check, command, snapshot, seed_public_files
from smoke import model_config


class JoinedHTTPServer(ThreadingHTTPServer):
    # Wait for all in-flight usage accounting before finalizing the run.
    daemon_threads = False
    block_on_close = True


class Meter:
    def __init__(self, model, directory, remaining):
        self.model, self.directory, self.remaining = model, directory, remaining
        self.calls, self.cost, self.reserved = [], 0.0, 0.0
        self.blocked = False
        self.lock = threading.Lock()

    def handler(self):
        meter = self

        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if self.path != "/v1/chat/completions":
                    self.send_error(404)
                    return
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                payload["model"] = meter.model["name"]
                if payload.get("stream"):
                    payload["stream_options"] = {"include_usage": True}
                raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                # Conservative admission estimate: one input token per UTF-8 byte.
                reserve = (len(raw) * 0.7 + payload.get("max_tokens", 4096) * 2.8) / 1_000_000
                with meter.lock:
                    number = len(meter.calls) + 1
                    if number > 34 or meter.cost + meter.reserved + reserve > meter.remaining:
                        meter.blocked = True
                        self.send_error(429, "Evaluation budget exhausted")
                        return
                    meter.reserved += reserve
                    event = {"request": number, "usage": None, "status": None}
                    meter.calls.append(event)
                (meter.directory / (str(number) + "-request.json")).write_bytes(raw)
                start = time.monotonic()
                headers_sent = False
                try:
                    request = urllib.request.Request(meter.model["baseURL"].rstrip("/") + "/chat/completions",
                        data=raw, headers={"Content-Type": "application/json",
                        "Authorization": "Bearer " + meter.model["apiKey"]})
                    with urllib.request.urlopen(request, timeout=120) as response:
                        content_type = response.headers.get("Content-Type", "application/json")
                        self.send_response(200)
                        self.send_header("Content-Type", content_type)
                        self.send_header("Connection", "close")
                        self.end_headers()
                        headers_sent = True
                        self.close_connection = True
                        with (meter.directory / (str(number) + "-response.txt")).open("wb") as capture:
                            if payload.get("stream"):
                                for line in response:
                                    capture.write(line)
                                    if line.startswith(b"data: ") and line.strip() != b"data: [DONE]":
                                        chunk = json.loads(line[6:])
                                        if chunk.get("usage"):
                                            event["usage"] = chunk["usage"]
                                    self.wfile.write(line)
                                    self.wfile.flush()
                                    if line.strip() == b"data: [DONE]":
                                        # Preserve the blank SSE event separator when stopping at DONE.
                                        self.wfile.write(b"\n")
                                        self.wfile.flush()
                                        break
                            else:
                                body = response.read()
                                capture.write(body)
                                event["usage"] = json.loads(body).get("usage")
                                self.wfile.write(body)
                                self.wfile.flush()
                    if event["usage"]:
                        usage = event["usage"]
                        event["cost_cny"] = (usage["prompt_tokens"] * 0.7 + usage["completion_tokens"] * 2.8) / 1_000_000
                    event["status"] = 200
                except Exception as error:
                    event["status"] = getattr(error, "code", 502)
                    event["error_type"] = type(error).__name__
                    if not headers_sent:
                        self.send_error(502, "Upstream model request failed")
                finally:
                    if event.get("usage"):
                        usage = event["usage"]
                        event["cost_cny"] = (usage["prompt_tokens"] * 0.7 + usage["completion_tokens"] * 2.8) / 1_000_000
                    event["seconds"] = time.monotonic() - start
                    with meter.lock:
                        # Keep reserve charged when usage is unknown; never invent zero usage.
                        meter.reserved -= reserve
                        meter.cost += event.get("cost_cny", reserve)
                        (meter.directory.parent / "usage.json").write_text(
                            json.dumps(meter.calls, indent=2), encoding="utf-8")
        return Proxy


def run_task(task, config, model, batch, repetition, remaining, variant, traced=False, runtime_paths=None):
    output = batch / (task["id"] + "-" + str(repetition))
    output.mkdir()
    workspace = output / "workspace"
    snapshot(task["base_commit"], workspace)
    seed_public_files(task, workspace)
    for argv in (["git", "init", "-q"], ["git", "config", "core.longpaths", "true"], ["git", "add", "."],
                 ["git", "-c", "user.name=Eval", "-c", "user.email=eval@localhost", "commit", "-qm", "Task starting snapshot"]):
        subprocess.run(argv, cwd=workspace, check=True, capture_output=True)
    (output / "http").mkdir()
    meter = Meter(model, output / "http", remaining)
    proxy = JoinedHTTPServer(("127.0.0.1", 0), meter.handler())
    worker = threading.Thread(target=proxy.serve_forever, daemon=True)
    worker.start()
    runtime = copy.deepcopy(config)
    selected = runtime["models"][runtime["defaultModel"]]
    runtime["models"] = {runtime["defaultModel"]: selected}
    selected["apiKey"] = "local-evaluation-proxy"
    selected["baseURL"] = "http://127.0.0.1:" + str(proxy.server_port) + "/v1"
    runtime["tools"]["bash"]["timeoutSeconds"] = 180
    if variant == "keep-tool-results":
        runtime["ai"]["keepRecentToolResults"] = 100000
    if variant in ("baseline", "delivery-review", "verification-feedback"):
        runtime["ai"]["deliveryReviewEnabled"] = variant == "delivery-review"
    (output / "runtime.yaml").write_text(yaml.safe_dump(runtime, allow_unicode=True), encoding="utf-8")
    contract = {key: task[key] for key in ("prompt", "allowed_paths")}
    if task.get("public_files"):
        contract["public_verification"] = True
        contract["verification_gate_enabled"] = variant == "verification-feedback"
    if traced:
        contract.update(session_id=f"standard-eval-{batch.name}-{task['id']}-{repetition}",
                        require_tracing=True)
    if runtime_paths and runtime_paths.get("local_span_backup"):
        contract["local_span_backup"] = True
    (output / "input.json").write_text(json.dumps(contract, ensure_ascii=False), encoding="utf-8")
    paths = runtime_paths or {"classes": str(ROOT / "target/classes"),
        "adapter": str(ROOT / "evals/.build"),
        "dependencies": (ROOT / "target/eval-classpath.txt").read_text().strip()}
    classpath = os.pathsep.join([paths["classes"], paths["adapter"], paths["dependencies"]])
    result = {"kind": "agent_run", "task_id": task["id"], "repetition": repetition, "variant": variant,
        "model": model["name"], "base_commit": task["base_commit"], "human_corrections": 0,
        "cost_basis": "list_price_estimate_before_cache_or_account_discounts",
        "contract_sha256": hashlib.sha256(json.dumps(task, sort_keys=True).encode()).hexdigest(),
        "acceptance_sha256": hashlib.sha256((ROOT / "evals/acceptance" / task["acceptance_file"]).read_bytes()).hexdigest(),
        "isolation": "restricted_local_tools_not_os_sandbox", "passed": False}
    if traced:
        result["session_id"] = contract["session_id"]
    print("RUN " + task["id"] + " " + str(repetition), flush=True)
    try:
        execution = command(["java", "-Dfile.encoding=UTF-8", "-cp", classpath, "EvalMain",
            str(output / "runtime.yaml"), str(output / "input.json"), str(output / "tools.jsonl"),
            str(output / "history.json")], workspace, output / "agent.log", task["timeout_seconds"])
        result["execution"] = execution
        (output / "execution.json").write_text(json.dumps(execution, indent=2), encoding="utf-8")
    finally:
        proxy.shutdown()
        proxy.server_close()
        worker.join()
    result["api_requests"] = len(meter.calls)
    result["budget_accounted_cny"] = meter.cost
    result["usage_complete"] = bool(meter.calls) and all(c.get("usage") for c in meter.calls)
    result["cost_cny"] = sum(c["cost_cny"] for c in meter.calls) if result["usage_complete"] else None
    result["input_tokens"] = sum(c["usage"]["prompt_tokens"] for c in meter.calls) if result["usage_complete"] else None
    result["output_tokens"] = sum(c["usage"]["completion_tokens"] for c in meter.calls) if result["usage_complete"] else None
    patch = subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=workspace)
    (output / "submission.patch").write_bytes(patch)
    result["patch_sha256"] = hashlib.sha256(patch).hexdigest()
    if execution["timed_out"]:
        result["status"] = "timeout"
    elif meter.blocked:
        result["status"] = "budget_exhausted"
    elif execution["exit_code"] != 0:
        result["status"] = "execution_error"
    elif any(c["status"] != 200 for c in meter.calls):
        result["status"] = "provider_error"
    elif not meter.calls:
        result["status"] = "no_model_request"
    elif not (output / "history.json").exists():
        result["status"] = "missing_history"
    elif "已达到最大工具调用轮次" in (output / "history.json").read_text(encoding="utf-8"):
        result["status"] = "iteration_limit"
    elif not patch:
        result["status"] = "no_patch"
    else:
        try:
            result["verification"] = check(task, task["base_commit"], output / "verification",
                shutil.which("mvn.cmd" if os.name == "nt" else "mvn"), output / "submission.patch")
            result["passed"] = (result["verification"]["status"] == "passed"
                and result["verification"]["regression"]["status"] == "passed")
            result["status"] = "passed" if result["passed"] else "verification_failed"
        except (ValueError, subprocess.CalledProcessError) as error:
            result["status"] = "invalid_patch"
            result["error_type"] = type(error).__name__
    result["tools"] = []
    if (output / "tools.jsonl").exists():
        events = [json.loads(line) for line in (output / "tools.jsonl").read_text(encoding="utf-8").splitlines()]
        result["tool_attempts"] = sum(e["event"] == "tool_attempt" for e in events)
        result["blocked_attempts"] = sum(e.get("blocked", False) for e in events)
    (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("RESULT " + task["id"] + " " + str(repetition) + " " + result["status"] +
          " cost=" + str(result["cost_cny"]), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tasks", nargs="+", default=["edit-crlf"])
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--budget-cny", type=float, default=5)
    parser.add_argument("--variant", choices=["baseline", "keep-tool-results", "delivery-review"], default="baseline")
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--maintenance-note")
    args = parser.parse_args()
    config, model = model_config()
    if model["name"] != "Qwen/Qwen3-Coder-30B-A3B-Instruct":
        parser.error("Update pricing and freeze the new configuration before changing models")
    batch = args.resume.resolve() if args.resume else ROOT / "evals/results" / ("agent-" + args.variant + "-" + uuid.uuid4().hex[:10])
    if not args.resume:
        batch.mkdir()
    sanitized = copy.deepcopy(config)
    for setting in sanitized["models"].values():
        setting["apiKey"] = "REDACTED"
    manifest = {"agent_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "variant": args.variant, "config": sanitized, "budget_cny": args.budget_cny,
        "adapter_sha256": hashlib.sha256((ROOT / "evals/java/EvalMain.java").read_bytes()).hexdigest(),
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "runs": []}
    if args.resume:
        saved = json.loads((batch / "manifest.json").read_text(encoding="utf-8"))
        if saved.get("evaluation_status") == "invalidated":
            parser.error("Cannot resume invalidated batches")
        for key in ("agent_commit", "variant", "config", "adapter_sha256", "budget_cny"):
            if saved[key] != manifest[key]:
                parser.error("Resume would change frozen field: " + key)
        if saved["runner_sha256"] != manifest["runner_sha256"]:
            if not args.maintenance_note:
                parser.error("Runner changed; a documented accounting-only maintenance note is required")
            saved.setdefault("maintenance", []).append({"runner_sha256": manifest["runner_sha256"],
                                                       "note": args.maintenance_note})
        manifest = saved
    shutil.copyfile(__file__, batch / ("runner-" + hashlib.sha256(Path(__file__).read_bytes()).hexdigest()[:12] + ".py"))
    (batch / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("BATCH " + str(batch), flush=True)
    spent = sum(r.get("budget_accounted_cny", 0) for r in manifest["runs"])
    for task_id in args.tasks:
        task = json.loads((ROOT / "evals/tasks" / (task_id + ".json")).read_text(encoding="utf-8"))
        for repetition in range(1, args.repetitions + 1):
            if (batch / (task_id + "-" + str(repetition)) / "result.json").exists():
                continue
            if spent >= args.budget_cny:
                print("Batch budget exhausted", flush=True)
                return 1
            result = run_task(task, config, model, batch, repetition, args.budget_cny - spent, args.variant)
            spent += result["budget_accounted_cny"]
            manifest["runs"].append(result)
            (batch / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
