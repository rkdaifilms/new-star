"""Deterministic Unicode code-point tokenizer for multilingual experiments."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass


@dataclass
class UnicodeCharTokenizer:
    """Map Unicode code points to IDs; unknown code points map to <unk>.

    This is a small character-level tokenizer, not a subword tokenizer. It
    handles Indic scripts without expanding each character into UTF-8 bytes,
    but combining marks and multi-code-point graphemes remain separate tokens.
    """

    tokens: list[str]

    UNK = "<unk>"

    def __post_init__(self) -> None:
        if not self.tokens or self.tokens[0] != self.UNK:
            raise ValueError("token vocabulary must start with the <unk> token")
        if len(set(self.tokens)) != len(self.tokens):
            raise ValueError("token vocabulary must not contain duplicates")

    @classmethod
    def train(cls, text: str, max_vocab_size: int | None = None) -> "UnicodeCharTokenizer":
        if not isinstance(text, str) or not text:
            raise ValueError("training text must be a non-empty string")
        counts = Counter(text)
        ordered = sorted(counts, key=lambda char: (-counts[char], ord(char)))
        if max_vocab_size is not None:
            if max_vocab_size < 2:
                raise ValueError("max_vocab_size must be at least 2")
            ordered = ordered[:max_vocab_size - 1]
        return cls([cls.UNK, *ordered])

    @classmethod
    def from_dict(cls, payload: dict) -> "UnicodeCharTokenizer":
        if payload.get("type") != "unicode-codepoint-v1":
            raise ValueError("unsupported tokenizer format")
        return cls(list(payload["tokens"]))

    def to_dict(self) -> dict:
        return {"type": "unicode-codepoint-v1", "tokens": self.tokens}

    def encode(self, text: str) -> list[int]:
        lookup = {token: index for index, token in enumerate(self.tokens)}
        unknown = 0
        return [lookup.get(char, unknown) for char in text]

    def decode(self, token_ids: list[int]) -> str:
        result = []
        for token_id in token_ids:
            if not isinstance(token_id, int) or token_id < 0 or token_id >= len(self.tokens):
                raise ValueError("token ID is outside the tokenizer vocabulary")
            token = self.tokens[token_id]
            result.append("\\uFFFD" if token == self.UNK else token)
        return "".join(result)

    def __len__(self) -> int:
        return len(self.tokens)
