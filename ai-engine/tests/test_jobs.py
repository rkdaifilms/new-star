"""Tests for the durable background job queue."""
import sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ai_engine.jobs import JobQueue

class JobQueueTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.queue=JobQueue(Path(self.temp.name)/"jobs.sqlite3",max_pending=3,lease_seconds=30,max_attempts=2)
    def tearDown(self):
        self.temp.cleanup()
    def test_enqueue_claim_complete_and_owner_scope(self):
        job,created=self.queue.enqueue("client-a",{"model":"auto","messages":[]})
        self.assertTrue(created)
        claimed=self.queue.claim("worker-a")
        self.assertEqual(claimed["id"],job["id"])
        self.assertIsNone(self.queue.get(job["id"],"client-b"))
        self.assertTrue(self.queue.succeed(job["id"],"worker-a",{"choices":[]}))
        result=self.queue.get(job["id"],"client-a")
        self.assertEqual(result["status"],"succeeded")
        self.assertEqual(result["result"],{"choices":[]})
    def test_idempotency_returns_original_job(self):
        first,created=self.queue.enqueue("client-a",{"messages":[]}, "key-1")
        second,created_again=self.queue.enqueue("client-a",{"messages":[{"role":"user"}]}, "key-1")
        self.assertTrue(created)
        self.assertFalse(created_again)
        self.assertEqual(first["id"],second["id"])
    def test_capacity_is_enforced(self):
        for client in ("a","b","c"):
            self.queue.enqueue(client,{"messages":[]})
        with self.assertRaises(OverflowError):
            self.queue.enqueue("d",{"messages":[]})
    def test_failure_retries_then_fails(self):
        job,_=self.queue.enqueue("client-a",{"messages":[]})
        self.queue.claim("worker-a")
        self.assertTrue(self.queue.fail(job["id"],"worker-a","temporary",retry_delay=0.5))
        with self.queue._connect() as db:
            db.execute("UPDATE jobs SET available_at=0 WHERE id=?",(job["id"],))
        claimed=self.queue.claim("worker-b")
        self.assertEqual(claimed["attempts"],2)
        self.assertTrue(self.queue.fail(job["id"],"worker-b","permanent"))
        self.assertEqual(self.queue.get(job["id"],"client-a")["status"],"failed")
    def test_finished_jobs_are_pruned_after_retention_window(self):
        job,_=self.queue.enqueue("client-a",{"messages":[]})
        self.queue.claim("worker-a")
        self.queue.succeed(job["id"],"worker-a",{"ok":True})
        with self.queue._connect() as db:
            db.execute("UPDATE jobs SET updated_at=0 WHERE id=?",(job["id"],))
        self.queue.enqueue("client-b",{"messages":[]})
        self.assertIsNone(self.queue.get(job["id"],"client-a"))
    def test_expired_lease_can_be_reclaimed(self):
        job,_=self.queue.enqueue("client-a",{"messages":[]})
        self.queue.claim("worker-a")
        with self.queue._connect() as db:
            db.execute("UPDATE jobs SET lease_until=0 WHERE id=?",(job["id"],))
        reclaimed=self.queue.claim("worker-b")
        self.assertEqual(reclaimed["id"],job["id"])
        self.assertEqual(reclaimed["attempts"],2)

if __name__ == "__main__":
    unittest.main()
