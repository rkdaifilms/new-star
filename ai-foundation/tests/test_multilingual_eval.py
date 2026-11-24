import sys
import unittest
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from evaluate import evaluate_language_corpora
from corpus import require_complete_language_coverage, supported_language_codes
from generation_eval import char_ngram_f1, load_cases
from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer


class MultilingualEvaluationTests(unittest.TestCase):
    def test_canonical_catalog_has_large_language_set_and_coverage_fails_closed(self):
        supported = supported_language_codes()
        self.assertGreaterEqual(len(supported), 90)
        with self.assertRaisesRegex(ValueError, "coverage mismatch"):
            require_complete_language_coverage({"en": "short validation"})
        complete = {code: "held out sample text" for code in supported}
        require_complete_language_coverage(complete)

    def test_generation_metrics_reward_reference_overlap_and_handle_empty_output(self):
        self.assertEqual(char_ngram_f1("नमस्ते दुनिया", "नमस्ते दुनिया"), 1.0)
        self.assertGreater(char_ngram_f1("नेपाली भाषा", "नेपाली भाषा राम्रो"), 0.0)
        self.assertEqual(char_ngram_f1("reference", ""), 0.0)

    def test_generation_case_loader_validates_multilingual_reference_schema(self):
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "cases.jsonl"
            dataset.write_text(
                '{"language":"ne","prompt":"नमस्ते","reference":"नमस्ते"}\n',
                encoding="utf-8",
            )
            cases = load_cases(dataset)
            self.assertEqual(cases[0]["language"], "ne")
            self.assertEqual(cases[0]["prompt"], "नमस्ते")

    def test_evaluation_reports_each_unicode_language_and_macro_metrics(self):
        validation = {
            "ar": "اللغة العربية جميلة ومفيدة للبحث.",
            "bn": "বাংলা ভাষায় তথ্য অনুসন্ধান করা যায়।",
            "en": "Search should understand language and context.",
            "es": "La búsqueda debe comprender el idioma y el contexto.",
            "hi": "हिंदी भाषा में खोज और उत्तर उपयोगी होने चाहिए।",
            "ja": "日本語の検索と回答を正しく評価します。",
            "ne": "नेपाली भाषामा खोज र उत्तर परीक्षण गरिन्छ।",
            "zh": "搜索系统需要理解中文语境和含义。",
        }
        tokenizer = UnicodeCharTokenizer.train("\n".join(validation.values()), 4096)
        model = NewStarGPT(NewStarConfig(
            vocab_size=len(tokenizer), block_size=8, width=16, layers=1, heads=2
        ))
        report = evaluate_language_corpora(
            model, validation, tokenizer=tokenizer, batch_size=2, device="cpu"
        )
        self.assertEqual(set(report["languages"]), set(validation))
        self.assertGreater(report["macro_average_cross_entropy"], 0)
        self.assertGreater(report["macro_geometric_mean_perplexity"], 1)
        for metrics in report["languages"].values():
            self.assertGreater(metrics["evaluated_tokens"], 0)
            self.assertGreater(metrics["perplexity"], 1)


if __name__ == "__main__":
    unittest.main()
