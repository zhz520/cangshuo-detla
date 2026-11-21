#!/usr/bin/env python3
"""Inventory the repository's SQL files without executing SQL or writing files.

delta_game.sql is the fresh-install baseline, not an upgrade input. The two
upgrade directories are independent naming schemes, not a combined apply order.
The scan is non-recursive. Versions come from filenames, not release metadata;
SHA-256 hashes the working-tree bytes without newline or BOM normalization.
Same-source version/date repeats, exact copies and cross-source name matches
(same version and topic, ignoring case and dash/underscore spelling) are
separate findings. A name match does not establish SQL equivalence.
"""

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import stat
import sys


ROOT = Path(__file__).resolve().parents[2]
INITIAL_SQL = Path("delta_game.sql")
UPGRADE_SOURCES = {
    "backend": (
        Path("delta-game/delta-admin/src/main/resources/db/migration"),
        re.compile(r"V(?P<version>[0-9]{8}(?:_[0-9]+)?)__(?P<topic>.+)\.sql", re.I),
    ),
    "baota": (
        Path("deploy/baota/migrations"),
        re.compile(r"(?P<version>[0-9]{8})-(?P<topic>.+)\.sql", re.I),
    ),
}

TABLE_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_$]{0,63}")
# Lex only enough to identify direct statement prefixes. In particular, a
# verb in an INSERT payload or PREPARE string is never a statement prefix.
SQL_TOKEN = re.compile(
    r"(?P<comment>/\*[\s\S]*?(?:\*/|\Z)|--(?=\s|\Z)[^\r\n]*|#[^\r\n]*)"
    r"""|(?P<string>'(?:[^'\\]|\\[\s\S]|'')*(?:'|\\?\Z)"""
    r"""|"(?:[^"\\]|\\[\s\S]|"")*(?:"|\\?\Z))"""
    r"|(?P<quoted>`(?:[^`]|``)*(?:`|\Z))"
    r"|(?P<word>[\w$\x80-\U0010ffff]+)|(?P<symbol>\S)"
)


class InventoryError(ValueError):
    """A filesystem or input failure safe to report without SQL text."""


def check_path(root, relative, *, directory=False):
    """Reject symlinks and junctions before opening an inventory entry."""
    path = root
    for i, part in enumerate(relative.parts):
        path /= part
        name = path.relative_to(root).as_posix()
        try:
            info = path.lstat()
        except OSError:
            raise InventoryError(f"missing or inaccessible: {name}") from None
        reparse = getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT
        if stat.S_ISLNK(info.st_mode) or reparse:
            raise InventoryError(f"linked path is not supported: {name}")
        is_directory = directory or i < len(relative.parts) - 1
        expected = stat.S_ISDIR if is_directory else stat.S_ISREG
        if not expected(info.st_mode):
            raise InventoryError(f"unexpected file type: {name}")
    return path


def read_script(root, relative, phase, source, version):
    path = check_path(root, relative)
    try:
        content = path.read_bytes()
    except OSError:
        raise InventoryError(f"cannot read: {relative.as_posix()}") from None
    row = {
        "phase": phase,
        "source": source,
        "version": version,
        "path": relative.as_posix(),
        "sha256": hashlib.sha256(content).hexdigest(),
    }
    return row, content


def scripts(root):
    try:
        root = root.resolve(strict=True)
    except (OSError, RuntimeError):
        raise InventoryError("repository root cannot be resolved") from None
    if not root.is_dir():
        raise InventoryError("repository root is not a directory")
    yield (*read_script(root, INITIAL_SQL, "initial-install", "baseline", None), None)
    for source, (directory, pattern) in UPGRADE_SOURCES.items():
        path = check_path(root, directory, directory=True)
        try:
            candidates = [item.name for item in path.iterdir() if item.suffix.lower() == ".sql"]
        except OSError:
            raise InventoryError(f"cannot list: {directory.as_posix()}") from None

        def sort_key(name):
            match = pattern.fullmatch(name)
            version = tuple(map(int, match["version"].split("_"))) if match else ()
            return (match is None, version, name)

        for name in sorted(candidates, key=sort_key):
            match = pattern.fullmatch(name)
            label = match["version"] if match else None
            version = f"V{label}" if label and source == "backend" else label
            topic = match["topic"].lower().replace("_", "-") if match else None
            yield (*read_script(root, directory / name, "upgrade", source, version), (label, topic))


def inventory(root):
    """List raw working-tree hashes; duplicates are findings, not failures."""
    files = []
    by_version = defaultdict(list)
    by_digest = defaultdict(list)
    by_name = defaultdict(list)
    unversioned = []
    for row, _, name_key in scripts(root):
        files.append(row)
        path = row["path"]
        by_digest[row["sha256"]].append(path)
        if row["phase"] != "upgrade":
            continue
        if row["version"] is None:
            unversioned.append(path)
        else:
            by_version[row["source"], row["version"]].append(path)
            by_name[name_key].append(row)
    return {
        "files": files,
        "repeatedVersions": [
            {"source": source, "version": version, "paths": paths}
            for (source, version), paths in by_version.items() if len(paths) > 1
        ],
        "identicalFiles": [
            {"sha256": digest, "paths": paths}
            for digest, paths in sorted(by_digest.items()) if len(paths) > 1
        ],
        "crossSourceNameMatches": [
            {
                "filenameVersion": label,
                "paths": [row["path"] for row in rows],
                "allBytesIdentical": len({row["sha256"] for row in rows}) == 1,
            }
            for (label, _), rows in sorted(by_name.items())
            if len({row["source"] for row in rows}) > 1
        ],
        "unversionedUpgradeFiles": unversioned,
    }


def statement_prefixes(sql):
    prefix = []
    for token in SQL_TOKEN.finditer(sql):
        kind, value = token.lastgroup, token.group()
        if kind == "comment":
            continue
        if kind == "symbol" and value == ";":
            if prefix:
                yield prefix
                prefix = []
        elif len(prefix) < 9:  # Longest supported prefix ends at schema.table.
            prefix.append((kind, value))
    if prefix:
        yield prefix


def ddl_target(prefix):
    def word(i):
        if i < len(prefix) and prefix[i][0] == "word":
            return prefix[i][1].upper()
        return None

    def identifier(i):
        if i >= len(prefix):
            return None
        kind, value = prefix[i]
        if kind == "word":
            return value
        if kind == "quoted" and len(value) >= 2 and value.endswith("`"):
            return value[1:-1].replace("``", "`")
        return None

    operation = word(0)
    if operation not in {"CREATE", "ALTER"}:
        return None
    i = 2 if operation == "CREATE" and word(1) == "TEMPORARY" else 1
    if word(i) != "TABLE":
        return None
    i += 1
    if operation == "CREATE" and [word(i + n) for n in range(3)] == ["IF", "NOT", "EXISTS"]:
        i += 3
    name = identifier(i)
    if name is not None and prefix[i + 1:i + 2] == [("symbol", ".")]:
        name = identifier(i + 2)  # Match only the table component of schema.table.
    return (operation + "_TABLE", name.lower()) if name else None


def table_inventory(root, table):
    """Find literal CREATE/ALTER TABLE targets, not effective schema state."""
    if not TABLE_NAME.fullmatch(table):
        raise InventoryError("table must be an unqualified ASCII identifier")
    matches = []
    skipped = []
    for row, content, _ in scripts(root):
        try:
            sql = content.decode("utf-8-sig")
        except UnicodeDecodeError:
            skipped.append({"path": row["path"], "reason": "not-utf8"})
            continue
        operations = set()
        for prefix in statement_prefixes(sql):
            if prefix[0][0] == "word" and prefix[0][1].upper() == "DELIMITER":
                skipped.append({"path": row["path"], "reason": "custom-delimiter"})
                break
            target = ddl_target(prefix)
            if target and target[1] == table.lower():
                operations.add(target[0])
        else:
            if operations:
                matches.append({**row, "operations": sorted(operations)})
    return {"table": table, "matches": matches, "skippedFiles": skipped}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root (default: script's repository)")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser(
        "inventory", help="list direct SQL files, date/version repeats and exact-byte duplicates"
    )
    table = commands.add_parser(
        "table", help="find files with literal CREATE/ALTER TABLE statements for one table",
        description=(
            "Case-insensitive, static only. Ignores comments (including MySQL executable "
            "comments), strings, dynamic SQL and startup code. Non-UTF-8 and custom-delimiter "
            "files are skipped. No matches does not prove a table is absent."
        ),
    )
    table.add_argument("name", help="unqualified table name, without quoting")
    args = parser.parse_args(argv)
    try:
        result = inventory(args.root) if args.command == "inventory" else table_inventory(args.root, args.name)
    except InventoryError as error:
        print(f"schema inventory: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
