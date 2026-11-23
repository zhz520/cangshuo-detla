#!/usr/bin/env python3
"""Fail when a built static bundle references a missing file."""

import argparse
import json
import re
import sys
import urllib.parse
import zipfile
from pathlib import Path

TEXT_SUFFIXES = {'.html', '.js', '.css', '.json', '.svg'}
INJECTED_ARCHIVE_FILES = {'cangshuo-version.json'}
REFERENCE = re.compile(r'(?:^|["\'(])(/?(?:assets|static|pages-[\w-]+/static)/[^"\'\s<>)]+)')


def references(root):
    found = set()
    for path in root.rglob('*'):
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding='utf-8', errors='ignore')
        except OSError:
            continue
        for match in REFERENCE.findall(text):
            ref = urllib.parse.unquote(match).split('?', 1)[0].split('#', 1)[0].lstrip('/')
            if not ref or ref.endswith('/') or ref.endswith('.map') or '${' in ref or '..' in ref.split('/'):
                continue
            found.add(ref)
    return found


def archive_check(root, archive):
    with zipfile.ZipFile(archive) as zipped:
        entries = {item.filename for item in zipped.infolist() if not item.is_dir()}
    missing = sorted(name for name in entries
                     if name not in INJECTED_ARCHIVE_FILES and not (root / name).is_file())
    extra = sorted(path.relative_to(root).as_posix() for path in root.rglob('*')
                   if path.is_file() and path.relative_to(root).as_posix() not in entries)
    return missing, extra


def check(root, archive=None):
    root = root.resolve()
    if not (root / 'index.html').is_file():
        raise ValueError('index.html missing: ' + str(root))
    missing = sorted(ref for ref in references(root) if not (root / ref).is_file())
    result = {'root': str(root), 'missingReferences': missing, 'missingReferenceCount': len(missing)}
    if archive:
        missing_entries, extra_entries = archive_check(root, archive)
        result.update({'archive': str(Path(archive).resolve()),
                       'missingArchiveEntries': missing_entries[:50],
                       'missingArchiveEntryCount': len(missing_entries),
                       'extraFiles': extra_entries[:50], 'extraFileCount': len(extra_entries)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='+', help='built static directories')
    parser.add_argument('--archive', help='optional ZIP that must match the first directory')
    args = parser.parse_args()
    archive = args.archive if args.archive and len(args.directories) == 1 else None
    results = [check(Path(directory), archive) for directory in args.directories]
    print(json.dumps(results, ensure_ascii=False, indent=2))
    failed = any(item['missingReferenceCount'] or item.get('missingArchiveEntryCount', 0)
                 for item in results)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())




