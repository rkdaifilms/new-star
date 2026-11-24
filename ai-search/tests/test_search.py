import json
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from new_star_search import PageParser, SafeCrawler, SearchIndex, make_handler, normalize_url, host_is_allowed, resolve_public_host

class SearchIndexTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.index=SearchIndex(Path(self.temp.name)/"index.sqlite3")
    def tearDown(self):
        self.temp.cleanup()
    def test_index_and_rank_relevant_pages(self):
        self.index.upsert("https://example.org/ai","New Star AI","Multilingual foundation model and neural search engine")
        self.index.upsert("https://example.org/ride","Nepal Ride Booking","Local transport and driver services")
        results=self.index.search("multilingual model",5)
        self.assertEqual(len(results),1)
        self.assertEqual(results[0]["title"],"New Star AI")
        self.assertEqual(results[0]["source"],"example.org")
    def test_upsert_updates_existing_url(self):
        self.index.upsert("https://example.org/page","Old title","old searchable text")
        self.index.upsert("https://example.org/page","New title","new searchable material")
        self.assertEqual(self.index.page_count(),1)
        self.assertEqual(self.index.search("new searchable")[0]["title"],"New title")
    def test_query_and_limit_validation(self):
        self.index.upsert("https://example.org/page","Search","indexed content")
        self.assertEqual(self.index.search("!!!"),[])
        self.assertLessEqual(len(self.index.search("indexed content",100)),10)
    def test_html_parser_extracts_title_text_and_links_without_script_text(self):
        parser=PageParser("https://example.org/start")
        parser.feed('<html><title>Example</title><script>secret injected</script><body>Visible text<a href="/next">Next</a></body></html>')
        title,body,links=parser.parsed()
        self.assertEqual(title,"Example")
        self.assertIn("Visible text",body)
        self.assertNotIn("secret injected",body)
        self.assertIn("https://example.org/next",links)
    def test_url_and_domain_policy(self):
        self.assertEqual(normalize_url("https://EXAMPLE.org"),"https://example.org/")
        with self.assertRaises(ValueError): normalize_url("http://example.org")
        with self.assertRaises(ValueError): normalize_url("https://user:pass@example.org")
        self.assertTrue(host_is_allowed("news.example.org",("example.org",)))
        self.assertFalse(host_is_allowed("example.org.attacker.test",("example.org",)))
    def test_crawler_rejects_domains_outside_allowlist_before_dns(self):
        crawler=SafeCrawler(self.index,("allowed.example",),max_pages=2)
        with self.assertRaises(ValueError):
            crawler._check_url("https://not-allowed.example/page")
    def test_private_dns_destinations_are_blocked(self):
        with patch("socket.getaddrinfo",return_value=[(2,1,6,"",("127.0.0.1",443))]):
            with self.assertRaisesRegex(ValueError,"private, local, or reserved"):
                resolve_public_host("public-looking.example")
    def test_authenticated_search_http_endpoint(self):
        server=ThreadingHTTPServer(("127.0.0.1",0),make_handler(self.index,"test-secret"))
        thread=threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            base=f"http://127.0.0.1:{server.server_address[1]}"
            self.index.upsert("https://example.org/page","New Star","New Star multilingual search")
            body=json.dumps({"query":"multilingual search","limit":3}).encode()
            with self.assertRaises(HTTPError) as denied:
                urlopen(Request(base+"/search",data=body,headers={"Content-Type":"application/json"}))
            self.assertEqual(denied.exception.code,401)
            request=Request(base+"/search",data=body,headers={"Content-Type":"application/json","Authorization":"Bearer test-secret"})
            with urlopen(request) as response:
                payload=json.loads(response.read())
            self.assertEqual(payload["results"][0]["title"],"New Star")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

if __name__=="__main__":
    unittest.main()
