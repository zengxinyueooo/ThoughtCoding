"""Prospective transfer grader: identical per-case suites, calibrated at each base."""
import json
import shutil
import xml.etree.ElementTree as ET
from run import ROOT, snapshot as original_snapshot, seed_public_files
from run_v2 import apply_patch, PUBLIC
from command_wallclock import command
from scoring_v2 import evaluate_test_reports

CALIBRATION=ROOT/'evals/results/transfer-v4-calibration'


def snapshot(commit,destination):
    original_snapshot(commit,destination)
    test=destination/'src/test/java/com/thoughtcoding/tool/ToolDispatcherTimeoutTest.java'
    if test.exists():shutil.copyfile(ROOT/'evals/regression_profiles/v2/ToolDispatcherTimeoutTest.java',test)


def check(task,commit,destination,maven,patch=None):
    snapshot(commit,destination)
    seed_public_files(dict(task,public_files=PUBLIC),destination)
    apply_patch(task,destination,patch)
    expected=json.loads((CALIBRATION/'expected-suites.json').read_text(encoding='utf-8'))[task['id']]
    regression=command([maven,'-B','-ntp','test'],destination,destination.parent/(destination.name+'-regression.log'),task['timeout_seconds'])
    reports=list((destination/'target/surefire-reports').glob('TEST-*.xml'))
    validation=evaluate_test_reports(regression,reports,minimum_tests=sum(expected.values()))
    if validation['passed']:
        actual={ET.parse(p).getroot().get('name'):int(ET.parse(p).getroot().get('tests')) for p in reports}
        if actual!=expected:validation.update(passed=False,reason='suite_set_or_counts_changed')
    regression.update(validation=validation,status='passed' if validation['passed'] else 'failed',tests=validation['tests'])
    acceptance=destination/'src/test/java/com/thoughtcoding/eval'/task['acceptance_file']
    acceptance.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(ROOT/'evals/acceptance'/task['acceptance_file'],acceptance)
    report=destination/'target/surefire-reports'/('TEST-'+task['acceptance_class']+'.xml')
    if report.exists():raise ValueError('Private report exists before private execution')
    outcome=command([maven,'-B','-ntp','-Dtest='+task['acceptance_class'],'-DfailIfNoTests=true','test'],destination,destination.parent/(destination.name+'-acceptance.log'),task['timeout_seconds'])
    private_count=2 if task['id']=='session-latest' else 3
    evidence=evaluate_test_reports(outcome,[report],minimum_tests=private_count)
    if evidence['passed'] and evidence['tests']!=private_count:
        evidence.update(passed=False,reason='private_test_count_changed')
    outcome.update(evidence,status='passed' if evidence['passed'] else 'acceptance_failed',regression=regression,scoring_version='test-evidence-v4-per-case-calibrated',final_public_tests_equal=True)
    return outcome
