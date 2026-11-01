import os
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from command_wallclock import command, TIMEOUT_BASIS


class WallclockTests(unittest.TestCase):
    def simulated_exit(self, monotonic, wall, timeout=900, polls=(0,)):
        child = Mock(returncode=0)
        child.poll.side_effect = polls
        with tempfile.TemporaryDirectory() as directory:
            with patch('command_wallclock.subprocess.Popen', return_value=child), \
                    patch('command_wallclock.time.monotonic', side_effect=monotonic), \
                    patch('command_wallclock.time.time', side_effect=wall), \
                    patch('command_wallclock.time.sleep'):
                result = command(['not-executed'], directory, Path(directory) / 'test.log', timeout)
        return result, child

    def test_child_completed_during_external_pause_is_still_over_budget(self):
        result, child = self.simulated_exit([10, 7010], [100, 7100])
        self.assertTrue(result['timed_out'])
        self.assertIsNone(result['exit_code'])
        self.assertEqual(0, result['actual_exit_code'])
        self.assertEqual(7000, result['seconds'])
        child.wait.assert_not_called()

    def test_wall_only_advance_catches_windows_suspend_or_forward_jump(self):
        result, child = self.simulated_exit([10, 10.2], [100, 7100])
        self.assertTrue(result['timed_out'])
        self.assertEqual(7000, result['seconds'])
        self.assertFalse(result['performance_comparable'])
        self.assertIn('clock_divergence_possible_suspend_or_clock_adjustment',
                      result['timing_anomalies'][0]['kinds'])
        child.wait.assert_not_called()

    def test_wall_rollback_does_not_erase_elapsed_time(self):
        result, _ = self.simulated_exit([10, 110, 1010], [100, 200, -400], polls=(None, 0))
        self.assertTrue(result['timed_out'])
        self.assertEqual(1000, result['seconds'])
        self.assertEqual(-500, result['wall_seconds'])
        self.assertIn('wall_clock_rollback', result['timing_anomalies'][0]['kinds'])

    def test_monotonic_rollback_is_retained_and_wall_budget_wins(self):
        result, _ = self.simulated_exit([10, -10], [100, 1001])
        self.assertTrue(result['timed_out'])
        self.assertEqual(901, result['seconds'])
        self.assertIn('monotonic_clock_rollback', result['timing_anomalies'][0]['kinds'])

    def test_boundary_is_timeout_even_for_successful_exit(self):
        result, _ = self.simulated_exit([0, 900], [100, 1000])
        self.assertTrue(result['timed_out'])

    def test_clock_resolution_noise_is_not_accumulated_per_poll(self):
        result, _ = self.simulated_exit([0, 0.125, 0.1875, 0.3125], [0, 0.1, 0.2, 0.3],
                                        polls=(None, None, 0))
        self.assertAlmostEqual(0.3125, result['seconds'])

    def test_invalid_budget_does_not_launch_child(self):
        for budget in (0, -1, float('nan'), float('inf'), True, '900'):
            with self.subTest(budget=budget), patch('command_wallclock.subprocess.Popen') as spawn:
                with self.assertRaises(ValueError):
                    command(['not-executed'], '.', 'unused.log', budget)
                spawn.assert_not_called()

    def test_normal_and_failed_child_exits_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            for code in (0, 7):
                with self.subTest(code=code):
                    log = Path(directory) / 'test.log'
                    result = command([sys.executable, '-c', f'print("ok"); raise SystemExit({code})'],
                                     directory, log, 10)
                    self.assertEqual(code, result['exit_code'])
                    self.assertFalse(result['timed_out'])
                    self.assertEqual(TIMEOUT_BASIS, result['timeout_basis'])
                    self.assertIn('ok', log.read_text())
                    log.unlink()

    def test_over_budget_child_is_terminated_and_reaped(self):
        children = []
        original = subprocess.Popen
        def spawn(*args, **kwargs):
            process = original(*args, **kwargs)
            children.append(process)
            return process
        with tempfile.TemporaryDirectory() as directory:
            with patch('command_wallclock.subprocess.Popen', side_effect=spawn):
                result = command([sys.executable, '-c', 'import time; time.sleep(30)'],
                                 directory, Path(directory) / 'test.log', 0.3)
        self.assertTrue(result['timed_out'])
        self.assertIsNone(result['exit_code'])
        self.assertTrue(result['termination']['cleanup_complete'])
        self.assertTrue(all(p.poll() is not None for p in children))

    def test_interruption_terminates_child_and_preserves_exception(self):
        children = []
        original = subprocess.Popen
        def spawn(*args, **kwargs):
            process = original(*args, **kwargs)
            children.append(process)
            return process
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'test.log'
            with patch('command_wallclock.subprocess.Popen', side_effect=spawn), \
                    patch('command_wallclock.time.sleep', side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    command([sys.executable, '-c', 'import time; time.sleep(30)'], directory, log, 10)
            log.unlink()
        self.assertTrue(all(p.poll() is not None for p in children))

    @unittest.skipUnless(os.name == 'nt', 'Windows process-tree cleanup integration')
    def test_windows_timeout_also_terminates_grandchild(self):
        import ctypes
        from ctypes import wintypes
        script = ('import subprocess, sys, time; '
                  'p=subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"]); '
                  'print(p.pid, flush=True); time.sleep(30)')
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'tree.log'
            result = command([sys.executable, '-c', script], directory, log, 1)
            pid = int(log.read_text().strip())
        self.assertTrue(result['timed_out'])
        self.assertTrue(result['termination']['cleanup_complete'])
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if handle:
            try:
                code = wintypes.DWORD()
                self.assertTrue(kernel.GetExitCodeProcess(handle, ctypes.byref(code)))
                self.assertNotEqual(259, code.value)  # STILL_ACTIVE
            finally:
                kernel.CloseHandle(handle)

    def test_tree_kill_failure_falls_back_and_is_not_hidden(self):
        child = Mock(pid=123, returncode=-1)
        child.poll.return_value = None
        from command_wallclock import _terminate_process_tree
        with patch('command_wallclock.os.name', 'nt'), \
                patch('command_wallclock.subprocess.run', side_effect=OSError):
            evidence = _terminate_process_tree(child)
        child.kill.assert_called_once()
        child.wait.assert_called_once_with(timeout=10)
        self.assertFalse(evidence['cleanup_complete'])
        self.assertTrue(evidence['parent_reaped'])

    def test_poll_exception_does_not_bypass_cleanup_or_hide_original_error(self):
        child = Mock(pid=123, returncode=-1)
        child.poll.side_effect = OSError('synthetic poll error')
        from command_wallclock import _terminate_process_tree
        with patch('command_wallclock.os.name', 'nt'), \
                patch('command_wallclock.subprocess.run', return_value=Mock(returncode=0)):
            evidence = _terminate_process_tree(child)
        child.kill.assert_called_once()
        child.wait.assert_called_once_with(timeout=10)
        self.assertIn('poll_OSError', evidence['errors'])
        self.assertTrue(evidence['parent_reaped'])


if __name__ == '__main__': unittest.main()
