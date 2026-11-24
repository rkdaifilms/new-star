# New Star Foundation Model — first from-scratch milestone

This folder contains New Star's own experimental decoder-only Transformer architecture. It is not an API wrapper around Gemini/OpenAI and does not download pretrained model weights. The network is initialized from random weights and can be trained on a UTF-8 corpus that the project owner has the rights to use.

## What is implemented

- A decoder-only causal Transformer using PyTorch's multi-head attention.
- A multilingual hybrid tokenizer: frequent Unicode characters become compact tokens, while UTF-8 byte fallback preserves every unseen character across scripts and emoji.
- Learned token and position embeddings, causal attention masking, feed-forward blocks, residual paths, and tied input/output embeddings.
- A next-token prediction training loop with validation loss, AdamW, and gradient clipping.
- Local checkpoint saving and text generation.
- A deterministic held-out evaluation command reporting cross-entropy (nats/token or nats/byte depending on checkpoint) and perplexity.

## Run a tiny experiment

Use Python 3.10+ and install the separate training dependency:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r ai-foundation/requirements.txt
python ai-foundation/train.py --data ./your-permitted-corpus.txt --steps 2000 --seed 1337
# If interrupted, resume toward a larger total step count:
python ai-foundation/train.py --data ./your-permitted-corpus.txt --steps 4000 --seed 1337 \\
  --resume-from artifacts/newstar-small.pt.resume.pt
python ai-foundation/evaluate.py --checkpoint artifacts/newstar-small.pt --data ./held-out-permitted-corpus.txt
# For per-language scores, pass a validation-only JSONL file:
python ai-foundation/evaluate.py --checkpoint artifacts/newstar-small.pt --data ./held-out-languages.jsonl --data-format jsonl
python ai-foundation/generate.py --checkpoint artifacts/newstar-small.pt --prompt "Namaste" --tokens 120
```

The scripts import their model directly from the same source folder. Keep evaluation data separate from training data: use a held-out corpus that was not included in the training file. Evaluation uses deterministic sampling so repeated runs with the same checkpoint, data, and seed produce the same reported loss. The first experiments should use a small, curated corpus and a small model on CPU. A GPU substantially reduces training time. The corpus must be lawfully obtained and suitable for the intended use; do not scrape private data or include secrets or personal records.


### Balanced multilingual corpus (JSONL)

For multiple languages, prefer a JSONL file with explicit language labels and
separate train/validation records. Each line is one JSON object:

```json
{"language":"ne","split":"train","text":"यहाँ अनुमति प्राप्त नेपाली प्रशिक्षण सामग्री"}
{"language":"ne","split":"validation","text":"अलग नेपाली validation सामग्री"}
{"language":"hi","split":"train","text":"यहाँ अनुमति प्राप्त हिंदी प्रशिक्षण सामग्री"}
{"language":"hi","split":"validation","text":"अलग हिंदी validation सामग्री"}
{"language":"en","split":"train","text":"Permitted English training material"}
{"language":"en","split":"validation","text":"Separate English validation material"}
```

Run it with `--data-format jsonl`. Training samples each batch row from a
uniformly selected language, so a large language corpus does not automatically
dominate every batch. Every language must have its own held-out validation
records and enough tokens for the selected block size. Language labels are
provided by the dataset curator; this milestone does not automatically detect
the language of a user's prompt. Use balanced, licensed, high-quality data for
each language and evaluate each language separately before claiming support.



## Run the experimental native checkpoint server

After training a Unicode checkpoint, start the local HTTP adapter:

```sh
python ai-foundation/serve.py --checkpoint artifacts/newstar-small.pt
```

It listens on `127.0.0.1:8765` and exposes `GET /health`,
`POST /v1/completions`, and `POST /v1/chat/completions`. The chat endpoint
accepts ordinary `system`, `user`, and `assistant` messages and returns an
OpenAI-style non-streaming response. Set `temperature`, `top_k`, `top_p`, and
`repetition_penalty` to control sampling. Streaming is not implemented. To bind to
a non-loopback interface, set `NEW_STAR_FOUNDATION_API_KEY` first; requests
must then include `Authorization: Bearer ...`. Do not expose this experimental
server directly to the public internet. It has no production-grade abuse
controls, tenant isolation, queueing, or high-availability design. Its text
quality depends entirely on the checkpoint and training corpus.

## Reply-language routing in the hosted gateway

The AI gateway gives an explicitly selected app language priority. If no
supported language preference is supplied, it uses conservative Unicode-script
detection on the latest user message to guide the reply language. Shared scripts
such as Devanagari (Hindi/Nepali), Arabic (Arabic/Urdu/Persian), Han, Latin, and
Cyrillic are deliberately treated as ambiguous and resolved from wording and
context by the language model. This is a routing hint, not a standalone
language-identification model; short or mixed-script messages fall back to
contextual language identification.

## Honest capability boundary

This is an architecture and training-pipeline starting point, not a useful ChatGPT/Gemini-level assistant yet. A small character/byte-fallback model trained on a small corpus will produce weak output. High-quality independent models require large, carefully licensed datasets, multilingual tokenizer research, distributed training, extensive evaluation, safety training, and substantial compute. No pretrained weights, image/video generator, speech model, production endpoint, or deployed service is included in this milestone.

## Separation from bootstrap inference

`ai-inference/` remains a separate CPU inference test service for the small third-party Qwen GGUF bootstrap model. It is not silently relabeled as New Star's trained foundation model. The from-scratch model in this folder is experimental until it has reproducible training, benchmark results, safety evaluation, and a separately implemented serving adapter.


## Instruction-tuning data preparation (SFT)

The experimental instruction-data module validates JSONL chat examples and
creates causal next-token targets that train **only on assistant response
tokens**. System and user text remains in the context but is masked with the
PyTorch `-100` ignore label. Each record must contain `language`, `split`
(`train` or `validation`), and a `messages` array using `system`,
`user`, and `assistant` roles. Conversations must alternate user/assistant
turns and end with an assistant response; an optional system message may appear
only first. Every language needs both training and held-out validation records.

This is data preparation, not a completed fine-tuning run. Review source
permissions, quality, privacy, safety, and language balance before training.
Do not mix validation conversations into training. The current baseline does
not yet include an SFT optimizer loop or a production assistant-quality claim.


## Run response-only multilingual supervised fine-tuning

After preparing a permitted JSONL chat dataset with both `train` and
`validation` records for every language, run the separate SFT training loop:

```sh
python ai-foundation/sft_train.py --data ./permitted-instructions.jsonl \
  --steps 1000 --batch-size 8 --block-size 256 --eval-interval 100 \
  --output artifacts/newstar-sft.pt
```

SFT writes a separate resumable state file next to the output checkpoint at
each evaluation interval (and at the final step). If training is interrupted,
continue to a larger total step count with the same dataset and training
configuration:

```sh
python ai-foundation/sft_train.py --data ./permitted-instructions.jsonl \
  --steps 2000 --batch-size 8 --block-size 256 --eval-interval 100 \
  --output artifacts/newstar-sft.pt \
  --resume-from artifacts/newstar-sft.pt.resume.pt
```

The resume file includes model weights, optimizer state, batch-sampling RNG,
and PyTorch RNG state. The trainer rejects a changed dataset or incompatible
training configuration rather than silently resuming a different experiment.
The `--steps` value is the desired total step count, not additional steps.
The pretraining and SFT resume files are separate formats. Both trainers reject a changed dataset or incompatible training configuration. For pretraining, the resume file also verifies the tokenizer fingerprint. The `--steps` value is the desired total step count, not additional steps.

The SFT loop trains only on assistant response targets; system/user tokens and
padding positions are ignored by the loss. Each batch row selects a language
uniformly. Within each language, validation cross-entropy is weighted by
the number of supervised assistant-response tokens; the final score is an
unweighted macro-average across languages. The checkpoint sidecar records the
seed, model/training configuration, per-language example counts, and final
validation score for experiment tracking. The tokenizer is learned from training conversations only. The output
checkpoint uses the same native format as the pretraining checkpoint and writes
a sidecar JSON with training metadata. This script has not been run on a
large-scale licensed dataset in CI; passing unit tests verifies the pipeline
mechanics, not assistant quality or model readiness. Keep the validation set
strictly held out and review privacy, licensing, and safety before training.
