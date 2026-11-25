#!/usr/bin/env python3
"""Syntax-check inline JavaScript embedded in an HTML page using Node.js."""

from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("html_file", type=Path)
    args = parser.parse_args()

    try:
        html = args.html_file.read_text(encoding="utf-8")
    except OSError as exc:
        parser.error(f"cannot read {args.html_file}: {exc}")

    scripts = re.findall(
        r"<script\\b(?![^>]*\\bsrc\\s*=)[^>]*>([\\s\\S]*?)</script\\s*>",
        html,
        flags=re.IGNORECASE,
    )
    scripts = [script for script in scripts if script.strip()]
    if not scripts:
        raise SystemExit(f"No inline JavaScript found in {args.html_file}")

    for index, source in enumerate(scripts, start=1):
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", suffix=".js", delete=False
        ) as handle:
            handle.write(source)
            temp_path = Path(handle.name)
        try:
            result = subprocess.run(
                ["node", "--check", str(temp_path)],
                check=False,
                capture_output=True,
                text=True,
            )
        finally:
            temp_path.unlink(missing_ok=True)
        if result.returncode:
            print(f"Inline script {index} failed syntax validation:")
            print(result.stdout, end="")
            print(result.stderr, end="")
            return result.returncode

    print(f"Validated {len(scripts)} inline JavaScript block(s) in {args.html_file}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
