"""No-network failure-mode tests for durable trace upload/readback."""
import copy
from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest

from upload_spool_v3 import (attrs, decoded, digest, load_spool, mismatch_fields,
                             one_span, read, save, sync_spool, upload_lock)


TRACE = 'a' * 32
ROOT = 'b' * 16
CHILD = 'c' * 16


def attribute(key, value):
    return dict(key=key, value=dict(stringValue=value))


def fixture(directory):
    spool = directory / 'otlp-spool'
    spool.mkdir()
    shared = [attribute('langfuse.session.id', 'synthetic-v3-test'),
              attribute('langfuse.environment', 'offline-transport-test')]
    root = dict(traceId=TRACE, spanId=ROOT, name='task', kind=1,
                startTimeUnixNano='1793150400000000123', endTimeUnixNano='1793150401000000321',
                attributes=shared + [attribute('langfuse.observation.type', 'span'),
                                     attribute('langfuse.observation.input', json.dumps('test query')),
                                     attribute('langfuse.observation.output', json.dumps('test answer')),
                                     attribute('langfuse.observation.metadata.llm_call_count', '1'),
                                     attribute('langfuse.observation.metadata.tool_call_count', '0')])
    child = copy.deepcopy(root)
    child.update(spanId=CHILD, parentSpanId=ROOT, name='llm.synthetic')
    child['attributes'] = shared + [attribute('langfuse.observation.type', 'generation'),
                                   attribute('langfuse.observation.model.name', 'synthetic-not-a-model-call'),
                                   attribute('langfuse.observation.usage_details', '{"input":2,"output":1,"total":3}'),
                                   attribute('langfuse.observation.input', '[{"role":"user","content":"hello"}]'),
                                   attribute('langfuse.observation.output', '[{"role":"assistant","content":"hi"}]')]
    files, summaries, payloads = [], [], []
    for span in (child, root):
        payload = dict(resourceSpans=[dict(resource=dict(attributes=[]), scopeSpans=[dict(scope=dict(name='test'), spans=[span])])])
        filename = TRACE + '-' + span['spanId'] + '.json'
        raw = json.dumps(payload).encode()
        (spool / filename).write_bytes(raw)
        files.append(dict(file='otlp-spool/' + filename, trace_id=TRACE, span_id=span['spanId'], sha256=digest(raw)))
        summaries.append(dict(id=span['spanId'], traceId=TRACE))
        payloads.append(payload)
    (directory / 'spans.jsonl').write_text('\n'.join(json.dumps(s) for s in summaries), encoding='utf-8')
    save(directory / 'otlp-spool-completion.json', dict(schema_version=3, complete=True,
         exported_span_count=2, summary_span_count=2, span_files=files, agent_completed=True))
    return payloads


def observation(span):
    a = attrs(span)
    stamp = lambda key: datetime.fromtimestamp(int(span[key]) / 1e9, timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    result = dict(id=span['spanId'], traceId=span['traceId'], name=span['name'],
                  parentObservationId=span.get('parentSpanId'), type=a['langfuse.observation.type'].upper(),
                  input=a.get('langfuse.observation.input'), output=a.get('langfuse.observation.output'),
                  sessionId=a['langfuse.session.id'], environment=a['langfuse.environment'],
                  model=a.get('langfuse.observation.model.name', ''),
                  startTime=stamp('startTimeUnixNano'), endTime=stamp('endTimeUnixNano'),
                  usageDetails=decoded(a.get('langfuse.observation.usage_details', '{}')), level='DEFAULT')
    return result


class FakeTransport:
    target_id = 'isolated-fake-target'

    def __init__(self, mode='ok'):
        self.cloud, self.posts, self.mode = [], [], mode

    def observations(self, trace_id):
        if self.mode == 'read_error':
            raise TimeoutError('read failure')
        return copy.deepcopy(self.cloud)

    def export(self, payload):
        self.posts.append(copy.deepcopy(payload))
        if self.mode == 'timeout_no_accept':
            raise TimeoutError('ambiguous write')
        rows = [observation(s) for r in payload['resourceSpans'] for scope in r['scopeSpans'] for s in scope['spans']]
        if self.mode == 'partial':
            self.cloud.extend(rows[:1])
            return dict(partialSuccess=dict(rejectedSpans=str(len(rows) - 1)))
        self.cloud.extend(rows)
        if self.mode == 'timeout_after_accept':
            raise TimeoutError('server accepted but response was lost')
        return {}


class SpoolUploadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.payloads = fixture(self.directory)

    def sync(self, transport, upload=True):
        return sync_spool(self.directory, transport, upload=upload, attempts=1, delay=0)

    def test_roundtrip_and_repeat_sends_no_duplicate(self):
        t = FakeTransport()
        state = self.sync(t)
        self.assertTrue(state['complete'])
        self.assertEqual(state['verified_count'], 2)
        self.assertTrue(self.sync(t)['complete'])
        self.assertEqual(len(t.posts), 1)

    def test_existing_cloud_is_verified_without_post(self):
        t = FakeTransport()
        t.cloud = [observation(one_span(p)) for p in self.payloads]
        self.assertTrue(self.sync(t)['complete'])
        self.assertEqual(t.posts, [])

    def test_lost_ack_resolves_via_readback(self):
        t = FakeTransport('timeout_after_accept')
        self.assertTrue(self.sync(t)['complete'])
        self.assertTrue(self.sync(t)['complete'])
        self.assertEqual(len(t.posts), 1)

    def test_uncertain_attempt_stays_unresolved_without_replay(self):
        t = FakeTransport('timeout_no_accept')
        self.assertFalse(self.sync(t)['complete'])
        self.assertFalse(self.sync(t)['complete'])
        self.assertEqual(len(t.posts), 1)
        self.assertEqual({v['state'] for v in read(self.directory / 'spool-upload-v3.json')['spans'].values()}, {'uncertain'})

    def test_prepared_before_crash_is_not_sent_again(self):
        t = FakeTransport()
        self.sync(t, upload=False)
        checkpoint = self.directory / 'spool-upload-v3.json'
        state = read(checkpoint)
        for s in state['spans'].values():
            s['state'] = 'prepared'
        save(checkpoint, state)
        self.assertFalse(self.sync(t)['complete'])
        self.assertEqual(t.posts, [])

    def test_partial_response_does_not_replay_rejected_or_accepted(self):
        t = FakeTransport('partial')
        state = self.sync(t)
        self.assertFalse(state['complete'])
        self.assertEqual(state['verified_count'], 1)
        self.assertFalse(self.sync(t)['complete'])
        self.assertEqual(len(t.posts), 1)

    def test_wrong_io_is_conflict_not_success_or_overwrite(self):
        t = FakeTransport()
        t.cloud = [observation(one_span(p)) for p in self.payloads]
        t.cloud[0]['output'] = 'wrong answer'
        state = self.sync(t)
        self.assertFalse(state['complete'])
        self.assertEqual(state['spans'][TRACE + '-' + CHILD]['mismatch_fields'], ['output'])
        self.assertEqual(t.posts, [])

    def test_no_receipt_or_hash_corruption_fails_before_network(self):
        path = self.directory / 'otlp-spool' / (TRACE + '-' + ROOT + '.json')
        path.write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'hash'):
            self.sync(FakeTransport())
        (self.directory / 'otlp-spool-completion.json').unlink()
        with self.assertRaises(FileNotFoundError):
            self.sync(FakeTransport())

    def test_missing_parent_fails_even_with_valid_hash(self):
        path = self.directory / 'otlp-spool' / (TRACE + '-' + CHILD + '.json')
        body = read(path)
        one_span(body)['parentSpanId'] = 'd' * 16
        raw = json.dumps(body).encode()
        path.write_bytes(raw)
        receipt = read(self.directory / 'otlp-spool-completion.json')
        receipt['span_files'][0]['sha256'] = digest(raw)
        save(self.directory / 'otlp-spool-completion.json', receipt)
        with self.assertRaisesRegex(ValueError, 'parent'):
            load_spool(self.directory)

    def test_preflight_error_sends_nothing(self):
        t = FakeTransport('read_error')
        with self.assertRaises(TimeoutError):
            self.sync(t)
        self.assertEqual(t.posts, [])

    def test_exclusive_lock_prevents_concurrent_sender(self):
        with upload_lock(self.directory):
            with self.assertRaises(FileExistsError):
                self.sync(FakeTransport())

    def test_target_change_refused(self):
        t = FakeTransport()
        self.sync(t, upload=False)
        t.target_id = 'different-project'
        with self.assertRaisesRegex(ValueError, 'target'):
            self.sync(t)

    def test_usage_parent_type_time_all_verified(self):
        span = one_span(self.payloads[0])
        o = observation(span)
        self.assertEqual(mismatch_fields(span, o), [])
        o.update(parentObservationId=None, type='SPAN', startTime='2026-01-01T00:00:00Z', usageDetails={})
        self.assertEqual(mismatch_fields(span, o), ['parentObservationId', 'startTime', 'type', 'usageDetails'])


if __name__ == '__main__':
    unittest.main()
