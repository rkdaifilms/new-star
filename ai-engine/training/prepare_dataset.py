import argparse
import hashlib
import json
import re
from pathlib import Path

SECRET_PATTERNS = [
    re.compile(r"(?i)\b(sk-[a-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,})\b"),
    re.compile(r"(?i)\b(password|passwd|secret|api[_ -]?key|access[_ -]?token)\s*[:=]\s*[^\s,;]{8,}"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"),
]


def clean_record(value, line_number):
    if not isinstance(value, dict) or not isinstance(value.get("messages"), list):
        raise ValueError(f"line {line_number}: expected an object with a messages array")
    messages = value["messages"]
    if not 2 <= len(messages) <= 32:
        raise ValueError(f"line {line_number}: messages must contain 2 to 32 turns")
    cleaned = []
    total = 0
    last_role = None
    for item in messages:
        if not isinstance(item, dict) or item.get("role") not in {"system", "user", "assistant"}:
            raise ValueError(f"line {line_number}: invalid message role")
        content = item.get("content")
        if not isinstance(content, str) or not content.strip() or len(content) > 12000:
            raise ValueError(f"line {line_number}: message content is empty or exceeds 12,000 characters")
        content = content.strip()
        if any(pattern.search(content) for pattern in SECRET_PATTERNS):
            raise ValueError(f"line {line_number}: possible credential/secret detected; remove it before training")
        total += len(content)
        if total > 30000:
            raise ValueError(f"line {line_number}: conversation exceeds 30,000 characters")
        if item["role"] == "assistant" and last_role == "assistant":
            raise ValueError(f"line {line_number}: consecutive assistant turns are not supported")
        cleaned.append({"role": item["role"], "content": content})
        last_role = item["role"]
    if not any(item["role"] == "user" for item in cleaned) or cleaned[-1]["role"] != "assistant":
        raise ValueError(f"line {line_number}: conversation must contain a user turn and end with assistant")
    return {"messages": cleaned}


def main():
    parser = argparse.ArgumentParser(description="Validate and split private New Star chat training JSONL")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--validation-ratio", type=float, default=0.1)
    args = parser.parse_args()
    if not 0.05 <= args.validation_ratio <= 0.3:
        raise SystemExit("validation-ratio must be between 0.05 and 0.30")
    source = Path(args.input)
    if not source.is_file():
        raise SystemExit("Training input file does not exist")
    records = []
    seen = set()
    input_count = 0
    with source.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            input_count += 1
            try:
                record = clean_record(json.loads(line), line_number)
            except (json.JSONDecodeError, ValueError) as error:
                raise SystemExit(str(error))
            canonical = json.dumps(record, ensure_ascii=False, sort_keys=True)
            digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
            if digest not in seen:
                seen.add(digest)
                records.append((digest, record))
    if len(records) < 10:
        raise SystemExit("At least 10 unique validated conversations are required")
    records.sort(key=lambda item: item[0])
    validation_count = max(1, round(len(records) * args.validation_ratio))
    validation = records[:validation_count]
    training = records[validation_count:]
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    for name, group in (("train.jsonl", training), ("validation.jsonl", validation)):
        with (output / name).open("w", encoding="utf-8") as stream:
            for _, record in group:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
    report = {
        "unique_conversations": len(records),
        "training_conversations": len(training),
        "validation_conversations": len(validation),
        "duplicate_conversations_removed": input_count - len(records),
        "source_file": source.name,
        "credentials_scan": "passed",
    }
    (output / "dataset-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
