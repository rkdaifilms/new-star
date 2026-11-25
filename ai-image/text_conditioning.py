"""Deterministic, dependency-free caption tokenization for the experimental image model."""
from __future__ import annotations

import hashlib
import re

TEXT_VOCAB_SIZE = 8192
MAX_TEXT_TOKENS = 32
_TOKEN_PATTERN = re.compile(r"[^\W_]+|[^\w\s]", flags=re.UNICODE)


def encode_caption(caption: str, max_tokens: int = MAX_TEXT_TOKENS) -> list[int]:
    if not isinstance(caption, str) or not caption.strip():
        raise ValueError("caption must be a non-empty string")
    if not isinstance(max_tokens, int) or isinstance(max_tokens, bool) or max_tokens < 1:
        raise ValueError("max_tokens must be a positive integer")
    pieces = _TOKEN_PATTERN.findall(caption.casefold())
    if not pieces:
        raise ValueError("caption must contain at least one token")
    encoded = []
    for piece in pieces[:max_tokens]:
        digest = hashlib.blake2b(piece.encode("utf-8"), digest_size=8).digest()
        encoded.append(1 + int.from_bytes(digest, "big") % (TEXT_VOCAB_SIZE - 1))
    return encoded


def encode_captions(captions: list[str], max_tokens: int = MAX_TEXT_TOKENS) -> list[list[int]]:
    if not isinstance(captions, list):
        raise ValueError("captions must be a list of strings")
    return [encode_caption(caption, max_tokens) for caption in captions]
