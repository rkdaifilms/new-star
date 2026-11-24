import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ai_engine.worker_pool import desired_worker_count

class WorkerAutoscalerTests(unittest.TestCase):
    def test_scales_to_zero_when_queue_is_idle(self):
        self.assertEqual(desired_worker_count(0,0,4,2),0)
    def test_scales_up_with_pending_and_running_work(self):
        self.assertEqual(desired_worker_count(5,1,8,2),3)
    def test_respects_max_worker_capacity(self):
        self.assertEqual(desired_worker_count(100,10,4,2),4)
    def test_invalid_worker_ratio_is_bounded(self):
        self.assertEqual(desired_worker_count(1,0,0,0),1)

if __name__=="__main__":
    unittest.main()
