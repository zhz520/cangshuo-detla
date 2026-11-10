#!/usr/bin/env python3
"""Aggregate release, report, state and window evidence into a cutover go/no-go.

The tool only reads files. It never changes DNS, databases, containers or tenant
state; a missing or failing evidence item blocks readiness.
"""

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

MIGRATION = Path(__file__).resolve().parent
sys.path.insert(0, str(MIGRATION))
sys.path.insert(0, str(MIGRATION.parent / "updater"))
import shared_registry
import tenant_state

SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
HASH = re.compile(r"^[0-9a-f]{64}$")


def load(path, limit=2_000_000):
    source = Path(path)
    if not source.is_file() or source.stat().st_size > limit:
        raise ValueError("file missing or too large: " + str(source))
    return json.loads(source.read_text(encoding="utf-8"))


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def check_release(manifest_path, artifacts_dir):
    check = {"name": "release", "status": "blocked"}
    try:
        manifest = load(manifest_path)
        version = manifest.get("version")
        if not isinstance(version, str) or not SEMVER.fullmatch(version) or manifest.get("schemaVersion") != 1:
            raise ValueError("invalid version or schema")
        assets = manifest.get("assets")
        if not isinstance(assets, list) or len(assets) != 3:
            raise ValueError("release must contain three assets")
        by_component = {}
        for asset in assets:
            component = asset.get("component")
            if component not in ("backend", "admin", "h5") or component in by_component:
                raise ValueError("invalid component set")
            if not isinstance(asset.get("size"), int) or asset["size"] <= 0 or not HASH.fullmatch(asset.get("sha256", "")):
                raise ValueError("invalid asset metadata")
            by_component[component] = asset
        if set(by_component) != {"backend", "admin", "h5"}:
            raise ValueError("component set mismatch")
        if not artifacts_dir:
            raise ValueError("artifacts directory is required")
        for component, asset in by_component.items():
            path = Path(artifacts_dir) / asset["name"]
            if not path.is_file() or path.stat().st_size != asset["size"] or file_hash(path) != asset["sha256"]:
                raise ValueError("artifact mismatch: " + component)
        check.update(status="ok", version=version, assets=len(by_component))
    except Exception as error:
        check["error"] = type(error).__name__ + ": " + str(error)
    return check


def check_report(name, path, validator):
    check = {"name": name, "status": "missing"}
    if not path:
        return check
    try:
        report = load(path)
        detail = validator(report)
        check.update(status="ok", detail=detail)
    except Exception as error:
        check.update(status="blocked", error=type(error).__name__ + ": " + str(error))
    return check


def check_file_report(report):
    if report.get("identical") is not True or report.get("missing") or report.get("changed"):
        raise ValueError("file report is not identical")
    return {"sourceFiles": report.get("sourceFiles"), "extra": len(report.get("extra", []))}


def check_finance_report(report):
    if report.get("passed") is not True or report.get("totalViolations") != 0:
        raise ValueError("finance report is not clean")
    return {"checks": len(report.get("checks", []))}


def check_staging_report(report, version):
    if report.get("passed") is not True and report.get("status") not in ("OK", "SUCCESS"):
        raise ValueError("staging report did not pass")
    if report.get("version") and report.get("version") != version:
        raise ValueError("staging version mismatch")
    return {"version": report.get("version", version)}


def check_registry(path, tenant):
    check = {"name": "registry", "status": "blocked"}
    try:
        document = shared_registry.load(path)
        rows = shared_registry.validate_registry(document)
        selected = next((row for row in rows if row["id"] == tenant), None)
        if selected is None or not selected["active"] or not selected["hosts"]:
            raise ValueError("tenant is not active with hosts")
        check.update(status="ok", activeTenants=len([row for row in rows if row["active"]]),
                     hosts=len(selected["hosts"]))
    except Exception as error:
        check["error"] = type(error).__name__ + ": " + str(error)
    return check


def check_state(state_dir, tenant):
    check = {"name": "tenant_state", "status": "blocked"}
    try:
        state = tenant_state.load_state(state_dir, tenant)
        if state.get("phase") != "accepted":
            raise ValueError("tenant state is not accepted; current=" + str(state.get("phase")))
        check.update(status="ok", phase=state["phase"], revision=state.get("revision"))
    except Exception as error:
        check["error"] = type(error).__name__ + ": " + str(error)
    return check


def check_window(start_value, end_value, now):
    check = {"name": "maintenance_window", "status": "blocked"}
    try:
        start = tenant_state.instant(start_value, "maintenance start")
        end = tenant_state.instant(end_value, "maintenance end")
        if end <= start or end - start > timedelta(hours=8):
            raise ValueError("window is invalid or longer than eight hours")
        if not start <= now <= end:
            raise ValueError("window is not open")
        check.update(status="ok", startAt=start.isoformat(), endAt=end.isoformat())
    except Exception as error:
        check["error"] = type(error).__name__ + ": " + str(error)
    return check


def check_customer(accepted):
    return {"name": "customer_acceptance", "status": "ok" if accepted else "blocked"}


def audit(args):
    now = tenant_state.instant(args.now, "--now") if args.now else datetime.now(timezone.utc)
    release = check_release(args.release_manifest, args.artifacts_dir)
    version = release.get("version", "")
    checks = [
        release,
        check_state(args.state_dir, args.tenant),
        check_registry(args.registry, args.tenant),
        check_report("file_delta", args.file_report, check_file_report),
        check_report("finance", args.finance_report, check_finance_report),
        check_report("staging", args.staging_report, lambda report: check_staging_report(report, version)),
        check_window(args.maintenance_start, args.maintenance_end, now),
        check_customer(args.customer_accepted),
    ]
    blocking = [check["name"] for check in checks if check["status"] != "ok"]
    return {
        "version": 1,
        "tenant": args.tenant,
        "releaseVersion": version,
        "generatedAt": now.isoformat(),
        "checks": checks,
        "blocking": blocking,
        "ready": not blocking,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("audit",))
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--release-manifest", required=True)
    parser.add_argument("--artifacts-dir")
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--registry", required=True)
    parser.add_argument("--file-report")
    parser.add_argument("--finance-report")
    parser.add_argument("--staging-report")
    parser.add_argument("--maintenance-start")
    parser.add_argument("--maintenance-end")
    parser.add_argument("--customer-accepted", action="store_true")
    parser.add_argument("--now")
    parser.add_argument("--report")
    parser.add_argument("--fail-if-not-ready", action="store_true")
    args = parser.parse_args()
    result = audit(args)
    if args.report:
        target = Path(args.report)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(str(temporary), str(target))
    print(json.dumps({"tenant": result["tenant"], "releaseVersion": result["releaseVersion"],
                      "ready": result["ready"], "blocking": result["blocking"]}, ensure_ascii=False))
    return 1 if args.fail_if_not_ready and not result["ready"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError, RuntimeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
