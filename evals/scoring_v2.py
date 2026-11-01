"""Fail-closed test evidence validation for future batches; no historical rescoring."""
from pathlib import Path
import xml.etree.ElementTree as ET

VERSION = 'test-evidence-v2'


def evaluate_test_reports(execution, reports, minimum_tests=1):
    result = dict(version=VERSION, passed=False, tests=0, failures=0, errors=0, skipped=0)
    if execution.get('timed_out'):
        return dict(result, reason='timeout')
    if execution.get('exit_code') != 0:
        return dict(result, reason='command_failed')
    reports = list(reports)
    if not reports or any(not Path(path).is_file() for path in reports):
        return dict(result, reason='missing_report')
    try:
        for path in reports:
            root = ET.parse(path).getroot()
            if root.tag != 'testsuite':
                raise ValueError('Expected a Surefire testsuite')
            counts = {key: int(root.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
            if any(value < 0 for value in counts.values()):
                raise ValueError('Negative count')
            if counts['tests'] != len(root.findall('testcase')):
                raise ValueError('Testcase count mismatch')
            if counts['failures'] + counts['errors'] + counts['skipped'] > counts['tests']:
                raise ValueError('Inconsistent totals')
            for key, value in counts.items():
                result[key] += value
            # Never trust only the aggregate attributes.
            if root.findall('.//failure') or root.findall('.//error') or root.findall('.//skipped'):
                return dict(result, reason='nonpassing_testcase')
    except (OSError, ET.ParseError, KeyError, ValueError):
        return dict(result, reason='invalid_report')
    if result['tests'] < minimum_tests:
        return dict(result, reason='insufficient_tests')
    if result['failures'] or result['errors'] or result['skipped']:
        return dict(result, reason='nonpassing_counts')
    return dict(result, passed=True, reason='passed')
