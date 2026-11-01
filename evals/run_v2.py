"""Versioned grader; same final tests for both public-feedback arms."""
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from run import ROOT, snapshot as original_snapshot, seed_public_files, command
from scoring_v2 import evaluate_test_reports, VERSION

PUBLIC = {'src/test/java/com/thoughtcoding/publicchecks/PublicWriteCompatibilityTest.java':
          'evals/public_checks/PublicWriteCompatibilityTest.java'}


def snapshot(commit, destination):
    original_snapshot(commit, destination)
    target = destination / 'src/test/java/com/thoughtcoding/tool/ToolDispatcherTimeoutTest.java'
    if not target.exists():
        raise ValueError('Unsupported starting version for regression profile v2')
    shutil.copyfile(ROOT / 'evals/regression_profiles/v2/ToolDispatcherTimeoutTest.java', target)


def apply_patch(task, destination, patch):
    if not patch:
        return
    subprocess.run(['git', 'init', '-q'], cwd=destination, check=True, capture_output=True)
    raw = subprocess.check_output(['git', 'apply', '--numstat', '-z', str(patch)], cwd=destination)
    paths = [item.decode('utf-8').split('\t', 2)[2] for item in raw.split(b'\0') if item]
    if not paths or not set(paths).issubset(set(task['allowed_paths'])):
        raise ValueError('Patch outside task scope')
    subprocess.run(['git', 'apply', '--check', str(patch)], cwd=destination, check=True, capture_output=True)
    subprocess.run(['git', 'apply', str(patch)], cwd=destination, check=True, capture_output=True)


def check(task, commit, destination, maven, patch=None):
    snapshot(commit, destination)
    final_task = dict(task, public_files=PUBLIC)
    seed_public_files(final_task, destination)
    apply_patch(task, destination, patch)
    regression = command([maven, '-B', '-ntp', 'test'], destination,
                         destination.parent / (destination.name + '-regression.log'), task['timeout_seconds'])
    reports = list((destination / 'target/surefire-reports').glob('TEST-*.xml'))
    validation = evaluate_test_reports(regression, reports, minimum_tests=118)
    expected = json.loads((ROOT / 'evals/results/public-feedback-v2-calibration-r2/expected-regression-suites.json').read_text(encoding='utf-8'))
    if validation['passed']:
        actual = {ET.parse(p).getroot().attrib['name']: int(ET.parse(p).getroot().attrib['tests']) for p in reports}
        if actual != expected:
            validation.update(passed=False, reason='regression_suite_set_or_counts_changed')
    regression.update(validation=validation, tests=validation['tests'], status='passed' if validation['passed'] else 'failed')
    acceptance = destination / 'src/test/java/com/thoughtcoding/eval' / task['acceptance_file']
    acceptance.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'evals/acceptance' / task['acceptance_file'], acceptance)
    report = destination / 'target/surefire-reports' / ('TEST-' + task['acceptance_class'] + '.xml')
    if report.exists():
        raise ValueError('Private acceptance report unexpectedly exists before first execution')
    outcome = command([maven, '-B', '-ntp', '-Dtest=' + task['acceptance_class'], '-DfailIfNoTests=true', 'test'],
                      destination, destination.parent / (destination.name + '-acceptance.log'), task['timeout_seconds'])
    evidence = evaluate_test_reports(outcome, [report], minimum_tests=3)
    outcome.update(evidence, status='passed' if evidence['passed'] else 'acceptance_failed',
                   regression=regression, commit=commit, scoring_version=VERSION,
                   regression_profile='v2-explicit-cancellation-signals', final_public_tests_equal=True)
    return outcome
