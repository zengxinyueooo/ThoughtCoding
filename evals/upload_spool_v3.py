"""Validate a finished v3 local spool, then explicitly upload/read back original spans.

Default is local-only. No model calls or score writes. A durable prepared marker
precedes every POST. Uncertain/partial/previously accepted requests are never
blindly replayed; --verify can resolve them through the Observations v2 API.
"""
import argparse
import base64
from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


VERSION = 'durable-spool-v3'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    """Same-directory atomic replacement, with data flushed before the rename."""
    path = Path(path)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temporary.open('xb') as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2).encode('utf-8'))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def attribute_value(value):
    for key in ('stringValue', 'boolValue', 'doubleValue', 'bytesValue'):
        if key in value:
            return value[key]
    if 'intValue' in value:
        return int(value['intValue'])
    if 'arrayValue' in value:
        return [attribute_value(v) for v in value['arrayValue'].get('values', [])]
    if 'kvlistValue' in value:
        return {v['key']: attribute_value(v['value']) for v in value['kvlistValue'].get('values', [])}
    raise ValueError('Unsupported OTLP attribute value')


def attrs(span):
    return {a['key']: attribute_value(a['value']) for a in span.get('attributes', [])}


def decoded(value):
    if isinstance(value, str):
        try:
            return json.loads(value)
        except (ValueError, TypeError):
            pass
    return value


def one_span(payload):
    resources = payload['resourceSpans']
    if len(resources) != 1 or len(resources[0]['scopeSpans']) != 1:
        raise ValueError('Expected one resource and scope per spool file')
    spans = resources[0]['scopeSpans'][0]['spans']
    if len(spans) != 1:
        raise ValueError('Expected exactly one original span per file')
    return spans[0]


def load_spool(directory):
    directory = Path(directory).resolve()
    receipt_path = directory / 'otlp-spool-completion.json'
    receipt = read(receipt_path)
    if receipt.get('schema_version') != 3 or receipt.get('complete') is not True:
        raise ValueError('No successful v3 persistence completion receipt')
    entries = {}
    for item in receipt['span_files']:
        relative = item['file']
        # Only the canonical spool-relative path can be uploaded.
        if not re.fullmatch(r'otlp-spool/[0-9a-f]{32}-[0-9a-f]{16}\.json', relative):
            raise ValueError('Unsafe spool filename')
        filename = relative.split('/')[1]
        path = directory / relative
        raw = path.read_bytes()
        if digest(raw) != item['sha256']:
            raise ValueError('Spool hash mismatch')
        payload = json.loads(raw)
        span = one_span(payload)
        key = span['traceId'] + '-' + span['spanId']
        if filename != key + '.json' or key in entries:
            raise ValueError('Duplicate or mismatched original span identity')
        if span['traceId'] != item['trace_id'] or span['spanId'] != item['span_id']:
            raise ValueError('Receipt identity mismatch')
        if not (0 < int(span['startTimeUnixNano']) <= int(span['endTimeUnixNano'])):
            raise ValueError('Invalid span time interval')
        entries[key] = dict(payload=payload, span=span, sha256=item['sha256'])
    if not entries or len(entries) != receipt['exported_span_count']:
        raise ValueError('Missing local spans')
    if {p.name for p in (directory / 'otlp-spool').glob('*.json')} != {k + '.json' for k in entries}:
        raise ValueError('Spool directory differs from completion receipt')
    summaries = [json.loads(line) for line in (directory / 'spans.jsonl').read_text(encoding='utf-8').splitlines()]
    summary_ids = [r['traceId'] + '-' + r['id'] for r in summaries]
    if len(summaries) != receipt['summary_span_count'] or Counter(summary_ids) != Counter(entries.keys()):
        raise ValueError('Summary and original span identities differ')
    traces = {}
    for entry in entries.values():
        span = entry['span']
        traces.setdefault(span['traceId'], []).append(span)
    for trace_spans in traces.values():
        by_id = {s['spanId']: s for s in trace_spans}
        roots = [s for s in trace_spans if not s.get('parentSpanId') or s['parentSpanId'] == '0' * 16]
        if len(roots) != 1 or roots[0]['name'] != 'task':
            raise ValueError('Expected one original task root per trace')
        root = roots[0]
        for span in trace_spans:
            visited, current = set(), span
            while current is not root:
                if current['spanId'] in visited or current.get('parentSpanId') not in by_id:
                    raise ValueError('Missing parent or cyclic trace hierarchy')
                visited.add(current['spanId'])
                current = by_id[current['parentSpanId']]
        root_attrs = attrs(root)
        counts = Counter(str(attrs(s).get('langfuse.observation.type', 'span')).lower() for s in trace_spans)
        for counter, kind in (('llm_call_count', 'generation'), ('tool_call_count', 'tool')):
            value = root_attrs.get('langfuse.observation.metadata.' + counter)
            if value is None or int(value) != counts[kind]:
                raise ValueError('Root counter disagrees with complete local spans')
    return receipt, entries


def epoch_millis(value):
    return int(datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp() * 1000)


def mismatch_fields(span, observation):
    """Compare identities, hierarchy, stored IO/model/usage and ms-resolution time.

    This verifies Langfuse's observation representation; the original OTLP payload
    remains the authoritative source for events/links/scope/nanosecond precision.
    """
    a = attrs(span)
    expected = dict(id=span['spanId'], traceId=span['traceId'], name=span['name'],
                    parentObservationId=span.get('parentSpanId') or None,
                    type=str(a.get('langfuse.observation.type', 'span')).upper())
    if expected['parentObservationId'] == '0' * 16:
        expected['parentObservationId'] = None
    for attr_name, field in [('langfuse.session.id', 'sessionId'), ('langfuse.environment', 'environment'),
                             ('langfuse.observation.model.name', 'model')]:
        if attr_name in a:
            expected[field] = a[attr_name]
    errors = [field for field, value in expected.items() if observation.get(field) != value]
    for field in ('input', 'output'):
        attr_name = 'langfuse.observation.' + field
        if attr_name in a and (field not in observation or decoded(a[attr_name]) != decoded(observation[field])):
            errors.append(field)
    usage = decoded(a.get('langfuse.observation.usage_details', '{}'))
    remote_usage = observation.get('usageDetails') or {}
    if not isinstance(usage, dict) or any(remote_usage.get(k) != v for k, v in usage.items()):
        errors.append('usageDetails')
    for local, remote in [('startTimeUnixNano', 'startTime'), ('endTimeUnixNano', 'endTime')]:
        try:
            if abs(int(span[local]) // 1_000_000 - epoch_millis(observation[remote])) > 1:
                errors.append(remote)
        except (KeyError, ValueError, TypeError):
            errors.append(remote)
    if span.get('status', {}).get('code') == 2 and observation.get('level') != 'ERROR':
        errors.append('level')
    return sorted(set(errors))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, 'API redirect refused', headers, fp)


class LangfuseTransport:
    def __init__(self):
        self.base = os.environ['LANGFUSE_BASE_URL'].rstrip('/')
        uri = urllib.parse.urlsplit(self.base)
        if uri.scheme not in ('http', 'https') or not uri.hostname or uri.username or uri.password or uri.query or uri.fragment:
            raise ValueError('Invalid Langfuse base URL')
        if uri.scheme != 'https' and uri.hostname not in ('localhost', '127.0.0.1', '::1'):
            raise ValueError('Unencrypted remote Langfuse endpoint refused')
        public, secret = os.environ['LANGFUSE_PUBLIC_KEY'], os.environ['LANGFUSE_SECRET_KEY']
        if not public or not secret:
            raise ValueError('Langfuse credentials are empty')
        self.target_id = digest((self.base + ':' + public).encode())
        self.headers = {'Content-Type': 'application/json',
                        'Authorization': 'Basic ' + base64.b64encode((public + ':' + secret).encode()).decode()}
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, body=None):
        headers = dict(self.headers)
        if body is not None:
            headers['x-langfuse-ingestion-version'] = '4'
        raw = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, headers=headers, data=raw)
        with self.opener.open(request, timeout=30) as response:
            data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024:
                raise ValueError('API response exceeds size limit')
            return json.loads(data)

    def observations(self, trace_id):
        result, cursor, cursors = [], None, set()
        for _ in range(100):
            query = dict(traceId=trace_id, fields='core,basic,io,usage,model', limit=100)
            if cursor:
                query['cursor'] = cursor
            page = self.request('/api/public/v2/observations?' + urllib.parse.urlencode(query))
            result.extend(page['data'])
            cursor = page.get('meta', {}).get('nextCursor')
            if not cursor:
                return result
            if cursor in cursors:
                raise ValueError('Observation pagination repeated a cursor')
            cursors.add(cursor)
            time.sleep(3.2)
        raise ValueError('Observation pagination exceeded bound')

    def export(self, payload):
        return self.request('/api/public/otel/v1/traces', payload)


@contextmanager
def upload_lock(directory):
    lock = directory / '.spool-upload-v3.lock'
    lock.mkdir()  # A stale lock must be inspected, never automatically stolen.
    try:
        (lock / 'owner.json').write_text(json.dumps(dict(pid=os.getpid())), encoding='utf-8')
        yield
    finally:
        (lock / 'owner.json').unlink(missing_ok=True)
        lock.rmdir()


def sync_spool(directory, transport, upload=False, attempts=3, delay=4):
    directory = Path(directory).resolve()
    receipt, entries = load_spool(directory)
    checkpoint = directory / 'spool-upload-v3.json'
    with upload_lock(directory):
        frozen = {key: row['sha256'] for key, row in entries.items()}
        if checkpoint.exists():
            state = read(checkpoint)
            if state['target_id'] != transport.target_id or state['payload_hashes'] != frozen:
                raise ValueError('Upload target or original payload changed since checkpoint')
        else:
            state = dict(version=VERSION, target_id=transport.target_id, payload_hashes=frozen,
                         spans={key: dict(state='new') for key in entries})
        trace_ids = sorted({row['span']['traceId'] for row in entries.values()})

        def readback():
            cloud = {}
            for i, trace_id in enumerate(trace_ids):
                if i:
                    time.sleep(delay)
                observations = transport.observations(trace_id)
                for observation in observations:
                    key = observation['traceId'] + '-' + observation['id']
                    if key in cloud:
                        raise ValueError('Duplicate observation identity returned by cloud')
                    cloud[key] = observation
            for key, entry in entries.items():
                item = state['spans'][key]
                if key in cloud:
                    errors = mismatch_fields(entry['span'], cloud[key])
                    item.update(state='conflict' if errors else 'verified', mismatch_fields=errors)
                elif item['state'] in ('verified', 'conflict'):
                    item.update(state='readback_missing', mismatch_fields=[])
            state['last_readback_at'] = datetime.now(timezone.utc).isoformat()
            state['verified_count'] = sum(item['state'] == 'verified' for item in state['spans'].values())
            state['complete'] = state['verified_count'] == len(entries)
            save(checkpoint, state)

        # A failed preflight never sends anything. Prior receipt != proof of visibility.
        readback()
        if upload:
            pending = [key for key in entries if state['spans'][key]['state'] == 'new']
            # Bound request payloads; every group gets its own durable intent marker.
            groups, group, size = [], [], 0
            for key in pending:
                estimated = len(json.dumps(entries[key]['payload'], ensure_ascii=False).encode())
                if estimated > 3 * 1024 * 1024:
                    raise ValueError('Single span exceeds upload size bound')
                if group and size + estimated > 3 * 1024 * 1024:
                    groups.append(group)
                    group, size = [], 0
                group.append(key)
                size += estimated
            if group:
                groups.append(group)
            for index, keys in enumerate(groups):
                if index:
                    time.sleep(delay)
                payload = dict(resourceSpans=[r for key in keys for r in entries[key]['payload']['resourceSpans']])
                for key in keys:
                    state['spans'][key].update(state='prepared', attempted_at=datetime.now(timezone.utc).isoformat())
                save(checkpoint, state)
                try:
                    response = transport.export(payload)
                    if not isinstance(response, dict):
                        raise ValueError('Unexpected OTLP response')
                    partial = response.get('partialSuccess')
                    status = 'partial_response' if partial is not None else 'accepted_pending_readback'
                    for key in keys:
                        state['spans'][key].update(state=status)
                        if partial is not None:
                            state['spans'][key]['rejected_spans_in_request'] = partial.get('rejectedSpans', 'unknown')
                except Exception as error:
                    for key in keys:
                        state['spans'][key].update(state='uncertain', error_type=type(error).__name__)
                        if isinstance(error, urllib.error.HTTPError):
                            state['spans'][key]['http_status'] = error.code
                save(checkpoint, state)
        for _ in range(attempts):
            if state['complete']:
                break
            time.sleep(delay)
            readback()
        state.update(local_complete=True, agent_completed=receipt.get('agent_completed'),
                     model_calls_by_uploader=0, score_writes=0,
                     expected_count=len(entries), accepted_spans_replayed=False)
        save(checkpoint, state)
        return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run_directory', type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--upload', action='store_true')
    mode.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    receipt, entries = load_spool(args.run_directory)
    if not (args.upload or args.verify):
        print(json.dumps(dict(local_complete=True, spans=len(entries), agent_completed=receipt.get('agent_completed'), network_calls=0)))
        return
    state = sync_spool(args.run_directory, LangfuseTransport(), upload=args.upload)
    print(json.dumps({key: state[key] for key in ('complete', 'verified_count', 'expected_count', 'model_calls_by_uploader', 'score_writes')}))
    if not state['complete']:
        raise SystemExit(2)


if __name__ == '__main__':
    main()
