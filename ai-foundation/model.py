"""Small decoder-only Transformer language model built from standard PyTorch layers.

This is an educational/trainable New Star architecture baseline, not a pretrained
frontier model. It starts with byte tokens so no external tokenizer/model is needed.
"""
from __future__ import annotations

import torch
from torch import nn
from torch.utils.checkpoint import checkpoint


class NewStarConfig:
    def __init__(self, vocab_size: int = 256, block_size: int = 256,
                 width: int = 256, layers: int = 4, heads: int = 4,
                 dropout: float = 0.1, gradient_checkpointing: bool = False):
        if width % heads:
            raise ValueError("width must be divisible by heads")
        if min(vocab_size, block_size, width, layers, heads) < 1:
            raise ValueError("model dimensions must be positive")
        if not 0.0 <= dropout < 1.0:
            raise ValueError("dropout must be in the range [0, 1)")
        self.vocab_size = vocab_size
        self.block_size = block_size
        self.width = width
        self.layers = layers
        self.heads = heads
        self.dropout = dropout
        self.gradient_checkpointing = bool(gradient_checkpointing)


class CausalBlock(nn.Module):
    def __init__(self, config: NewStarConfig):
        super().__init__()
        self.norm1 = nn.LayerNorm(config.width)
        self.attention = nn.MultiheadAttention(
            config.width, config.heads, dropout=config.dropout, batch_first=True
        )
        self.norm2 = nn.LayerNorm(config.width)
        self.feed_forward = nn.Sequential(
            nn.Linear(config.width, 4 * config.width),
            nn.GELU(),
            nn.Linear(4 * config.width, config.width),
            nn.Dropout(config.dropout),
        )

    def forward(self, x: torch.Tensor, causal_mask: torch.Tensor) -> torch.Tensor:
        normalized = self.norm1(x)
        attended, _ = self.attention(
            normalized, normalized, normalized, attn_mask=causal_mask,
            need_weights=False,
        )
        x = x + attended
        return x + self.feed_forward(self.norm2(x))

    def forward_with_cache(self, x: torch.Tensor, causal_mask: torch.Tensor):
        """Run a prompt and retain each layer's normalized K/V input states."""
        normalized = self.norm1(x)
        attended, _ = self.attention(
            normalized, normalized, normalized, attn_mask=causal_mask,
            need_weights=False,
        )
        output = x + attended
        output = output + self.feed_forward(self.norm2(output))
        return output, normalized

    def forward_incremental(self, x: torch.Tensor, past_states: torch.Tensor):
        """Process one token while attending to cached states and the current token."""
        normalized = self.norm1(x)
        all_states = torch.cat((past_states, normalized), dim=1)
        attended, _ = self.attention(
            normalized, all_states, all_states, need_weights=False,
        )
        output = x + attended
        output = output + self.feed_forward(self.norm2(output))
        return output, all_states


class NewStarGPT(nn.Module):
    """Decoder-only causal language model with learned token/position embeddings."""

    def __init__(self, config: NewStarConfig | None = None):
        super().__init__()
        self.config = config or NewStarConfig()
        c = self.config
        self.token_embedding = nn.Embedding(c.vocab_size, c.width)
        self.position_embedding = nn.Embedding(c.block_size, c.width)
        self.dropout = nn.Dropout(c.dropout)
        self.blocks = nn.ModuleList([CausalBlock(c) for _ in range(c.layers)])
        self.final_norm = nn.LayerNorm(c.width)
        self.language_head = nn.Linear(c.width, c.vocab_size, bias=False)
        self.language_head.weight = self.token_embedding.weight

    def forward(self, tokens: torch.Tensor, targets: torch.Tensor | None = None):
        if tokens.ndim != 2 or tokens.shape[1] < 1 or tokens.shape[1] > self.config.block_size:
            raise ValueError("tokens must be [batch, sequence] with 1 <= sequence <= block_size")
        if tokens.dtype != torch.long:
            raise ValueError("tokens must use torch.long token IDs")
        if torch.any(tokens < 0) or torch.any(tokens >= self.config.vocab_size):
            raise ValueError("token IDs must be within the configured vocabulary")
        if targets is not None:
            if targets.shape != tokens.shape or targets.dtype != torch.long:
                raise ValueError("targets must be torch.long and match the token shape")
            valid_targets = targets != -100
            if not torch.any(valid_targets):
                raise ValueError("targets must contain at least one non-masked token")
            if torch.any(targets[valid_targets] < 0) or torch.any(targets[valid_targets] >= self.config.vocab_size):
                raise ValueError("target IDs must be -100 (ignored) or within the configured vocabulary")
        positions = torch.arange(tokens.shape[1], device=tokens.device)
        x = self.dropout(self.token_embedding(tokens) + self.position_embedding(positions))
        mask = torch.triu(
            torch.ones(tokens.shape[1], tokens.shape[1], device=tokens.device,
                       dtype=torch.bool), diagonal=1
        )
        for block in self.blocks:
            if self.training and self.config.gradient_checkpointing and x.requires_grad:
                x = checkpoint(block, x, mask, use_reentrant=False)
            else:
                x = block(x, mask)
        logits = self.language_head(self.final_norm(x))
        loss = None
        if targets is not None:
            loss = nn.functional.cross_entropy(
                logits.reshape(-1, logits.size(-1)), targets.reshape(-1)
            )
        return logits, loss

    def forward_with_cache(self, tokens: torch.Tensor):
        """Prefill a prompt and return logits plus per-layer attention caches."""
        if tokens.ndim != 2 or tokens.shape[1] < 1 or tokens.shape[1] > self.config.block_size:
            raise ValueError("tokens must be [batch, sequence] with 1 <= sequence <= block_size")
        if tokens.dtype != torch.long:
            raise ValueError("tokens must use torch.long token IDs")
        if torch.any(tokens < 0) or torch.any(tokens >= self.config.vocab_size):
            raise ValueError("token IDs must be within the configured vocabulary")
        positions = torch.arange(tokens.shape[1], device=tokens.device)
        x = self.dropout(self.token_embedding(tokens) + self.position_embedding(positions))
        mask = torch.triu(
            torch.ones(tokens.shape[1], tokens.shape[1], device=tokens.device,
                       dtype=torch.bool), diagonal=1
        )
        caches = []
        for block in self.blocks:
            x, layer_cache = block.forward_with_cache(x, mask)
            caches.append(layer_cache)
        return self.language_head(self.final_norm(x)), caches

    def forward_incremental(self, token: torch.Tensor, position: int,
                            caches: list[torch.Tensor]):
        """Advance cached decoding by exactly one token at its absolute window position."""
        if token.ndim != 2 or token.shape[1] != 1 or token.dtype != torch.long:
            raise ValueError("incremental token must be a [batch, 1] torch.long tensor")
        if not 0 <= position < self.config.block_size:
            raise ValueError("incremental position must be inside block_size")
        if len(caches) != len(self.blocks):
            raise ValueError("one attention cache is required for every Transformer block")
        if torch.any(token < 0) or torch.any(token >= self.config.vocab_size):
            raise ValueError("token IDs must be within the configured vocabulary")
        for cache in caches:
            if (not isinstance(cache, torch.Tensor) or cache.ndim != 3
                    or cache.shape[0] != token.shape[0]
                    or cache.shape[1] != position
                    or cache.shape[2] != self.config.width
                    or cache.device != token.device):
                raise ValueError("attention cache shape/device does not match token batch and position")
        x = self.dropout(self.token_embedding(token) + self.position_embedding.weight[position:position + 1])
        next_caches = []
        for block, cache in zip(self.blocks, caches):
            x, next_cache = block.forward_incremental(x, cache)
            next_caches.append(next_cache)
        return self.language_head(self.final_norm(x)), next_caches

    @torch.no_grad()
    def generate(self, tokens: torch.Tensor, max_new_tokens: int = 100,
                 temperature: float = 1.0, top_k: int | None = 50,
                 top_p: float = 0.95, repetition_penalty: float = 1.0) -> torch.Tensor:
        """Sample continuations using per-layer KV-state caching and bounded context."""
        if not isinstance(max_new_tokens, int) or isinstance(max_new_tokens, bool) or max_new_tokens < 0:
            raise ValueError("max_new_tokens must be a non-negative integer")
        if not isinstance(temperature, (int, float)) or isinstance(temperature, bool) or not 0 < temperature <= 2:
            raise ValueError("temperature must be greater than 0 and at most 2")
        if top_k is not None and (not isinstance(top_k, int) or isinstance(top_k, bool) or top_k < 1):
            raise ValueError("top_k must be None or a positive integer")
        if not isinstance(top_p, (int, float)) or isinstance(top_p, bool) or not 0 < top_p <= 1:
            raise ValueError("top_p must be greater than 0 and at most 1")
        if not isinstance(repetition_penalty, (int, float)) or isinstance(repetition_penalty, bool) or repetition_penalty < 1:
            raise ValueError("repetition_penalty must be at least 1")
        if tokens.ndim != 2 or tokens.shape[1] < 1:
            raise ValueError("generation prompt must be a non-empty [batch, sequence] tensor")
        if tokens.dtype != torch.long:
            raise ValueError("generation tokens must use torch.long token IDs")
        if torch.any(tokens < 0) or torch.any(tokens >= self.config.vocab_size):
            raise ValueError("generation tokens must be within the configured vocabulary")
        self.eval()
        if max_new_tokens == 0:
            return tokens
        context = tokens[:, -self.config.block_size:]
        logits, caches = self.forward_with_cache(context)

        for step in range(max_new_tokens):
            next_logits = logits[:, -1, :] / float(temperature)
            if repetition_penalty > 1:
                for row in range(tokens.shape[0]):
                    seen = torch.unique(tokens[row])
                    values = next_logits[row, seen]
                    next_logits[row, seen] = torch.where(
                        values > 0, values / float(repetition_penalty),
                        values * float(repetition_penalty),
                    )
            if top_k is not None:
                k = min(top_k, next_logits.shape[-1])
                threshold = torch.topk(next_logits, k, dim=-1).values[:, -1, None]
                next_logits = next_logits.masked_fill(next_logits < threshold, float("-inf"))
            if top_p < 1:
                sorted_logits, sorted_indices = torch.sort(next_logits, descending=True, dim=-1)
                sorted_probs = torch.softmax(sorted_logits, dim=-1)
                cumulative = torch.cumsum(sorted_probs, dim=-1)
                remove = cumulative - sorted_probs >= float(top_p)
                sorted_logits = sorted_logits.masked_fill(remove, float("-inf"))
                filtered = torch.full_like(next_logits, float("-inf"))
                next_logits = filtered.scatter(1, sorted_indices, sorted_logits)
            probabilities = torch.softmax(next_logits, dim=-1)
            next_token = torch.multinomial(probabilities, num_samples=1)
            tokens = torch.cat((tokens, next_token), dim=1)
            if step + 1 == max_new_tokens:
                break
            if context.shape[1] < self.config.block_size:
                logits, caches = self.forward_incremental(
                    next_token, context.shape[1], caches
                )
                context = torch.cat((context, next_token), dim=1)
            else:
                context = tokens[:, -self.config.block_size:]
                logits, caches = self.forward_with_cache(context)
        return tokens
