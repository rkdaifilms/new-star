from __future__ import annotations

import pytest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bpe_tokenizer import ByteBPETokenizer


def test_byte_bpe_round_trips_multilingual_text_and_emoji():
    corpus = "नमस्ते संसार नमस्ते संसार hello world hello 🌍"
    tokenizer = ByteBPETokenizer.train(corpus, max_merges=80, min_frequency=2)
    samples = [corpus, "नयाँ भाषा हिन्दी বাংলা العربية", "unseen 🧪 text"]
    for sample in samples:
        assert tokenizer.decode(tokenizer.encode(sample)) == sample


def test_byte_bpe_learns_merges_and_reduces_repeated_text_tokens():
    corpus = "new star new star new star"
    tokenizer = ByteBPETokenizer.train(corpus, max_merges=20, min_frequency=2)
    assert len(tokenizer) > 256
    assert len(tokenizer.encode(corpus)) < len(corpus.encode("utf-8"))


def test_byte_bpe_serialization_is_deterministic():
    corpus = "repeat repeat नेपाली नेपाली"
    trained = ByteBPETokenizer.train(corpus, max_merges=32)
    restored = ByteBPETokenizer.from_dict(trained.to_dict())
    assert restored.to_dict() == trained.to_dict()
    assert restored.encode(corpus) == trained.encode(corpus)
    assert restored.decode(restored.encode(corpus)) == corpus


def test_byte_bpe_rejects_invalid_inputs():
    with pytest.raises(ValueError, match="non-empty"):
        ByteBPETokenizer.train("")
    with pytest.raises(ValueError, match="non-negative"):
        ByteBPETokenizer.train("abc", max_merges=-1)
    with pytest.raises(ValueError, match="outside"):
        ByteBPETokenizer([]).decode([256])
    with pytest.raises(ValueError, match="Unicode scalar"):
        ByteBPETokenizer.train("valid" + chr(0xD800) + "invalid")
    with pytest.raises(ValueError, match="Unicode scalar"):
        ByteBPETokenizer([]).encode("invalid" + chr(0xDFFF) + "text")



def test_byte_bpe_rejects_merge_parts_not_defined_by_prior_rules():
    with pytest.raises(ValueError, match="earlier merge outputs"):
        ByteBPETokenizer([(b"ab", b"c")])


def test_byte_bpe_rejects_ambiguous_duplicate_merge_outputs():
    with pytest.raises(ValueError, match="outputs must be unique"):
        ByteBPETokenizer([(b"a", b"b"), (b"b", b"c"), (b"ab", b"c"), (b"a", b"bc")])


def test_byte_bpe_training_skips_duplicate_merge_outputs():
    # Both (a, bc) and (ab, c) can produce b"abc"; only one may enter
    # the vocabulary so decoding remains unambiguous.
    tokenizer = ByteBPETokenizer.train("abc abc ab c a bc", max_merges=20, min_frequency=1)
    outputs = [left + right for left, right in tokenizer.merges]
    assert len(outputs) == len(set(outputs))
    sample = "abc abc ab c a bc"
    assert tokenizer.decode(tokenizer.encode(sample)) == sample


def test_byte_bpe_benchmark_reports_compression_and_round_trip():
    from benchmark_tokenizer import benchmark

    report = benchmark(
        "नमस्ते संसार hello world नमस्ते संसार",
        max_merges=32,
        min_frequency=2,
        iterations=2,
    )
    assert report["round_trip_verified"] is True
    assert report["characters"] > 0
    assert report["utf8_bytes"] > 0
    assert report["tokens"] > 0
    assert report["vocabulary_size"] >= 256
    assert report["encode_seconds_mean"] >= 0
    assert report["decode_seconds_mean"] >= 0


def test_unicode_char_tokenizer_rejects_lone_surrogates():
    from tokenizer import UnicodeCharTokenizer

    with pytest.raises(ValueError, match="valid Unicode scalar"):
        UnicodeCharTokenizer.train("invalid-" + chr(0xD800))
    tokenizer = UnicodeCharTokenizer.train("valid multilingual नमस्ते")
    with pytest.raises(ValueError, match="valid Unicode scalar"):
        tokenizer.encode("invalid-" + chr(0xDFFF))

def test_heap_bpe_encoder_matches_reference_ranked_leftmost_merging():
    import random

    rng = random.Random(20261125)
    corpus = "abracadabra banana bandana " * 8
    tokenizer = ByteBPETokenizer.train(corpus, max_merges=80, min_frequency=2)

    def reference_encode(text):
        sequence = [bytes([value]) for value in text.encode("utf-8")]
        while len(sequence) > 1:
            candidates = [
                (tokenizer._merge_ranks[(sequence[index], sequence[index + 1])], index)
                for index in range(len(sequence) - 1)
                if (sequence[index], sequence[index + 1]) in tokenizer._merge_ranks
            ]
            if not candidates:
                break
            _, index = min(candidates)
            sequence[index:index + 2] = [sequence[index] + sequence[index + 1]]
        return [
            tokenizer._token_to_id[token] if len(token) > 1 else token[0]
            for token in sequence
        ]

    alphabet = "abracadabra नमस्ते 🌍"
    samples = [corpus, "", "a", "unseen multilingual नमस्ते"]
    samples.extend(
        "".join(rng.choice(alphabet) for _ in range(rng.randrange(0, 90)))
        for _ in range(100)
    )
    for sample in samples:
        assert tokenizer.encode(sample) == reference_encode(sample)
        assert tokenizer.decode(tokenizer.encode(sample)) == sample

