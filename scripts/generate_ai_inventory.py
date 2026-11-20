from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs' / 'ai' / 'inventory.json'


def admin_views() -> list[str]:
    base = ROOT / 'delta-admin-ui' / 'src' / 'views'
    return sorted(path.relative_to(ROOT).as_posix() for path in base.rglob('*.vue') if path.is_file())


def admin_api_modules() -> list[dict]:
    result = []
    for path in sorted((ROOT / 'delta-admin-ui' / 'src' / 'api').glob('*.js')):
        text = path.read_text(encoding='utf-8', errors='ignore')
        exports = sorted(set(re.findall(r'export\s+(?:function|const)\s+(\w+)', text)))
        urls = sorted(set(re.findall(r'url\s*:\s*[\'\"]([^\'\"]+)', text)))
        result.append({'module': path.name, 'exports': exports, 'urls': urls})
    return result


def h5_pages() -> list[str]:
    data = json.loads((ROOT / 'delta-mp' / 'pages.json').read_text(encoding='utf-8'))
    return sorted(item['path'] for item in data.get('pages', []))


def admin_routes() -> list[str]:
    text = (ROOT / 'delta-admin-ui' / 'src' / 'router' / 'index.js').read_text(encoding='utf-8', errors='ignore')
    return sorted(set(re.findall(r"path\s*:\s*['\"]([^'\"]+)['\"]", text)))


def db_tables() -> list[str]:
    path = ROOT / 'delta_game.sql'
    if not path.exists():
        return []
    text = path.read_text(encoding='utf-8', errors='ignore')
    return sorted(set(re.findall(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[^A-Za-z0-9_]*([A-Za-z0-9_]+)", text, re.I)))


def migrations() -> list[str]:
    base = ROOT / 'delta-game' / 'delta-admin' / 'src' / 'main' / 'resources' / 'db' / 'migration'
    return sorted(path.name for path in base.glob('*.sql')) if base.exists() else []


def backend_tests() -> list[str]:
    base = ROOT / 'delta-game'
    return sorted(path.relative_to(ROOT).as_posix() for path in base.rglob('src/test/java/**/*Test.java') if path.is_file())


def admin_tests() -> list[str]:
    base = ROOT / 'delta-admin-ui' / 'test'
    return sorted(path.name for path in base.glob('*.mjs')) if base.exists() else []


def h5_tests() -> list[str]:
    base = ROOT / 'delta-mp' / 'test'
    return sorted(path.name for path in base.glob('*.mjs')) if base.exists() else []


def controllers() -> list[dict]:
    rows = json.loads((ROOT / 'contracts' / 'endpoint-baseline.json').read_text(encoding='utf-8'))
    counts = {}
    for row in rows:
        counts[row['controller']] = counts.get(row['controller'], 0) + 1
    return [{'name': name, 'endpoints': count} for name, count in sorted(counts.items())]


def build() -> dict:
    catalog = json.loads((ROOT / 'delta-game/delta-admin/src/main/resources/platform/catalog.json').read_text(encoding='utf-8'))
    endpoints = json.loads((ROOT / 'contracts' / 'endpoint-baseline.json').read_text(encoding='utf-8'))
    openapi = json.loads((ROOT / 'contracts' / 'openapi.baseline.json').read_text(encoding='utf-8'))
    operations = sum(1 for item in openapi.get('paths', {}).values() for method in item if method.lower() in {'get', 'post', 'put', 'delete', 'patch'})
    return {
        'version': catalog['version'],
        'endpointCount': len(endpoints),
        'operationCount': operations,
        'schemaCount': len(openapi.get('components', {}).get('schemas', {})),
        'controllers': controllers(),
        'adminViews': admin_views(),
        'adminApiModules': admin_api_modules(),
        'h5Pages': h5_pages(),
        'adminRoutes': admin_routes(),
        'dbTables': db_tables(),
        'migrations': migrations(),
        'backendTests': backend_tests(),
        'adminTests': admin_tests(),
        'h5Tests': h5_tests(),
    }


def main() -> None:
    OUT.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    data = json.loads(OUT.read_text(encoding='utf-8'))
    print('wrote ' + str(OUT))
    print('controllers=%d adminViews=%d adminRoutes=%d apiModules=%d h5Pages=%d tables=%d migrations=%d tests=%d/%d/%d' % (
        len(data['controllers']), len(data['adminViews']), len(data['adminRoutes']), len(data['adminApiModules']), len(data['h5Pages']), len(data['dbTables']), len(data['migrations']), len(data['backendTests']), len(data['adminTests']), len(data['h5Tests'])))


if __name__ == '__main__':
    main()
