"""Prevent GitHub Actions storage from filling with preview build bundles."""

from pathlib import Path
import sys


root = Path(__file__).resolve().parents[2]
workflow_dir = root / ".github" / "workflows"
violations = []

for workflow in sorted((*workflow_dir.glob("*.yml"), *workflow_dir.glob("*.yaml"))):
    source = workflow.read_text(encoding="utf-8")
    if "upload-artifact" in source or "preview-build-" in source:
        violations.append(workflow.relative_to(root).as_posix())

if violations:
    print("GitHub Actions must not upload preview build bundles. Use deploy/updater/package_release.py "
          "and GitHub Release assets for formal delivery.", file=sys.stderr)
    for workflow in violations:
        print(f"- {workflow}", file=sys.stderr)
    sys.exit(1)

print("CI storage guard passed: no per-push Actions artifact uploads.")
