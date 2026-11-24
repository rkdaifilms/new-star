"""Small decoder-only Transformer language model built from standard PyTorch layers.

This is an educational/trainable New Star architecture baseline, not a pretrained
frontier model. It starts with byte tokens so no external tokenizer/model is needed.
"""
from __future__ import annotations

import torch
from torch import nn


class NewStarConfig:
    def __init__(self, vocab_size: int = 256, block_size: int = 256,
                 width: int = 256, layers: int = 4, heads: int = 4,
                 dropout: float = 0.1):
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
            raise ValueError("tokens must use torch.long byte-token IDs")
        if torch.any(tokens < 0) or torch.any(tokens >= self.config.vocab_size):
            raise ValueError("token IDs must be within the configured vocabulary")
        if targets is not None:
            if targets.shape != tokens.shape or targets.dtype != torch.long:
                raise ValueError("targets must be torch.long and match the token shape")
            if torch.any(targets < 0) or torch.any(targets >= self.config.vocab_size):
                raise ValueError("target IDs must be within the configured vocabulary")
        positions = torch.arange(tokens.shape[1], device=tokens.device)
        x = self.dropout(self.token_embedding(tokens) + self.position_embedding(positions))
        mask = torch.triu(
            torch.ones(tokens.shape[1], tokens.shape[1], device=tokens.device,
                       dtype=torch.bool), diagonal=1
        )
        for block in self.blocks:
            x = block(x, mask)
        logits = self.language_head(self.final_norm(x))
        loss = None
        if targets is not None:
            loss = nn.functional.cross_entropy(
                logits.reshape(-1, logits.size(-1)), targets.reshape(-1)
            )
        return logits, loss

    @torch.no_grad()
    def generate(self, tokens: torch.Tensor, max_new_tokens: int = 100,
                 temperature: float = 1.0) -> torch.Tensor:
        if not isinstance(max_new_tokens, int) or max_new_tokens < 0:
            raise ValueError("max_new_tokens must be a non-negative integer")
        if temperature <= 0:
            raise ValueError("temperature must be greater than zero")
        if tokens.ndim != 2 or tokens.shape[1] < 1:
            raise ValueError("generation prompt must be a non-empty [batch, sequence] tensor")
        if tokens.dtype != torch.long:
            raise ValueError("generation tokens must use torch.long byte-token IDs")
        if torch.any(tokens < 0) or torch.any(tokens >= self.config.vocab_size):
            raise ValueError("generation token IDs must be within the configured vocabulary")
        self.eval()
        for _ in range(max_new_tokens):
            context = tokens[:, -self.config.block_size:]
            logits, _ = self(context)
            probabilities = torch.softmax(logits[:, -1, :] / temperature, dim=-1)
            next_token = torch.multinomial(probabilities, num_samples=1)
            tokens = torch.cat((tokens, next_token), dim=1)
        return tokens
