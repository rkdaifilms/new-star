# New Star Foundation Model — first from-scratch milestone

This folder contains New Star's own experimental decoder-only Transformer architecture. It is not an API wrapper around Gemini/OpenAI and does not download pretrained model weights. The network is initialized from random weights and can be trained on a UTF-8 corpus that the project owner has the rights to use.

## What is implemented

- A decoder-only causal Transformer using PyTorch's multi-head attention.
- A byte-level vocabulary (all 256 byte values), so there is no external tokenizer dependency.
- Learned token and position embeddings, causal attention masking, feed-forward blocks, residual paths, and tied input/output embeddings.
- A next-byte prediction training loop with validation loss, AdamW, and gradient clipping.
- Local checkpoint saving and text generation.

## Run a tiny experiment

Use Python 3.10+ and install the separate training dependency:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r ai-foundation/requirements.txt
python -m ai_foundation.train --data ./your-permitted-corpus.txt --steps 2000
python -m ai_foundation.generate --checkpoint artifacts/newstar-small.pt --prompt "Namaste" --tokens 120
```

The first experiments should use a small, curated corpus and a small model on CPU. A GPU substantially reduces training time. The corpus must be lawfully obtained and suitable for the intended use; do not scrape private data or include secrets or personal records.

## Honest capability boundary

This is an architecture and training-pipeline starting point, not a useful ChatGPT/Gemini-level assistant yet. A tiny byte-level model trained on a small corpus will produce weak output. High-quality independent models require large, carefully licensed datasets, tokenizer research, distributed training, extensive evaluation, safety training, and substantial compute. No pretrained weights, image/video generator, speech model, production endpoint, or deployed service is included in this milestone.

## Separation from bootstrap inference

`ai-inference/` remains a separate CPU inference test service for the small third-party Qwen GGUF bootstrap model. It is not silently relabeled as New Star's trained foundation model. The from-scratch model in this folder is experimental until it has reproducible training, benchmark results, safety evaluation, and a separately implemented serving adapter.
