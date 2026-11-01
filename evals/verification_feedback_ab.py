"""Preregistered public verification feedback: 1 task x 3 repetitions x 2 arms; no outcome-driven reruns."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import statistics
import subprocess
import sys
import time
import uuid

from agent_run import run_task
from expanded_eval import sanitized
from run import ROOT, check
from smoke import model_config
from standard_eval import credentials, save
import langfuse_metrics as metrics

TASKS = ['write-limit']
ARMS = ['baseline', 'verification-feedback']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def local_metrics(directory):
    path = directory / 'spans.jsonl'
    rows = [json.loads(s) for s in path.read_text(encoding='utf-8').splitlines()] if path.exists() else []
    root = next((r for r in rows if r.get('name') == 'task' and not r.get('parentObservationId')), None)
    tools = [r for r in rows if r.get('type') == 'TOOL']
    generations = [r for r in rows if r.get('type') == 'GENERATION']
    return {'trace_id': root['traceId'] if root else None,
        'e2e_latency_s': (metrics.timestamp(root['endTime']) - metrics.timestamp(root['startTime'])).total_seconds()
            if root and root.get('endTime') else None,
        'llm_calls': len(generations), 'tool_calls': len(tools),
        'tool_successes': sum(metrics.tool_outcome(r) == 'success' for r in tools),
        'tool_errors': sum(metrics.tool_outcome(r) == 'error' for r in tools),
        'tool_blocked': sum(metrics.tool_outcome(r) == 'blocked' for r in tools),
        'metrics_source': 'original local OTel span backup; same trace IDs as cloud export'}


def execute(task, config, model, arm_dir, repetition, runtime):
    variant = arm_dir.name.rsplit('--', 1)[1]
    directory = arm_dir / f"{task['id']}-{repetition}"
    result = run_task(task, config, model, arm_dir, repetition, 2.0, variant, True, runtime)
    raw_status = result['status']
    # Consistent final-delivery policy: recovered transport errors are not an
    # automatic failure. The unchanged independent tests decide the artifact.
    if raw_status == 'provider_error' and (directory / 'submission.patch').stat().st_size \
            and (directory / 'history.json').exists() and not result['execution']['timed_out'] \
            and result['execution']['exit_code'] == 0 \
            and '已达到最大工具调用轮次' not in (directory / 'history.json').read_text(encoding='utf-8'):
        verification = check(task, task['base_commit'], directory / 'recovered-verification',
            shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn'), directory / 'submission.patch')
        result['verification'] = verification
        result['passed'] = verification['status'] == 'passed' and verification['regression']['status'] == 'passed'
        result['status'] = 'recovered_provider_passed' if result['passed'] else 'recovered_provider_verification_failed'
    result['original_status'] = raw_status
    result['arm'] = variant
    gate_file = directory / 'verification-gate.jsonl'
    gate_events = [json.loads(line) for line in gate_file.read_text(encoding='utf-8').splitlines()] if gate_file.exists() else []
    result['public_gate_attempts'] = len(gate_events)
    result['public_gate_events'] = gate_events
    result['feedback_requests'] = min(2, sum(e['status'] != 'PASSED' for e in gate_events))
    result['public_gate_last_status'] = gate_events[-1]['status'] if gate_events else 'DISABLED'
    fixture = directory / 'workspace/src/test/java/com/thoughtcoding/publicchecks/PublicWriteCompatibilityTest.java'
    result['public_fixture_unchanged'] = sha(fixture) == sha(ROOT / 'evals/public_checks/PublicWriteCompatibilityTest.java')
    assert result['public_fixture_unchanged'], 'Public fixture was modified'
    result.update(local_metrics(directory))
    result['historical_proxy_cost_estimate_cny'] = result.pop('cost_cny', None)
    result['cost_basis'] = 'Historical allowance only, not verified model billing'
    save(directory / 'result.json', result)
    return result


def report(batch, publish=False):
    if (batch / 'INVALIDATED.json').exists():
        raise ValueError('Invalidated batch cannot be reported or scored as an Agent experiment')
    manifest = json.loads((batch / 'manifest.json').read_text(encoding='utf-8'))
    groups = {}
    for arm in ARMS:
        runs = [r for r in manifest['runs'] if r['arm'] == arm]
        tools = sum(r.get('tool_calls', 0) for r in runs)
        successful_tools = sum(r.get('tool_successes', 0) for r in runs)
        durations = [r['e2e_latency_s'] for r in runs if r.get('e2e_latency_s') is not None]
        groups[arm] = {'runs': len(runs), 'successes': sum(r['passed'] for r in runs),
            'task_success_rate': sum(r['passed'] for r in runs) / len(runs) if runs else None,
            'mean_e2e_s': statistics.mean(durations) if durations else None,
            'llm_calls': sum(r.get('llm_calls', 0) for r in runs), 'tool_calls': tools,
            'tool_success_rate': successful_tools / tools if tools else None,
            'per_task': {task: {'successes': sum(r['passed'] for r in runs if r['task_id'] == task),
                'runs': sum(r['task_id'] == task for r in runs)} for task in TASKS}}
    summary = {'kind': 'delivery_review_controlled_ab', 'groups': groups,
        'completed': len(manifest['runs']), 'scheduled': 6, 'all_trials_reported': len(manifest['runs']) == 6,
        'scope': 'selected development tasks; 3 repetitions per task, not population-level proof',
        'single_variable': 'contract.verification_gate_enabled', 'historical_30_run_baseline_unchanged': True}
    save(batch / 'summary.json', summary)
    if publish:
        base, headers = credentials()
        import urllib.request
        uploads = []
        for run in manifest['runs']:
            if not run.get('trace_id'):
                continue
            item = {'id': str(uuid.uuid5(uuid.NAMESPACE_URL, batch.name + run['session_id'])),
                'traceId': run['trace_id'], 'name': 'task_success', 'dataType': 'BOOLEAN',
                'value': float(run['passed']), 'comment': f"Controlled A/B; arm={run['arm']}; task={run['task_id']}; repetition={run['repetition']}; unchanged independent acceptance + original regression; status={run['status']}"}
            req = urllib.request.Request(base + '/api/public/scores', data=json.dumps([item]).encode(),
                headers=dict(headers, **{'Content-Type': 'application/json'}))
            with urllib.request.urlopen(req, timeout=30) as response:
                answer = json.load(response)
            if answer.get('errors'):
                raise RuntimeError('Score rejected')
            uploads.append(item)
            save(batch / 'score-uploads.json', uploads)
            time.sleep(3.2)
    lines = ['# Bad Case 驱动的公开验证反馈 A/B 实验', '',
        '固定 write-limit 1 类任务，两组各 3 次，共 6 次；两组均有同样公开测试，只有改进组在STOP自动验证并反馈续修。模型、工具权限、预算、初始版本和验收测试一致。',
        '唯一变量为 verification_gate_enabled，之前提示策略均关闭；选题来自已知开发集，不能推断通用能力或统计显著性。',
        '运行前已固定实验清单；包含所有计划结果，不按成绩重跑或只选成功样本。', '',
        '| 组别 | 完成运行 | 成功 | 成功率 | 平均 E2E 秒 | LLM 调用总数 | 工具成功率 |',
        '| --- | --- | --- | --- | --- | --- | --- |']
    for arm, g in groups.items():
        lines.append(f"| {arm} | {g['runs']} | {g['successes']} | {g['task_success_rate']} | {g['mean_e2e_s']} | {g['llm_calls']} | {g['tool_success_rate']} |")
    lines += ['', '| 组别 | Case | 次数 | 验收 | 状态 | 原始 Trace |', '| --- | --- | --- | --- | --- | --- |']
    for run in manifest['runs']:
        # Optional UI link belongs to the current user's project, not the original experiment.
        ui = os.environ.get('LANGFUSE_UI_BASE_URL', '').rstrip('/')
        project = os.environ.get('LANGFUSE_PROJECT_ID', '')
        url = (ui + '/project/' + project + '/traces/' + (run.get('trace_id') or '')) if ui and project else ''
        lines.append(f"| {run['arm']} | {run['task_id']} | {run['repetition']} | {run['passed']} | {run['status']} | [查看]({url}) |")
    (batch / 'README.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps(summary), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--resume', type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    if args.report:
        report(args.report.resolve(), args.publish)
        return
    credentials()
    os.environ['THOUGHTCODING_TRACING_ENABLED'] = 'true'
    os.environ['THOUGHTCODING_ENVIRONMENT'] = 'offline-ab-delivery-review'
    config, model = model_config()
    assert model['name'] == 'Qwen/Qwen3-Coder-30B-A3B-Instruct'
    if args.resume:
        batch = args.resume.resolve()
        if (batch / 'INVALIDATED.json').exists():
            raise ValueError('Invalidated batch cannot be resumed')
        manifest = json.loads((batch / 'manifest.json').read_text(encoding='utf-8'))
        assert manifest['configuration'] == sanitized(config)
    else:
        batch = ROOT / 'evals/results' / ('verification-feedback-ab-' + uuid.uuid4().hex[:10])
        batch.mkdir()
        shutil.copytree(ROOT / 'target/classes', batch / 'runtime/classes')
        shutil.copytree(ROOT / 'evals/.build', batch / 'runtime/adapter')
        runtime = {'classes': str(batch / 'runtime/classes'), 'adapter': str(batch / 'runtime/adapter'),
            'dependencies': (ROOT / 'target/eval-classpath.txt').read_text().strip(), 'local_span_backup': True}
        tasks = {t: json.loads((ROOT / 'evals/tasks' / (t + '.json')).read_text(encoding='utf-8')) for t in TASKS}
        tasks['write-limit']['public_files'] = {'src/test/java/com/thoughtcoding/publicchecks/PublicWriteCompatibilityTest.java': 'evals/public_checks/PublicWriteCompatibilityTest.java'}
        public_calibration = json.loads((ROOT / 'evals/results/public-write-check-calibration-v1/verified.json').read_text(encoding='utf-8'))
        assert public_calibration['ready'] and public_calibration['public_source_sha256'] == sha(ROOT / 'evals/public_checks/PublicWriteCompatibilityTest.java')
        calibration = json.loads((ROOT / 'evals/EXPANDED_CALIBRATION.json').read_text(encoding='utf-8'))
        for t, task in tasks.items():
            assert calibration[t]['ready'] and calibration[t]['task_sha256'] == sha(ROOT / 'evals/tasks' / (t + '.json'))
            assert calibration[t]['acceptance_sha256'] == sha(ROOT / 'evals/acceptance' / task['acceptance_file'])
        sources = list((ROOT / 'src/main/java').rglob('*.java')) + [Path(__file__), ROOT / 'evals/agent_run.py', ROOT / 'evals/run.py', ROOT / 'evals/java/EvalMain.java', ROOT / 'evals/java/EvalTelemetry.java', ROOT / 'pom.xml', ROOT / 'evals/VERIFICATION_FEEDBACK_PLAN.md']
        sources += [ROOT / 'evals/public_checks/PublicWriteCompatibilityTest.java', ROOT / 'evals/calibrate_public_checks.py', ROOT / 'evals/results/public-write-check-calibration-v1/verified.json']
        sources += [ROOT / 'evals/tasks' / (t + '.json') for t in TASKS]
        sources += [ROOT / 'evals/acceptance' / task['acceptance_file'] for task in tasks.values()]
        for source in sources:
            target = batch / 'frozen-sources' / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        rng = random.Random(20261026)
        pairs = [(t, r) for t in TASKS for r in range(1, 4)]
        rng.shuffle(pairs)
        schedule = []
        for task, repetition in pairs:
            arms = list(ARMS)
            rng.shuffle(arms)
            schedule += [{'task_id': task, 'repetition': repetition, 'arm': arm} for arm in arms]
        manifest = {'kind': 'preregistered_delivery_review_ab', 'started_at': datetime.now(timezone.utc).isoformat(),
            'tasks': tasks, 'configuration': sanitized(config), 'model': model['name'],
            'runtime_paths': runtime, 'runtime_sha256': {str(p.relative_to(batch)): sha(p) for p in (batch / 'runtime').rglob('*.class')},
            'source_sha256': {str(p.relative_to(ROOT)): sha(p) for p in sources},
            'schedule': schedule, 'workers': 2, 'runs': [], 'trial_allowance_cny': 2,
            'single_variable': 'verification_gate_enabled false vs true; deliveryReviewEnabled false in both arms',
            'hypothesis': 'Public validation failure feedback may improve final delivery; both arms share fixtures and commands; no guaranteed uplift',
            'primary_metric': 'all independent acceptance + original regression tests pass',
            'policy_generation': 'v3-public-verification-feedback', 'prior_experiment': 'conditional-review-ab-6d2165288a',
            'stop_policy': 'all 6 scheduled trials; no optional stopping based on outcomes',
            'limitations': ['selected development tasks', '3 repetitions per task', 'no held-out generalization claim']}
        save(batch / 'manifest.json', manifest)
        for arm in ARMS:
            (batch / (batch.name + '--' + arm)).mkdir()
    for rel, expected in manifest['runtime_sha256'].items():
        assert sha(batch / rel) == expected, 'Frozen runtime changed'
    for rel, expected in manifest['source_sha256'].items():
        assert sha(ROOT / rel) == expected, 'Source changed during experiment'
    print('BATCH ' + str(batch), flush=True)
    pending = []
    runs = []
    for item in manifest['schedule']:
        directory = batch / (batch.name + '--' + item['arm']) / (item['task_id'] + '-' + str(item['repetition']))
        if (directory / 'result.json').exists():
            run = json.loads((directory / 'result.json').read_text(encoding='utf-8'))
            assert 'arm' in run and 'original_status' in run, 'Interrupted finalization requires audit'
            runs.append(run)
        else:
            assert not directory.exists(), 'Interrupted trial retained for audit, never silently rerun'
            pending.append(item)
    manifest['runs'] = runs
    save(batch / 'manifest.json', manifest)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = {pool.submit(execute, manifest['tasks'][i['task_id']], config, model,
            batch / (batch.name + '--' + i['arm']), i['repetition'], manifest['runtime_paths']): i for i in pending}
        for future in as_completed(futures):
            item = futures[future]
            try:
                result = future.result()
            except Exception as error:
                result = dict(item, passed=False, status='harness_error', error_type=type(error).__name__)
                save(batch / 'INVALIDATED.json', {'reason': 'Harness error; incomplete batch excluded',
                    'trial': item, 'error_type': type(error).__name__})
                for pending_future in futures:
                    pending_future.cancel()
                raise RuntimeError('Invalid harness batch; preserve artifacts and audit before a new run') from error
            manifest['runs'].append(result)
            save(batch / 'manifest.json', manifest)
            print('PROGRESS ' + str(len(manifest['runs'])) + '/6 ' + json.dumps(item) + ' ' + result['status'], flush=True)
    report(batch)


if __name__ == '__main__':
    main()
