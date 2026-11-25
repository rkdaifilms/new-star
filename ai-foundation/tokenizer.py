"""Universal Unicode tokenizer with learned code points and UTF-8 byte fallback."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass

from bpe_tokenizer import ByteBPETokenizer


def _validate_unicode_text(text: str) -> None:
    if not isinstance(text, str):
        raise ValueError("text to encode must be a string")
    try:
        text.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise ValueError("text must contain only valid Unicode scalar values") from exc


@dataclass
class UnicodeCharTokenizer:
    """Use frequent Unicode code points as single tokens and UTF-8 bytes otherwise.

    IDs 0..255 are reserved for raw UTF-8 bytes. Learned characters start at
    256, so every Unicode string remains representable, even if its characters
    were absent from the training corpus.
    """

    tokens: list[str]
    BYTE_VOCAB_SIZE = 256
    CHAR_OFFSET = 256
    FORMAT = "unicode-char-byte-fallback-v1"

    def __post_init__(self) -> None:
        if not isinstance(self.tokens, list) or any(
            not isinstance(token, str) or len(token) != 1
            or 0xD800 <= ord(token) <= 0xDFFF
            for token in self.tokens
        ):
            raise ValueError("character vocabulary must contain only valid single Unicode scalar values")
        if len(set(self.tokens)) != len(self.tokens):
            raise ValueError("token vocabulary must not contain duplicates")

    @classmethod
    def train(cls, text: str, max_vocab_size: int | None = None) -> "UnicodeCharTokenizer":
        if not isinstance(text, str) or not text:
            raise ValueError("training text must be a non-empty string")
        if max_vocab_size is not None and (
            not isinstance(max_vocab_size, int) or isinstance(max_vocab_size, bool)
            or max_vocab_size < cls.BYTE_VOCAB_SIZE
        ):
            raise ValueError("max_vocab_size must be an integer of at least 256 for byte fallback")
        _validate_unicode_text(text)
        counts = Counter(text)
        ordered = sorted(counts, key=lambda char: (-counts[char], ord(char)))
        if max_vocab_size is not None:
            ordered = ordered[:max_vocab_size - cls.BYTE_VOCAB_SIZE]
        return cls(ordered)

    @classmethod
    def from_dict(cls, payload: dict) -> "UnicodeCharTokenizer":
        if not isinstance(payload, dict) or payload.get("type") != cls.FORMAT:
            raise ValueError("unsupported tokenizer format")
        tokens = payload.get("tokens")
        if not isinstance(tokens, list):
            raise ValueError("character tokenizer payload must contain a token list")
        try:
            return cls(tokens)
        except (TypeError, ValueError) as exc:
            raise ValueError("invalid character tokenizer vocabulary") from exc

    def to_dict(self) -> dict:
        return {"type": self.FORMAT, "tokens": self.tokens}

    def encode(self, text: str) -> list[int]:
        _validate_unicode_text(text)
        lookup = {token: index + self.CHAR_OFFSET for index, token in enumerate(self.tokens)}
        ids: list[int] = []
        for char in text:
            token_id = lookup.get(char)
            if token_id is not None:
                ids.append(token_id)
            else:
                ids.extend(char.encode("utf-8"))
        return ids

    def decode(self, token_ids: list[int]) -> str:
        output: list[str] = []
        pending_bytes = bytearray()

        def flush_bytes() -> None:
            if pending_bytes:
                output.append(pending_bytes.decode("utf-8", errors="replace"))
                pending_bytes.clear()

        for token_id in token_ids:
            if not isinstance(token_id, int) or isinstance(token_id, bool) or token_id < 0 or token_id >= len(self):
                raise ValueError("token ID is outside the tokenizer vocabulary")
            if token_id < self.BYTE_VOCAB_SIZE:
                pending_bytes.append(token_id)
            else:
                flush_bytes()
                output.append(self.tokens[token_id - self.CHAR_OFFSET])
        flush_bytes()
        return "".join(output)

    def __len__(self) -> int:
        return self.BYTE_VOCAB_SIZE + len(self.tokens)


def tokenizer_from_dict(payload: dict):
    """Load a supported New Star tokenizer from a checkpoint payload."""
    if not isinstance(payload, dict):
        raise ValueError("tokenizer payload must be an object")
    tokenizer_type = payload.get("type")
    if tokenizer_type == UnicodeCharTokenizer.FORMAT:
        return UnicodeCharTokenizer.from_dict(payload)
    if tokenizer_type == ByteBPETokenizer.FORMAT:
        return ByteBPETokenizer.from_dict(payload)
    raise ValueError("unsupported New Star tokenizer format")
