from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI = ROOT / 'docs' / 'ai'
MAP = AI / 'project-map.json'
CATALOG = ROOT / 'delta-game/delta-admin/src/main/resources/platform/catalog.json'


def fail(message: str) -> None:
    print('AI context error: ' + message)
    raise SystemExit(1)


def main() -> None:
    if not MAP.exists():
        fail(f'missing {MAP}')
    data = json.loads(MAP.read_text(encoding='utf-8'))
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    if data.get('version') != catalog.get('version'):
        fail(f"version mismatch: project-map={data.get('version')} catalog={catalog.get('version')}")
    for rel in data.get('documents', []):
        if not (ROOT / rel).exists():
            fail('missing document: ' + rel)
    missing = []
    for feature in data.get('features', []):
        for group in ('backend', 'admin', 'h5'):
            for rel in feature.get(group, []):
                if not (ROOT / rel).exists():
                    missing.append(f"{feature.get('id')}:{rel}")
    if missing:
        for item in missing:
            print('  missing ' + item)
        fail(f'{len(missing)} feature paths are stale')
    contracts = data.get('contracts', {})
    for key in ('endpointBaseline', 'openapiBaseline', 'types'):
        rel = contracts.get(key)
        if not rel or not (ROOT / rel).exists():
            fail('missing contract: ' + key)
    endpoints = json.loads((ROOT / contracts['endpointBaseline']).read_text(encoding='utf-8'))
    openapi = json.loads((ROOT / contracts['openapiBaseline']).read_text(encoding='utf-8'))
    if len(endpoints) != contracts.get('endpoints'):
        fail(f"endpoint count mismatch: {len(endpoints)} != {contracts.get('endpoints')}")
    operations = sum(1 for item in openapi.get('paths', {}).values() for method in item if method.lower() in {'get','post','put','delete','patch'})
    if operations != contracts.get('operations'):
        fail(f"openapi operation mismatch: {operations} != {contracts.get('operations')}")
    schemas = len(openapi.get('components', {}).get('schemas', {}))
    if schemas != contracts.get('schemas'):
        fail(f"schema count mismatch: {schemas} != {contracts.get('schemas')}")
    print(f"AI context OK: version {data['version']}, {len(data['features'])} features, {operations} operations, {schemas} schemas")


if __name__ == '__main__':
    main()


