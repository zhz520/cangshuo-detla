from __future__ import annotations

import json
import sys
from pathlib import Path

from export_endpoint_baseline import OUT, collect


def key(row: dict) -> str:
    return f"{row['method']} {row['path']} ({row['controller']}.{row['handler']})"


def main() -> None:
    if not OUT.exists():
        raise SystemExit(f'baseline missing: {OUT}; run scripts/export_endpoint_baseline.py')
    expected = {key(row) for row in json.loads(OUT.read_text(encoding='utf-8'))}
    actual = {key(row) for row in collect()}
    removed = sorted(expected - actual)
    added = sorted(actual - expected)
    if not removed and not added:
        print(f'endpoint baseline OK ({len(actual)} endpoints)')
        return
    if removed:
        print('Removed or renamed endpoints:')
        for row in removed:
            print('  -', row)
    if added:
        print('New endpoints:')
        for row in added:
            print('  +', row)
    print('Update contracts/endpoint-baseline.json intentionally, then rerun.')
    sys.exit(1)


if __name__ == '__main__':
    main()
