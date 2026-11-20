from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / 'contracts' / 'openapi.baseline.json'
OUT = ROOT / 'contracts' / 'api-types.d.ts'
IDENTIFIER = re.compile(r'^[A-Za-z_$][A-Za-z0-9_$]*$')


def ref_name(ref: str) -> str:
    return ref.rsplit('/', 1)[-1]


def ts_type(schema: dict) -> str:
    if not isinstance(schema, dict):
        return 'unknown'
    if '$ref' in schema:
        return ref_name(schema['$ref'])
    if 'enum' in schema:
        values = ' | '.join(json.dumps(value, ensure_ascii=False) for value in schema['enum'])
        return f'({values})' if len(schema['enum']) > 1 else values
    for keyword in ('oneOf', 'anyOf'):
        if keyword in schema:
            parts = [ts_type(item) for item in schema[keyword]]
            return ' | '.join(f'({part})' if ' | ' in part else part for part in parts) or 'unknown'
    if 'allOf' in schema:
        parts = [ts_type(item) for item in schema['allOf']]
        return ' & '.join(f'({part})' if ' | ' in part else part for part in parts) or 'unknown'
    kind = schema.get('type')
    if isinstance(kind, list):
        kind = next((item for item in kind if item != 'null'), 'unknown')
    if kind == 'array':
        item = ts_type(schema.get('items', {}))
        return f'({item})[]' if ' | ' in item or ' & ' in item else f'{item}[]'
    if kind == 'object':
        if 'additionalProperties' in schema and isinstance(schema['additionalProperties'], dict):
            return f"Record<string, {ts_type(schema['additionalProperties'])}>"
        if schema.get('properties'):
            return 'Record<string, unknown>'
        return 'Record<string, unknown>'
    if kind in ('integer', 'number'):
        return 'number'
    if kind == 'boolean':
        return 'boolean'
    if kind == 'string':
        return 'string'
    return 'unknown'


def property_name(name: str) -> str:
    return name if IDENTIFIER.match(name) else json.dumps(name, ensure_ascii=False)


def doc_comment(description: str) -> list[str]:
    text = ' '.join(str(description).split())
    if not text:
        return []
    return ['  /**', f'   * {text}', '   */']


def render_schema(name: str, schema: dict) -> list[str]:
    if '$ref' in schema or 'enum' in schema or schema.get('type') not in (None, 'object'):
        return [f'export type {name} = {ts_type(schema)}']
    lines = []
    if schema.get('description'):
        lines.extend(doc_comment(schema['description']))
    lines.append(f'export interface {name} {{')
    required = set(schema.get('required', []))
    for prop, value in schema.get('properties', {}).items():
        if value.get('description'):
            lines.extend(doc_comment(value['description']))
        optional = '' if prop in required else '?'
        lines.append(f'  {property_name(prop)}{optional}: {ts_type(value)}')
    if not schema.get('properties'):
        lines.append('  [key: string]: unknown')
    lines.append('}')
    return lines


def render() -> str:
    document = json.loads(OPENAPI.read_text(encoding='utf-8'))
    schemas = document.get('components', {}).get('schemas', {})
    lines = [
        '// Generated from contracts/openapi.baseline.json. Do not edit by hand.',
        '// Regenerate with: python scripts/generate_openapi_types.py',
        '',
    ]
    for name in sorted(schemas):
        lines.extend(render_schema(name, schemas[name]))
        lines.append('')
    return '\n'.join(lines).rstrip() + '\n'


def main() -> None:
    OUT.write_text(render(), encoding='utf-8')
    document = json.loads(OPENAPI.read_text(encoding='utf-8'))
    count = len(document.get('components', {}).get('schemas', {}))
    print(f'wrote {OUT} ({count} schemas)')


if __name__ == '__main__':
    main()
