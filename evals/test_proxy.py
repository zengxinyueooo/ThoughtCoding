"""Transport regression checks; local HTTP only, no paid model calls."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import io
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
from agent_run import JoinedHTTPServer, Meter


class ProxyTest(unittest.TestCase):
    def test_usage_is_accounted_even_when_client_disconnects_during_delivery(self):
        class Response:
            headers = {"Content-Type": "text/event-stream"}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def __iter__(self):
                return iter([b'data: {"usage": {"prompt_tokens": 10, "completion_tokens": 2}}\n'])
        class Disconnected:
            def write(self, data): raise ConnectionAbortedError("test client disconnected")
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "http"
            directory.mkdir()
            meter = Meter({"name": "fake", "apiKey": "test-secret", "baseURL": "http://unused"}, directory, 1)
            handler_type = meter.handler()
            handler = handler_type.__new__(handler_type)
            body = b'{"stream": true, "messages": []}'
            handler.path = "/v1/chat/completions"
            handler.headers = {"Content-Length": str(len(body))}
            handler.rfile, handler.wfile = io.BytesIO(body), Disconnected()
            handler.send_response = lambda *args: None
            handler.send_header = lambda *args: None
            handler.end_headers = lambda: None
            with patch("urllib.request.urlopen", return_value=Response()):
                handler.do_POST()
            self.assertEqual(meter.calls[0]["error_type"], "ConnectionAbortedError")
            self.assertAlmostEqual(meter.calls[0]["cost_cny"], .0000126)
            self.assertAlmostEqual(meter.cost, .0000126)

    def test_first_event_arrives_before_upstream_finishes_and_usage_is_joined(self):
        release, received = threading.Event(), threading.Event()

        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                self.wfile.write(b'data: {"choices": [{"delta": {"content": "first"}}]}\n\n')
                self.wfile.flush()
                release.wait(5)
                self.wfile.write(b'data: {"choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 2}}\n\n')
                self.wfile.write(b'data: [DONE]\n\n')
                self.wfile.flush()

        upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        upstream_worker = threading.Thread(target=upstream.serve_forever)
        upstream_worker.start()
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "http"
            directory.mkdir()
            meter = Meter({"name": "fake", "apiKey": "test-secret", "baseURL":
                "http://127.0.0.1:" + str(upstream.server_port)}, directory, 1)
            proxy = JoinedHTTPServer(("127.0.0.1", 0), meter.handler())
            worker = threading.Thread(target=proxy.serve_forever)
            worker.start()
            errors = []
            def client():
                try:
                    request = urllib.request.Request("http://127.0.0.1:" + str(proxy.server_port) +
                        "/v1/chat/completions", data=json.dumps({"stream": True, "messages": []}).encode())
                    with urllib.request.urlopen(request, timeout=6) as response:
                        if b"first" in response.readline(): received.set()
                        response.read()
                except Exception as error:
                    errors.append(error)
            client_worker = threading.Thread(target=client)
            client_worker.start()
            try:
                self.assertTrue(received.wait(3), "Proxy buffered the first event until completion")
            finally:
                release.set()
                client_worker.join(7)
                proxy.shutdown()
                proxy.server_close()
                worker.join()
                upstream.shutdown()
                upstream.server_close()
                upstream_worker.join()
            self.assertFalse(errors)
            self.assertEqual(meter.calls[0]["usage"]["prompt_tokens"], 10)
            self.assertEqual(meter.calls[0]["status"], 200)
            self.assertAlmostEqual(meter.cost, (10 * .7 + 2 * 2.8) / 1_000_000)
            self.assertNotIn("test-secret", (directory / "1-request.json").read_text())

    def test_budget_rejects_before_contacting_upstream(self):
        with tempfile.TemporaryDirectory() as tmp:
            meter = Meter({"name": "fake"}, Path(tmp), 0)
            proxy = JoinedHTTPServer(("127.0.0.1", 0), meter.handler())
            worker = threading.Thread(target=proxy.serve_forever)
            worker.start()
            try:
                request = urllib.request.Request("http://127.0.0.1:" + str(proxy.server_port) +
                    "/v1/chat/completions", data=b'{"messages": []}')
                with self.assertRaises(urllib.error.HTTPError) as raised:
                    urllib.request.urlopen(request, timeout=3)
                self.assertEqual(raised.exception.code, 429)
                self.assertTrue(meter.blocked)
                self.assertEqual(meter.calls, [])
            finally:
                proxy.shutdown()
                proxy.server_close()
                worker.join()


if __name__ == "__main__":
    unittest.main()
