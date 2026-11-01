"""No model calls: adversarial checks of the future scoring contract."""
import tempfile
import unittest
from pathlib import Path
from scoring_v2 import evaluate_test_reports


class ScoringTests(unittest.TestCase):
    def test_evidence_matrix(self):
        cases = [
            ('pass', '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>', True),
            ('empty', '<testsuite tests="0" failures="0" errors="0" skipped="0"/>', False),
            ('skipped', '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase><skipped/></testcase></testsuite>', False),
            ('failure', '<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>', False),
            ('hidden_failure', '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>', False),
            ('false_count', '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase/></testsuite>', False),
            ('malformed', '<testsuite', False),
            ('missing_counts', '<testsuite><testcase/></testsuite>', False),
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.xml'
            for name, xml, expected in cases:
                with self.subTest(name=name):
                    path.write_text(xml, encoding='utf-8')
                    self.assertEqual(expected, evaluate_test_reports({'exit_code': 0, 'timed_out': False}, [path])['passed'])
            path.write_text(cases[0][1], encoding='utf-8')
            for execution in ({'exit_code': 0, 'timed_out': True}, {'exit_code': 1}, {'exit_code': None}):
                self.assertFalse(evaluate_test_reports(execution, [path])['passed'])
            self.assertFalse(evaluate_test_reports({'exit_code': 0}, [])['passed'])
            self.assertFalse(evaluate_test_reports({'exit_code': 0}, [path.with_name('missing.xml')])['passed'])
            self.assertFalse(evaluate_test_reports({'exit_code': 0}, [path], minimum_tests=2)['passed'])


if __name__ == '__main__':
    unittest.main()
