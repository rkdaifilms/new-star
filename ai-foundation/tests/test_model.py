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
