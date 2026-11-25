# New Star Foundation Model — first from-scratch milestone

This folder contains New Star's own experimental decoder-only Transformer architecture. It is not an API wrapper around Gemini/OpenAI and does not download pretrained model weights. The network is initialized from random weights and can be trained on a UTF-8 corpus that the project owner has the rights to use.

## What is implemented

- A decoder-only causal Transformer using PyTorch's multi-head attention.
- A multilingual hybrid tokenizer: frequent Unicode characters become compact tokens, while UTF-8 byte fallback preserves every unseen character across scripts and emoji.
- Learned token and position embeddings, causal attention masking, feed-forward blocks, residual paths, and tied input/output embeddings.
- A next-token prediction training loop with validation loss, AdamW, and gradient clipping.
- Local checkpoint saving and text generation.
- Incremental decoding with projected per-head key/value caches using PyTorch scaled dot-product attention. The prompt is prefetched once, each next token projects only its own query/key/value, and the cache is rebuilt when the fixed context window rolls over. This reduces repeated Transformer-layer work during generation; it does not change the model's context-window limit or make a small untrained checkpoint capable.

- A deterministic held-out evaluation command reporting cross-entropy (nats/token or nats/byte depending on checkpoint) and perplexity.


## Experimental byte-level BPE tokenizer

The deterministic byte-level BPE tokenizer in `bpe_tokenizer.py` learns merges
from UTF-8 text, retains byte fallback for every Unicode script and emoji, and
supports JSON-compatible serialization. It can now be selected by both
pretraining and SFT, is saved in a distinct checkpoint format, and is loaded by
the native generation, evaluation, and HTTP inference paths. Automated tests
cover multilingual round trips, unseen text, serialization, and a tiny
pretraining-to-inference checkpoint flow.

Train a small experimental vocabulary from a permitted corpus:

```python
from bpe_tokenizer import ByteBPETokenizer

tokenizer = ByteBPETokenizer.train(
    "permitted multilingual training text",
    max_merges=2000,
    min_frequency=2,
)
payload = tokenizer.to_dict()
restored = ByteBPETokenizer.from_dict(payload)
assert restored.decode(restored.encode("नमस्ते 🌍")) == "नमस्ते 🌍"
```

Select BPE for a new pretraining checkpoint with `--tokenizer bpe`; use
`--tokenizer char` (the default) to preserve the legacy character-tokenizer
behavior. The `--max-vocab-size` option sets the total target vocabulary ceiling;
for BPE, the available merge count is that value minus the 256 raw-byte IDs.
The tokenizer type and fingerprint are included in checkpoints and resume
validation, so a run cannot silently resume with a different tokenizer. BPE
checkpoints use `new-star-byte-bpe-gpt-v1` and are supported by
`generate.py`, `evaluate.py`, and `serve.py`.

Benchmark tokenizer compression and throughput on a permitted UTF-8 sample:
```sh
python ai-foundation/benchmark_tokenizer.py --data ./permitted-benchmark.txt \
  --max-merges 2000 --iterations 3 --output artifacts/bpe-benchmark.json
```
The report records the input fingerprint, vocabulary size, tokens per character,
tokens per UTF-8 byte, compression ratio, encode/decode throughput, and a
verified round trip. Compare the same held-out multilingual sample and settings
across experiments; compression alone does not measure language quality. This
small implementation is intended for research corpora, not production training.

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
python ai-foundation/evaluate.py --checkpoint artifacts/newstar-small.pt --data ./held-out-languages.jsonl --data-format jsonl --report-json artifacts/multilingual-benchmark.json
python ai-foundation/generate.py --checkpoint artifacts/newstar-small.pt --prompt "Namaste" --tokens 120
# Deterministic greedy decoding (no token sampling):
python ai-foundation/generate.py --checkpoint artifacts/newstar-small.pt --prompt "Namaste" --tokens 120 --greedy
# Reproducible sampling with an isolated RNG seed:
python ai-foundation/generate.py --checkpoint artifacts/newstar-small.pt --prompt "Namaste" --tokens 120 --seed 2026
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

Run multilingual pretraining with a provenance manifest (the audit runs automatically before training):

```sh
python ai-foundation/train.py --data ./permitted-multilingual.jsonl \\
  --data-format jsonl --manifest ./dataset-manifest.json --steps 2000
```

Run evaluation with `--data-format jsonl`. The evaluation command scans every next-token target in each held-out
language corpus (rather than estimating quality from a random sample), prints
token-weighted cross-entropy and perplexity per language plus a macro-average,
and `--report-json` optionally saves the metrics and evaluated-token counts
as a JSON artifact. Each report includes SHA-256 fingerprints for the checkpoint,
held-out data, and tokenizer, plus the model configuration and evaluation method,
so results can be traced to their exact inputs. This supports repeatable comparisons across checkpoints;
it does not establish language fluency by itself. Training samples each batch row from a
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
`GET /v1/models`, `POST /v1/completions`, and `POST /v1/chat/completions`.
The model-list route uses the OpenAI-compatible `list` response shape so local
clients can discover the configured checkpoint name before sending chat requests. The chat endpoint
accepts ordinary `system`, `user`, and `assistant` messages and returns an
OpenAI-style non-streaming response. Set `temperature`, `top_k`, `top_p`, and
`repetition_penalty` to control sampling. Set `do_sample` to `false` for
repeatable greedy argmax decoding; in that mode top-k/top-p filtering and
sampling randomness are bypassed. An optional integer `seed` creates an
isolated request-local random generator for reproducible sampling without
changing the process-wide PyTorch RNG state. Streaming is not implemented. To bind to
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

This module is paired with the response-only SFT optimizer loop documented
below. It is an experimental training pipeline, not a completed large-scale
fine-tuning run or a production assistant-quality claim. Review source
permissions, quality, privacy, safety, and language balance before training.
Do not mix validation conversations into training.


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

The resume file includes model weights, optimizer state, mixed-precision
gradient-scaler state, batch-sampling RNG, and PyTorch CPU/CUDA RNG state.
The trainer restores the scaler when present and remains compatible with older
resume files that predate scaler persistence. It rejects a changed dataset or
incompatible training configuration rather than silently resuming a different
experiment.
The `--steps` value is the desired total step count, not additional steps.
The pretraining and SFT resume files are separate formats. Both trainers persist and restore mixed-precision gradient-scaler state as well as optimizer and RNG state; older resume files without scaler state remain readable. Both trainers reject a changed dataset or incompatible training configuration. For pretraining, the resume file also verifies the tokenizer fingerprint. The `--steps` value is the desired total step count, not additional steps.

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


## Pre-training corpus audit

Before training on multilingual text JSONL, run the corpus audit. It checks
that every declared language has train and validation records, enforces
minimum character counts, detects exact duplicates after whitespace/case
normalization across the train/validation boundary, and requires a provenance
manifest. It also reports within-split duplicates as warnings.

Create a manifest with the dataset identity and reviewable rights information:

```json
{
  "dataset_name": "your dataset name",
  "source": "where the material came from",
  "license": "the applicable license",
  "permission_basis": "why you are authorized to use it"
}
```

Run the audit before training:

```sh
python ai-foundation/audit_corpus.py \\
  --data ./permitted-multilingual.jsonl \\
  --manifest ./dataset-manifest.json \\
  --min-train-chars 1000 --min-validation-chars 200 \\
  --report-json ./artifacts/corpus-audit.json
```

The command exits non-zero when required checks fail. Character counts are a
basic size guard, not a measure of linguistic quality. Duplicate detection
does not detect paraphrases, translations, or semantic overlap, and the tool
cannot verify that a claimed license or permission is genuine. A human must
review source terms and rights before training; never include private records,
secrets, or material you are not authorized to use.


Both multilingual pretraining and SFT require a provenance manifest and run
an exact normalized train/validation duplicate audit before optimization. The
result is saved beside the checkpoint as `.corpus-audit.json`. For SFT, the
audit uses the role-tagged conversation content. This catches exact overlap,
not paraphrase or semantic leakage; human review of data rights remains required.


## Experiment registry

Each completed checkpoint can be added to an append-only JSONL experiment
registry. The registry stores a unique run ID, UTC record time, training stage,
checkpoint SHA-256, selected training metrics/configuration, language list,
and (when present) the successful corpus-audit provenance summary. It refuses
to register a checkpoint whose audit sidecar explicitly reports failure.

Register a pretraining or SFT checkpoint after training:

```sh
python ai-foundation/experiment_registry.py \
  --metadata artifacts/newstar-small.pt.json \
  --registry artifacts/experiments.jsonl

python ai-foundation/experiment_registry.py \
  --metadata artifacts/newstar-sft.pt.json \
  --registry artifacts/experiments.jsonl \
  --stage sft
```

The registry is local experiment bookkeeping, not a remote tracking service or
a substitute for backing up checkpoint files. Keep the registry and checkpoint
artifacts together and do not include private dataset text or credentials.


## Compare registered experiments

Compare runs within the same training stage. A latest-vs-previous validation
loss delta is shown only when both runs have the same dataset fingerprint and
the same language set; otherwise the report explicitly marks them incomparable.
The best run is selected by the lowest recorded validation loss within that
stage. Optionally verify that checkpoint files still exist and match their
registered SHA-256 hashes.

```sh
python ai-foundation/compare_experiments.py \
  --registry artifacts/experiments.jsonl --stage pretraining \
  --verify-checkpoints --output artifacts/pretraining-comparison.json

python ai-foundation/compare_experiments.py \
  --registry artifacts/experiments.jsonl --stage sft
```

A lower validation loss is better only for comparable evaluation setups.
Changing the validation corpus, tokenizer, evaluation procedure, or model
objective can make scores incomparable even if the language set matches.
Keep validation data and evaluation settings fixed when measuring progress.


## Local inference benchmark

Measure generation latency and throughput on a saved New Star checkpoint.
The benchmark performs warmup separately, reports mean/median/p95 latency,
generated tokens per second, parameter count, runtime versions, and (on CUDA)
peak allocated device memory. Use identical checkpoints, prompts, token counts,
thread settings, and hardware when comparing runs.

```sh
python ai-foundation/benchmark_inference.py \
  --checkpoint artifacts/newstar-small.pt \
  --device cpu --threads 2 --warmup 3 --iterations 10 \
  --max-new-tokens 32 --output artifacts/cpu-benchmark.json
```

For CUDA, use `--device cuda` and omit `--threads`. This measures local
autoregressive generation only; it does not claim production serving capacity
or model quality. The experimental baseline may generate low-quality text
until trained on suitable, permitted data.


Experiment comparisons are deliberately strict. A validation-loss delta is shown
only when both runs have matching dataset and tokenizer fingerprints, language
sets, model configuration, evaluation protocol, and validation metric. The
"best" run is selected only from the compatibility group matching the latest
run; older records missing these fields are not silently treated as comparable.
Pretraining and SFT use distinct evaluation protocol identifiers.


## Hardware-aware training

The training scripts now resolve `--device auto|cpu|cuda|cuda:N` and `--precision auto|fp32|fp16|bf16`. Auto selects CUDA when PyTorch can see it; otherwise it uses CPU. CPU training is kept in FP32. CUDA auto precision selects BF16 when the device reports support and FP16 otherwise; FP16 uses gradient scaling. Runtime hardware details are printed at startup and saved in the checkpoint metadata. The chosen precision is included in resume compatibility checks.

Run a training job with automatic hardware selection:

```sh
python ai-foundation/train.py --data ./permitted-corpus.txt --device auto --precision auto --steps 2000
python ai-foundation/sft_train.py --data ./permitted-instructions.jsonl --manifest ./dataset-manifest.json --base-checkpoint ./artifacts/newstar-small.pt --device auto --precision auto --steps 1000
```

To force CPU execution, use `--device cpu --precision fp32`. To select a particular GPU, use `--device cuda:0 --precision auto`. A CUDA-enabled PyTorch installation and compatible NVIDIA driver are required for GPU training; installing the CPU-only PyTorch wheel will correctly leave auto mode on CPU.


## Reproducible CPU and NVIDIA GPU training containers

Training containers keep the corpus read-only and write checkpoints only to the mounted artifacts directory. Use only data you have the legal right to train on; provide a provenance manifest when using multilingual JSONL data.

CPU setup (Docker Engine required):

```sh
cd ai-foundation
mkdir -p data artifacts
cp /path/to/your-permitted-corpus.txt data/corpus.txt
NEW_STAR_CORPUS_DIR=./data NEW_STAR_ARTIFACTS_DIR=./artifacts docker compose --profile cpu run --build --rm train-cpu
```

NVIDIA GPU setup requires a compatible NVIDIA driver, NVIDIA Container Toolkit, and Docker Compose GPU device reservations:

```sh
cd ai-foundation
mkdir -p data artifacts
cp /path/to/your-permitted-corpus.txt data/corpus.txt
NEW_STAR_CORPUS_DIR=./data NEW_STAR_ARTIFACTS_DIR=./artifacts docker compose --profile gpu run --build --rm train-gpu
```

Set `NEW_STAR_TRAIN_STEPS` and `NEW_STAR_TOKENIZER` before the Compose command to change the run. The GPU image pins PyTorch 2.5.1 with CUDA 12.4 wheels; the host driver must support CUDA 12.4. The training scripts auto-select CUDA when available and report the selected device, precision, CUDA runtime, and GPU memory. CPU mode is FP32. This container setup provides reproducible execution environments; it does not supply GPU hardware, a training corpus, or trained weights. Start with a small authorized corpus and short run, inspect the loss/evaluation report, then scale only on hardware with sufficient memory.


## Multilingual generation quality evaluation

For generated-answer evaluation, prepare a reviewed, rights-cleared JSONL file with one case per language:

```json
{"language":"ne","prompt":"नेपालको राजधानी कहाँ हो?","reference":"नेपालको राजधानी काठमाडौं हो।"}
{"language":"hi","prompt":"नेपाल की राजधानी क्या है?","reference":"नेपाल की राजधानी काठमांडू है।"}
```

Run after training a checkpoint:

```bash
python generation_eval.py --checkpoint artifacts/newstar.pt --cases generation-cases.jsonl --max-new-tokens 96 --report-json artifacts/generation-quality.json
```

Add `--require-all-supported-languages` to fail unless every language in the gateway catalog has at least one reviewed case. Reports include per-language exact-match rate, character n-gram F1 (chrF-style), non-empty output rate and output length. Character n-gram F1 is a surface metric only; use bilingual human review and task-specific factual/safety tests before making quality claims. Do not treat synthetic CI examples as a quality benchmark.
