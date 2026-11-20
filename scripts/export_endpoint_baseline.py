from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'delta-game'
OUT = ROOT / 'contracts' / 'endpoint-baseline.json'

METHOD_MAP = {
    'GetMapping': 'GET',
    'PostMapping': 'POST',
    'PutMapping': 'PUT',
    'DeleteMapping': 'DELETE',
    'PatchMapping': 'PATCH',
}


def quoted(value: str) -> list[str]:
    return re.findall(r'"([^"]*)"', value)


def mapping_paths(args: str) -> list[str]:
    attr = re.search(r'(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")', args)
    source = attr.group(1) if attr else re.split(r'\b(?:produces|consumes|headers|params)\s*=', args)[0]
    return [value for value in quoted(source) if ';' not in value and not re.match(r'^(application|text|image)/', value)]


def class_prefixes(text: str) -> list[str]:
    match = re.search(r'@RequestMapping\s*\(([^)]*)\)\s*(?:@[^\n]+\s*)*public\s+class', text, re.S)
    if not match:
        return ['']
    values = mapping_paths(match.group(1))
    return values or ['']


def scan_file(path: Path) -> list[dict]:
    text = path.read_text(encoding='utf-8')
    prefixes = class_prefixes(text)
    controller = path.stem
    rows = []
    pattern = re.compile(r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*(?:\(([^)]*)\))?', re.S)
    for match in pattern.finditer(text):
        annotation, args = match.group(1), match.group(2) or ''
        if annotation == 'RequestMapping' and match.start() < text.find('public class'):
            continue
        methods = [METHOD_MAP[annotation]] if annotation in METHOD_MAP else []
        if annotation == 'RequestMapping':
            for method in re.findall(r'RequestMethod\.([A-Z]+)', args):
                methods.append(method)
        if not methods:
            methods = ['ANY']
        paths = mapping_paths(args)
        if not paths:
            paths = ['']
        after = text[match.end():]
        handler_match = re.search(r'public\s+[^\n(]+\s+(\w+)\s*\(', after)
        handler = handler_match.group(1) if handler_match else ''
        for method in methods:
            for prefix in prefixes:
                for suffix in paths:
                    full = ('/' + prefix.strip('/') + '/' + suffix.strip('/')).replace('//', '/') or '/'
                    rows.append({'method': method, 'path': full, 'controller': controller, 'handler': handler})
    return rows


def collect() -> list[dict]:
    rows = []
    for path in sorted(GAME.rglob('*Controller.java')):
        if '/src/test/' in path.as_posix():
            continue
        rows.extend(scan_file(path))
    unique = {json.dumps(row, ensure_ascii=False, sort_keys=True) for row in rows}
    return sorted((json.loads(row) for row in unique), key=lambda r: (r['path'], r['method'], r['controller'], r['handler']))


def main() -> None:
    rows = collect()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'wrote {OUT} ({len(rows)} endpoints)')


if __name__ == '__main__':
    main()
