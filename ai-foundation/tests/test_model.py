import sys
from pathlib import Path

import torch

# The source directory uses a hyphen in its repository path, so expose it
# explicitly instead of importing a nonexistent ai_foundation package.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model import NewStarConfig, NewStarGPT


def tiny_model():
    return NewStarGPT(NewStarConfig(
        vocab_size=256, block_size=16, width=16, layers=2, heads=4, dropout=0.0
    ))


def test_forward_returns_byte_logits_and_loss():
    model = tiny_model()
    tokens = torch.randint(0, 256, (2, 8))
    logits, loss = model(tokens, tokens)
    assert logits.shape == (2, 8, 256)
    assert loss is not None and torch.isfinite(loss)


def test_causal_attention_does_not_leak_future_tokens():
    model = tiny_model().eval()
    left = torch.tensor([[10, 11, 12]])
    changed_future = torch.tensor([[10, 99, 98]])
    with torch.no_grad():
        left_logits, _ = model(left)
        changed_logits, _ = model(changed_future)
    torch.testing.assert_close(left_logits[:, 0], changed_logits[:, 0])


def test_generation_appends_requested_number_of_tokens():
    model = tiny_model()
    prompt = torch.tensor([[65, 66]])
    result = model.generate(prompt, max_new_tokens=3, temperature=1.0)
    assert result.shape == (1, 5)
    assert result.dtype == torch.long


def test_rejects_invalid_dropout_and_token_ids():
    import pytest

    with pytest.raises(ValueError, match="dropout"):
        NewStarConfig(dropout=1.0)
    model = tiny_model()
    with pytest.raises(ValueError, match="vocabulary"):
        model(torch.tensor([[256]], dtype=torch.long))


def test_rejects_mismatched_targets_and_empty_generation_prompt():
    import pytest

    model = tiny_model()
    with pytest.raises(ValueError, match="targets"):
        model(torch.tensor([[1, 2]], dtype=torch.long),
              torch.tensor([[1]], dtype=torch.long))
    with pytest.raises(ValueError, match="non-empty"):
        model.generate(torch.empty((1, 0), dtype=torch.long), max_new_tokens=1)


def test_zero_token_generation_is_a_noop():
    model = tiny_model()
    prompt = torch.tensor([[65, 66]], dtype=torch.long)
    result = model.generate(prompt, max_new_tokens=0)
    torch.testing.assert_close(result, prompt)



def test_evaluation_is_reproducible_and_returns_finite_loss():
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from evaluate import evaluate_model

    model = tiny_model()
    corpus = torch.randint(0, 256, (256,), dtype=torch.long)
    first = evaluate_model(model, corpus, batch_size=2, batches=3, seed=7)
    second = evaluate_model(model, corpus, batch_size=2, batches=3, seed=7)
    assert first == second
    assert torch.isfinite(torch.tensor(first))


def test_evaluation_rejects_too_short_corpus():
    import pytest
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from evaluate import evaluate_model

    with pytest.raises(ValueError, match="block_size"):
        evaluate_model(tiny_model(), torch.tensor([1, 2, 3], dtype=torch.long))


def test_unicode_tokenizer_round_trips_multiple_scripts_and_emoji():
    from tokenizer import UnicodeCharTokenizer

    training = "नेपाली हिन्दी বাংলা English العربية 中文 日本語"
    tokenizer = UnicodeCharTokenizer.train(training, max_vocab_size=512)
    sample = "नेपाली हिन्दी বাংলা English العربية 中文 日本語 🛰️"
    encoded = tokenizer.encode(sample)
    assert tokenizer.decode(encoded) == sample
    assert max(encoded) < len(tokenizer)


def test_unicode_tokenizer_uses_byte_fallback_for_unseen_characters():
    from tokenizer import UnicodeCharTokenizer

    tokenizer = UnicodeCharTokenizer.train("hello", max_vocab_size=300)
    unseen = "नमस्ते 🌏"
    encoded = tokenizer.encode(unseen)
    assert all(0 <= token_id < len(tokenizer) for token_id in encoded)
    assert tokenizer.decode(encoded) == unseen


def test_unicode_tokenizer_serialization_round_trip():
    from tokenizer import UnicodeCharTokenizer

    tokenizer = UnicodeCharTokenizer.train("नमस्ते hello")
    restored = UnicodeCharTokenizer.from_dict(tokenizer.to_dict())
    assert restored.decode(restored.encode("नमस्ते 🌍")) == "नमस्ते 🌍"


def test_language_corpus_loader_keeps_train_and_validation_separate(tmp_path):
    import json
    from corpus import load_language_corpora

    path = tmp_path / "multilingual.jsonl"
    records = [
        {"language": "ne", "split": "train", "text": "नेपाली प्रशिक्षण सामग्री " * 12},
        {"language": "ne", "split": "validation", "text": "नेपाली जाँच सामग्री " * 12},
        {"language": "en", "split": "train", "text": "English training material " * 12},
        {"language": "en", "split": "validation", "text": "English validation material " * 12},
    ]
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in records), encoding="utf-8")
    train, validation = load_language_corpora(path)
    assert set(train) == {"ne", "en"}
    assert "जाँच" not in train["ne"]
    assert "प्रशिक्षण" not in validation["ne"]


def test_language_corpus_loader_rejects_missing_validation_language(tmp_path):
    import json
    import pytest
    from corpus import load_language_corpora

    path = tmp_path / "incomplete.jsonl"
    path.write_text(json.dumps({"language": "hi", "split": "train", "text": "नमस्ते " * 20}, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(ValueError, match="languages differ"):
        load_language_corpora(path)


def test_balanced_batch_samples_from_all_declared_languages():
    from train import sample_balanced_batch

    corpora = {
        "en": torch.arange(300, dtype=torch.long),
        "ne": torch.arange(300, dtype=torch.long) + 1,
    }
    generator = torch.Generator(device="cpu").manual_seed(17)
    x, y = sample_balanced_batch(corpora, batch_size=32, block_size=8,
                                 device="cpu", generator=generator)
    assert x.shape == (32, 8)
    assert y.shape == (32, 8)
    assert torch.all(y[:, :-1] == x[:, 1:])


def test_validation_corpus_loader_accepts_validation_only_jsonl(tmp_path):
    import json
    from corpus import load_language_validation_corpora

    path = tmp_path / "heldout.jsonl"
    rows = [
        {"language": "ne", "split": "validation", "text": "नेपाली जाँच सामग्री " * 12},
        {"language": "en", "split": "validation", "text": "English heldout material " * 12},
    ]
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8")
    result = load_language_validation_corpora(path)
    assert set(result) == {"ne", "en"}
    assert "नेपाली" in result["ne"]


def test_native_inference_server_formats_chat_messages():
    from serve import format_chat_messages

    prompt = format_chat_messages([
        {"role": "system", "content": "You are New Star."},
        {"role": "user", "content": "नमस्ते"},
    ])
    assert prompt == "system: You are New Star.\nuser: नमस्ते\nassistant:"


def test_native_inference_server_rejects_invalid_chat_history():
    import pytest
    from serve import format_chat_messages

    with pytest.raises(ValueError, match="final chat message"):
        format_chat_messages([
            {"role": "user", "content": "Hello"},
            {"role": "assistant", "content": "Hi"},
        ])


def test_generation_supports_top_k_top_p_and_repetition_controls():
    model = tiny_model()
    prompt = torch.tensor([[65, 66]], dtype=torch.long)
    result = model.generate(prompt, max_new_tokens=4, temperature=0.8,
                            top_k=12, top_p=0.9, repetition_penalty=1.1)
    assert result.shape == (1, 6)


def test_generation_rejects_invalid_sampling_parameters():
    import pytest
    model = tiny_model()
    prompt = torch.tensor([[65, 66]], dtype=torch.long)
    with pytest.raises(ValueError, match="top_p"):
        model.generate(prompt, top_p=0)
    with pytest.raises(ValueError, match="repetition_penalty"):
        model.generate(prompt, repetition_penalty=0.9)
    with pytest.raises(ValueError, match="top_k"):
        model.generate(prompt, top_k=0)



def test_instruction_example_masks_prompt_and_trains_only_assistant_tokens():
    from instruction_data import encode_instruction_example
    from tokenizer import UnicodeCharTokenizer

    messages = [
        {"role": "system", "content": "Answer briefly."},
        {"role": "user", "content": "नमस्ते?"},
        {"role": "assistant", "content": "नमस्ते!"},
    ]
    tokenizer = UnicodeCharTokenizer.train("".join(m["content"] for m in messages), 512)
    inputs, labels = encode_instruction_example(messages, tokenizer, max_length=256)
    assert len(inputs) == len(labels)
    assert any(label == -100 for label in labels)
    assert any(label != -100 for label in labels)
    model = NewStarGPT(NewStarConfig(
        vocab_size=len(tokenizer), block_size=256, width=16, layers=1, heads=4, dropout=0.0
    ))
    _, loss = model(torch.tensor([inputs]), torch.tensor([labels]))
    assert loss is not None and torch.isfinite(loss)


def test_instruction_loader_rejects_invalid_turn_order_and_split_leakage(tmp_path):
    import json
    import pytest
    from instruction_data import load_instruction_corpora, validate_messages

    with pytest.raises(ValueError, match="alternate"):
        validate_messages([
            {"role": "user", "content": "Question"},
            {"role": "user", "content": "Second question"},
        ])
    path = tmp_path / "instruction.jsonl"
    row = {
        "language": "ne", "split": "train",
        "messages": [
            {"role": "user", "content": "प्रश्न"},
            {"role": "assistant", "content": "उत्तर"},
        ],
    }
    path.write_text(json.dumps(row, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(ValueError, match="languages differ"):
        load_instruction_corpora(path)


def test_instruction_encoder_rejects_examples_truncated_before_answer():
    import pytest
    from instruction_data import encode_instruction_example
    from tokenizer import UnicodeCharTokenizer

    messages = [
        {"role": "user", "content": "A very long prompt " * 10},
        {"role": "assistant", "content": "short answer"},
    ]
    tokenizer = UnicodeCharTokenizer.train("".join(m["content"] for m in messages), 512)
    with pytest.raises(ValueError, match="no assistant response"):
        encode_instruction_example(messages, tokenizer, max_length=8)
