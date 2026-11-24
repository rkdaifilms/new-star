import unittest
from unittest.mock import patch

import torch

from hardware import resolve_device, resolve_precision, hardware_report


class HardwareRuntimeTests(unittest.TestCase):
    def test_auto_device_uses_cpu_when_cuda_is_unavailable(self):
        with patch("torch.cuda.is_available", return_value=False):
            self.assertEqual(resolve_device("auto"), torch.device("cpu"))

    def test_explicit_cuda_fails_without_cuda(self):
        with patch("torch.cuda.is_available", return_value=False):
            with self.assertRaisesRegex(ValueError, "no available CUDA device"):
                resolve_device("cuda")

    def test_cpu_auto_precision_is_fp32(self):
        self.assertEqual(resolve_precision(torch.device("cpu"), "auto"), "fp32")

    def test_cpu_rejects_half_precision(self):
        with self.assertRaisesRegex(ValueError, "CPU training currently supports fp32"):
            resolve_precision(torch.device("cpu"), "fp16")

    def test_invalid_device_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "device must be"):
            resolve_device("mps")

    def test_cpu_hardware_report_is_explicit(self):
        report = hardware_report(torch.device("cpu"), "fp32")
        self.assertEqual(report["device_type"], "cpu")
        self.assertEqual(report["device_name"], "CPU")
        self.assertFalse(report["cuda_available"])


if __name__ == "__main__":
    unittest.main()
