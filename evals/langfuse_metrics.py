"""Read-only seven-metric reports for explicitly accepted ThoughtCoding traces.

Credentials come exclusively from environment variables. No model calls, scores
uploads, raw prompt exports, or changes to the existing coding benchmark.
"""
import argparse
import base64
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path
import statistics
import urllib.error
import urllib.parse
import urllib.request
import uuid

SYNTHETIC_SESSIONS = {"thoughtcoding-tracing-smoke", "thoughtcoding-packaged-smoke", "maintenance"}


def timestamp(value):
    if not value:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Time must include a timezone, such as Z or +08:00")
    return parsed.astimezone(timezone.utc)


def number(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        result = float(value)
        return result if math.isfinite(result) and result >= 0 else None
    except (TypeError, ValueError):
        return None


def metadata(row, key):
    data = row.get("metadata") or {}
    return data.get(key, data.get("attributes.langfuse.observation.metadata." + key))


def token_counts(row):
    usage = row.get("usageDetails") or {}
    # API inputUsage/outputUsage default to zero even when usage was not supplied.
    incoming, outgoing, total = (number(usage.get(key)) for key in ("input", "output", "total"))
    if total is None and incoming is not None and outgoing is not None:
        total = incoming + outgoing
    return {"input": incoming, "output": outgoing, "total": total}


def cost_usd(row):
    details = row.get("costDetails") or {}
    total = number(details.get("total"))
    if total is not None:
        return total
    incoming, outgoing = number(details.get("input")), number(details.get("output"))
    if incoming is not None and outgoing is not None:
        return incoming + outgoing
    # A matched Langfuse pricing model or an explicit nonzero cost is evidence.
    total = number(row.get("totalCost"))
    if total is not None and (row.get("modelId") or row.get("internalModelId") or total > 0):
        return total
    return None


def tool_outcome(row):
    status = metadata(row, "status")
    if status in ("blocked", "cancelled"):
        return status
    success = metadata(row, "success")
    if success is False or str(success).lower() == "false" or status in ("error", "timeout"):
        return "error"
    if str(row.get("level", "")).upper() == "ERROR":
        return "error"
    if success is True or str(success).lower() == "true" or status == "success":
        return "success"
    return "unknown"


def load_acceptance(path):
    if path is None:
        return {}
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict):
        raise ValueError("Acceptance must be an object keyed by trace ID")
    for trace_id, verdict in data.items():
        if not isinstance(verdict, dict) or type(verdict.get("success")) is not bool:
            raise ValueError(f"Acceptance for {trace_id} requires boolean success")
        if verdict.get("method") not in ("manual", "tests", "external") or not verdict.get("reason"):
            raise ValueError(f"Acceptance for {trace_id} requires method and reason")
    return data


def metric_for_trace(rows, verdict=None, base_url=""):
    roots = [r for r in rows if r.get("name") == "task" and not r.get("parentObservationId")]
    root = roots[0] if len(roots) == 1 else {}
    complete = bool(root.get("endTime")) and all(r.get("endTime") for r in rows)
    issues = [] if len(roots) == 1 else ["Expected exactly one task root"]
    if not complete:
        issues.append("Trace is unfinished or root has not been ingested")
    llms = [r for r in rows if str(r.get("type", "")).upper() == "GENERATION"]
    tools = [r for r in rows if str(r.get("type", "")).upper() == "TOOL"]
    subagents = [r for r in rows if r.get("name") == "agent.subagent"]
    tokens = [token_counts(r) for r in llms]
    counts = {key: sum(t[key] for t in tokens) if all(t[key] is not None for t in tokens) else None
              for key in ("input", "output", "total")}
    costs = [cost_usd(r) for r in llms]
    cost = sum(costs) if all(c is not None for c in costs) else None
    outcomes = [tool_outcome(r) for r in tools]
    errors = outcomes.count("error")
    error_rate = errors / len(tools) if tools and "unknown" not in outcomes else None
    duration = None
    if complete:
        duration = (timestamp(root["endTime"]) - timestamp(root["startTime"])).total_seconds()
        if duration < 0:
            issues.append("Negative task duration")
            duration = None
    for key, actual in (("llm_call_count", len(llms)), ("tool_call_count", len(tools)),
                        ("subagent_count", len(subagents))):
        recorded = number(metadata(root, key))
        if recorded is not None and recorded != actual:
            issues.append(f"Root {key}={recorded:g} differs from exported observations={actual}")
            complete = False
    ids = {r["id"] for r in rows}
    if any(r.get("parentObservationId") and r["parentObservationId"] not in ids for r in rows):
        complete = False
        issues.append("A parent observation is missing")
    if not complete:
        duration = None
    trace_id = rows[0]["traceId"]
    project_id = root.get("projectId")
    return {
        "trace_id": trace_id, "session_id": root.get("sessionId"),
        "trace_url": f"{base_url}/project/{project_id}/traces/{trace_id}" if project_id and base_url else None,
        "complete": complete, "runtime_status": metadata(root, "status"),
        "task_success": verdict["success"] if verdict and complete else None,
        "acceptance": verdict, "e2e_latency_s": duration,
        "foreground_latency_ms": number(metadata(root, "foreground_latency_ms")),
        "llm_calls": len(llms), "tool_calls": len(tools), "tool_errors": errors,
        "tool_error_rate": error_rate,
        "tool_successes": outcomes.count("success"),
        "tool_success_rate": outcomes.count("success") / len(tools)
            if tools and "unknown" not in outcomes else None,
        "tool_blocked": outcomes.count("blocked"),
        "tool_cancelled": outcomes.count("cancelled"), "tool_unknown": outcomes.count("unknown"),
        "tokens": counts, "cost_usd": cost,
        "cost_missing_generations": sum(c is None for c in costs),
        "subagent_count": len(subagents), "data_quality_issues": issues,
        "models": sorted({r.get("model") for r in llms if r.get("model")}),
        "observations": [{"id": r["id"], "parent_id": r.get("parentObservationId"),
                          "name": r.get("name"), "type": r.get("type")} for r in rows],
    }


def aggregate(records):
    completed = [r for r in records if r["complete"]]
    judged = [r for r in completed if r["task_success"] is not None]
    successes = sum(r["task_success"] for r in judged)
    latencies = sorted(r["e2e_latency_s"] for r in completed if r["e2e_latency_s"] is not None)
    tool_calls = sum(r["tool_calls"] for r in records)
    tool_errors = sum(r["tool_errors"] for r in records)
    all_complete = bool(records) and len(completed) == len(records)
    def complete_sum(values):
        return sum(values) if all_complete and all(v is not None for v in values) else None
    return {
        "task_count": len(records), "completed_tasks": len(completed), "labeled_tasks": len(judged),
        "successes": successes,
        "task_success_rate": successes / len(records) if all_complete and len(judged) == len(records) else None,
        "labeled_success_rate": successes / len(judged) if judged else None,
        "e2e_latency_mean_s": statistics.mean(latencies) if latencies else None,
        "e2e_latency_median_s": statistics.median(latencies) if latencies else None,
        "e2e_latency_p95_s": latencies[math.ceil(.95 * len(latencies)) - 1] if latencies else None,
        "llm_calls": sum(r["llm_calls"] for r in records), "tool_calls": tool_calls,
        "tool_errors": tool_errors,
        "tool_successes": sum(r["tool_successes"] for r in records),
        "tool_success_rate": sum(r["tool_successes"] for r in records) / tool_calls
            if all_complete and tool_calls and all(r["tool_unknown"] == 0 for r in records) else None,
        "tool_error_rate": tool_errors / tool_calls if all_complete and tool_calls
            and all(r["tool_unknown"] == 0 for r in records) else None,
        "tokens": {k: complete_sum([r["tokens"][k] for r in records]) for k in ("input", "output", "total")},
        "cost_usd": complete_sum([r["cost_usd"] for r in records]),
        "subagent_count": sum(r["subagent_count"] for r in records),
    }


def fetch_observations(base, headers, start, end, trace_id=None):
    params = {"fromStartTime": start.isoformat(), "toStartTime": end.isoformat(), "limit": 100,
              "fields": "core,basic,usage,metadata,model"}
    if trace_id:
        params["traceId"] = trace_id
    rows, seen_cursors = {}, set()
    for _ in range(100):
        request = urllib.request.Request(base + "/api/public/v2/observations?" + urllib.parse.urlencode(params),
                                         headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Langfuse read failed: HTTP {error.code} (response omitted)") from None
        for row in result.get("data", []):
            rows[(row["traceId"], row["id"])] = row
        cursor = (result.get("meta") or {}).get("cursor")
        if not cursor:
            return list(rows.values())
        if cursor in seen_cursors:
            raise RuntimeError("Repeated API cursor; refusing an incomplete report")
        seen_cursors.add(cursor)
        params["cursor"] = cursor
    raise RuntimeError("API pagination limit reached; choose a smaller time range")


def markdown(report):
    summary = report["summary"]
    def fmt(value, percentage=False):
        if value is None:
            return "未知 / 不适用"
        if percentage:
            return f"{value:.2%}"
        return f"{value:,.6f}".rstrip("0").rstrip(".") if isinstance(value, float) else f"{value:,}"
    lines = ["# ThoughtCoding tracing 指标报告", "", f"生成时间：{report['generated_at']}",
             "", f"样本 {summary['task_count']}；完成 {summary['completed_tasks']}；"
             f"有验收标注 {summary['labeled_tasks']}。合成连通性测试不计入。",
             "", "| 指标 | 本批结果 |", "| --- | --- |",
             f"| Task Success Rate | {fmt(summary['task_success_rate'], True)} |",
             f"| E2E Latency 平均 / 中位数 / P95（秒） | "
             f"{fmt(summary['e2e_latency_mean_s'])} / {fmt(summary['e2e_latency_median_s'])} / "
             f"{fmt(summary['e2e_latency_p95_s'])} |",
             f"| LLM Calls | {summary['llm_calls']} |", f"| Tool Calls | {summary['tool_calls']} |",
             f"| Tool Error Rate | {fmt(summary['tool_error_rate'], True)} "
             f"（{summary['tool_errors']}/{summary['tool_calls']}） |",
             f"| Tool Success Rate | {fmt(summary['tool_success_rate'], True)} "
             f"（{summary['tool_successes']}/{summary['tool_calls']}） |",
             f"| Tokens 输入 / 输出 / 合计 | {fmt(summary['tokens']['input'])} / "
             f"{fmt(summary['tokens']['output'])} / {fmt(summary['tokens']['total'])} |",
             f"| Cost USD | {fmt(summary['cost_usd'])} |",
             f"| SubAgent Count | {summary['subagent_count']} |", "",
             "任务成功只来自独立验收标注，不从运行状态推断。未标注样本仍保留，整体成功率标为未知。",
             "Tool Error Rate 为执行错误数 / Tool Calls；权限阻止、用户取消另列，零调用时比率不适用。",
             "Token 只累加 GENERATION，缺失 usage 或有效定价时报告未知，不将 API 默认零值当作实际零消耗。",
             "E2E 使用根 Span 起止时间，包含所属后台工作；不累加嵌套 Span 耗时。",
             "P95 为 nearest-rank；单样本只是链路验证，不代表整体能力或稳定性。", "", "## 逐任务"]
    for record in report["tasks"]:
        lines += ["", f"### {record['trace_id']}", "",
                  f"Trace：{record['trace_url'] or '不可用'}", "",
                  f"运行状态：{record['runtime_status']}；验收：{record['task_success']}；"
                  f"模型：{', '.join(record['models']) or '未知'}。",
                  f"LLM {record['llm_calls']} 次；工具 {record['tool_calls']} 次；"
                  f"工具错误 {record['tool_errors']}；阻止 {record['tool_blocked']}；"
                  f"取消 {record['tool_cancelled']}；SubAgent {record['subagent_count']}。"]
        if record["acceptance"]:
            reason = str(record['acceptance']['reason']).replace("\n", " ")
            lines += ["", f"验收方式：{record['acceptance']['method']}。依据：{reason}"]
        if record["cost_missing_generations"]:
            lines += ["", f"{record['cost_missing_generations']} 个模型调用缺少有效费用，Cost 保留未知。"]
        for issue in record["data_quality_issues"]:
            lines += ["", "数据完整性提示：" + issue]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace-id", action="append", help="Repeat to select specific traces")
    parser.add_argument("--from-time", help="ISO timestamp with timezone; defaults to 24 hours ago")
    parser.add_argument("--to-time", help="ISO timestamp with timezone; defaults to now")
    parser.add_argument("--acceptance", type=Path)
    parser.add_argument("--output-dir", type=Path, help="Must be a new directory")
    args = parser.parse_args()
    now = datetime.now(timezone.utc)
    start, end = timestamp(args.from_time) or now - timedelta(hours=24), timestamp(args.to_time) or now
    if start >= end:
        parser.error("from-time must precede to-time")
    try:
        verdicts = load_acceptance(args.acceptance)
        base = os.environ["LANGFUSE_BASE_URL"].rstrip("/")
        parts = urllib.parse.urlsplit(base)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.query or parts.fragment:
            raise ValueError("Invalid LANGFUSE_BASE_URL")
        auth = base64.b64encode((os.environ["LANGFUSE_PUBLIC_KEY"] + ":" +
                                os.environ["LANGFUSE_SECRET_KEY"]).encode()).decode()
        rows = {}
        for trace_id in list(dict.fromkeys(args.trace_id or [None])):
            for row in fetch_observations(base, {"Authorization": "Basic " + auth}, start, end, trace_id):
                rows[(row["traceId"], row["id"])] = row
        groups = defaultdict(list)
        for row in rows.values():
            if row.get("sessionId") not in SYNTHETIC_SESSIONS:
                groups[row["traceId"]].append(row)
        missing = set(args.trace_id or []) - groups.keys()
        if missing:
            raise ValueError("Selected trace missing or synthetic: " + ", ".join(sorted(missing)))
        records = [metric_for_trace(group, verdicts.get(trace_id), base)
                   for trace_id, group in sorted(groups.items())]
        if not records:
            raise ValueError("No real task traces found in the selected time range")
        report = {"schema_version": 1, "kind": "langfuse_trace_metrics", "generated_at": now.isoformat(),
                  "query": {"from_time": start.isoformat(), "to_time": end.isoformat(),
                            "trace_ids": args.trace_id}, "summary": aggregate(records), "tasks": records}
        directory = args.output_dir or Path(__file__).parent / "results" / (
                "langfuse-metrics-" + now.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6])
        directory.mkdir(parents=True, exist_ok=False)
        (directory / "metrics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        (directory / "metrics.md").write_text(markdown(report), encoding="utf-8")
        print(json.dumps(report["summary"], ensure_ascii=False))
        print("Report: " + str((directory / "metrics.md").resolve()))
    except (KeyError, ValueError, RuntimeError, OSError) as error:
        parser.exit(1, f"Metric report failed: {error}\n")


if __name__ == "__main__":
    main()
