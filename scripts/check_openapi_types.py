from __future__ import annotations

import sys
from pathlib import Path

from generate_openapi_types import OUT, render


def main() -> None:
    if not OUT.exists():
        raise SystemExit(f'type baseline missing: {OUT}; run scripts/generate_openapi_types.py')
    expected = render()
    actual = OUT.read_text(encoding='utf-8')
    if expected != actual:
        print(f'{OUT} is stale. Run: python scripts/generate_openapi_types.py')
        sys.exit(1)
    print(f'openapi types OK ({OUT.stat().st_size} bytes)')


if __name__ == '__main__':
    main()
