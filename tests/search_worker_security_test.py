from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "ai-engine" / "self-hosted" / "search-worker" / "main.py"
SPEC = importlib.util.spec_from_file_location("new_star_private_search_worker", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load New Star private search worker")
worker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker)


class SearchWorkerURLSafetyTests(unittest.TestCase):
    @patch.object(worker.socket, "getaddrinfo")
    def test_rejects_private_dns_destination(self, resolve):
        resolve.return_value = [(None, None, None, None, ("127.0.0.1", 443))]
        with self.assertRaisesRegex(worker.HTTPException, "Only public"):
            worker.validate_url("https://example.com/")

    @patch.object(worker.socket, "getaddrinfo")
    def test_rejects_mixed_public_and_private_dns_answers(self, resolve):
        resolve.return_value = [
            (None, None, None, None, ("8.8.8.8", 443)),
            (None, None, None, None, ("10.0.0.8", 443)),
        ]
        with self.assertRaisesRegex(worker.HTTPException, "Only public"):
            worker.validate_url("https://example.com/")

    @patch.object(worker.socket, "getaddrinfo")
    def test_rejects_nonstandard_ports(self, resolve):
        with self.assertRaisesRegex(worker.HTTPException, "Only public"):
            worker.validate_url("https://example.com:8443/")
        resolve.assert_not_called()


class SearchWorkerResponseSafetyTests(unittest.TestCase):
    def make_response(self, status=200, headers=None, chunks=()):
        peer = SimpleNamespace(getpeername=lambda: ("8.8.8.8", 443))
        connection = SimpleNamespace(sock=peer)
        response = Mock()
        response.raw = SimpleNamespace(_connection=connection)
        response.status_code = status
        response.headers = headers or {"content-type": "text/plain"}
        response.encoding = "utf-8"
        response.iter_content.return_value = iter(chunks)
        response.close = Mock()
        return response

    @patch.object(worker, "validate_url", return_value="https://example.com/")
    @patch.object(worker.requests, "Session")
    def test_redirect_is_rejected_and_response_closed(self, session_factory, _validate):
        response = self.make_response(status=302, headers={"location": "http://127.0.0.1/"})
        session_factory.return_value.get.return_value = response
        with self.assertRaisesRegex(worker.HTTPException, "redirects are not followed"):
            worker.fetch_page("https://example.com/")
        session_factory.return_value.get.assert_called_once()
        self.assertFalse(session_factory.return_value.get.call_args.kwargs["allow_redirects"])
        response.close.assert_called()

    @patch.object(worker, "validate_url", return_value="https://example.com/")
    @patch.object(worker.requests, "Session")
    def test_declared_oversize_response_is_rejected_before_body_read(self, session_factory, _validate):
        response = self.make_response(headers={
            "content-type": "text/plain",
            "content-length": str(worker.MAX_BYTES + 1),
        })
        session_factory.return_value.get.return_value = response
        with self.assertRaisesRegex(worker.HTTPException, "2 MiB"):
            worker.fetch_page("https://example.com/")
        response.iter_content.assert_not_called()
        response.close.assert_called()

    @patch.object(worker, "validate_url", return_value="https://example.com/")
    @patch.object(worker.requests, "Session")
    def test_streamed_oversize_response_is_rejected_and_closed(self, session_factory, _validate):
        response = self.make_response(chunks=[b"x" * (worker.MAX_BYTES + 1)])
        session_factory.return_value.get.return_value = response
        with self.assertRaisesRegex(worker.HTTPException, "2 MiB"):
            worker.fetch_page("https://example.com/")
        response.close.assert_called()


if __name__ == "__main__":
    unittest.main()
