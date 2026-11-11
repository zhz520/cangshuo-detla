#!/usr/bin/env python3
"""Verify a signed shared service-group release plan and emit a dry-run plan.

This tool never starts containers, changes a database or accepts commands from
the signed plan. The operator configuration owns every host path and container
name; the signed plan only selects a release, tenants and a maintenance window.
"""

import argparse
import base64
import binascii
import json
import os
import re
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shared_registry

FORMAT = "cangshuo-shared-release-v1"
COMPONENTS = ("backend", "admin", "h5")
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
GROUP = re.compile(r"^[a-z0-9][a-z0-9-]{0,47}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
TENANT_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,47}$")
MAX_ARTIFACT = 500_000_000


def instant(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(field + " must be an ISO-8601 UTC timestamp")
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except AttributeError:
        parsed = None
        fallback = re.sub(r"([+-]\d{2}):(\d{2})$", r"\1\2", text)
        for pattern in ("%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S%z"):
            try:
                parsed = datetime.strptime(fallback, pattern)
                break
            except ValueError:
                pass
        if parsed is None:
            raise ValueError(field + " must be an ISO-8601 UTC timestamp")
    except ValueError as error:
        raise ValueError(field + " must be an ISO-8601 UTC timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(field + " must include a timezone")
    return parsed.astimezone(timezone.utc)


def semver(value, field):
    if not isinstance(value, str) or not SEMVER.fullmatch(value):
        raise ValueError(field + " must be MAJOR.MINOR.PATCH")
    return tuple(int(part) for part in value.split("."))


def load_json(path, limit=2_000_000):
    source = Path(path)
    if not source.is_file() or source.stat().st_size > limit:
        raise ValueError("JSON file missing or too large: " + str(source))
    try:
        return json.loads(source.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError("Invalid JSON: " + str(source)) from error


def validate_config(raw):
    if not isinstance(raw, dict):
        raise ValueError("config must be an object")
    required = ("service_group_id", "root", "state_dir", "compose_directory",
                "compose_files", "env_file", "mysql_container", "containers",
                "registry_file", "backup_paths", "repository", "public_key_path")
    for key in required:
        if key not in raw:
            raise ValueError("config is missing " + key)
    group = raw["service_group_id"]
    if not isinstance(group, str) or not GROUP.fullmatch(group):
        raise ValueError("invalid service_group_id")
    repository = raw["repository"]
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        raise ValueError("repository must be owner/name")
    for key in ("root", "state_dir", "compose_directory", "env_file", "registry_file", "public_key_path"):
        path = Path(str(raw[key]))
        if not path.is_absolute():
            raise ValueError(key + " must be an absolute path")
    compose_files = raw["compose_files"]
    if not isinstance(compose_files, list) or not compose_files:
        raise ValueError("compose_files must be a non-empty list")
    for name in compose_files:
        if not isinstance(name, str) or not name or ".." in Path(name).parts or Path(name).is_absolute():
            raise ValueError("compose_files must contain relative file names")
    containers = raw["containers"]
    if not isinstance(containers, dict) or not all(isinstance(containers.get(name), str) and containers[name] for name in COMPONENTS):
        raise ValueError("containers must name backend, admin and h5")
    if not isinstance(raw["mysql_container"], str) or not raw["mysql_container"]:
        raise ValueError("mysql_container must be a container name")
    paths = raw["backup_paths"]
    if not isinstance(paths, list) or any(not Path(str(path)).is_absolute() for path in paths):
        raise ValueError("backup_paths must contain absolute paths")
    if not isinstance(raw.get("allow_execute", False), bool):
        raise ValueError("allow_execute must be boolean")
    if raw.get("github_token_path") and not Path(str(raw["github_token_path"])).is_absolute():
        raise ValueError("github_token_path must be an absolute path")
    if raw.get("platform_tenants_dir") and not Path(str(raw["platform_tenants_dir"])).is_absolute():
        raise ValueError("platform_tenants_dir must be an absolute path")
    maximum = raw.get("maintenance_max_minutes", 240)
    if not isinstance(maximum, int) or not 1 <= maximum <= 1440:
        raise ValueError("maintenance_max_minutes must be 1..1440")
    retention = raw.get("release_retention", 3)
    if not isinstance(retention, int) or retention < 2:
        raise ValueError("release_retention must be at least 2 for rollback")
    return raw


def load_config(path):
    return validate_config(load_json(path))


def tenant_rows(config):
    document = shared_registry.load(config["registry_file"])
    return shared_registry.validate_registry(document)


def active_tenants(config):
    return [row["id"] for row in tenant_rows(config) if row["active"]]


def current_state(state_dir):
    path = Path(state_dir) / "current.json"
    if not path.exists():
        return {"release": None, "components": {}}
    data = load_json(path)
    if not isinstance(data, dict):
        raise ValueError("current.json must be an object")
    release = data.get("release")
    if release is not None:
        semver(release, "current.release")
    components = data.get("components", {})
    if not isinstance(components, dict):
        raise ValueError("current.components must be an object")
    for name, version in components.items():
        if name not in COMPONENTS:
            raise ValueError("current.components has an unknown component: " + str(name))
        semver(version, "current.components." + name)
    return {"release": release, "components": components}


def verify_envelope(envelope, public_key_path, now=None):
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives import serialization
    except ImportError as error:
        raise ValueError("cryptography is required to verify shared release plans") from error
    if not isinstance(envelope, dict) or envelope.get("format") != FORMAT:
        raise ValueError("Invalid shared release format")
    payload, signature = envelope.get("payload", ""), envelope.get("signature", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,1500000}", payload) or not re.fullmatch(r"[A-Za-z0-9_-]{80,90}", signature):
        raise ValueError("Invalid shared release signature encoding")
    key_data = Path(public_key_path).read_bytes()
    try:
        public_key = serialization.load_pem_public_key(key_data)
        public_key.verify(base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4)),
                          (FORMAT + "." + payload).encode())
    except (InvalidSignature, ValueError, TypeError, binascii.Error) as error:
        raise ValueError("Shared release signature does not verify") from error
    try:
        plan = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    except (ValueError, json.JSONDecodeError, binascii.Error) as error:
        raise ValueError("Shared release payload is invalid") from error
    if not isinstance(plan, dict) or plan.get("format") != FORMAT:
        raise ValueError("Shared release payload has the wrong format")
    return plan


def validate_plan(plan, config, tenants, current, now, require_window=False):
    if not isinstance(plan, dict):
        raise ValueError("plan must be an object")
    try:
        uuid.UUID(str(plan["id"]))
    except (KeyError, ValueError) as error:
        raise ValueError("plan id must be a UUID") from error
    if plan.get("serviceGroupId") != config["service_group_id"]:
        raise ValueError("plan is for a different service group")
    issued = instant(plan.get("issuedAt"), "issuedAt")
    expires = instant(plan.get("expiresAt"), "expiresAt")
    if issued > now + timedelta(seconds=300) or expires <= now:
        raise ValueError("release plan is expired or issued in the future")
    window = plan.get("maintenanceWindow")
    if not isinstance(window, dict):
        raise ValueError("maintenanceWindow is required")
    start = instant(window.get("startAt"), "maintenanceWindow.startAt")
    end = instant(window.get("endAt"), "maintenanceWindow.endAt")
    if end <= start or end - start > timedelta(minutes=config.get("maintenance_max_minutes", 240)):
        raise ValueError("maintenance window is invalid or too long")
    if require_window and not start <= now <= end:
        raise ValueError("maintenance window is not open")
    if plan.get("schemaVersion") != 1:
        raise ValueError("only database schemaVersion=1 is supported")
    if plan.get("backupRequired") is not True:
        raise ValueError("backupRequired must be true")
    if not isinstance(plan.get("releaseTag"), str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", plan["releaseTag"]):
        raise ValueError("releaseTag is invalid")
    selected = plan.get("tenants")
    if not isinstance(selected, list) or not selected or any(not isinstance(item, str) or not TENANT_ID.fullmatch(item) for item in selected):
        raise ValueError("tenants must be a non-empty list of tenant ids")
    if len(selected) != len(set(selected)):
        raise ValueError("tenants contains duplicates")
    if set(selected) != set(tenants):
        raise ValueError("plan tenants do not match active registry tenants")
    target = plan.get("targetVersion")
    source = plan.get("fromVersion")
    target_tuple = semver(target, "targetVersion")
    source_tuple = semver(source, "fromVersion")
    if target_tuple < source_tuple:
        raise ValueError("targetVersion cannot be lower than fromVersion")
    if current.get("release") and source != current["release"]:
        raise ValueError("fromVersion does not match the installed release")
    packages = plan.get("packages")
    if not isinstance(packages, list) or len(packages) != len(COMPONENTS):
        raise ValueError("plan must contain exactly backend, admin and h5 packages")
    seen = set()
    prefix = "https://github.com/" + config["repository"] + "/releases/download/" + plan["releaseTag"] + "/"
    for package in packages:
        if not isinstance(package, dict):
            raise ValueError("package must be an object")
        component = package.get("component")
        if component not in COMPONENTS or component in seen:
            raise ValueError("package component is invalid or duplicated")
        seen.add(component)
        if package.get("version") != target:
            raise ValueError("all components must match targetVersion")
        installed = current.get("components", {}).get(component)
        if installed and semver(package.get("version"), "package.version") < semver(installed, "current.components." + component):
            raise ValueError("component downgrade is forbidden: " + component)
        size = package.get("size")
        if not isinstance(size, int) or not 1 <= size <= MAX_ARTIFACT:
            raise ValueError("package size is invalid: " + component)
        if not isinstance(package.get("sha256"), str) or not HEX64.fullmatch(package["sha256"]):
            raise ValueError("package sha256 is invalid: " + component)
        url = package.get("url")
        if not isinstance(url, str) or not url.startswith(prefix):
            raise ValueError("package URL is not bound to the configured repository and release tag: " + component)
        parsed = urlsplit(url)
        if parsed.query or parsed.fragment or not parsed.path.endswith(".zip"):
            raise ValueError("package URL must be a plain ZIP release asset: " + component)
        if any(part == ".." for part in Path(parsed.path).parts) or component not in Path(parsed.path).name:
            raise ValueError("package URL does not identify the component asset: " + component)
    if seen != set(COMPONENTS):
        raise ValueError("plan is missing a component package")
    marker = Path(config["state_dir"]) / "installed-plans" / plan["id"]
    if marker.exists():
        raise ValueError("release plan was already installed")
    items = plan.get("items", [])
    if not isinstance(items, list) or any(not isinstance(item, str) or len(item) > 200 for item in items):
        raise ValueError("items must be a list of short strings")
    return {"plan": plan, "issued": issued, "expires": expires, "start": start, "end": end,
            "target": target, "source": source, "tenants": selected, "inWindow": start <= now <= end}


def audit_record(plan, result, now, detail=None):
    record = {
        "at": now.astimezone(timezone.utc).isoformat(),
        "serviceGroupId": plan["serviceGroupId"],
        "releaseId": plan["id"],
        "releaseTag": plan["releaseTag"],
        "fromVersion": plan["fromVersion"],
        "targetVersion": plan["targetVersion"],
        "schemaVersion": plan["schemaVersion"],
        "tenants": list(plan["tenants"]),
        "result": result,
    }
    if detail:
        record["detail"] = detail
    return record


def append_audit(path, record):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    os.chmod(target, 0o600)


def dry_run(plan, config, tenants, current, now, require_window=False):
    checked = validate_plan(plan, config, tenants, current, now, require_window)
    steps = ["verify-signature", "verify-tenant-registry"]
    steps += ["acquire-lock", "backup-mysql", "backup-files", "download-and-verify-artifacts",
              "stage-release", "start-backend", "health-backend", "start-admin", "health-admin",
              "start-h5", "health-h5", "write-current-release", "write-audit", "prune-history"]
    blocking = []
    if not config.get("allow_execute", False):
        blocking.append("config allow_execute is false")
    if not checked["inWindow"]:
        blocking.append("maintenance window is not open")
    return {
        "status": "VALID",
        "serviceGroupId": plan["serviceGroupId"],
        "releaseId": plan["id"],
        "releaseTag": plan["releaseTag"],
        "fromVersion": checked["source"],
        "targetVersion": checked["target"],
        "schemaVersion": plan["schemaVersion"],
        "tenants": checked["tenants"],
        "maintenanceWindow": {"startAt": checked["start"].isoformat(), "endAt": checked["end"].isoformat()},
        "inWindow": checked["inWindow"],
        "executeAllowed": not blocking,
        "blockingReasons": blocking,
        "steps": steps,
    }


def config_example():
    return {
        "service_group_id": "delta-shared-prod",
        "root": "/www/delta/shared",
        "state_dir": "/var/lib/delta-shared/updater",
        "compose_directory": "/www/delta/shared",
        "compose_files": ["compose.production.yml"],
        "env_file": "/www/delta/shared/shared.env",
        "mysql_container": "delta-shared-mysql",
        "containers": {"backend": "delta-shared-backend", "admin": "delta-admin-ui", "h5": "delta-h5"},
        "registry_file": "/www/delta/shared/tenant-registry.json",
        "backup_paths": ["/www/delta/data/upload", "/www/delta/data/platform"],
        "repository": "zhz520/cangshuo-detla",
        "public_key_path": "/www/delta/shared/public.pem",
        "github_token_path": "/www/delta/shared/github-token",
        "platform_tenants_dir": "/www/delta/data/platform/tenants",
        "health_host": "jaxapi738.idcgov.cn",
        "inbox_dir": "/var/lib/delta-shared/updater/inbox",
        "allow_execute": False,
        "maintenance_max_minutes": 240,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "inspect", "config-example"))
    parser.add_argument("envelope", nargs="?")
    parser.add_argument("--config")
    parser.add_argument("--now", help="UTC timestamp for deterministic validation")
    parser.add_argument("--require-window", action="store_true", help="reject a plan outside its maintenance window")
    parser.add_argument("--audit", help="append a redacted audit record")
    args = parser.parse_args()
    if args.command == "config-example":
        print(json.dumps(config_example(), ensure_ascii=False, indent=2))
        return 0
    if not args.envelope or not args.config:
        raise ValueError("validate/inspect require an envelope and --config")
    config = load_config(args.config)
    now = instant(args.now, "--now") if args.now else datetime.now(timezone.utc)
    envelope = load_json(args.envelope)
    plan = verify_envelope(envelope, config["public_key_path"], now)
    tenants = active_tenants(config)
    current = current_state(config["state_dir"])
    result = dry_run(plan, config, tenants, current, now, args.require_window)
    if args.command == "inspect":
        result = {"status": "VALID", "plan": plan, "dryRun": result}
    if args.audit:
        append_audit(args.audit, audit_record(plan, "VALID" if result["status"] == "VALID" else "REJECTED", now))
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
