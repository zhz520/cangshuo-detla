#!/usr/bin/env python3
"""Build and query the compact, generated context index for Delta.

The index is machine-readable. Agents should use `brief`, `impact`, `state` or
`explain` instead of reading the whole file.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


ROOT = Path(__file__).resolve().parents[1]
AI = ROOT / "docs" / "ai"
INDEX = AI / "index.json"
ROUTING = AI / "routing.json"
CATALOG = ROOT / "delta-game/delta-admin/src/main/resources/platform/catalog.json"
CURRENT_STATE = ROOT / "docs/CURRENT_STATE.md"
CHANGELOG = ROOT / "docs/CHANGELOG.md"

REQUIRED_DOCS = [
    "docs/README.md",
    "docs/VIBE_CODING.md",
    "docs/CURRENT_STATE.md",
    "docs/ARCHITECTURE.md",
    "docs/BUSINESS_RULES.md",
    "docs/OPERATIONS.md",
    "docs/CONFIGURATION.md",
    "docs/CHANGELOG.md",
    "docs/ai/CONTEXT.md",
    "docs/ai/routing.json",
    "docs/ai/index.json",
]

DOC_LIMITS = {
    "docs/README.md": 10_000,
    "docs/VIBE_CODING.md": 24_000,
    "docs/CURRENT_STATE.md": 14_000,
    "docs/ARCHITECTURE.md": 22_000,
    "docs/BUSINESS_RULES.md": 28_000,
    "docs/OPERATIONS.md": 24_000,
    "docs/CONFIGURATION.md": 22_000,
    "docs/CHANGELOG.md": 24_000,
    "docs/ai/CONTEXT.md": 24_000,
    "docs/ai/index.json": 150_000,
    "AGENTS.md": 10_000,
}

MANAGED_ROOTS = (
    "delta-game/",
    "delta-admin-ui/",
    "delta-mp/",
    "deploy/",
    "contracts/",
    "scripts/",
    "docs/",
    "AGENTS.md",
    "README.md",
)


def fail(message: str) -> None:
    print("AI context error: " + message, file=sys.stderr)
    raise SystemExit(1)


def run(*args: str, check: bool = False) -> str:
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode:
        fail("cannot determine Git scope; check the base ref and fetch sufficient history")
    return result.stdout.strip() if result.returncode == 0 else ""


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def catalog_version() -> str:
    return str(read_json(CATALOG)["version"])


def controller_files() -> dict[str, str]:
    found: dict[str, str] = {}
    for path in (ROOT / "delta-game").rglob("*Controller.java"):
        if "/target/" not in path.as_posix():
            found[path.stem] = rel(path)
    return found


def module_rows() -> list[dict]:
    rows = []
    controllers = controller_files()
    endpoint_rows = read_json(ROOT / "contracts/endpoint-baseline.json")
    endpoint_counts: dict[str, int] = {}
    for row in endpoint_rows:
        endpoint_counts[row["controller"]] = endpoint_counts.get(row["controller"], 0) + 1
    for pom in sorted((ROOT / "delta-game").glob("*/pom.xml")):
        module_dir = pom.parent
        module = module_dir.name
        pom_text = pom.read_text(encoding="utf-8", errors="ignore")
        own_artifact = re.search(r"<artifactId>([^<]+)</artifactId>", pom_text)
        deps = sorted(set(re.findall(r"<artifactId>(delta-[A-Za-z0-9_-]+)</artifactId>", pom_text)))
        if own_artifact:
            deps = [dep for dep in deps if dep != own_artifact.group(1)]
        java_root = module_dir / "src/main/java"
        java_files = sorted(java_root.rglob("*.java")) if java_root.exists() else []
        if java_files:
            package = re.search(r"^package\s+([\w.]+)", java_files[0].read_text(encoding="utf-8", errors="ignore"), re.M)
            package_root = package.group(1) if package else ""
        else:
            package_root = ""
        module_controllers = []
        for name, file in controllers.items():
            if file.startswith(f"delta-game/{module}/"):
                module_controllers.append({"name": name, "file": file, "endpoints": endpoint_counts.get(name, 0)})
        tests = sorted(rel(path) for path in module_dir.rglob("src/test/java/**/*Test.java") if path.is_file())
        rows.append({
            "id": module,
            "pom": rel(pom),
            "packageRoot": package_root,
            "dependsOn": deps,
            "mainJavaCount": len(java_files),
            "controllers": sorted(module_controllers, key=lambda item: item["name"]),
            "tests": tests,
        })
    return rows


def admin_rows() -> dict:
    base = ROOT / "delta-admin-ui"
    views = sorted(rel(path) for path in (base / "src/views").rglob("*.vue") if path.is_file())
    router_text = (base / "src/router/index.js").read_text(encoding="utf-8", errors="ignore")
    routes = sorted(set(re.findall(r"path\s*:\s*['\"]([^'\"]+)['\"]", router_text)))
    api_modules = []
    for path in sorted((base / "src/api").glob("*.js")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        api_modules.append({
            "module": path.name,
            "exports": sorted(set(re.findall(r"export\s+(?:async\s+)?(?:function|const)\s+(\w+)", text))),
            "urls": sorted(set(re.findall(r"url\s*:\s*['\"]([^'\"]+)", text))),
        })
    tests = sorted(rel(path) for path in (base / "test").glob("*.mjs"))
    return {"views": views, "routes": routes, "apiModules": api_modules, "tests": tests}


def h5_rows() -> dict:
    data = read_json(ROOT / "delta-mp/pages.json")
    pages = [item["path"] for item in data.get("pages", [])]
    subpackages = []
    for package in data.get("subPackages", []):
        subpackages.append({
            "root": package.get("root", ""),
            "pages": sorted(item["path"] for item in package.get("pages", [])),
        })
    tests = sorted(rel(path) for path in (ROOT / "delta-mp/test").glob("*.mjs"))
    return {"pages": sorted(pages), "subPackages": subpackages, "tests": tests}


def database_rows() -> dict:
    sql = (ROOT / "delta_game.sql").read_text(encoding="utf-8", errors="ignore")
    tables = sorted(set(re.findall(
        r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[^A-Za-z0-9_]*([A-Za-z0-9_]+)", sql, re.I)))
    migration_roots = [
        ("admin", ROOT / "delta-game/delta-admin/src/main/resources/db/migration"),
        ("baota", ROOT / "deploy/baota/migrations"),
    ]
    migrations = []
    for kind, base in migration_roots:
        if base.exists():
            migrations.extend({"kind": kind, "path": rel(path)} for path in sorted(base.glob("*.sql")))
    compatibility = ROOT / "delta-game/delta-admin/src/main/java/com/delta/admin/config/DatabaseCompatibility.java"
    return {
        "baseTables": tables,
        "migrations": migrations,
        "compatibility": rel(compatibility) if compatibility.exists() else "",
    }


def contract_rows() -> dict:
    endpoints = read_json(ROOT / "contracts/endpoint-baseline.json")
    openapi = read_json(ROOT / "contracts/openapi.baseline.json")
    operations = sum(1 for item in openapi.get("paths", {}).values()
                     for method in item if method.lower() in {"get", "post", "put", "delete", "patch"})
    return {
        "endpoints": len(endpoints),
        "operations": operations,
        "schemas": len(openapi.get("components", {}).get("schemas", {})),
    }


def digest_inputs(modules: list[dict], admin: dict, h5: dict, database: dict, contracts: dict) -> str:
    stable = {
        "modules": [{key: value for key, value in row.items() if key not in {"tests"}} | {"tests": row["tests"]} for row in modules],
        "admin": admin,
        "h5": h5,
        "database": database,
        "contracts": contracts,
        "routingSha256": hashlib.sha256(
            json.dumps(read_json(ROUTING), ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest() if ROUTING.exists() else "",
    }
    return "sha256:" + hashlib.sha256(json.dumps(stable, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def build_index() -> dict:
    modules = module_rows()
    admin = admin_rows()
    h5 = h5_rows()
    database = database_rows()
    contracts = contract_rows()
    return {
        "schemaVersion": 1,
        "catalogVersion": catalog_version(),
        "inputDigest": digest_inputs(modules, admin, h5, database, contracts),
        "counts": {
            "modules": len(modules),
            "controllers": sum(len(row["controllers"]) for row in modules),
            "adminViews": len(admin["views"]),
            "adminRoutes": len(admin["routes"]),
            "h5Pages": len(h5["pages"]) + sum(len(row["pages"]) for row in h5["subPackages"]),
            "baseTables": len(database["baseTables"]),
            "migrations": len(database["migrations"]),
            "backendTests": sum(len(row["tests"]) for row in modules),
            "adminTests": len(admin["tests"]),
            "h5Tests": len(h5["tests"]),
        },
        "modules": modules,
        "admin": admin,
        "h5": h5,
        "database": database,
        "contracts": contracts,
    }


def json_text(value) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def routing() -> list[dict]:
    data = read_json(ROUTING)
    if data.get("schemaVersion") != 1 or not isinstance(data.get("routes"), list):
        fail("routing.json schema invalid")
    ids = set()
    for route in data["routes"]:
        for key in ("id", "risk", "terms", "paths", "docs", "checks", "commands"):
            if key not in route:
                fail(f"route missing {key}: {route}")
        if route["id"] in ids:
            fail("duplicate route id: " + route["id"])
        ids.add(route["id"])
        if "tests" in route and (not isinstance(route["tests"], list) or
                                 not all(isinstance(path, str) for path in route["tests"])):
            fail(f"route {route['id']} has invalid tests")
        for path in route.get("tests", []):
            target = (ROOT / path).resolve()
            if not target.is_relative_to(ROOT) or not target.is_file():
                fail(f"route {route['id']} references missing or external test: {path}")
        for doc in route["docs"]:
            if not (ROOT / "docs" / doc).is_file():
                fail(f"route {route['id']} references missing docs/{doc}")
    return data["routes"]


def path_matches(path: str, pattern: str) -> bool:
    return fnmatch.fnmatchcase(path, pattern) or path == pattern.rstrip("/*")


def matching_routes(paths: list[str], task: str = "") -> list[dict]:
    text = task.lower()
    rows = []
    for route in routing():
        path_hit = any(path_matches(path, pattern) for path in paths for pattern in route["paths"])
        term_hit = bool(text) and any(str(term).lower() in text for term in route["terms"])
        if path_hit or term_hit:
            rows.append({"route": route, "pathHit": path_hit, "termHit": term_hit})
    order = {"P0": 0, "P1": 1, "P2": 2}
    return sorted(rows, key=lambda item: (
        order.get(item["route"]["risk"], 9),
        0 if item["pathHit"] else 1,
        item["route"]["id"],
    ))


def changed_paths(base: str | None) -> list[str]:
    paths = []
    if base:
        text = run("git", "-c", "core.quotepath=false", "diff", "--name-only", f"{base}...HEAD", "--", check=True)
    else:
        text = run("git", "-c", "core.quotepath=false", "diff", "--name-only", "HEAD", "--", check=True)
        untracked = run("git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard", check=True)
        paths.extend(untracked.splitlines())
    paths.extend(text.splitlines())
    return sorted({line.strip() for line in paths if line.strip()})


def relevant_tests(index: dict, routes: list[dict]) -> list[str]:
    pinned = list(dict.fromkeys(path for item in routes for path in item["route"].get("tests", [])))
    if pinned:
        return pinned
    ids = {item["route"]["id"] for item in routes}
    candidates = []
    if "backend-module" in ids or "money" in ids or "security" in ids or "tenant-database" in ids or "release" in ids:
        for module in index["modules"]:
            candidates.extend(module["tests"])
    if "admin-ui" in ids or "ai-context" in ids:
        candidates.extend(index["admin"]["tests"])
    if "h5" in ids:
        candidates.extend(index["h5"]["tests"])
    ignore = {"java", "test", "src", "service", "controller", "mapper", "module", "backend", "api", "money"}
    keywords = set()
    for item in routes:
        keywords.update(str(term).lower() for term in item["route"]["terms"]
                        if re.fullmatch(r"[a-z0-9_-]+", str(term).lower()) and str(term).lower() not in ignore)
    if keywords:
        filtered = [path for path in candidates if any(keyword in path.lower() for keyword in keywords)]
        if filtered:
            candidates = filtered
    return sorted(set(candidates))[:16]


def command_brief(args) -> None:
    index = read_json(INDEX)
    paths = [path.replace("\\", "/").removeprefix("./") for path in args.paths] if args.paths is not None else changed_paths(args.base)
    routes = matching_routes(paths, args.task or "")
    head = run("git", "rev-parse", "--short", "HEAD")
    branch = run("git", "branch", "--show-current")
    dirty = bool(run("git", "status", "--porcelain"))
    tag_rows = run("git", "tag", "--list", "v*", "--sort=-v:refname").splitlines()
    current_tag = tag_rows[0] if tag_rows else ""
    tag_commit = run("git", "rev-list", "-n", "1", current_tag) if current_tag else ""
    print("Delta context brief")
    print(f"branch={branch or '(detached)'} head={head or '?'} dirty={'yes' if dirty else 'no'}")
    print(f"catalog={index['catalogVersion']} latestTag={current_tag or 'none'} tagCommit={tag_commit[:12] or 'none'}")
    print("mustRead=docs/ai/CONTEXT.md,docs/VIBE_CODING.md")
    print(f"changed={len(paths)} task={args.task or ''}")
    if not routes:
        print("routes=NONE")
        print("STOP: no route matched; extend docs/ai/routing.json before changing code.")
        if args.strict and (paths or args.task or not args.base):
            raise SystemExit(1)
        return
    print("routes=" + ",".join(f"{item['route']['id']}({item['route']['risk']})" for item in routes))
    docs = []
    for item in routes:
        for doc in item["route"]["docs"]:
            if doc not in docs:
                docs.append(doc)
    print("docs=" + ",".join("docs/" + doc for doc in docs))
    checks = []
    for item in routes:
        checks.extend(value for value in item["route"]["checks"] if value not in checks)
    print("checks=" + "; ".join(checks))
    tests = relevant_tests(index, routes)
    print("tests=" + (", ".join(tests) if tests else "(none resolved)"))
    print("commands:")
    seen = set()
    for item in routes:
        for command in item["route"]["commands"]:
            if command not in seen:
                seen.add(command)
                print("  " + command)
    unmapped = [path for path in paths if not any(path_matches(path, pattern) for item in routes for pattern in item["route"]["paths"])]
    if unmapped:
        print("unmapped=" + ", ".join(unmapped[:10]))
        if args.strict:
            fail(f"{len(unmapped)} path(s) have no route")


def command_state(_args) -> None:
    version = catalog_version()
    head = run("git", "rev-parse", "HEAD")
    branch = run("git", "branch", "--show-current")
    dirty = bool(run("git", "status", "--porcelain"))
    tag = ""
    candidates = run("git", "tag", "--list", f"v{version}")
    if candidates:
        tag = candidates.splitlines()[0]
    tag_commit = run("git", "rev-list", "-n", "1", tag) if tag else ""
    state_text = CURRENT_STATE.read_text(encoding="utf-8") if CURRENT_STATE.exists() else ""
    print(json.dumps({
        "branch": branch,
        "head": head,
        "dirty": dirty,
        "catalogVersion": version,
        "tag": tag,
        "tagCommit": tag_commit,
        "currentStateMentionsVersion": version in state_text,
    }, ensure_ascii=False, indent=2))


def command_impact(args) -> None:
    index = read_json(INDEX)
    paths = args.paths or changed_paths(args.base)
    routes = matching_routes(paths, args.task or "")
    report = {
        "catalogVersion": index["catalogVersion"],
        "changed": paths,
        "routes": [item["route"] for item in routes],
        "tests": relevant_tests(index, routes),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


def command_explain(args) -> None:
    index = read_json(INDEX)
    text = args.topic.lower()
    found = [route for route in routing() if route["id"] == args.topic or text in route["id"].lower()
             or any(text in str(term).lower() or str(term).lower() in text for term in route["terms"])]
    if not found:
        fail("unknown route/topic: " + args.topic)
    print(json.dumps(found, ensure_ascii=False, indent=2))


def local_links(path: Path) -> list[str]:
    errors = []
    text = path.read_text(encoding="utf-8-sig")
    for match in re.finditer(r"\[[^\]\n]*\]\((<[^>\n]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\)", text):
        target = match.group(1).strip("<>")
        if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith("#"):
            continue
        target = target.split("#", 1)[0]
        if not target:
            continue
        candidate = (path.parent / target).resolve()
        try:
            candidate.relative_to(ROOT.resolve())
        except ValueError:
            continue
        if not candidate.exists():
            errors.append(f"{rel(path)} -> {target}")
    return errors


def validate() -> None:
    errors = []
    for name in REQUIRED_DOCS:
        path = ROOT / name
        if not path.is_file():
            errors.append("missing " + name)
        elif path.stat().st_size > DOC_LIMITS.get(name, 30_000):
            errors.append(f"{name} exceeds size target {DOC_LIMITS.get(name, 30_000)}")
    allowed_root = {Path(name).name for name in REQUIRED_DOCS if Path(name).parent == Path("docs")}
    for path in (ROOT / "docs").glob("*"):
        if path.is_file() and path.name not in allowed_root:
            errors.append("unexpected root doc: docs/" + path.name)
    index = build_index()
    actual = INDEX.read_text(encoding="utf-8") if INDEX.exists() else ""
    if actual != json_text(index):
        errors.append("docs/ai/index.json is stale; run python scripts/ai_context.py build")
    routes = routing()
    for route in routes:
        for doc in route["docs"]:
            if not (ROOT / "docs" / doc).is_file():
                errors.append(f"route {route['id']} missing docs/{doc}")
    version = catalog_version()
    if CURRENT_STATE.exists() and version not in CURRENT_STATE.read_text(encoding="utf-8"):
        errors.append("CURRENT_STATE does not mention catalog version " + version)
    if CHANGELOG.exists() and f" {version}" not in CHANGELOG.read_text(encoding="utf-8"):
        errors.append("CHANGELOG does not mention current version " + version)
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8") if (ROOT / "AGENTS.md").exists() else ""
    for required in ("docs/ai/CONTEXT.md", "scripts/ai_context.py"):
        if required not in agents:
            errors.append("AGENTS.md missing " + required)
    for name in REQUIRED_DOCS:
        path = ROOT / name
        if path.suffix == ".md" and path.is_file():
            errors.extend(local_links(path))
    managed = []
    for line in run("git", "ls-files").splitlines():
        if not line.startswith(MANAGED_ROOTS):
            continue
        if not (ROOT / line).is_file():
            continue
        if any(part in {"node_modules", "dist", "target", ".tmp-build"} for part in Path(line).parts):
            continue
        managed.append(line)
    for path in managed:
        if not any(path_matches(path, pattern) for route in routes for pattern in route["paths"]):
            errors.append("unrouted managed path: " + path)
    if errors:
        for error in errors:
            print("FAIL: " + error, file=sys.stderr)
        raise SystemExit(1)
    print(f"AI context OK: {index['catalogVersion']}, {index['counts']['modules']} modules, "
          f"{index['counts']['controllers']} controllers, {index['counts']['backendTests']}/"
          f"{index['counts']['adminTests']}/{index['counts']['h5Tests']} tests, {len(routes)} routes")


def selftest() -> None:
    samples = {
        "delta-game/delta-pay/src/main/java/com/delta/pay/service/PaymentServiceImpl.java": "money",
        "delta-game/delta-common/src/main/java/com/delta/common/config/SecurityConfig.java": "security",
        "delta-game/delta-admin/src/main/java/com/delta/admin/config/DatabaseCompatibility.java": "tenant-database",
        "delta-admin-ui/src/views/system/SystemConfig.vue": "admin-ui",
        "delta-mp/pages/login/index.vue": "h5",
        "deploy/updater/shared_worker.py": "release",
        "docs/ai/CONTEXT.md": "ai-context",
    }
    for path, expected in samples.items():
        ids = {item["route"]["id"] for item in matching_routes([path])}
        if expected not in ids:
            fail(f"selftest expected {expected} for {path}, got {sorted(ids)}")
    unknown = matching_routes(["unmapped/example.bin"])
    if unknown:
        fail("selftest expected unknown path to have no route")
    tasks = {
        "项目问题复核与优化方案": "ai-context",
        "打手提现押金": "money",
        "协同打单拒绝后再次邀请": "order-team",
        "商品详情图片不显示": "product-media",
        "微信电脑浏览器生成支付二维码": "money",
        "短信已受理但没有回执": "authority-services",
        "文件安全租户隔离": "file-access",
    }
    for task, expected in tasks.items():
        hits = matching_routes([], task)
        if not any(hit["route"]["id"] == expected and hit["route"].get("tests") for hit in hits):
            fail(f"selftest missing route and pinned tests: {expected} for {task}")
    for expected, args in (
        (0, ["brief", "--strict", "--paths", "docs/ai/routing.json"]),
        (0, ["brief", "--strict", "--base", "HEAD"]),
        (1, ["brief", "--strict", "--paths", "unmapped/example.bin"]),
        (1, ["brief", "--strict", "--task", "短信回执", "--paths", "unmapped/example.bin"]),
        (1, ["brief", "--strict", "--base", "missing-ai-context-selftest-ref"]),
    ):
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), *args],
                                cwd=ROOT, capture_output=True)
        if result.returncode != expected:
            fail(f"selftest expected exit {expected}, got {result.returncode}: {args}")
    print("AI context selftest passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build")
    build.add_argument("--check", action="store_true")
    validate_parser = sub.add_parser("validate")
    validate_parser.add_argument("--strict", action="store_true")
    brief = sub.add_parser("brief")
    brief.add_argument("--task", default="")
    scope = brief.add_mutually_exclusive_group()
    scope.add_argument("--paths", nargs="*")
    scope.add_argument("--base")
    brief.add_argument("--strict", action="store_true", help="Fail on unmatched tasks or unmapped paths")
    state = sub.add_parser("state")
    impact = sub.add_parser("impact")
    impact.add_argument("paths", nargs="*")
    impact.add_argument("--task", default="")
    impact.add_argument("--base")
    impact.add_argument("--json", action="store_true")
    explain = sub.add_parser("explain")
    explain.add_argument("topic")
    sub.add_parser("selftest")
    args = parser.parse_args()
    if args.command == "build":
        content = json_text(build_index())
        if args.check:
            if not INDEX.exists() or INDEX.read_text(encoding="utf-8") != content:
                fail("index is stale")
            print("AI context index is current")
        else:
            AI.mkdir(parents=True, exist_ok=True)
            INDEX.write_text(content, encoding="utf-8")
            print("wrote " + rel(INDEX))
    elif args.command == "validate":
        validate()
    elif args.command == "brief":
        command_brief(args)
    elif args.command == "state":
        command_state(args)
    elif args.command == "impact":
        command_impact(args)
    elif args.command == "explain":
        command_explain(args)
    elif args.command == "selftest":
        selftest()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        fail(type(error).__name__ + ": " + str(error))

