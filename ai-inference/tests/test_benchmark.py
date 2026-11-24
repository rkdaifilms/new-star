import importlib.util
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "benchmark.py"
SPEC = importlib.util.spec_from_file_location("new_star_benchmark", SCRIPT)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(benchmark)


def test_percentile_handles_single_sample():
    assert benchmark.percentile([2.5], 0.95) == 2.5


def test_percentile_returns_sorted_upper_sample():
    assert benchmark.percentile([9.0, 1.0, 5.0], 0.95) == 9.0


def test_percentile_returns_sorted_lower_sample():
    assert benchmark.percentile([9.0, 1.0, 5.0], 0.0) == 1.0
