from __future__ import annotations

import hashlib
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from scripts.download_model import ensure_model


def test_downloads_model_and_verifies_checksum(tmp_path):
    payload = b"small test model bytes"

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    destination = tmp_path / "models" / "model.gguf"
    try:
        result = ensure_model(
            destination,
            f"http://127.0.0.1:{server.server_port}/model",
            hashlib.sha256(payload).hexdigest(),
        )
    finally:
        server.shutdown()
        thread.join(timeout=2)
        server.server_close()

    assert result == destination
    assert destination.read_bytes() == payload
    assert not destination.with_name("model.gguf.part").exists()


def test_rejects_checksum_mismatch_and_removes_partial_file(tmp_path):
    payload = b"not the pinned model"

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    destination = tmp_path / "model.gguf"
    try:
        with pytest.raises(RuntimeError, match="SHA-256"):
            ensure_model(destination, f"http://127.0.0.1:{server.server_port}/model", "0" * 64)
    finally:
        server.shutdown()
        thread.join(timeout=2)
        server.server_close()

    assert not destination.exists()
    assert not destination.with_name("model.gguf.part").exists()
