"""Stable SHA-256 identity for a frontend build tree."""

import hashlib
from pathlib import Path


GENERATED_VERSION_FILE = "cangshuo-version.json"
CHUNK = 1024 * 1024


def tree_sha256(root):
    """Hash relative paths and file contents, excluding the generated version file."""
    root = Path(root).resolve()
    if not root.is_dir():
        raise ValueError("build tree is not a directory")
    files = []
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError("build tree may not contain symbolic links")
        if path.is_file() and path.relative_to(root).as_posix() != GENERATED_VERSION_FILE:
            files.append(path)
    files.sort(key=lambda path: path.relative_to(root).as_posix())
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        digest.update(path.stat().st_size.to_bytes(8, "big"))
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(CHUNK), b""):
                digest.update(chunk)
    return digest.hexdigest()
