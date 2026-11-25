"""Deterministic byte-level BPE tokenizer for New Star experiments.

IDs 0..255 are UTF-8 bytes. Learned merge tokens are appended in training order,
so every Unicode string remains representable without an unknown-token ID.
This is a small-corpus research implementation, not a production tokenizer.
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass


@dataclass
class ByteBPETokenizer:
    merges: list[tuple[bytes, bytes]]

    BYTE_VOCAB_SIZE = 256
    FORMAT = "new-star-byte-bpe-v1"

    def __post_init__(self) -> None:
        if not isinstance(self.merges, list):
            raise ValueError("BPE merges must be a list of byte pairs")
        normalized = []
        for pair in self.merges:
            if (not isinstance(pair, (tuple, list)) or len(pair) != 2
                    or not all(isinstance(part, bytes) for part in pair)):
                raise ValueError("each BPE merge must contain exactly two byte strings")
            left, right = pair
            if not left or not right:
                raise ValueError("BPE merge parts must be non-empty")
            normalized.append((left, right))
        if len(set(normalized)) != len(normalized):
            raise ValueError("BPE merge rules must be unique")
        outputs = [left + right for left, right in normalized]
        if len(set(outputs)) != len(outputs):
            raise ValueError("BPE merge outputs must be unique")
        self.merges = normalized
        self._merge_ranks = {pair: rank for rank, pair in enumerate(self.merges)}
        self._token_to_id = {
            output: self.BYTE_VOCAB_SIZE + rank
            for rank, output in enumerate(outputs)
        }

    @classmethod
    def train(cls, text: str, max_merges: int = 1000,
              min_frequency: int = 2) -> "ByteBPETokenizer":
        if not isinstance(text, str) or not text:
            raise ValueError("training text must be a non-empty string")
        if (not isinstance(max_merges, int) or isinstance(max_merges, bool)
                or max_merges < 0):
            raise ValueError("max_merges must be a non-negative integer")
        if (not isinstance(min_frequency, int) or isinstance(min_frequency, bool)
                or min_frequency < 1):
            raise ValueError("min_frequency must be a positive integer")

        # Keep whitespace and punctuation: BPE learns from the exact UTF-8 stream.
        sequence = [bytes([value]) for value in text.encode("utf-8")];
        merges: list[tuple[bytes, bytes]] = []
        for _ in range(max_merges):
            pairs = Counter(zip(sequence, sequence[1:]))
            if not pairs:
                break
            known_outputs = {left + right for left, right in merges}
            eligible = [
                (pair, frequency) for pair, frequency in pairs.items()
                if pair[0] + pair[1] not in known_outputs
            ]
            if not eligible:
                break
            best, frequency = min(
                eligible, key=lambda item: (-item[1], item[0])
            )
            if frequency < min_frequency:
                break
            left, right = best
            merges.append((left, right))
            merged = left + right
            next_sequence: list[bytes] = []
            index = 0
            while index < len(sequence):
                if (index + 1 < len(sequence)
                        and sequence[index] == left
                        and sequence[index + 1] == right):
                    next_sequence.append(merged)
                    index += 2
                else:
                    next_sequence.append(sequence[index])
                    index += 1
            sequence = next_sequence
        return cls(merges)

    def encode(self, text: str) -> list[int]:
        if not isinstance(text, str):
            raise ValueError("text to encode must be a string")
        sequence: list[bytes] = [bytes([value]) for value in text.encode("utf-8")]
        while len(sequence) > 1:
            candidates = [
                (self._merge_ranks[(sequence[i], sequence[i + 1])], i)
                for i in range(len(sequence) - 1)
                if (sequence[i], sequence[i + 1]) in self._merge_ranks
            ]
            if not candidates:
                break
            _, index = min(candidates)
            sequence[index:index + 2] = [sequence[index] + sequence[index + 1]]
        return [
            self._token_to_id.get(token, token[0])
            if len(token) == 1 else self._token_to_id[token]
            for token in sequence
        ]

    def decode(self, token_ids: list[int]) -> str:
        expanded = bytearray()
        for token_id in token_ids:
            if not isinstance(token_id, int) or isinstance(token_id, bool) or not 0 <= token_id < len(self):
                raise ValueError("token ID is outside the tokenizer vocabulary")
            if token_id < self.BYTE_VOCAB_SIZE:
                expanded.append(token_id)
            else:
                expanded.extend(self._expand(token_id - self.BYTE_VOCAB_SIZE))
        return expanded.decode("utf-8", errors="strict")

    def _expand(self, merge_index: int) -> bytes:
        left, right = self.merges[merge_index]
        return self._expand_bytes(left) + self._expand_bytes(right)

    def _expand_bytes(self, token: bytes) -> bytes:
        # Merge outputs are unique; recursively resolve by their creation index.
        token_id = self._token_to_id.get(token)
        if token_id is None:
            return token
        return self._expand(token_id - self.BYTE_VOCAB_SIZE)

    def to_dict(self) -> dict:
        return {
            "type": self.FORMAT,
            "merges": [[left.hex(), right.hex()] for left, right in self.merges],
        }

    @classmethod
    def from_dict(cls, payload: dict) -> "ByteBPETokenizer":
        if not isinstance(payload, dict) or payload.get("type") != cls.FORMAT:
            raise ValueError("unsupported tokenizer format")
        raw_merges = payload.get("merges")
        if not isinstance(raw_merges, list):
            raise ValueError("BPE tokenizer payload must contain a merge list")
        merges = []
        try:
            for pair in raw_merges:
                if (not isinstance(pair, list) or len(pair) != 2
                        or not all(isinstance(part, str) for part in pair)):
                    raise ValueError("each BPE merge must contain exactly two hexadecimal strings")
                decoded = tuple(bytes.fromhex(part) for part in pair)
                if any(decoded_part.hex() != part.lower() for decoded_part, part in zip(decoded, pair)):
                    raise ValueError("BPE merge hexadecimal strings must be canonical")
                merges.append(decoded)
            return cls(merges)
        except (TypeError, ValueError) as exc:
            raise ValueError("invalid byte-BPE tokenizer payload") from exc

    def __len__(self) -> int:
        return self.BYTE_VOCAB_SIZE + len(self.merges)
