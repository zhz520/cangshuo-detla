from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENDPOINTS = ROOT / 'contracts' / 'endpoint-baseline.json'
OPENAPI = ROOT / 'contracts' / 'openapi.baseline.json'
HTTP_METHODS = {'get', 'post', 'put', 'delete', 'patch'}


def normalize(path: str) -> str:
    # Spring MVC allows regex path variables; OpenAPI renders them without the regex.
    path = re.sub(r'\{([^}:]+):[^}]+\}', r'{\1}', path)
    return path.rstrip('/') or '/'


def source_operations() -> set[tuple[str, str]]:
    rows = json.loads(ENDPOINTS.read_text(encoding='utf-8'))
    return {(row['method'].upper(), normalize(row['path'])) for row in rows}


def openapi_operations() -> tuple[dict, set[tuple[str, str]]]:
    document = json.loads(OPENAPI.read_text(encoding='utf-8'))
    version = document.get('openapi', '')
    if not version.startswith('3.'):
        raise SystemExit(f'openapi baseline has unsupported version: {version!r}')
    operations = set()
    for path, item in document.get('paths', {}).items():
        for method in item:
            if method.lower() in HTTP_METHODS:
                operations.add((method.upper(), normalize(path)))
    return document, operations


def main() -> None:
    if not ENDPOINTS.exists():
        raise SystemExit(f'endpoint baseline missing: {ENDPOINTS}')
    if not OPENAPI.exists():
        raise SystemExit(f'openapi baseline missing: {OPENAPI}; run scripts/export-openapi-baseline.ps1')
    expected = source_operations()
    document, actual = openapi_operations()
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    if missing:
        print('Endpoints present in source but missing from OpenAPI:')
        for method, path in missing:
            print(f'  - {method} {path}')
    if extra:
        print('Endpoints present in OpenAPI but missing from source baseline:')
        for method, path in extra:
            print(f'  + {method} {path}')
    if missing or extra:
        print('Regenerate both baselines intentionally after reviewing the endpoint change.')
        sys.exit(1)
    schemas = len(document.get('components', {}).get('schemas', {}))
    print(f'openapi baseline OK ({len(actual)} operations, {schemas} schemas)')


if __name__ == '__main__':
    main()

