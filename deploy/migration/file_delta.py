#!/usr/bin/env python3
"""Read-only manifests and resumable file delta copies for tenant migrations.

The source is never modified. Sync only adds or replaces files that differ; it
does not delete files that exist only in the target. Symlinks and paths outside
the two roots are rejected.
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def inside(path, parent):
    try:
        Path(path).resolve().relative_to(Path(parent).resolve())
        return True
    except ValueError:
        return False


def root_path(value):
    path = Path(value)
    if not path.is_absolute():
        raise ValueError("root must be an absolute path")
    if not path.is_dir():
        raise ValueError("root is not a directory: " + str(path))
    return path.resolve()


def hash_file(path):
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
    return digest.hexdigest(), size


def excluded(relative, excludes):
    parts = Path(relative).parts
    return any(relative == item or relative.startswith(item.rstrip("/") + "/") for item in excludes)


def scan(root, excludes=()):
    files = {}
    root = root_path(root)
    excludes = tuple(str(item) for item in excludes)
    for directory, dirnames, filenames in os.walk(str(root), followlinks=False):
        current = Path(directory)
        for name in list(dirnames):
            candidate = current / name
            if candidate.is_symlink():
                raise ValueError("symlinked directory is not supported: " + str(candidate.relative_to(root)))
            relative = candidate.relative_to(root).as_posix()
            if excluded(relative, excludes):
                dirnames.remove(name)
        for name in filenames:
            path = current / name
            if path.is_symlink():
                raise ValueError("symlinked file is not supported: " + str(path.relative_to(root)))
            relative = path.relative_to(root).as_posix()
            if excluded(relative, excludes):
                continue
            digest, size = hash_file(path)
            files[relative] = {"size": size, "sha256": digest}
    return files


def manifest(root, excludes=()):
    root = root_path(root)
    return {
        "version": 1,
        "root": str(root),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "files": scan(root, excludes),
    }


def compare(source, target, excludes=()):
    source_root = root_path(source)
    target_root = root_path(target)
    source_files = scan(source_root, excludes)
    target_files = scan(target_root, excludes)
    source_names = set(source_files)
    target_names = set(target_files)
    missing = sorted(source_names - target_names)
    extra = sorted(target_names - source_names)
    changed = sorted(name for name in source_names & target_names
                     if source_files[name] != target_files[name])
    return {
        "version": 1,
        "source": str(source_root),
        "target": str(target_root),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceFiles": len(source_files),
        "targetFiles": len(target_files),
        "missing": missing,
        "changed": changed,
        "extra": extra,
        "missingBytes": sum(source_files[name]["size"] for name in missing),
        "changedBytes": sum(source_files[name]["size"] for name in changed),
        "identical": not missing and not changed and not extra,
    }


def copy_verified(source, target):
    source = Path(source)
    target = Path(target)
    source_hash, source_size = hash_file(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".file-delta-", dir=str(target.parent))
    os.close(descriptor)
    temporary = Path(temporary)
    try:
        with source.open("rb") as input_stream, temporary.open("wb") as output_stream:
            while True:
                chunk = input_stream.read(1024 * 1024)
                if not chunk:
                    break
                output_stream.write(chunk)
        copied_hash, copied_size = hash_file(temporary)
        if copied_hash != source_hash or copied_size != source_size:
            raise ValueError("source changed while copying: " + str(source))
        os.chmod(temporary, 0o644)
        os.replace(str(temporary), str(target))
    finally:
        if temporary.exists():
            temporary.unlink()


def sync(source, target, excludes=(), apply=False):
    comparison = compare(source, target, excludes)
    source_root = Path(comparison["source"])
    target_root = Path(comparison["target"])
    copied = []
    for name in comparison["missing"] + comparison["changed"]:
        source_file = source_root / name
        target_file = target_root / name
        if not inside(target_file, target_root):
            raise ValueError("target escaped root: " + name)
        if apply:
            copy_verified(source_file, target_file)
        copied.append(name)
    result = dict(comparison)
    result["applied"] = bool(apply)
    result["copied"] = copied
    if apply:
        result["after"] = compare(source_root, target_root, excludes)
    return result


def write_report(value, path):
    if not path:
        return
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(str(temporary), str(target))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command")
    manifest_parser = sub.add_parser("manifest")
    manifest_parser.add_argument("--root", required=True)
    manifest_parser.add_argument("--output")
    manifest_parser.add_argument("--exclude", action="append", default=[])
    for name in ("compare", "sync", "verify"):
        item = sub.add_parser(name)
        item.add_argument("--source", required=True)
        item.add_argument("--target", required=True)
        item.add_argument("--report")
        item.add_argument("--exclude", action="append", default=[])
        if name == "sync":
            item.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not args.command:
        parser.error("a command is required")
    if args.command == "manifest":
        result = manifest(args.root, args.exclude)
        write_report(result, args.output)
        print(json.dumps({"files": len(result["files"]), "output": args.output or "stdout"}, ensure_ascii=False))
        return 0
    if args.command == "compare":
        result = compare(args.source, args.target, args.exclude)
        write_report(result, args.report)
        print(json.dumps({key: result[key] for key in ("sourceFiles", "targetFiles", "missing", "changed", "extra", "missingBytes", "changedBytes", "identical")}, ensure_ascii=False))
        return 0
    if args.command == "sync":
        result = sync(args.source, args.target, args.exclude, args.apply)
        write_report(result, args.report)
        print(json.dumps({key: result[key] for key in ("applied", "copied", "missing", "changed", "extra", "identical")}, ensure_ascii=False))
        return 0
    result = compare(args.source, args.target, args.exclude)
    write_report(result, args.report)
    print(json.dumps({key: result[key] for key in ("sourceFiles", "targetFiles", "missing", "changed", "extra", "identical")}, ensure_ascii=False))
    return 0 if result["identical"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
