#!/usr/bin/env python3
"""Fail when a backend module imports a com.delta package from an undeclared module."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "delta-game"
EXTRACTED_MODULES = {
    "delta-platform",
    "delta-workbench",
    "delta-reporting",
    "delta-identity",
    "delta-notification",
}
PACKAGE_PATTERN = re.compile(r"^package\s+com\.delta\.([A-Za-z0-9_.]+);", re.MULTILINE)
IMPORT_PATTERN = re.compile(r"^\s*import\s+(?:static\s+)?com\.delta\.([A-Za-z0-9_]+)\.", re.MULTILINE)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def module_names() -> list[str]:
    document = ET.parse(GAME / "pom.xml").getroot()
    modules = next((node for node in document if local_name(node.tag) == "modules"), None)
    if modules is None:
        raise SystemExit("delta-game/pom.xml has no <modules>")
    return [node.text.strip() for node in modules if node.text and node.text.strip()]


def direct_delta_dependencies(module: str) -> set[str]:
    document = ET.parse(GAME / module / "pom.xml").getroot()
    dependencies = next((node for node in document if local_name(node.tag) == "dependencies"), None)
    result: set[str] = set()
    if dependencies is None:
        return result
    for dependency in dependencies:
        if local_name(dependency.tag) != "dependency":
            continue
        group = next((node.text.strip() for node in dependency if local_name(node.tag) == "groupId" and node.text), "")
        artifact = next((node.text.strip() for node in dependency if local_name(node.tag) == "artifactId" and node.text), "")
        if group == "com.delta" and artifact:
            result.add(artifact)
    return result


def source_root(module: str) -> Path:
    return GAME / module / "src" / "main" / "java"


def package_roots(modules: list[str]) -> tuple[dict[str, set[str]], list[str]]:
    roots: dict[str, set[str]] = defaultdict(set)
    errors: list[str] = []
    for module in modules:
        root = source_root(module)
        if not root.exists():
            continue
        for path in root.rglob("*.java"):
            match = PACKAGE_PATTERN.search(path.read_text(encoding="utf-8"))
            if not match:
                errors.append(f"{path}: missing com.delta package declaration")
                continue
            roots[match.group(1).split(".", 1)[0]].add(module)
    for segment, owners in sorted(roots.items()):
        if len(owners) > 1:
            errors.append(f"com.delta.{segment} is claimed by multiple modules: {', '.join(sorted(owners))}")
    return roots, errors


def check() -> dict:
    modules = module_names()
    dependencies = {module: direct_delta_dependencies(module) for module in modules}
    roots, errors = package_roots(modules)
    root_owner = {segment: next(iter(owners)) for segment, owners in roots.items() if len(owners) == 1}
    violations = list(errors)
    checked_imports = 0

    for module in modules:
        if module in EXTRACTED_MODULES and "delta-admin" in dependencies[module]:
            violations.append(f"{module} declares a dependency on delta-admin")
        root = source_root(module)
        if not root.exists():
            continue
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            for segment in sorted(set(IMPORT_PATTERN.findall(text))):
                target = root_owner.get(segment)
                if not target or target == module:
                    continue
                checked_imports += 1
                if target not in dependencies[module]:
                    violations.append(
                        f"{path.relative_to(ROOT)} imports com.delta.{segment} ({target}) "
                        f"but {module}/pom.xml does not declare {target}"
                    )

    return {
        "modules": modules,
        "packageRoots": {segment: sorted(owners) for segment, owners in sorted(roots.items())},
        "checkedCrossModuleImports": checked_imports,
        "violations": violations,
    }


def main() -> int:
    result = check()
    if "--json" in sys.argv:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"modules={len(result['modules'])} packageRoots={len(result['packageRoots'])} "
              f"crossModuleImports={result['checkedCrossModuleImports']}")
        for violation in result["violations"]:
            print(f"- {violation}")
        if not result["violations"]:
            print("module boundaries OK")
    return 1 if result["violations"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
