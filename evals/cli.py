"""Public entrypoint for the v3 runtime / v4 transfer evaluation.

No model or Langfuse calls occur unless explicitly running the corresponding command.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EVALS = ROOT / 'evals'
TASKS = ['write-string-type', 'write-lf-budget', 'write-valid-utf16',
         'write-line-width', 'write-control-text', 'dispatcher-failure',
         'registry-invalid', 'session-latest']



def calibration_status():
    """Only a successful, unchanged calibration can admit a new paid batch."""
    path = EVALS / 'results/transfer-v4-calibration/verified.json'
    if not path.is_file():
        return False, 'Run calibrate first'
    try:
        import hashlib
        value = json.loads(path.read_text(encoding='utf-8'))
        if value.get('ready') is not True or not value.get('hashes'):
            return False, 'Calibration is incomplete'
        for relative, expected in value['hashes'].items():
            source = (ROOT / relative).resolve()
            if not source.is_relative_to(ROOT.resolve()) or not source.is_file():
                return False, 'Calibration input is missing or outside the repository'
            if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
                return False, 'Calibration inputs changed; archive and regenerate calibration'
    except (OSError, ValueError, TypeError):
        return False, 'Calibration metadata is invalid'
    return True, 'Calibration inputs verified'


def doctor():
    checks = []
    def add(name, ok, detail):
        checks.append(dict(name=name, ok=bool(ok), detail=detail))
    add('python', sys.version_info >= (3, 10), 'Python 3.10+ required')
    add('PyYAML', importlib.util.find_spec('yaml') is not None,
        'python -m pip install -r evals/requirements.txt')
    for name in ('git', 'java', 'javac', 'mvn'):
        executable = shutil.which(name + '.cmd') if name == 'mvn' and os.name == 'nt' else shutil.which(name)
        add(name, executable is not None, executable or 'missing from PATH')
        if executable and name in ('java', 'javac'):
            result = subprocess.run([executable, '-version'], capture_output=True, text=True, timeout=15)
            match = re.search(r'(?:version\s+"|javac\s+)(\d+)', result.stdout + result.stderr)
            add(name + '-21', match and int(match[1]) >= 21, 'JDK 21+ required')
    if shutil.which('git'):
        for ident in TASKS:
            task = json.loads((EVALS / 'tasks' / (ident + '.json')).read_text(encoding='utf-8'))
            for key in ('base_commit', 'reference_commit'):
                if key not in task:
                    continue
                result = subprocess.run(['git', 'cat-file', '-e', task[key] + '^{commit}'],
                                        cwd=ROOT, capture_output=True)
                add('history:' + ident + ':' + key, result.returncode == 0,
                    'Full Git history required; avoid shallow clone')
            add('acceptance:' + ident, (EVALS / 'acceptance' / task['acceptance_file']).is_file(),
                task['acceptance_file'])
    # Never print configuration values or credentials.
    report = dict(ready=all(c['ok'] for c in checks), checks=checks,
                  network_calls=0, model_calls=0,
                  calibration_ready=calibration_status()[0],
                  runtime_built=(EVALS / '.build-v3/EvalMainV3.class').is_file())
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['ready'] else 1


def invoke(script, *arguments):
    return subprocess.run([sys.executable, str(EVALS / script), *map(str, arguments)], cwd=ROOT).returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    for name in ('doctor', 'build', 'calibrate', 'test'):
        sub.add_parser(name)
    run = sub.add_parser('run', help='26 paid trials using the frozen experiment design')
    run.add_argument('--prepare-only', action='store_true')
    run.add_argument('--resume', type=Path)
    upload = sub.add_parser('spans', help='Inspect, upload or verify one trial spool')
    upload.add_argument('directory', type=Path)
    mode = upload.add_mutually_exclusive_group()
    mode.add_argument('--upload', action='store_true')
    mode.add_argument('--verify', action='store_true')
    args = parser.parse_args(argv)
    if args.action == 'doctor':
        return doctor()
    if args.action == 'build':
        code = invoke('build.py')
        return code or invoke('build_v3.py')
    if args.action == 'calibrate':
        if (EVALS / 'results/transfer-v4-calibration').exists():
            parser.error('Calibration directory already exists; preserve it and review before creating a new calibration. No overwrite performed.')
        return invoke('calibrate_transfer_v4.py')
    if args.action == 'test':
        return subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(EVALS),
            '-p', 'test_*.py'], cwd=ROOT).returncode
    if args.action == 'run':
        if not (EVALS / '.build-v3/EvalMainV3.class').is_file():
            parser.error('Run build first')
        ready, reason = calibration_status()
        if not ready:
            parser.error(reason)
        extra = ['--prepare-only'] if args.prepare_only else []
        if args.resume:
            extra += ['--resume', str(args.resume.resolve())]
        return invoke('transfer_v4.py', *extra)
    if not (args.directory / 'otlp-spool-completion.json').is_file():
        parser.error('Choose a single trial directory containing otlp-spool-completion.json, not the batch root')
    extra = ['--upload'] if args.upload else ['--verify'] if args.verify else []
    return invoke('upload_spool_v3.py', args.directory.resolve(), *extra)


if __name__ == '__main__':
    raise SystemExit(main())
