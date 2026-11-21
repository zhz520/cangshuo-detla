#!/usr/bin/env python3
"""Verify all deployable component versions and the release note agree."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_json(relative: str) -> dict:
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def main() -> int:
    catalog = load_json("delta-game/delta-admin/src/main/resources/platform/catalog.json")
    admin_package = load_json("delta-admin-ui/package.json")
    admin_lock = load_json("delta-admin-ui/package-lock.json")
    h5_package = load_json("delta-mp/package.json")
    h5_lock = load_json("delta-mp/package-lock.json")
    manifest = load_json("delta-mp/manifest.json")

    versions = {
        "catalog.json": catalog.get("version"),
        "delta-admin-ui/package.json": admin_package.get("version"),
        "delta-admin-ui/package-lock.json": (admin_lock.get("packages") or {}).get("", {}).get("version"),
        "delta-mp/package.json": h5_package.get("version"),
        "delta-mp/package-lock.json": (h5_lock.get("packages") or {}).get("", {}).get("version"),
        "delta-mp/manifest.json": manifest.get("versionName"),
    }
    unique = {value for value in versions.values() if value}
    if None in versions.values() or "" in versions.values():
        print("missing version value:")
        for name, value in versions.items():
            if not value:
                print(f"- {name}")
        return 1
    if len(unique) != 1:
        print("component versions are not aligned:")
        for name, value in versions.items():
            print(f"- {name}: {value}")
        return 1

    version = unique.pop()
    release_note = ROOT / "docs" / f"{version}-发布说明.md"
    if not release_note.exists():
        print(f"release note missing: {release_note.relative_to(ROOT)}")
        return 1
    print(f"version alignment OK: {version}; release note present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
