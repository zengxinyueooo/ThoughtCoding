"""Scoped prospective runtime: v2 rubric, reliable deadlines, full-span entrypoint.

Use in a fresh process, once around the complete batch, with the worker executor
INSIDE the context so every worker is joined before original bindings return::

    with bind_runtime(adapter_path=frozen_adapter):
        with ThreadPoolExecutor(max_workers=2) as pool:
            ... pool.submit(agent_run.run_task, ...) ...

This module never starts a batch, reads credentials, changes the scoring rubric,
or uploads telemetry. Freeze this source and command_wallclock.py for new runs.
"""
from contextlib import contextmanager
import os
from pathlib import Path
import threading

import agent_run
import command_wallclock
import run_v2


_binding_lock = threading.Lock()
ENTRYPOINT = 'EvalMainV3'


def _agent_arguments(argv, adapter_path, cwd):
    argv = list(argv)
    # Replace the main class, never a task/config argument that happens to have
    # the same string. Current frozen agent_run always supplies this exact form.
    if not argv or Path(str(argv[0])).stem.lower() != 'java':
        return argv
    classpath_options = [i for i, arg in enumerate(argv) if arg in ('-cp', '-classpath', '--class-path')]
    if len(classpath_options) != 1:
        raise ValueError('v3 Java commands require exactly one explicit classpath')
    position = classpath_options[0]
    if len(argv) <= position + 2 or argv[position + 2] not in ('EvalMain', ENTRYPOINT):
        raise ValueError('v3 Agent command must use the versioned evaluation entrypoint')
    # Java resolves relative classpaths from the child working directory.
    entries = [(Path(cwd) / p).resolve() for p in str(argv[position + 1]).split(os.pathsep) if p]
    if entries.count(adapter_path) != 1:
        raise ValueError('Agent classpath must contain the configured v3 adapter exactly once')
    if not (adapter_path / (ENTRYPOINT + '.class')).is_file():
        raise ValueError('Build the v3 adapter before running the Agent')
    for entry in entries[:entries.index(adapter_path)]:
        if (entry / (ENTRYPOINT + '.class')).is_file() or entry.suffix.lower() in ('.jar', '.zip') or '*' in str(entry):
            raise ValueError('Place the v3 adapter before any possible entrypoint shadowing')
    argv[position + 2] = ENTRYPOINT
    return argv


@contextmanager
def bind_runtime(*, adapter_path):
    """Bind once in the main thread; parallel Agent/grader calls share one wrapper.

Nested/concurrent bindings are rejected rather than waiting or overwriting one
another. Use separate processes for simultaneous batches. Callers must join all
workers before leaving this context, including when cancelling a batch.
    """
    if threading.current_thread() is not threading.main_thread():
        raise RuntimeError('Bind v3 once in the main thread outside the worker pool')
    if not _binding_lock.acquire(blocking=False):
        raise RuntimeError('A v3 runtime is already bound in this process')
    previous = []
    active = False
    try:
        adapter_path = Path(adapter_path).resolve()
        if not (adapter_path / (ENTRYPOINT + '.class')).is_file():
            raise ValueError('Missing compiled EvalMainV3.class in adapter_path')
        active = True

        def execute(argv, cwd, log, timeout=900):
            if not active:
                raise RuntimeError('v3 runtime scope has ended')
            return command_wallclock.command(_agent_arguments(argv, adapter_path, cwd), cwd, log, timeout)

        replacements = [(agent_run, 'command', execute), (run_v2, 'command', execute),
                        (agent_run, 'snapshot', run_v2.snapshot), (agent_run, 'check', run_v2.check)]
        for module, name, replacement in replacements:
            previous.append((module, name, getattr(module, name)))
            setattr(module, name, replacement)
        yield dict(runtime_version='v3', entrypoint=ENTRYPOINT, adapter_path=str(adapter_path),
                   timeout_basis=command_wallclock.TIMEOUT_BASIS, scoring_version='test-evidence-v2')
    finally:
        active = False
        for module, name, original in reversed(previous):
            setattr(module, name, original)
        _binding_lock.release()
