"""Export a reviewed publication overlay without reports, local credentials or results.

Run from any directory: python evals/prepare_publication.py
The new directory is under ignored evals/results; no Git index or remote is modified.
"""
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import uuid

ROOT = Path(__file__).resolve().parents[1]
EVALS = ROOT / 'evals'
ENTRYPOINTS = ['cli', 'build', 'build_v3', 'calibrate_transfer_v4', 'transfer_v4',
              'upload_spool_v3', 'prepare_transfer_v4', 'prepare_publication',
              'test_cli', 'test_command_wallclock', 'test_proxy', 'test_runtime_v3',
              'test_scoring_v2', 'test_upload_spool_v3', 'test_prepare_publication']
PATTERNS = {
    'credential_format': re.compile(r'(?:sk|pk)-(?:lf-)?[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----'),
    'personal_home_path': re.compile(r'(?:[A-Za-z]:[/\\]Users[/\\][^/\\\s]+|/(?:home|Users)/[^/\s]+)'),
    'email_address': re.compile(r'[\w.+-]+@(?!(?:example\.com|thoughtcoding\.local)(?![\w.-]))[\w.-]+\.[A-Za-z]{2,}'),
    'historical_langfuse_id': re.compile(r'cmv[a-z0-9]{15,}'),
}


def modules():
    pending = list(ENTRYPOINTS)
    found = set()
    while pending:
        name = pending.pop()
        path = EVALS / (name + '.py')
        if name in found or not path.is_file():
            continue
        found.add(name)
        for node in ast.walk(ast.parse(path.read_text(encoding='utf-8-sig'))):
            if isinstance(node, ast.Import):
                pending.extend(a.name.split('.')[0] for a in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                pending.append(node.module.split('.')[0])
    return sorted(found)


def local_secrets():
    """In-memory comparison only. Values are never written to the export or audit."""
    import yaml
    result = set()
    def collect(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if re.search('key|token|secret|password', str(key), re.I) and isinstance(child, str) and len(child) >= 12:
                    if not re.search('REPLACE|YOUR_|EXAMPLE', child, re.I):
                        result.add(child)
                else:
                    collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)
    for relative in ('config.yaml', 'src/main/resources/config.yaml'):
        path = ROOT / relative
        if path.is_file():
            collect(yaml.safe_load(path.read_text(encoding='utf-8-sig')))
    env = ROOT / '.env'
    if env.is_file():
        for line in env.read_text(encoding='utf-8-sig').splitlines():
            key, separator, value = line.partition('=')
            value = value.strip().strip('\"\'')
            if separator and re.search('KEY|TOKEN|SECRET|PASSWORD', key, re.I) and len(value) >= 12:
                result.add(value)
    return result


def sources():
    selected = {EVALS / (name + '.py') for name in modules()}
    for directory in ('java', 'tasks', 'acceptance', 'public_checks', 'reference',
                      'reference-patches', 'regression_profiles'):
        selected.update(p for p in (EVALS / directory).rglob('*') if p.is_file())
    # Include production sources/tests, respecting local config exclusion explicitly.
    selected.update(p for p in (ROOT / 'src').rglob('*')
                    if p.is_file() and p.name != 'config.yaml' and p.suffix not in ('.class', '.pyc'))
    for relative in ('pom.xml', 'LICENSE', '.gitignore', '.env.example',
                     'config.example.yaml', 'start-local.ps1', 'evals/requirements.txt',
                     'evals/config.siliconflow.example.yaml', 'evals/USAGE.md'):
        selected.add(ROOT / relative)
    missing = [p.relative_to(ROOT).as_posix() for p in selected if not p.is_file()]
    if missing:
        raise ValueError('Missing required source files: ' + ', '.join(missing))
    return sorted(selected)


def scan(directory, secrets):
    findings = []
    for path in sorted(directory.rglob('*')):
        if not path.is_file():
            continue
        try:
            lines = path.read_text(encoding='utf-8-sig').splitlines()
        except UnicodeError:
            findings.append(dict(file=path.relative_to(directory).as_posix(), kind='unreviewed_binary'))
            continue
        for number, line in enumerate(lines, 1):
            kinds = [kind for kind, pattern in PATTERNS.items() if pattern.search(line)]
            if any(secret in line for secret in secrets):
                kinds.append('exact_local_credential')
            for kind in kinds:
                findings.append(dict(file=path.relative_to(directory).as_posix(), line=number, kind=kind))
    return findings


PROTOCOL = '''# Public evaluation protocol

This is a reproducible prospective design, not an experimental results report.
Use cli.py and USAGE.md. The runtime is v3; transfer scoring is v4.

- Five WriteTool requirements, two trials per arm: 20 trials.
- Three historical regression tasks, one trial per arm: 6 trials.
- Baseline versus availability of the same public normal-path compatibility checks.
- Same model, starting code, final private acceptance, regression, tool restrictions,
  time/iteration budget and historical budget estimate in both arms.
- Random interleaving with seed 20261029, two workers, 26 planned trials.
- Freeze inputs/runtime before model calls; retain every result. Do not rerun for a better outcome.
- Calibrate base failure, reference success and applicable known-bad rejection using real Java tests.
- Final grading requires command success, complete expected test suites and private acceptance.
  Timeouts, skipped/missing tests and budget exhaustion are not successful delivery.
- Report the new requirements and historical regression cohorts separately.
- Retain incomplete telemetry as incomplete; missing usage is not zero.
- Local tools are restricted but this is not an operating-system sandbox.
- This is an internal, seen-module suite. No cross-module or statistically significant improvement claim.

Model, budget details, generated evidence and optional Langfuse upload steps are in USAGE.md.
The older protocol filenames are retained solely for source-freeze compatibility;
use cli.py rather than the historical batch entrypoints.
'''


def main():
    output = EVALS / 'results' / ('github-ready-' + uuid.uuid4().hex[:10])
    output.mkdir(parents=True)
    for source in sources():
        target = output / source.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    for name in ('TRANSFER_V4_PLAN.md', 'PUBLIC_FEEDBACK_V3_PLAN.md', 'PUBLIC_FEEDBACK_V2_PLAN.md'):
        (output / 'evals' / name).write_text(PROTOCOL, encoding='utf-8')
    (output / 'evals/README.md').write_text('# Evaluation\n\nStart with [USAGE.md](USAGE.md).\n', encoding='utf-8')
    (output / 'PUBLICATION.md').write_text(
        '# ThoughtCoding evaluation publication overlay\n\n'
        'These files are an overlay for the existing ThoughtCoding repository. Preserve its full Git history: '
        'task commits must remain available. Do not create a new history containing only this directory.\n\n'
        'Start with [evals/USAGE.md](evals/USAGE.md). Reports, credentials and raw results are excluded. '
        'Merge these paths into a reviewed branch; this export does not commit or push anything.\n', encoding='utf-8')
    findings = scan(output, local_secrets())
    # Hash only payload files, excluding the manifest itself.
    files = {p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(output.rglob('*')) if p.is_file()}
    audit = dict(ready=not findings, files=files, findings=findings,
                 report_data_included=False, git_history_included=False,
                 scan_scope='known local credentials, key formats, personal home paths, non-fixture email addresses, historical Langfuse IDs and binary rejection',
                 limitation='Pattern and known-value checks do not prove absence of every possible private string.')
    (output / 'PUBLISH_FILES.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding='utf-8')
    print(output)
    print(json.dumps(dict(ready=audit['ready'], files=len(files), findings=findings), ensure_ascii=False, indent=2))
    return 0 if audit['ready'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
