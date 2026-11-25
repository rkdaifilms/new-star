import json
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vector_server import make_handler


class FakeStore:
    def health(self):
        return True

    def search(self, query, limit, source=None):
        return [{"title": "नयाँ तारा", "url": "https://example.org/ne", "source": "example.org",
                 "snippet": "नेपाली खोज नतिजा", "similarity": 0.91}]


class VectorServerTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(FakeStore(), "secret"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_authenticated_semantic_search(self):
        body = json.dumps({"query": "नेपालीमा खोज", "limit": 3}, ensure_ascii=False).encode()
        request = Request(self.base + "/search", data=body, headers={
            "Content-Type": "application/json", "Authorization": "Bearer secret"})
        with urlopen(request) as response:
            result = json.loads(response.read())
        self.assertEqual(result["retrieval"], "hybrid-vector-fts-rrf")
        self.assertEqual(result["results"][0]["title"], "नयाँ तारा")

    def test_rejects_missing_auth(self):
        body = b'{"query":"test"}'
        with self.assertRaises(HTTPError) as error:
            urlopen(Request(self.base + "/search", data=body, headers={"Content-Type": "application/json"}))
        self.assertEqual(error.exception.code, 401)

    def test_rejects_invalid_query(self):
        body = b'{"query":"  "}'
        request = Request(self.base + "/search", data=body, headers={
            "Content-Type": "application/json", "Authorization": "Bearer secret"})
        with self.assertRaises(HTTPError) as error:
            urlopen(request)
        self.assertEqual(error.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
