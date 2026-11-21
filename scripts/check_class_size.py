#!/usr/bin/env python3
"""Prevent new oversized Java classes; existing ones are documented in a baseline."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "delta-game"
BASELINE = ROOT / "docs" / "ai" / "oversized-class-baseline.json"
THRESHOLD = 20 * 1024


def oversized() -> dict[str, int]:
    result: dict[str, int] = {}
    for path in SOURCE.rglob("*.java"):
        if "src/main/java" not in path.as_posix():
            continue
        size = path.stat().st_size
        if size > THRESHOLD:
            result[path.relative_to(ROOT).as_posix()] = size
    return dict(sorted(result.items()))


def main() -> int:
    current = oversized()
    if "--write-baseline" in sys.argv:
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {BASELINE.relative_to(ROOT)} ({len(current)} oversized classes)")
        return 0

    baseline = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    new_files = sorted(set(current) - set(baseline))
    removed = sorted(set(baseline) - set(current))
    if new_files:
        print(f"new oversized Java classes (> {THRESHOLD // 1024}KB):")
        for path in new_files:
            print(f"- {path} ({current[path] / 1024:.1f}KB)")
        print("Split by business capability or intentionally update the baseline after review.")
        return 1
    print(f"class size OK: {len(current)} known oversized classes, no new files")
    if removed:
        print(f"baseline can be regenerated; removed: {', '.join(removed)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
