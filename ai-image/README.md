# New Star Image AI — experimental from-scratch DDPM with optional caption conditioning

This package is New Star's first independent image-model training path. It contains a compact image-space U-Net denoiser and a Denoising Diffusion Probabilistic Model (DDPM) schedule implemented with PyTorch. It starts from random weights and does not call Gemini, OpenAI, Stable Diffusion, or another hosted image API.

## Current capability

- Unconditional image generation from a permitted local image dataset.
- Experimental caption-conditioned image generation: an optional per-image caption map is hashed into deterministic token IDs and pooled into the denoiser conditioning path. This is a small from-scratch baseline, not a semantic pretrained text encoder.
- Classifier-free guidance: caption dropout during training teaches a null-caption path; sampling can combine conditional and unconditional predictions with a bounded guidance scale.
- RGB image loading, resizing, normalization, dataset SHA-256 audit, and a required provenance/permission manifest.
- Training from random initialization, gradient clipping, exponential moving average (EMA) weights, atomic checkpoint writing, seeded sampling, and PNG output.
- CPU support for small experiments and optional CUDA execution when a compatible PyTorch runtime is installed.

Caption conditioning is implemented as an experimental hashed-token baseline. It is not comparable to CLIP/T5 conditioning and does not imply useful prompt understanding. Inpainting, high-resolution synthesis, video, and a production image model remain unimplemented. A useful model requires a sufficiently large, diverse, high-quality, accurately captioned dataset and substantial training. The automated tests use tiny synthetic images only to verify the code path; they do not create a useful image generator.

## Dataset permission manifest

Create a JSON file alongside your image folder:

```json
{
  "dataset_name": "my permitted image collection",
  "source": "where the images came from",
  "license": "the actual dataset license",
  "permission_basis": "why this collection may legally be used for model training",
  "captions": {
    "images/photo-001.jpg": "a red bicycle beside a stone wall",
    "images/photo-002.jpg": "snow-covered mountains under a clear sky"
  }
}
```

Do not train on private photos, personal records, or images without permission. The manifest is an audit record supplied by the dataset curator; it does not independently verify the truth of a license claim.

## Install and train

From the repository root:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r ai-image/requirements.txt
python ai-image/train.py --data ./permitted-images --manifest ./image-manifest.json \
  --output ./artifacts/newstar-image-ddpm.pt --image-size 32 --steps 10000 --ema-decay 0.999 --device cpu
```

For CUDA, install a PyTorch build compatible with the selected NVIDIA driver and use `--device cuda`. Begin with 32×32 images and a small test run to measure memory and throughput before increasing image size, model width, batch size, or training steps.

To train the experimental caption-conditioned variant, add a `captions` object to the manifest. Its keys must exactly match every image's relative path under the dataset directory, and every value must be a non-empty caption. Then add `--text-conditioned`:

```sh
python ai-image/train.py --data ./permitted-images --manifest ./image-manifest.json \\
  --output ./artifacts/newstar-image-text-ddpm.pt --image-size 32 --steps 10000 \\
  --text-conditioned --caption-dropout 0.1 --device cpu
```

Caption tokens use a deterministic BLAKE2 hash into a fixed 8,192-entry vocabulary. This makes training and inference reproducible without downloading an external text model, but hash collisions and lack of learned word semantics limit prompt understanding. The training manifest is audited to ensure there is exactly one caption per image. Do not describe this baseline as a high-quality general-purpose text-to-image model.


Training writes an atomic resumable state file beside the final checkpoint at each
checkpoint interval (default: 100 optimizer steps), named
`newstar-image-ddpm.pt.resume.pt`. The resume state includes model and optimizer
weights, EMA weights, the exact shuffled-dataset position, independent random-generator
states, the dataset fingerprint, and the training configuration. Generation loads EMA weights
when available; the raw model weights are retained for further training and evaluation. To resume,
keep the same dataset, batch size, image size, model width, diffusion steps,
learning rate, and seed, and set `--steps` to the desired **total** optimizer
step count:

```sh
python ai-image/train.py --data ./permitted-images --manifest ./image-manifest.json \
  --output ./artifacts/newstar-image-ddpm.pt --image-size 32 --steps 20000 \
  --checkpoint-interval 100 --resume-from ./artifacts/newstar-image-ddpm.pt.resume.pt \
  --device cpu
```

A changed dataset fingerprint or incompatible training configuration is rejected.
The CPU resume test compares a two-step uninterrupted run with a one-step run
resumed to two steps and checks that both raw and EMA model weights match exactly. This
verifies restart mechanics, not image quality.

## Generate

```sh
python ai-image/generate.py --checkpoint ./artifacts/newstar-image-ddpm.pt \
  --output ./artifacts/newstar-generated.png --count 4 --seed 2026 --device cpu
```

The output is an image grid saved as PNG. The same checkpoint and seed reproduce the same sample on the same compatible runtime. Different hardware or library versions can produce small numeric differences.

For a caption-conditioned checkpoint, provide a prompt and optional classifier-free guidance scale:

```sh
python ai-image/generate.py --checkpoint ./artifacts/newstar-image-text-ddpm.pt \\
  --prompt "a red bicycle beside a stone wall" --guidance-scale 2.0 \\
  --output ./artifacts/newstar-prompt-generated.png --count 1 --seed 2026 --device cpu
```

## Evaluate on held-out images

Keep a separate evaluation folder that was not used for training, and give it
its own accurate permission manifest. The evaluator loads EMA weights when
present, uses a fixed seed for repeatable noise/timestep selection, and writes
an atomic JSON report:

```sh
python ai-image/evaluate.py \
  --checkpoint ./artifacts/newstar-image-ddpm.pt \
  --data ./heldout-images --manifest ./heldout-manifest.json \
  --batch-size 8 --seed 2026 \
  --output ./artifacts/newstar-image-evaluation.json --device cpu
```

The report includes held-out dataset and manifest fingerprints, sample counts,
and noise-prediction MSE. MSE is a denoising diagnostic only; it is not a
perceptual metric such as FID and does not establish that generated images are
visually useful. The evaluator compares the full image-and-manifest fingerprint with the training
checkpoint and also rejects any held-out image whose SHA-256 content hash overlaps
with a training image, even if the file was renamed or the manifest changed.
Evaluation data must be separate from the training folder for the result to be a
meaningful held-out measurement.

## Local image API

Start the local service after training:

```sh
python ai-image/serve.py --checkpoint ./artifacts/newstar-image-ddpm.pt --host 127.0.0.1 --port 8780
```

It exposes `GET /health`, `GET /v1/models`, and `POST /v1/images/generations`. The generation endpoint accepts `n` (1–4), optional `seed`, and the checkpoint-native `size`; it returns PNG bytes as base64 in the `data[].b64_json` field. For an unconditional checkpoint it rejects `prompt`; for a caption-conditioned checkpoint it requires a non-empty `prompt` and routes its deterministic token IDs through the U-Net. The text-conditioned baseline is experimental and may not reliably follow natural-language prompts. The optional `guidance_scale` defaults to 1.0 (no guidance); values from 0 to 10 are accepted. Values above 1 trade diversity for stronger caption conditioning, but do not overcome the hashed-token representation’s limited semantic understanding. Set `NEW_STAR_IMAGE_API_KEY` before binding beyond loopback; clients must send a bearer token. Do not expose the experimental service directly to the public internet.

## CPU container and Compose

From the repository root, put the permitted images under `./data/images` and the provenance file at `./data/manifest.json`. Then run:

```sh
NEW_STAR_IMAGE_TRAIN_STEPS=10000 docker compose -f ai-image/docker-compose.yml --profile train run --rm train
export NEW_STAR_IMAGE_API_KEY="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
docker compose -f ai-image/docker-compose.yml --profile serve up -d --build serve
```

The serving container binds inside the container network, while the published host port is restricted to `127.0.0.1:8780`. Keep the API key private. Stop the service with `docker compose -f ai-image/docker-compose.yml --profile serve down`.

## Model boundary

This is an experimental DDPM baseline with optional hashed-caption conditioning, not a trained commercial image model. The prompt-conditioning and guidance code paths exist, but the repository does not contain a licensed training dataset or trained image weights. No claim of useful prompt understanding, image quality, or production readiness should be made until training and independent evaluation on a suitable captioned dataset are complete.
