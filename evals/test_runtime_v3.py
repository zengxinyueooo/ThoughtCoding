from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import agent_run
import command_wallclock
import run_v2
from runtime_v3 import bind_runtime


class RuntimeV3Tests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.adapter = self.root / 'adapter'
        self.adapter.mkdir()
        (self.adapter / 'EvalMainV3.class').write_bytes(b'fake class for wiring tests only')

    def test_bindings_restore_after_exception_and_can_be_used_again(self):
        original = (agent_run.command, run_v2.command, agent_run.snapshot, agent_run.check)
        for iteration in range(2):
            with self.assertRaisesRegex(ValueError, 'synthetic failure'):
                with bind_runtime(adapter_path=self.adapter):
                    self.assertIs(agent_run.command, run_v2.command)
                    self.assertIs(agent_run.snapshot, run_v2.snapshot)
                    self.assertIs(agent_run.check, run_v2.check)
                    captured = agent_run.command
                    with self.assertRaisesRegex(RuntimeError, 'already bound'):
                        with bind_runtime(adapter_path=self.adapter):
                            pass
                    raise ValueError('synthetic failure')
            self.assertEqual(original, (agent_run.command, run_v2.command, agent_run.snapshot, agent_run.check))
            with self.assertRaisesRegex(RuntimeError, 'scope has ended'):
                captured(['not-executed'], self.root, self.root / 'unused.log')

    def test_workers_share_binding_but_cannot_rebind(self):
        def worker(number):
            with self.assertRaisesRegex(RuntimeError, 'main thread'):
                with bind_runtime(adapter_path=self.adapter):
                    pass
            return agent_run.command([sys.executable, '-c', 'print("worker")'],
                                     self.root, self.root / f'{number}.log', 10)
        with bind_runtime(adapter_path=self.adapter):
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(worker, range(2)))
        self.assertTrue(all(r['exit_code'] == 0 and r['timeout_basis'] == command_wallclock.TIMEOUT_BASIS
                            for r in results))

    def test_only_main_class_changes_and_old_classpath_fails_closed(self):
        with bind_runtime(adapter_path=self.adapter), patch('command_wallclock.command') as execute:
            argv = ['java', '-Dfile.encoding=UTF-8', '-cp', str(self.adapter), 'EvalMain', 'EvalMain']
            agent_run.command(argv, self.root, self.root / 'unused.log', 1)
            self.assertEqual(['java', '-Dfile.encoding=UTF-8', '-cp', str(self.adapter), 'EvalMainV3', 'EvalMain'],
                             execute.call_args.args[0])
            self.assertEqual('EvalMain', argv[4])
            execute.reset_mock()
            with self.assertRaisesRegex(ValueError, 'configured v3 adapter'):
                agent_run.command(['java', '-cp', str(self.root / 'old'), 'EvalMain'], self.root, 'unused.log')
            execute.assert_not_called()

    def test_relative_classpath_uses_child_working_directory(self):
        with bind_runtime(adapter_path=self.adapter), patch('command_wallclock.command') as execute:
            agent_run.command(['java', '-cp', 'adapter', 'EvalMain'], self.root, 'unused.log')
            self.assertEqual('EvalMainV3', execute.call_args.args[0][3])

    def test_missing_class_and_shadowing_fail_without_leaking_bindings(self):
        original = agent_run.command
        with self.assertRaisesRegex(ValueError, 'Missing compiled'):
            with bind_runtime(adapter_path=self.root / 'missing'):
                pass
        self.assertIs(original, agent_run.command)
        shadow = self.root / 'shadow'
        shadow.mkdir()
        (shadow / 'EvalMainV3.class').write_bytes(b'wrong class')
        with bind_runtime(adapter_path=self.adapter), patch('command_wallclock.command') as execute:
            with self.assertRaisesRegex(ValueError, 'shadowing'):
                agent_run.command(['java', '-cp', os.pathsep.join([str(shadow), str(self.adapter)]), 'EvalMain'],
                                  self.root, 'unused.log')
            execute.assert_not_called()

    def test_actual_agent_and_grader_paths_use_one_executor_without_model_or_network(self):
        # Exercise run_task -> run_v2.check. External model/server/Git/Maven are
        # isolated; all three command paths execute real local Python children.
        root = self.root
        acceptance = root / 'evals/acceptance/Acceptance.java'
        acceptance.parent.mkdir(parents=True)
        acceptance.write_text('// synthetic fixture')
        suites = root / 'evals/results/public-feedback-v2-calibration-r2/expected-regression-suites.json'
        suites.parent.mkdir(parents=True)
        suites.write_text(json.dumps({'regression': 118}))
        task = dict(id='synthetic', base_commit='fixture', prompt='wiring only', allowed_paths=['sample'],
                    acceptance_file='Acceptance.java', acceptance_class='synthetic.Acceptance', timeout_seconds=10)
        config = dict(defaultModel='synthetic', models={'synthetic': {}}, tools={'bash': {}}, ai={})
        model = {'name': 'synthetic-no-model'}
        calls = []
        real_command = command_wallclock.command

        def snapshot(commit, destination):
            destination.mkdir(parents=True)

        def execute(argv, cwd, log, timeout):
            calls.append(list(argv))
            cwd = Path(cwd)
            if argv[0] == 'java':
                self.assertEqual('EvalMainV3', argv[4])
                Path(argv[-1]).write_text('[]')
            else:
                reports = cwd / 'target/surefire-reports'
                reports.mkdir(parents=True, exist_ok=True)
                name, count = ('synthetic.Acceptance', 3) if any(str(a).startswith('-Dtest=') for a in argv) else ('regression', 118)
                (reports / f'TEST-{name}.xml').write_text(
                    f'<testsuite name="{name}" tests="{count}" failures="0" errors="0" skipped="0">'
                    + '<testcase/>' * count + '</testsuite>')
            return real_command([sys.executable, '-c', 'print("local command wiring check")'], cwd, log, timeout)

        class Server:
            server_port = 1234
            def __init__(self, *args): pass
            def serve_forever(self): pass
            def shutdown(self): pass
            def server_close(self): pass

        meter = SimpleNamespace(calls=[dict(status=200, usage={'prompt_tokens': 1, 'completion_tokens': 1}, cost_cny=0)],
                                cost=0, blocked=False, handler=lambda: None)
        with ExitStack() as stack:
            for target, replacement in [
                ('agent_run.ROOT', root), ('run_v2.ROOT', root), ('run_v2.snapshot', snapshot),
                ('agent_run.seed_public_files', lambda *a: None), ('run_v2.seed_public_files', lambda *a: None),
                ('run_v2.apply_patch', lambda *a: None), ('agent_run.Meter', lambda *a: meter),
                ('agent_run.JoinedHTTPServer', Server), ('agent_run.subprocess.run', lambda *a, **k: None),
                ('agent_run.subprocess.check_output', lambda *a, **k: b'synthetic patch'),
                ('command_wallclock.command', execute)]:
                stack.enter_context(patch(target, replacement))
            stack.enter_context(bind_runtime(adapter_path=self.adapter))
            result = agent_run.run_task(task, config, model, root, 1, 1, 'baseline', runtime_paths={
                'classes': str(root / 'classes'), 'adapter': str(self.adapter), 'dependencies': ''})
        self.assertTrue(result['passed'], result)
        self.assertEqual(3, len(calls))
        self.assertEqual('EvalMainV3', calls[0][4])
        for evidence in (result['execution'], result['verification'], result['verification']['regression']):
            self.assertEqual(command_wallclock.TIMEOUT_BASIS, evidence['timeout_basis'])
        self.assertEqual(118, result['verification']['regression']['tests'])
        self.assertEqual(3, result['verification']['tests'])


if __name__ == '__main__':
    unittest.main()
