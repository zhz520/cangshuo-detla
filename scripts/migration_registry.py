from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "deploy" / "migration" / "migration-registry.json"


def collect(root: Path = ROOT) -> dict:
    files: list[tuple[Path, str]] = []
    files.extend((path, "APPLY") for path in sorted((root / "deploy" / "baota" / "migrations").glob("*.sql")))
    files.extend((path, "STARTUP") for path in sorted((root / "delta-game" / "delta-admin" / "src" / "main" / "resources" / "db").glob("*.sql")))
    files.extend((path, "BASELINE") for path in sorted(root.glob("delta_game*.sql")))
    entries = []
    by_checksum: dict[str, list[str]] = defaultdict(list)
    for path, source in files:
        data = path.read_bytes()
        checksum = hashlib.sha256(data).hexdigest()
        relative = path.relative_to(root).as_posix()
        entries.append({
            "id": path.stem,
            "file": relative,
            "source": source,
            "sha256": checksum,
            "bytes": len(data),
        })
        by_checksum[checksum].append(relative)
    duplicates = [
        {"sha256": checksum, "files": items}
        for checksum, items in sorted(by_checksum.items())
        if len(items) > 1
    ]
    return {
        "schemaVersion": 1,
        "generatedFrom": "scripts/migration_registry.py",
        "migrations": sorted(entries, key=lambda item: (item["source"], item["file"])),
        "duplicateChecksums": duplicates,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and verify the Delta migration registry.")
    parser.add_argument("--check", action="store_true", help="verify the checked-in registry matches source files")
    parser.add_argument("--strict", action="store_true", help="fail when duplicate migration checksums are present")
    args = parser.parse_args()
    current = collect()
    if args.check:
        if not REGISTRY.exists():
            raise SystemExit(f"migration registry missing: {REGISTRY}")
        expected = json.loads(REGISTRY.read_text(encoding="utf-8"))
        if expected != current:
            raise SystemExit("migration registry is stale; run python scripts/migration_registry.py")
        print(f"migration registry OK ({len(current['migrations'])} files, {len(current['duplicateChecksums'])} duplicate checksum groups)")
    else:
        REGISTRY.parent.mkdir(parents=True, exist_ok=True)
        REGISTRY.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {REGISTRY} ({len(current['migrations'])} files)")
    if args.strict and current["duplicateChecksums"]:
        for duplicate in current["duplicateChecksums"]:
            print("duplicate migration checksum:", duplicate["sha256"], ", ".join(duplicate["files"]))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
