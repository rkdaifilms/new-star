import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from audit_corpus import audit_corpus
from corpus import load_language_corpora, load_language_validation_corpora


class CorpusValidationTests(unittest.TestCase):
    def write_jsonl(self, path, rows):
        path.write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8",
        )

    def write_manifest(self, path):
        path.write_text(json.dumps({
            "dataset_name": "permitted-test-corpus",
            "source": "locally authored synthetic test fixtures",
            "license": "CC0-1.0",
            "permission_basis": "created for automated tests",
        }), encoding="utf-8")

    def test_language_loader_requires_matching_nonempty_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "corpus.jsonl"
            self.write_jsonl(data, [
                {"language": "ne", "split": "train", "text": "नेपाली प्रशिक्षण"},
                {"language": "ne", "split": "validation", "text": "नेपाली परीक्षण"},
                {"language": "en", "split": "train", "text": "English training"},
                {"language": "en", "split": "validation", "text": "English validation"},
            ])
            train, validation = load_language_corpora(data)
            self.assertEqual(set(train), {"ne", "en"})
            self.assertEqual(set(validation), {"ne", "en"})
            self.assertIn("नेपाली प्रशिक्षण", train["ne"])

    def test_language_loader_rejects_language_without_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "corpus.jsonl"
            self.write_jsonl(data, [
                {"language": "en", "split": "train", "text": "training only"},
                {"language": "ne", "split": "validation", "text": "validation only"},
            ])
            with self.assertRaisesRegex(ValueError, "languages differ"):
                load_language_corpora(data)

    def test_validation_only_loader_does_not_require_training_records(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "validation.jsonl"
            self.write_jsonl(data, [
                {"language": "ne", "split": "validation", "text": "held out नेपाली"},
                {"language": "en", "split": "validation", "text": "held out English"},
            ])
            result = load_language_validation_corpora(data)
            self.assertEqual(set(result), {"ne", "en"})

    def test_audit_fails_on_exact_cross_split_leakage(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "corpus.jsonl"
            manifest = Path(directory) / "manifest.json"
            self.write_jsonl(data, [
                {"language": "en", "split": "train", "text": "Same   sentence"},
                {"language": "en", "split": "validation", "text": " same sentence "},
            ])
            self.write_manifest(manifest)
            report = audit_corpus(data, manifest)
            self.assertFalse(report["ok"])
            self.assertEqual(
                report["languages"]["en"]["cross_split_duplicate_texts"], 1
            )

    def test_audit_accepts_valid_multilingual_split_and_role_tagged_sft(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "corpus.jsonl"
            manifest = Path(directory) / "manifest.json"
            self.write_jsonl(data, [
                {"language": "en", "split": "train", "text": "unique training text"},
                {"language": "en", "split": "validation", "text": "different validation text"},
                {"language": "ne", "split": "train", "messages": [
                    {"role": "user", "content": "प्रश्न"},
                    {"role": "assistant", "content": "प्रशिक्षण उत्तर"},
                ]},
                {"language": "ne", "split": "validation", "messages": [
                    {"role": "user", "content": "अर्को प्रश्न"},
                    {"role": "assistant", "content": "परीक्षण उत्तर"},
                ]},
            ])
            self.write_manifest(manifest)
            report = audit_corpus(data, manifest)
            self.assertTrue(report["ok"], report["errors"])
            self.assertEqual(set(report["languages"]), {"en", "ne"})
            self.assertEqual(report["provenance"]["dataset_name"], "permitted-test-corpus")

    def test_audit_rejects_incomplete_provenance_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / "corpus.jsonl"
            manifest = Path(directory) / "manifest.json"
            self.write_jsonl(data, [
                {"language": "en", "split": "train", "text": "training"},
                {"language": "en", "split": "validation", "text": "validation"},
            ])
            manifest.write_text(json.dumps({"dataset_name": "missing-rights"}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "manifest missing required fields"):
                audit_corpus(data, manifest)


if __name__ == "__main__":
    unittest.main()
