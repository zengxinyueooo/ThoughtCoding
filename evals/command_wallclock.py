"""Conservative elapsed-budget executor for prospective v3 runs only.

Wall time can jump and a monotonic clock may omit suspend time on some systems.
Accumulate nonnegative increments for each clock and charge the larger total,
retaining both raw clocks and anomalies. A forward jump can expire a run; such a run
must not be used for a clean latency comparison. Frozen v2 is not changed.
"""
import math
import os
from pathlib import Path
import signal
import subprocess
import time


CLOCK_DIVERGENCE_SECONDS = 0.5
TIMEOUT_BASIS = 'max_accumulated_nonnegative_clock_deltas_v3'


class _ElapsedBudget:
    def __init__(self):
        self.started_monotonic = self.last_monotonic = time.monotonic()
        self.started_wall = self.last_wall = time.time()
        self.seconds = 0.0
        self.monotonic_progress = self.wall_progress = 0.0
        self.anomalies = []

    def sample(self):
        monotonic, wall = time.monotonic(), time.time()
        monotonic_delta, wall_delta = monotonic - self.last_monotonic, wall - self.last_wall
        kinds = []
        if wall_delta < 0:
            kinds.append('wall_clock_rollback')
        if monotonic_delta < 0:
            kinds.append('monotonic_clock_rollback')
        if abs(wall_delta - monotonic_delta) > CLOCK_DIVERGENCE_SECONDS:
            kinds.append('clock_divergence_possible_suspend_or_clock_adjustment')
        if kinds:
            self.anomalies.append(dict(kinds=kinds, wall_delta_s=wall_delta,
                                       monotonic_delta_s=monotonic_delta))
        # Compare accumulated clocks, not max(delta) at every poll: Windows
        # monotonic resolution can otherwise add rounding noise on every loop.
        self.monotonic_progress += max(0.0, monotonic_delta)
        self.wall_progress += max(0.0, wall_delta)
        self.seconds = max(self.monotonic_progress, self.wall_progress)
        self.last_monotonic, self.last_wall = monotonic, wall
        return self.seconds

    def evidence(self):
        return dict(seconds=self.seconds, wall_seconds=self.last_wall - self.started_wall,
                    monotonic_seconds=self.last_monotonic - self.started_monotonic,
                    timeout_basis=TIMEOUT_BASIS, timing_anomalies=self.anomalies,
                    performance_comparable=not self.anomalies)


def _terminate_process_tree(process):
    """Best-effort tree kill, parent fallback, and bounded reap; never hide errors."""
    evidence = dict(tree_termination_attempted=True, errors=[])
    try:
        if os.name == 'nt':
            result = subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            evidence['tree_command_exit_code'] = result.returncode
            if result.returncode:
                evidence['errors'].append('taskkill_exit_' + str(result.returncode))
        else:
            os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass  # The process exited between poll and kill.
    except Exception as error:
        evidence['errors'].append(type(error).__name__)
    try:
        alive = process.poll() is None
    except Exception as error:
        alive = True
        evidence['errors'].append('poll_' + type(error).__name__)
    if alive:
        try:
            process.kill()
        except ProcessLookupError:
            pass
        except Exception as error:
            evidence['errors'].append('kill_' + type(error).__name__)
    try:
        process.wait(timeout=10)
        evidence['parent_reaped'] = True
    except Exception as error:
        evidence['parent_reaped'] = False
        evidence['errors'].append(type(error).__name__)
    evidence['cleanup_complete'] = evidence['parent_reaped'] and not evidence['errors']
    return evidence


def command(argv, cwd, log, timeout=900):
    """Run one command; budget includes startup, external pause and timeout cleanup.

Return the existing exit_code/timed_out/seconds contract plus timing evidence.
Timeout takes precedence even if the child finished while this process paused.
This covers this command, not later scoring or trace uploads.
"""
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) \
            or not math.isfinite(timeout) or timeout <= 0:
        raise ValueError('timeout must be a positive finite number of seconds')
    clock = _ElapsedBudget()
    with Path(log).open('wb') as output:
        process = subprocess.Popen(argv, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=os.name != 'nt')
        try:
            while True:
                code = process.poll()
                elapsed = clock.sample()
                if elapsed >= timeout:
                    termination = _terminate_process_tree(process) if code is None else None
                    if termination is not None:
                        clock.sample()
                    return dict(exit_code=None, actual_exit_code=process.returncode, timed_out=True,
                                timeout_seconds=timeout, termination=termination, **clock.evidence())
                if code is not None:
                    return dict(exit_code=code, timed_out=False, timeout_seconds=timeout, **clock.evidence())
                time.sleep(min(0.1, timeout - elapsed))
        except BaseException as error:
            # In particular, Ctrl+C and exceptions in the polling loop must not
            # leave a Java/Maven process continuing in the background.
            try:
                alive = process.poll() is None
            except Exception:
                alive = True
            if alive:
                termination = _terminate_process_tree(process)
                if not termination['cleanup_complete'] and hasattr(error, 'add_note'):
                    error.add_note('Command cleanup incomplete: ' + repr(termination))
            raise
