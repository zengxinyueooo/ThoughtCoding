"""Frozen 3-task x 3-repeat x 2-arm experiment; no optional stopping."""
import argparse
from runtime_v3 import bind_runtime
from upload_spool_v3 import load_spool
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import statistics
import uuid
import xml.etree.ElementTree as ET

import agent_run
from expanded_eval import sanitized
from run import ROOT
from run_v2 import check, snapshot, PUBLIC
from smoke import model_config
from standard_eval import credentials, save
from verification_feedback_ab import local_metrics

TASKS = ['write-limit', 'write-utf8-bytes', 'write-nul-content']
ARMS = ['baseline', 'public-tests']


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def execute(item, manifest, config, model, batch):
    arm, ident, repetition = item['arm'], item['task_id'], item['repetition']
    task = dict(manifest['tasks'][ident])
    if arm == 'public-tests': task['public_files'] = PUBLIC
    parent = batch / (batch.name + '--' + arm)
    directory = parent / (ident + '-' + str(repetition))
    # Both variants use the original system prompt, with STOP gate disabled.
    result = agent_run.run_task(task, config, model, parent, repetition, 2.0, 'baseline', True, manifest['runtime_paths'])
    result['original_status'] = result['status']
    if result['status'] == 'provider_error' and (directory / 'submission.patch').stat().st_size \
            and result['execution']['exit_code'] == 0 and not result['execution']['timed_out'] \
            and (directory / 'history.json').exists() \
            and '已达到最大工具调用轮次' not in (directory / 'history.json').read_text(encoding='utf-8'):
        verification = check(task, task['base_commit'], directory / 'recovered-verification', (shutil.which('mvn.cmd') or shutil.which('mvn')), directory / 'submission.patch')
        result['verification'] = verification
        result['passed'] = verification['status'] == verification['regression']['status'] == 'passed'
        result['status'] = 'recovered_provider_passed' if result['passed'] else 'recovered_provider_verification_failed'
    fixture = directory / 'workspace' / next(iter(PUBLIC))
    assert fixture.exists() == (arm == 'public-tests')
    if fixture.exists(): assert sha(fixture) == sha(ROOT / next(iter(PUBLIC.values())))
    contract = json.loads((directory / 'input.json').read_text(encoding='utf-8'))
    assert not contract.get('verification_gate_enabled')
    result.update(arm=arm, variant=arm, public_fixture_unchanged=True, scoring_version='test-evidence-v2')
    result.update(local_metrics(directory))
    try:
        receipt, spans = load_spool(directory)
        result['local_trace_complete'] = True
    except (OSError, ValueError, KeyError) as error:
        result['local_trace_complete'] = False
        result['trace_evidence_error'] = type(error).__name__
    result['performance_comparable'] = result['execution'].get('performance_comparable', False) and not result['execution']['timed_out']
    result['runtime_version'] = 'v3'
    result['historical_proxy_cost_estimate_cny'] = result.pop('cost_cny', None)
    result['cost_basis'] = 'historical estimate, not verified billing'
    save(directory / 'result.json', result)
    return result


def report(batch, manifest):
    groups = {}
    for arm in ARMS:
        runs = [r for r in manifest['runs'] if r['arm'] == arm]
        tools = sum(r.get('tool_calls', 0) for r in runs)
        durations = [r['e2e_latency_s'] for r in runs if r.get('e2e_latency_s') is not None and r.get('performance_comparable')]
        groups[arm] = dict(runs=len(runs), successes=sum(r['passed'] for r in runs),
            task_success_rate=sum(r['passed'] for r in runs) / len(runs) if runs else None,
            mean_e2e_s=statistics.mean(durations) if durations else None, latency_sample_count=len(durations), latency_all_runs_valid=len(durations)==len(runs),
            llm_calls=sum(r.get('llm_calls', 0) for r in runs), tool_calls=tools,
            tool_success_rate=sum(r.get('tool_successes', 0) for r in runs) / tools if tools else None,
            usage_complete_runs=sum(r['usage_complete'] for r in runs),
            per_task={t: dict(runs=sum(r['task_id'] == t for r in runs), successes=sum(r['passed'] for r in runs if r['task_id'] == t)) for t in TASKS})
    summary = dict(kind='public_diagnostic_availability_ab_v3', scheduled=18, completed=len(manifest['runs']),
        all_trials_reported=len(manifest['runs']) == 18, groups=groups, no_generalization_claim=True)
    save(batch / 'summary.json', summary)
    print(json.dumps(summary), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--resume', type=Path)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    credentials()
    config, model = model_config()
    assert model['name'] == 'Qwen/Qwen3-Coder-30B-A3B-Instruct'
    os.environ['THOUGHTCODING_TRACING_ENABLED'] = 'true'
    os.environ['THOUGHTCODING_ENVIRONMENT'] = 'offline-public-feedback-v3'
    # This process alone uses v2; historical runners and result files are untouched.
    if args.resume:
        batch = args.resume.resolve()
        assert not (batch / 'INVALIDATED.json').exists()
        manifest = json.loads((batch / 'manifest.json').read_text(encoding='utf-8'))
        assert manifest['configuration'] == sanitized(config)
    else:
        calibration = json.loads((ROOT / 'evals/results/public-feedback-v2-calibration-r2/verified.json').read_text(encoding='utf-8'))
        assert calibration['ready']
        assert json.loads((ROOT / 'evals/results/public-feedback-v2-calibration-r2/full-regression.json').read_text(encoding='utf-8'))['ready']
        expected_sets = []
        for ident in TASKS:
            reports = ROOT / 'evals/results/public-feedback-v2-calibration-r2' / (ident + '--reference/target/surefire-reports')
            suites = [ET.parse(p).getroot() for p in reports.glob('TEST-*.xml')]
            expected_sets.append({s.attrib['name']: int(s.attrib['tests']) for s in suites if not s.attrib['name'].startswith('com.thoughtcoding.eval.')})
        assert expected_sets[0] == expected_sets[1] == expected_sets[2] and sum(expected_sets[0].values()) == 118
        expected_path = ROOT / 'evals/results/public-feedback-v2-calibration-r2/expected-regression-suites.json'
        assert json.loads(expected_path.read_text(encoding='utf-8')) == expected_sets[0]
        for rel, expected in calibration['hashes'].items(): assert sha(ROOT / rel) == expected
        batch = ROOT / 'evals/results' / ('public-feedback-v3-' + uuid.uuid4().hex[:10])
        batch.mkdir()
        shutil.copytree(ROOT / 'target/classes', batch / 'runtime/classes')
        shutil.copytree(ROOT / 'evals/.build-v3', batch / 'runtime/adapter')
        runtime = dict(classes=str(batch / 'runtime/classes'), adapter=str(batch / 'runtime/adapter'),
            dependencies=(ROOT / 'target/eval-classpath.txt').read_text().strip(), local_span_backup=True)
        sources = list((ROOT / 'src/main/java').rglob('*.java'))
        sources += [ROOT / 'evals' / p for p in ('public_feedback_v3.py', 'runtime_v3.py', 'command_wallclock.py', 'upload_spool_v3.py', 'build_v3.py', 'java/EvalTelemetryV3.java', 'PUBLIC_FEEDBACK_V3_PLAN.md', 'run_v2.py', 'scoring_v2.py', 'run.py', 'agent_run.py', 'verification_feedback_ab.py', 'langfuse_metrics.py', 'expanded_eval.py', 'smoke.py', 'standard_eval.py', 'PUBLIC_FEEDBACK_V2_PLAN.md', 'java/EvalMain.java', 'java/EvalTelemetry.java', 'results/public-feedback-v2-calibration-r2/verified.json')]
        sources += [ROOT / rel for rel in calibration['hashes']]
        sources += [ROOT / 'evals' / p for p in ('calibrate_public_feedback_v2.py', 'calibrate_full_regression_v2.py', 'results/public-feedback-v2-calibration-r2/full-regression.json', 'test_scoring_v2.py')]
        sources += [expected_path]
        sources += [ROOT / 'pom.xml']
        sources = list(dict.fromkeys(sources))
        for source in sources:
            destination = batch / 'frozen-sources' / source.relative_to(ROOT)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        schedule = [dict(task_id=t, repetition=r, arm=a) for t in TASKS for r in range(1, 4) for a in ARMS]
        random.Random(20261028).shuffle(schedule)
        manifest = dict(kind='preregistered_public_feedback_v3', started_at=datetime.now(timezone.utc).isoformat(),
            configuration=sanitized(config), model=model['name'], runtime_paths=runtime,
            runtime_sha256={str(p.relative_to(batch)): sha(p) for p in (batch / 'runtime').rglob('*.class')},
            source_sha256={str(p.relative_to(ROOT)): sha(p) for p in sources},
            tasks={t: json.loads((ROOT / 'evals/tasks' / (t + '.json')).read_text(encoding='utf-8')) for t in TASKS},
            schedule=schedule, runs=[], workers=2, single_variable='extra public diagnostic tests available',
            primary_metric='identical private acceptance and full regression pass within prospectively enforced Agent time budget', runtime_version='v3', random_seed=20261028, agent_budget_seconds=900, no_outcome_driven_reruns=True,
            limitations=['3 tasks in same WriteTool family', '3 repeats are not independent cases', 'two prospectively constructed transfer cases', 'no guaranteed uplift'])
        for arm in ARMS: (batch / (batch.name + '--' + arm)).mkdir()
        save(batch / 'manifest.json', manifest)
    for rel, expected in manifest['source_sha256'].items(): assert sha(ROOT / rel) == expected, rel
    for rel, expected in manifest['runtime_sha256'].items(): assert sha(batch / rel) == expected, rel
    print('BATCH ' + str(batch), flush=True)
    if args.prepare_only: return
    pending, runs = [], []
    for item in manifest['schedule']:
        directory = batch / (batch.name + '--' + item['arm']) / (item['task_id'] + '-' + str(item['repetition']))
        if (directory / 'result.json').exists():
            saved = json.loads((directory / 'result.json').read_text(encoding='utf-8'))
            assert saved.get('arm') == item['arm'] and saved.get('scoring_version') == 'test-evidence-v2'
            runs.append(saved)
        else:
            assert not directory.exists(), 'Retain interrupted trial; do not silently rerun'
            pending.append(item)
    manifest['runs'] = runs
    save(batch / 'manifest.json', manifest)
    with bind_runtime(adapter_path=manifest['runtime_paths']['adapter']), ThreadPoolExecutor(max_workers=2) as pool:
        futures = {pool.submit(execute, item, manifest, config, model, batch): item for item in pending}
        for future in as_completed(futures):
            try:
                result = future.result()
            except Exception as error:
                save(batch / 'INVALIDATED.json', dict(reason='harness_error', error_type=type(error).__name__, trial=futures[future]))
                for queued in futures: queued.cancel()
                raise
            manifest['runs'].append(result)
            save(batch / 'manifest.json', manifest)
            print('PROGRESS ' + str(len(manifest['runs'])) + '/18 ' + result['arm'] + ' ' + result['task_id'] + ' ' + result['status'], flush=True)
    for rel, expected in manifest['source_sha256'].items(): assert sha(ROOT / rel) == expected, rel
    for rel, expected in manifest['runtime_sha256'].items(): assert sha(batch / rel) == expected, rel
    save(batch / 'freeze-end-verification.json', dict(sources_unchanged=True, runtime_unchanged=True, completed=len(manifest['runs'])))
    report(batch, manifest)


if __name__ == '__main__': main()
