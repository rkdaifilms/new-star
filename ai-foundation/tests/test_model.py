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
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows),
        encoding="utf-8",
    )
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


def test_instruction_encoder_keeps_assistant_targets_when_truncating_long_prompt():
    from instruction_data import encode_instruction_example
    from tokenizer import UnicodeCharTokenizer

    messages = [
        {"role": "user", "content": "A very long prompt " * 10},
        {"role": "assistant", "content": "short answer"},
    ]
    tokenizer = UnicodeCharTokenizer.train("".join(m["content"] for m in messages), 512)
    inputs, labels = encode_instruction_example(messages, tokenizer, max_length=8)
    assert len(inputs) <= 8
    assert any(label != -100 for label in labels)

def test_sft_batch_sampler_and_macro_validation_are_finite():
    from instruction_data import encode_instruction_example
    from sft_train import evaluate_sft, sample_instruction_batch
    from tokenizer import UnicodeCharTokenizer

    conversations = [
        [{"role": "user", "content": "नमस्ते"}, {"role": "assistant", "content": "नमस्कार"}],
        [{"role": "user", "content": "Hello"}, {"role": "assistant", "content": "Hi there"}],
    ]
    tokenizer = UnicodeCharTokenizer.train(
        "".join(message["content"] for row in conversations for message in row), 512
    )
    encoded = [
        encode_instruction_example(row, tokenizer, max_length=16)
        for row in conversations
    ]
    corpora = {"en": encoded, "ne": encoded}
    generator = torch.Generator(device="cpu").manual_seed(19)
    x, y = sample_instruction_batch(corpora, batch_size=4, block_size=16,
                                    device="cpu", generator=generator)
    assert x.shape == y.shape
    assert torch.any(y != -100)
    model = NewStarGPT(NewStarConfig(
        vocab_size=len(tokenizer), block_size=16, width=16, layers=1, heads=4, dropout=0.0
    ))
    score = evaluate_sft(model, corpora, batch_size=2, block_size=16, device="cpu")
    assert torch.isfinite(torch.tensor(score))


def test_sft_validation_weights_tokens_then_macro_averages_languages():
    from sft_train import evaluate_sft

    class ControlledModel:
        def eval(self):
            return self

        def __call__(self, tokens, labels):
            losses = {1: 2.0, 3: 4.0, 7: 10.0}
            return None, torch.tensor(losses[int(tokens[0, 0])])

    corpora = {
        "en": [
            ([1, 2], [-100, 2]),
            ([3, 4, 5, 6], [-100, 4, 5, 6]),
        ],
        "ne": [
            ([7, 8], [-100, 8]),
        ],
    }
    # English loss = (2*1 + 4*3) / 4 = 3.5; macro with Nepali 10 = 6.75.
    score = evaluate_sft(
        ControlledModel(), corpora, batch_size=1, block_size=8, device="cpu"
    )
    assert score == 6.75


def test_sft_collation_masks_padding_and_preserves_response_labels():
    from sft_train import collate_examples

    examples = [
        ([1, 2, 3], [-100, 4, 5]),
        ([6, 7], [-100, 8]),
    ]
    x, y = collate_examples(examples, [0, 1], block_size=8, device="cpu")
    assert x.tolist() == [[1, 2, 3], [6, 7, 0]]
    assert y.tolist() == [[-100, 4, 5], [-100, 8, -100]]


def test_model_rejects_batch_with_no_unmasked_training_targets():
    import pytest
    model = tiny_model()
    tokens = torch.tensor([[65, 66, 67]], dtype=torch.long)
    labels = torch.full_like(tokens, -100)
    with pytest.raises(ValueError, match="at least one non-masked token"):
        model(tokens, labels)



def test_sft_cli_runs_one_step_and_saves_reloadable_checkpoint(tmp_path, monkeypatch):
    import json
    import sys
    from sft_train import main
    from model import NewStarGPT
    from tokenizer import UnicodeCharTokenizer

    path = tmp_path / "tiny-instructions.jsonl"
    rows = []
    for language, user_text, assistant_text in [
        ("ne", "नमस्ते प्रश्न", "नमस्कार उत्तर"),
        ("en", "hello question", "hello answer"),
    ]:
        for split, suffix in [("train", "train"), ("validation", "heldout")]:
            rows.append({
                "language": language,
                "split": split,
                "messages": [
                    {"role": "user", "content": f"{user_text} {suffix}"},
                    {"role": "assistant", "content": f"{assistant_text} {suffix}"},
                ],
            })
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows),
        encoding="utf-8",
    )
    manifest = tmp_path / "instruction-manifest.json"
    manifest.write_text(json.dumps({
        "dataset_name": "tiny test instructions",
        "source": "locally authored test fixture",
        "license": "CC0-1.0",
        "permission_basis": "created for automated tests",
    }), encoding="utf-8")
    checkpoint = tmp_path / "tiny-sft.pt"
    monkeypatch.setattr(sys, "argv", [
        "sft_train.py", "--data", str(path), "--manifest", str(manifest),
        "--output", str(checkpoint),
        "--steps", "1", "--batch-size", "2", "--block-size", "64",
        "--width", "16", "--layers", "1", "--heads", "4",
        "--eval-interval", "1", "--max-vocab-size", "512",
    ])
    main()

    assert checkpoint.is_file()
    assert checkpoint.with_suffix(".pt.corpus-audit.json").is_file()
    metadata = json.loads(checkpoint.with_suffix(".pt.json").read_text(encoding="utf-8"))
    assert metadata["training_method"] == "supervised-fine-tuning-response-only"
    assert metadata["languages"] == ["en", "ne"]
    assert metadata["seed"] == 1337
    assert metadata["learning_rate"] == 1e-4
    assert metadata["batch_size"] == 2
    assert metadata["block_size"] == 64
    assert metadata["final_validation_loss"] is not None
    assert metadata["validation_metric"].endswith("assistant-response cross-entropy")
    resume_checkpoint = checkpoint.with_suffix(".pt.resume.pt")
    assert resume_checkpoint.is_file()

    # Resume from the saved optimizer/model/RNG state and continue to step 2.
    monkeypatch.setattr(sys, "argv", [
        "sft_train.py", "--data", str(path), "--manifest", str(manifest),
        "--output", str(checkpoint), "--steps", "2", "--batch-size", "2", "--block-size", "64",
        "--width", "16", "--layers", "1", "--heads", "4",
        "--eval-interval", "1", "--max-vocab-size", "512",
        "--resume-from", str(resume_checkpoint),
    ])
    main()
    resumed_metadata = json.loads(
        checkpoint.with_suffix(".pt.json").read_text(encoding="utf-8")
    )
    assert resumed_metadata["training_steps"] == 2
    assert resumed_metadata["resumed_from_step"] == 1
    assert resumed_metadata["final_validation_loss"] is not None

    saved = torch.load(checkpoint, map_location="cpu", weights_only=False)
    tokenizer = UnicodeCharTokenizer.from_dict(saved["tokenizer"])
    restored = NewStarGPT(NewStarConfig(**saved["config"]))
    restored.load_state_dict(saved["state_dict"])
    assert tokenizer.decode(tokenizer.encode("नमस्ते 🌍")) == "नमस्ते 🌍"

    # Exercise the actual native HTTP adapter with the checkpoint just trained.
    import json as json_module
    import threading
    from http.server import ThreadingHTTPServer
    from urllib.request import Request, urlopen
    from serve import create_handler, load_engine

    served_model, served_tokenizer = load_engine(str(checkpoint), "cpu")
    httpd = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        create_handler(served_model, served_tokenizer, "tiny-sft-test", "cpu", "test-only-secret"),
    )
    worker = threading.Thread(target=httpd.serve_forever, daemon=True)
    worker.start()
    try:
        base_url = f"http://127.0.0.1:{httpd.server_port}"
        with urlopen(f"{base_url}/health", timeout=5) as response:
            health = json_module.loads(response.read().decode("utf-8"))
        assert health["ok"] is True
        assert health["engine"] == "new-star-from-scratch"

        request_body = json_module.dumps({
            "model": "tiny-sft-test",
            "messages": [{"role": "user", "content": "नमस्ते"}],
            "max_tokens": 2,
        }).encode("utf-8")
        # Protected inference must reject a missing bearer token.
        from urllib.error import HTTPError
        unauthorized = Request(
            f"{base_url}/v1/chat/completions",
            data=request_body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            urlopen(unauthorized, timeout=5)
            raise AssertionError("protected inference unexpectedly accepted an unauthenticated request")
        except HTTPError as error:
            assert error.code == 401

        request = Request(
            f"{base_url}/v1/chat/completions",
            data=request_body,
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer test-only-secret",
            },
            method="POST",
        )
        with urlopen(request, timeout=10) as response:
            completion = json_module.loads(response.read().decode("utf-8"))
        assert completion["object"] == "chat.completion"
        assert completion["model"] == "tiny-sft-test"
        assert completion["choices"][0]["message"]["role"] == "assistant"
        assert completion["usage"]["completion_tokens"] <= 2
    finally:
        httpd.shutdown()
        httpd.server_close()
        worker.join(timeout=5)



def test_pretraining_cli_saves_and_resumes_training_state(tmp_path, monkeypatch):
    import json
    import sys
    import pytest
    from train import main

    corpus = tmp_path / "permitted.txt"
    corpus.write_text(("नेपाली भाषा नमूना सामग्री English training text. " * 40), encoding="utf-8")
    checkpoint = tmp_path / "pretrain.pt"
    base_args = [
        "train.py", "--data", str(corpus), "--output", str(checkpoint),
        "--batch-size", "2", "--block-size", "8", "--width", "16",
        "--layers", "1", "--heads", "4", "--max-vocab-size", "512",
        "--seed", "23",
    ]
    monkeypatch.setattr(sys, "argv", base_args + ["--steps", "1"])
    main()

    resume_path = checkpoint.with_suffix(".pt.resume.pt")
    assert resume_path.is_file()
    first_meta = json.loads(
        checkpoint.with_suffix(".pt.json").read_text(encoding="utf-8")
    )
    assert first_meta["training_steps"] == 1
    assert first_meta["resumed_from_step"] == 0
    assert first_meta["final_validation_loss"] is not None

    monkeypatch.setattr(
        sys, "argv",
        base_args + ["--steps", "2", "--resume-from", str(resume_path)],
    )
    main()
    resumed_meta = json.loads(
        checkpoint.with_suffix(".pt.json").read_text(encoding="utf-8")
    )
    assert resumed_meta["training_steps"] == 2
    assert resumed_meta["resumed_from_step"] == 1
    assert resumed_meta["final_validation_loss"] is not None

    # A changed corpus must not silently reuse the old optimizer/model state.
    corpus.write_text(corpus.read_text(encoding="utf-8") + "changed", encoding="utf-8")
    monkeypatch.setattr(
        sys, "argv",
        base_args + ["--steps", "3", "--resume-from", str(resume_path)],
    )
    with pytest.raises(SystemExit):
        main()



def test_multilingual_benchmark_reports_per_language_and_macro_metrics():
    from evaluate import evaluate_language_corpora
    from tokenizer import UnicodeCharTokenizer

    tokenizer = UnicodeCharTokenizer.train("नेपाली सामग्री English text", 512)
    model = NewStarGPT(NewStarConfig(
        vocab_size=len(tokenizer), block_size=16, width=16,
        layers=2, heads=4, dropout=0.0,
    ))
    validation = {
        "ne": "नेपाली validation सामग्री " * 20,
        "en": "English heldout validation text " * 20,
    }
    first = evaluate_language_corpora(
        model, validation, tokenizer, batch_size=2, batches=3, seed=31
    )
    second = evaluate_language_corpora(
        model, validation, tokenizer, batch_size=2, batches=3, seed=31
    )
    assert first == second
    assert set(first["languages"]) == {"en", "ne"}
    assert all(item["cross_entropy"] >= 0 for item in first["languages"].values())
    expected_macro = sum(
        item["cross_entropy"] for item in first["languages"].values()
    ) / 2
    assert first["macro_average_cross_entropy"] == expected_macro
    assert first["macro_geometric_mean_perplexity"] > 0



def test_full_corpus_evaluation_counts_every_next_token_target():
    from evaluate import evaluate_full_corpus

    model = tiny_model()
    data = torch.randint(0, 256, (53,), dtype=torch.long)
    first_loss, first_count = evaluate_full_corpus(model, data, batch_size=2)
    second_loss, second_count = evaluate_full_corpus(model, data, batch_size=5)
    assert first_count == len(data) - 1
    assert second_count == len(data) - 1
    assert abs(first_loss - second_loss) < 1e-5
    assert torch.isfinite(torch.tensor(first_loss))



def test_text_evaluation_cli_writes_json_report(tmp_path, monkeypatch):
    import json
    import sys
    from evaluate import main
    from tokenizer import UnicodeCharTokenizer

    tokenizer = UnicodeCharTokenizer.train("hello world नमस्ते", 512)
    config = NewStarConfig(
        vocab_size=len(tokenizer), block_size=8, width=16,
        layers=1, heads=4, dropout=0.0,
    )
    model = NewStarGPT(config)
    checkpoint = tmp_path / "tiny-checkpoint.pt"
    torch.save({
        "format": "new-star-unicode-char-gpt-v1",
        "config": vars(config),
        "tokenizer": tokenizer.to_dict(),
        "state_dict": model.state_dict(),
    }, checkpoint)
    corpus = tmp_path / "heldout.txt"
    corpus.write_text("hello world नमस्ते " * 12, encoding="utf-8")
    report_path = tmp_path / "metrics" / "report.json"
    monkeypatch.setattr(sys, "argv", [
        "evaluate.py", "--checkpoint", str(checkpoint),
        "--data", str(corpus), "--report-json", str(report_path),
        "--batch-size", "2",
    ])
    main()
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["report_format"] == "new-star-evaluation-report-v1"
    assert report["evaluation_method"] == "full-corpus-token-weighted"
    assert len(report["checkpoint_sha256"]) == 64
    assert len(report["data_sha256"]) == 64
    assert len(report["tokenizer_sha256"]) == 64
    assert report["model_config"] == vars(config)
    assert report["evaluated_tokens"] == len(tokenizer.encode(corpus.read_text(encoding="utf-8"))) - 1
    assert report["heldout_cross_entropy"] > 0
    assert report["heldout_perplexity"] > 0



def test_multilingual_evaluation_cli_writes_reproducible_language_report(tmp_path, monkeypatch):
    import hashlib
    import json
    import sys
    from evaluate import main
    from tokenizer import UnicodeCharTokenizer

    tokenizer = UnicodeCharTokenizer.train("English heldout text नेपाली परीक्षण सामग्री", 512)
    config = NewStarConfig(
        vocab_size=len(tokenizer), block_size=8, width=16,
        layers=1, heads=4, dropout=0.0,
    )
    model = NewStarGPT(config)
    checkpoint = tmp_path / "multilingual-checkpoint.pt"
    torch.save({
        "format": "new-star-unicode-char-gpt-v1",
        "config": vars(config),
        "tokenizer": tokenizer.to_dict(),
        "state_dict": model.state_dict(),
    }, checkpoint)
    corpus = tmp_path / "heldout-languages.jsonl"
    rows = [
        {"language": "en", "split": "validation", "text": "English heldout text " * 5},
        {"language": "ne", "split": "validation", "text": "नेपाली परीक्षण सामग्री " * 5},
    ]
    corpus.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows),
        encoding="utf-8",
    )
    report_path = tmp_path / "metrics" / "multilingual.json"
    monkeypatch.setattr(sys, "argv", [
        "evaluate.py", "--checkpoint", str(checkpoint),
        "--data", str(corpus), "--data-format", "jsonl",
        "--report-json", str(report_path), "--batch-size", "2",
    ])
    main()

    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["report_format"] == "new-star-evaluation-report-v1"
    assert report["data_sha256"] == hashlib.sha256(corpus.read_bytes()).hexdigest()
    assert set(report["languages"]) == {"en", "ne"}
    assert report["seed"] == 1234
    for row in rows:
        language = row["language"]
        expected_tokens = len(tokenizer.encode(row["text"])) - 1
        assert report["languages"][language]["evaluated_tokens"] == expected_tokens
        assert report["languages"][language]["cross_entropy"] > 0
    expected_macro = sum(
        item["cross_entropy"] for item in report["languages"].values()
    ) / 2
    assert report["macro_average_cross_entropy"] == expected_macro


def test_corpus_audit_detects_cross_split_duplicates_and_reports(tmp_path):
    import json
    from audit_corpus import audit_corpus

    data = tmp_path / "corpus.jsonl"
    manifest = tmp_path / "manifest.json"
    rows = [
        {"language": "ne", "split": "train", "text": "नमस्ते   संसार"},
        {"language": "ne", "split": "validation", "text": "नमस्ते संसार"},
        {"language": "en", "split": "train", "text": "Hello training data"},
        {"language": "en", "split": "validation", "text": "Held out validation data"},
    ]
    data.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8")
    manifest.write_text(json.dumps({
        "dataset_name": "small test corpus", "source": "locally authored test fixture",
        "license": "CC0-1.0", "permission_basis": "created for automated tests",
    }), encoding="utf-8")
    report = audit_corpus(data, manifest)
    assert report["ok"] is False
    assert report["languages"]["ne"]["cross_split_duplicate_texts"] == 1
    assert any("duplicates" in error for error in report["errors"])


def test_corpus_audit_checks_minimum_sizes_and_manifest(tmp_path):
    import json
    import pytest
    from audit_corpus import audit_corpus

    data = tmp_path / "corpus.jsonl"
    manifest = tmp_path / "manifest.json"
    rows = [
        {"language": "en", "split": "train", "text": "short"},
        {"language": "en", "split": "validation", "text": "tiny"},
    ]
    data.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
    manifest.write_text(json.dumps({
        "dataset_name": "tiny", "source": "test fixture",
        "license": "CC0-1.0", "permission_basis": "test-created",
    }), encoding="utf-8")
    report = audit_corpus(data, manifest, min_train_chars=10, min_validation_chars=10)
    assert not report["ok"]
    assert any("below minimum" in error for error in report["errors"])
    manifest.write_text(json.dumps({"dataset_name": "missing provenance"}), encoding="utf-8")
    with pytest.raises(ValueError, match="manifest missing"):
        audit_corpus(data, manifest)



def test_experiment_registry_records_checkpoint_hash_and_audit_provenance(tmp_path):
    import hashlib
    import json
    from experiment_registry import register_experiment

    checkpoint = tmp_path / "model.pt"
    checkpoint.write_bytes(b"tiny checkpoint bytes")
    metadata = tmp_path / "model.pt.json"
    metadata.write_text(json.dumps({
        "training_steps": 12,
        "final_validation_loss": 2.5,
        "seed": 7,
        "languages": ["en", "ne"],
        "model_config": {"width": 16},
    }), encoding="utf-8")
    audit = tmp_path / "model.pt.corpus-audit.json"
    audit.write_text(json.dumps({
        "ok": True,
        "provenance": {"dataset_name": "test corpus", "license": "CC0-1.0"},
        "languages": {"en": {}, "ne": {}},
    }), encoding="utf-8")
    registry = tmp_path / "runs" / "experiments.jsonl"

    record = register_experiment(metadata, registry)
    stored = json.loads(registry.read_text(encoding="utf-8").splitlines()[0])
    assert record == stored
    assert record["stage"] == "pretraining"
    assert record["checkpoint_sha256"] == hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    assert record["corpus_audit"]["ok"] is True
    assert record["corpus_audit"]["language_count"] == 2
    assert record["corpus_audit"]["provenance"]["dataset_name"] == "test corpus"


def test_experiment_registry_refuses_failed_audit(tmp_path):
    import json
    import pytest
    from experiment_registry import register_experiment

    checkpoint = tmp_path / "model.pt"
    checkpoint.write_bytes(b"checkpoint")
    metadata = tmp_path / "model.pt.json"
    metadata.write_text(json.dumps({"training_steps": 1}), encoding="utf-8")
    audit = tmp_path / "model.pt.corpus-audit.json"
    audit.write_text(json.dumps({"ok": False, "languages": {}}), encoding="utf-8")

    with pytest.raises(ValueError, match="failed corpus audit"):
        register_experiment(metadata, tmp_path / "registry.jsonl")



def test_experiment_comparison_only_compares_matching_data_and_languages(tmp_path):
    import json
    from compare_experiments import compare_runs

    registry = tmp_path / "experiments.jsonl"
    records = [
        {"run_id": "run-a", "stage": "pretraining", "final_validation_loss": 3.0,
         "data_fingerprint": "dataset-1", "languages": ["en", "ne"],
         "tokenizer_fingerprint": "tok-v1", "model_config": {"width": 16},
         "evaluation_protocol": "eval-v1", "validation_metric": "ce"},
        {"run_id": "run-b", "stage": "pretraining", "final_validation_loss": 2.5,
         "data_fingerprint": "dataset-1", "languages": ["ne", "en"],
         "tokenizer_fingerprint": "tok-v1", "model_config": {"width": 16},
         "evaluation_protocol": "eval-v1", "validation_metric": "ce"},
        {"run_id": "run-c", "stage": "pretraining", "final_validation_loss": 1.2,
         "data_fingerprint": "dataset-2", "languages": ["en", "ne"],
         "tokenizer_fingerprint": "tok-v1", "model_config": {"width": 16},
         "evaluation_protocol": "eval-v1", "validation_metric": "ce"},
        {"run_id": "sft-a", "stage": "sft", "final_validation_loss": 4.0,
         "data_fingerprint": "dataset-1", "languages": ["en"],
         "tokenizer_fingerprint": "tok-v1", "model_config": {"width": 16},
         "evaluation_protocol": "eval-v1", "validation_metric": "ce"},
    ]
    registry.write_text("\n".join(json.dumps(row) for row in records), encoding="utf-8")

    report = compare_runs(registry, "pretraining")
    assert report["run_count"] == 3
    assert report["latest_run_id"] == "run-c"
    assert report["best_run_id"] == "run-c"
    assert report["comparable_to_previous"] is False
    assert report["validation_loss_delta_latest_minus_previous"] is None

    comparable = compare_runs(registry, "sft")
    assert comparable["run_count"] == 1
    assert comparable["comparable_to_previous"] is False


def test_experiment_comparison_reports_delta_for_same_dataset(tmp_path):
    import json
    from compare_experiments import compare_runs

    registry = tmp_path / "experiments.jsonl"
    records = [
        {"run_id": "older", "stage": "sft", "final_validation_loss": 2.0,
         "data_fingerprint": "same", "languages": ["en", "ne"],
         "tokenizer_fingerprint": "tok-v1", "model_config": {"width": 16},
         "evaluation_protocol": "eval-v1", "validation_metric": "ce"},
        {"run_id": "newer", "stage": "sft", "final_validation_loss": 1.7,
         "data_fingerprint": "same", "languages": ["ne", "en"],
         "tokenizer_fingerprint": "tok-v1", "model_config": {"width": 16},
         "evaluation_protocol": "eval-v1", "validation_metric": "ce"},
    ]
    registry.write_text("\n".join(json.dumps(row) for row in records), encoding="utf-8")
    report = compare_runs(registry, "sft")
    assert report["comparable_to_previous"] is True
    assert abs(report["validation_loss_delta_latest_minus_previous"] + 0.3) < 1e-8


def test_cpu_inference_benchmark_reports_latency_and_throughput(tmp_path):
    import json
    from benchmark_inference import benchmark
    from tokenizer import UnicodeCharTokenizer

    tokenizer = UnicodeCharTokenizer.train("Hello benchmark")
    config = NewStarConfig(
        vocab_size=len(tokenizer), block_size=32, width=16, layers=1, heads=4, dropout=0.0
    )
    model = NewStarGPT(config)
    checkpoint = tmp_path / "tiny.pt"
    torch.save({
        "format": "new-star-unicode-char-gpt-v1",
        "config": vars(config),
        "tokenizer": tokenizer.to_dict(),
        "state_dict": model.state_dict(),
    }, checkpoint)

    report = benchmark(
        str(checkpoint), prompt="Hi", device="cpu",
        warmup=0, iterations=2, max_new_tokens=2, threads=1,
    )
    assert report["benchmark"] == "new-star-generation-v1"
    assert report["device"] == "cpu"
    assert report["measured_iterations"] == 2
    assert report["generated_tokens"] == 4
    assert report["tokens_per_second"] > 0
    assert report["latency_seconds"]["median"] > 0
    assert report["parameter_count"] > 0


def test_cpu_benchmark_rejects_invalid_iteration_counts():
    import pytest
    from benchmark_inference import benchmark

    with pytest.raises(ValueError, match="iterations"):
        benchmark("not-needed.pt", warmup=0, iterations=0)


def test_experiment_comparison_rejects_tokenizer_or_model_mismatch(tmp_path):
    import json
    from compare_experiments import compare_runs

    common = {
        "stage": "pretraining",
        "data_fingerprint": "same-data",
        "languages": ["en", "ne"],
        "tokenizer_fingerprint": "same-tokenizer",
        "model_config": {"width": 16, "layers": 1},
        "evaluation_protocol": "eval-v1",
        "validation_metric": "cross-entropy",
    }
    records = [
        {"run_id": "baseline", **common, "final_validation_loss": 2.0},
        {"run_id": "different-tokenizer", **common,
         "tokenizer_fingerprint": "other-tokenizer", "final_validation_loss": 1.0},
        {"run_id": "different-model", **common,
         "model_config": {"width": 32, "layers": 2}, "final_validation_loss": 0.5},
    ]
    registry = tmp_path / "experiments.jsonl"
    registry.write_text("\n".join(json.dumps(row) for row in records), encoding="utf-8")
    report = compare_runs(registry, "pretraining")
    assert report["comparable_to_previous"] is False
    assert report["validation_loss_delta_latest_minus_previous"] is None
    # The final record defines the compatibility group; do not rank the
    # lower-loss run from a different model configuration against it.
    assert report["best_run_id"] == "different-model"
    assert "different model configuration" in report["incompatibility_reasons"]



def test_byte_bpe_pretraining_checkpoint_loads_in_native_inference_server(tmp_path, monkeypatch):
    import json
    import sys
    from train import main
    from serve import load_engine
    from bpe_tokenizer import ByteBPETokenizer

    corpus = tmp_path / "bpe-permitted.txt"
    corpus.write_text(
        ("नमस्ते संसार new star multilingual नमस्ते संसार " * 50),
        encoding="utf-8",
    )
    checkpoint = tmp_path / "bpe-model.pt"
    monkeypatch.setattr(sys, "argv", [
        "train.py", "--data", str(corpus), "--output", str(checkpoint),
        "--steps", "1", "--batch-size", "2", "--block-size", "2",
        "--width", "16", "--layers", "1", "--heads", "4",
        "--max-vocab-size", "257", "--tokenizer", "bpe", "--seed", "19",
    ])
    main()

    saved = torch.load(checkpoint, map_location="cpu", weights_only=True)
    assert saved["format"] == "new-star-byte-bpe-gpt-v1"
    assert saved["tokenizer"]["type"] == ByteBPETokenizer.FORMAT
    metadata = json.loads(
        checkpoint.with_suffix(".pt.json").read_text(encoding="utf-8")
    )
    assert metadata["tokenizer_type"] == ByteBPETokenizer.FORMAT
    assert metadata["vocabulary_size"] == saved["config"]["vocab_size"]

    model, tokenizer = load_engine(str(checkpoint), "cpu")
    sample = "नेपाली हिन्दी বাংলা 🌍"
    assert tokenizer.decode(tokenizer.encode(sample)) == sample
    assert model.config.vocab_size == len(tokenizer)


def test_byte_bpe_sft_checkpoint_loads_in_native_inference_server(tmp_path, monkeypatch):
    import json
    import sys
    from sft_train import main
    from serve import load_engine
    from bpe_tokenizer import ByteBPETokenizer

    data = tmp_path / "bpe-instructions.jsonl"
    rows = []
    for language, question, answer in [
        ("ne", "नमस्ते प्रश्न", "नमस्कार उत्तर"),
        ("en", "hello question", "hello answer"),
    ]:
        for split, marker in [("train", "train"), ("validation", "heldout")]:
            rows.append({
                "language": language,
                "split": split,
                "messages": [
                    {"role": "user", "content": f"{question} {marker}"},
                    {"role": "assistant", "content": f"{answer} {marker}"},
                ],
            })
    data.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows),
        encoding="utf-8",
    )
    manifest = tmp_path / "bpe-instructions-manifest.json"
    manifest.write_text(json.dumps({
        "dataset_name": "BPE SFT test fixture",
        "source": "locally authored automated test",
        "license": "CC0-1.0",
        "permission_basis": "created for automated tests",
    }), encoding="utf-8")
    checkpoint = tmp_path / "bpe-sft.pt"
    monkeypatch.setattr(sys, "argv", [
        "sft_train.py", "--data", str(data), "--manifest", str(manifest),
        "--output", str(checkpoint), "--steps", "1", "--batch-size", "2",
        "--block-size", "64", "--width", "16", "--layers", "1",
        "--heads", "4", "--eval-interval", "1", "--max-vocab-size", "320",
        "--tokenizer", "bpe",
    ])
    main()

    saved = torch.load(checkpoint, map_location="cpu", weights_only=True)
    assert saved["format"] == "new-star-byte-bpe-gpt-v1"
    assert saved["tokenizer"]["type"] == ByteBPETokenizer.FORMAT
    model, tokenizer = load_engine(str(checkpoint), "cpu")
    sample = "नेपाली हिन्दी বাংলা 🌍"
    assert tokenizer.decode(tokenizer.encode(sample)) == sample
    assert model.config.vocab_size == len(tokenizer)



def test_sft_can_finetune_existing_pretraining_checkpoint_without_retokenizing(tmp_path, monkeypatch):
    import hashlib
    import json
    import sys
    from sft_train import main
    from model import NewStarConfig, NewStarGPT
    from bpe_tokenizer import ByteBPETokenizer

    tokenizer = ByteBPETokenizer.train(
        "नमस्ते प्रश्न नमस्कार उत्तर hello question hello answer",
        max_merges=8,
    )
    config = NewStarConfig(
        vocab_size=len(tokenizer), block_size=64, width=16, layers=1, heads=4
    )
    base_model = NewStarGPT(config)
    base_checkpoint = tmp_path / "pretrained-base.pt"
    torch.save({
        "format": "new-star-byte-bpe-gpt-v1",
        "config": vars(config),
        "tokenizer": tokenizer.to_dict(),
        "state_dict": base_model.state_dict(),
    }, base_checkpoint)
    original_weights = {
        key: value.detach().clone()
        for key, value in base_model.state_dict().items()
    }

    data = tmp_path / "base-sft-instructions.jsonl"
    rows = []
    for language, question, answer in [
        ("ne", "नमस्ते प्रश्न", "नमस्कार उत्तर"),
        ("en", "hello question", "hello answer"),
    ]:
        for split, marker in [("train", "train"), ("validation", "heldout")]:
            rows.append({
                "language": language,
                "split": split,
                "messages": [
                    {"role": "user", "content": f"{question} {marker}"},
                    {"role": "assistant", "content": f"{answer} {marker}"},
                ],
            })
    data.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows),
        encoding="utf-8",
    )
    manifest = tmp_path / "base-sft-manifest.json"
    manifest.write_text(json.dumps({
        "dataset_name": "base checkpoint SFT fixture",
        "source": "locally authored automated test",
        "license": "CC0-1.0",
        "permission_basis": "created for automated tests",
    }), encoding="utf-8")
    output = tmp_path / "finetuned.pt"
    monkeypatch.setattr(sys, "argv", [
        "sft_train.py", "--data", str(data), "--manifest", str(manifest),
        "--output", str(output), "--steps", "1", "--batch-size", "2",
        "--block-size", "64", "--eval-interval", "1",
        "--base-checkpoint", str(base_checkpoint),
    ])
    main()

    saved = torch.load(output, map_location="cpu", weights_only=True)
    assert saved["config"] == vars(config)
    assert saved["tokenizer"] == tokenizer.to_dict()
    assert saved["format"] == "new-star-byte-bpe-gpt-v1"
    assert any(
        not torch.equal(original_weights[key], saved["state_dict"][key])
        for key in original_weights
    )
    metadata = json.loads(output.with_suffix(".pt.json").read_text(encoding="utf-8"))
    assert metadata["tokenizer_type"] == ByteBPETokenizer.FORMAT
    assert metadata["base_checkpoint"] == base_checkpoint.name
    assert metadata["base_checkpoint_fingerprint"] == hashlib.sha256(base_checkpoint.read_bytes()).hexdigest()



def test_gradient_checkpointing_forward_backward_matches_standard_model():
    config = NewStarConfig(
        vocab_size=256, block_size=8, width=16, layers=2, heads=2,
        dropout=0.0, gradient_checkpointing=True,
    )
    model = NewStarGPT(config).train()
    tokens = torch.randint(0, 256, (2, 8))
    _, loss = model(tokens, tokens)
    assert loss is not None and torch.isfinite(loss)
    loss.backward()
    assert model.token_embedding.weight.grad is not None
