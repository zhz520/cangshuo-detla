#!/usr/bin/env python3
"""Execute a verified shared service-group release with backup, health checks and rollback.

The signed plan never supplies a host command, container name or filesystem path.
Every execution path comes from the operator-owned config and the tenant registry.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path
import stat

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shared_release
import shared_registry

MAX_ARTIFACT = shared_release.MAX_ARTIFACT


def inside(path, parent):
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def write_json(path, value):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    os.chmod(temporary, 0o600)
    temporary.replace(target)


def remove(path):
    try:
        Path(path).unlink()
    except FileNotFoundError:
        pass


def read_optional(path):
    target = Path(path)
    return target.read_text(encoding="utf-8") if target.exists() else None


def prune_directories(parent, keep, preserve=()):
    parent = Path(parent)
    if not parent.is_dir():
        return
    preserved = {Path(path).resolve() for path in preserve if path}
    entries = []
    for path in parent.iterdir():
        if path.is_dir() and not path.is_symlink() and inside(path, parent):
            entries.append((path.stat().st_mtime, path))
    entries.sort(key=lambda item: (item[0], item[1].name), reverse=True)
    retained = {path.resolve() for _, path in entries[:max(0, int(keep))]} | preserved
    for _, path in entries:
        if path.resolve() not in retained:
            shutil.rmtree(path, ignore_errors=True)


def start_services(operations, health_host, services, override, expected_versions):
    for service in ("backend", "admin", "h5"):
        if service not in services:
            continue
        operations.compose_up([service], override)
        operations.health(service, expected_versions.get(service), health_host)


class DockerOperations:
    """Thin host adapter. It only accepts values already validated by the executor."""

    def __init__(self, config):
        self.config = config

    def run(self, args, timeout=180, stdout=None):
        return subprocess.run(args, check=True, stdout=stdout or subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=timeout)

    def backup(self, destination, plan, databases):
        target = Path(destination)
        target.mkdir(mode=0o700, parents=True)
        for tenant, database in sorted(databases.items()):
            if not re.fullmatch(r"[A-Za-z0-9_]{1,64}", database):
                raise ValueError("invalid tenant database name")
            output = target / ("tenant-" + tenant + ".sql")
            with output.open("wb") as stream:
                self.run(["docker", "exec", self.config["mysql_container"], "sh", "-c",
                          'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot '
                          '--single-transaction --routines --triggers --events --set-gtid-purged=OFF '
                          + database], timeout=self.config.get("backup_timeout_seconds", 300), stdout=stream)
            os.chmod(output, 0o600)
        with tarfile.open(target / "customer-files.tar.gz", "w:gz") as archive:
            for name in self.config.get("backup_paths", []):
                source = Path(name)
                if source.exists():
                    archive.add(source, arcname=str(source).lstrip("/"))
        os.chmod(target / "customer-files.tar.gz", 0o600)

    def download(self, package, target):
        target = Path(target)
        prefix = "https://github.com/" + self.config["repository"] + "/releases/download/"
        if not package["url"].startswith(prefix):
            raise ValueError("untrusted artifact URL")
        digest = hashlib.sha256()
        size = 0
        request = urllib.request.Request(package["url"], headers={"User-Agent": "Cangshuo-Shared-Updater"})
        with urllib.request.urlopen(request, timeout=self.config.get("download_timeout_seconds", 60)) as source, target.open("wb") as output:
            if not source.url.startswith(("https://github.com/", "https://release-assets.githubusercontent.com/", "https://objects.githubusercontent.com/")):
                raise ValueError("untrusted artifact redirect")
            while True:
                data = source.read(1024 * 1024)
                if not data:
                    break
                size += len(data)
                if size > MAX_ARTIFACT:
                    raise ValueError("artifact too large")
                digest.update(data)
                output.write(data)
        if size != package["size"] or digest.hexdigest() != package["sha256"]:
            raise ValueError("artifact checksum mismatch")

    def extract(self, archive, destination, package):
        destination = Path(destination)
        destination.mkdir()
        with zipfile.ZipFile(archive) as zipped:
            if sum(item.file_size for item in zipped.infolist()) > 1_000_000_000:
                raise ValueError("expanded archive too large")
            for item in zipped.infolist():
                target = (destination / item.filename).resolve()
                if not inside(target, destination) or "\\" in item.filename or stat.S_ISLNK(item.external_attr >> 16):
                    raise ValueError("unsafe archive path")
                if item.filename.startswith(".") or any(part.startswith(".") for part in Path(item.filename).parts):
                    raise ValueError("hidden archive files are forbidden")
            zipped.extractall(destination)
        if package["component"] == "backend":
            jar = destination / "delta-admin.jar"
            if not jar.is_file() or len(list(destination.iterdir())) != 1:
                raise ValueError("backend package must only contain delta-admin.jar")
            with zipfile.ZipFile(jar) as zipped:
                catalog = json.loads(zipped.read("BOOT-INF/classes/platform/catalog.json"))
            if catalog.get("version") != package["version"] or catalog.get("schemaVersion") != 1:
                raise ValueError("backend version does not match release")
        else:
            version = json.loads((destination / "cangshuo-version.json").read_text(encoding="utf-8"))
            if not (destination / "index.html").is_file() or version.get("version") != package["version"] or version.get("component") != package["component"]:
                raise ValueError("frontend version does not match release")

    def compose_up(self, services, override):
        command = ["docker", "compose", "--project-directory", self.config["compose_directory"]]
        if self.config.get("env_file"):
            command += ["--env-file", self.config["env_file"]]
        for name in self.config["compose_files"]:
            command += ["-f", name]
        if override:
            command += ["-f", str(override)]
        command += ["up", "-d", "--no-deps"] + list(services)
        self.run(command, timeout=self.config.get("compose_timeout_seconds", 300))

    def container_ip(self, service):
        inspected = json.loads(self.run(["docker", "inspect", self.config["containers"][service]]).stdout)[0]
        networks = inspected.get("NetworkSettings", {}).get("Networks", {})
        address = next((network.get("IPAddress") for network in networks.values() if network.get("IPAddress")), None)
        return inspected, address

    def health(self, service, expected_version, health_host):
        attempts = int(self.config.get("health_attempts", 90))
        interval = float(self.config.get("health_interval_seconds", 3))
        for _ in range(max(1, attempts)):
            try:
                inspected, address = self.container_ip(service)
                state = inspected.get("State", {})
                if not state.get("Running") or state.get("Health", {}).get("Status", "healthy") != "healthy" or not address:
                    raise RuntimeError("container not healthy")
                if service == "backend":
                    path, port = "/system/config/site", 8080
                    headers = {"Host": health_host, "User-Agent": "Cangshuo-HealthCheck"}
                else:
                    path, port = "/cangshuo-version.json", 80
                    headers = {"User-Agent": "Cangshuo-HealthCheck"}
                request = urllib.request.Request("http://%s:%s%s" % (address, port, path), headers=headers)
                with urllib.request.urlopen(request, timeout=5) as response:
                    body = response.read(65536)
                    if response.status != 200:
                        raise RuntimeError("health status")
                if service in ("admin", "h5") and expected_version:
                    version = json.loads(body)
                    if version.get("component") != service or version.get("version") != expected_version:
                        raise RuntimeError("frontend version mismatch")
                return
            except Exception:
                if _ + 1 >= max(1, attempts):
                    raise
                time.sleep(max(0, interval))


class SharedExecutor:
    def __init__(self, config, plan, operations=None, now=None):
        self.config = config
        self.plan = plan
        self.ops = operations or DockerOperations(config)
        self.now = now or datetime.now(timezone.utc)
        self.state = Path(config["state_dir"])
        self.state.mkdir(parents=True, exist_ok=True)
        os.chmod(self.state, 0o700)
        self.root = Path(config["root"])
        self.override = Path(config.get("compose_override") or self.root / "compose.shared-release.json")
        self.journal = self.state / "journal.json"
        self.current = self.state / "current.json"
        self.lock_path = self.state / "execute.lock"
        self.maintenance = self.state / "maintenance"
        self.rows = {row["id"]: row for row in shared_release.tenant_rows(config)}
        self.active = [row_id for row_id, row in self.rows.items() if row["active"]]
        self.checked = shared_release.validate_plan(plan, config, self.active, shared_release.current_state(self.state), self.now)
        hosts = [host for row in self.rows.values() if row["id"] in plan["tenants"] for host in row["hosts"]]
        selected_host = config.get("health_host") or (hosts[0] if hosts else "")
        self.health_host = shared_registry.normalize_host(selected_host) if selected_host else ""
        if self.health_host not in hosts:
            raise ValueError("health_host must belong to a selected tenant")
        self.databases = {row_id: self.rows[row_id]["database"] for row_id in plan["tenants"] if row_id in self.rows}
        if set(self.databases) != set(plan["tenants"]):
            raise ValueError("selected tenant has no database")

    def acquire(self):
        try:
            descriptor = os.open(self.lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError as error:
            raise RuntimeError("another shared release execution is running") from error
        os.write(descriptor, str(os.getpid()).encode())
        os.close(descriptor)

    def release_lock(self):
        remove(self.lock_path)

    def write_override(self, release_dir):
        merged = {"services": {}}
        for component in shared_release.COMPONENTS:
            source = release_dir / component
            destination = "/app/app.jar" if component == "backend" else "/usr/share/nginx/html"
            volume = str(source) + ":" + destination + ":ro"
            merged["services"][component] = {"volumes": [volume]}
        write_json(self.override, merged)

    def execute(self):
        if not self.config.get("allow_execute", False):
            raise PermissionError("shared execution is disabled by config")
        if self.maintenance.exists():
            raise RuntimeError("maintenance gate is active; recover the previous release first")
        if self.journal.exists():
            raise RuntimeError("previous execution journal exists; run recover")
        self.acquire()
        previous_override = None
        previous_current = None
        previous_versions = {}
        staging = self.state / "releases-staging" / self.plan["id"]
        backup_dir = self.state / "backups" / self.plan["id"]
        release_dir = self.root / "releases" / self.plan["id"]
        changed = False
        rollback_failed = False
        try:
            self.checked = shared_release.validate_plan(
                self.plan, self.config, self.active, shared_release.current_state(self.state),
                self.now, require_window=True)
            previous_override = read_optional(self.override)
            previous_current = read_optional(self.current)
            previous_versions = json.loads(previous_current).get("components", {}) if previous_current else {}
            if backup_dir.exists():
                raise ValueError("backup directory already exists")
            if release_dir.exists():
                raise ValueError("release directory already exists")
            self.ops.backup(backup_dir, self.plan, self.databases)
            for package in self.plan["packages"]:
                component = package["component"]
                archive = staging / (component + ".zip")
                archive.parent.mkdir(parents=True, exist_ok=True)
                self.ops.download(package, archive)
                self.ops.extract(archive, staging / component, package)
            release_dir.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(staging), str(release_dir))
            for path in release_dir.rglob("*"):
                os.chmod(path, 0o755 if path.is_dir() else 0o644)
            os.chmod(release_dir, 0o755)
            write_json(self.journal, {
                "id": self.plan["id"],
                "previousOverride": previous_override,
                "previousCurrent": previous_current,
                "services": list(shared_release.COMPONENTS),
                "versionsBefore": previous_versions,
                "releaseDir": str(release_dir),
                "serviceGroupId": self.plan["serviceGroupId"],
                "targetVersion": self.plan["targetVersion"],
                "healthHost": self.health_host,
                "schemaVersion": self.plan["schemaVersion"],
                "tenants": list(self.plan["tenants"]),
            })
            changed = True
            self.write_override(release_dir)
            start_services(self.ops, self.health_host, list(shared_release.COMPONENTS), self.override,
                           {package["component"]: package["version"] for package in self.plan["packages"]})
            write_json(self.current, {
                "release": self.plan["targetVersion"],
                "releaseId": self.plan["id"],
                "components": {package["component"]: package["version"] for package in self.plan["packages"]},
                "updatedAt": self.now.astimezone(timezone.utc).isoformat(),
            })
            marker = self.state / "installed-plans" / self.plan["id"]
            write_json(marker, self.plan)
            shared_release.append_audit(self.state / "audit.jsonl",
                                        shared_release.audit_record(self.plan, "SUCCEEDED", self.now))
            remove(self.journal)
            remove(self.maintenance)
            try:
                self.prune()
            except Exception:
                pass
        except Exception as error:
            if changed:
                try:
                    if previous_override is None:
                        remove(self.override)
                    else:
                        self.override.write_text(previous_override, encoding="utf-8")
                    if previous_current is None:
                        remove(self.current)
                    else:
                        self.current.write_text(previous_current, encoding="utf-8")
                    remove(self.state / "installed-plans" / self.plan["id"])
                    start_services(self.ops, self.health_host, list(shared_release.COMPONENTS),
                                   self.override if previous_override else None, previous_versions)
                    shutil.rmtree(release_dir, ignore_errors=True)
                    remove(self.journal)
                    shared_release.append_audit(self.state / "audit.jsonl",
                                                shared_release.audit_record(self.plan, "ROLLED_BACK", self.now))
                except Exception:
                    rollback_failed = True
                    self.maintenance.write_text("rollback failed for " + self.plan["id"] + "\n", encoding="utf-8")
                    shared_release.append_audit(self.state / "audit.jsonl",
                                                shared_release.audit_record(self.plan, "FAILED", self.now))
            else:
                shutil.rmtree(release_dir, ignore_errors=True)
                shared_release.append_audit(self.state / "audit.jsonl",
                                            shared_release.audit_record(self.plan, "FAILED", self.now))
            if rollback_failed:
                raise RuntimeError("release failed and rollback did not complete") from error
            raise
        finally:
            shutil.rmtree(self.state / "releases-staging" / self.plan["id"], ignore_errors=True)
            self.release_lock()

    def prune(self):
        preserve = {self.root / "releases" / self.plan["id"]}
        prune_directories(self.state / "backups", self.config.get("backup_retention", 3))
        prune_directories(self.root / "releases", self.config.get("release_retention", 3), preserve)


def recover(config, operations=None, now=None):
    now = now or datetime.now(timezone.utc)
    state = Path(config["state_dir"])
    journal_path = state / "journal.json"
    if not journal_path.exists():
        return {"status": "NO_JOURNAL"}
    journal = json.loads(journal_path.read_text(encoding="utf-8"))
    try:
        release_id = str(journal["id"])
        uuid.UUID(release_id)
    except (KeyError, ValueError) as error:
        raise ValueError("journal has an invalid release id") from error
    ops = operations or DockerOperations(config)
    override = Path(config.get("compose_override") or Path(config["root"]) / "compose.shared-release.json")
    current = state / "current.json"
    lock_path = state / "execute.lock"
    try:
        descriptor = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.write(descriptor, str(os.getpid()).encode())
        os.close(descriptor)
    except FileExistsError as error:
        raise RuntimeError("another shared release execution is running") from error
    try:
        previous_override = journal.get("previousOverride")
        if previous_override is None:
            remove(override)
        else:
            override.write_text(previous_override, encoding="utf-8")
            os.chmod(override, 0o600)
        previous_current = journal.get("previousCurrent")
        if previous_current is None:
            remove(current)
        else:
            current.write_text(previous_current, encoding="utf-8")
            os.chmod(current, 0o600)
        remove(state / "installed-plans" / release_id)
        health_host = journal.get("healthHost") or config.get("health_host")
        if not health_host:
            rows = shared_release.tenant_rows(config)
            health_host = next((host for row in rows if row["active"] for host in row["hosts"]), "")
        start_services(ops, health_host, ["backend", "admin", "h5"],
                       override if previous_override else None, journal.get("versionsBefore", {}))
        release_text = journal.get("releaseDir")
        if release_text:
            release_dir = Path(release_text)
            releases_root = (Path(config["root"]) / "releases").resolve()
            if release_dir.is_absolute() and inside(release_dir, releases_root) and release_dir.resolve() != releases_root:
                shutil.rmtree(release_dir, ignore_errors=True)
        remove(journal_path)
        remove(state / "maintenance")
        shared_release.append_audit(state / "audit.jsonl", {
            "at": now.astimezone(timezone.utc).isoformat(),
            "serviceGroupId": journal.get("serviceGroupId", ""),
            "releaseId": release_id,
            "targetVersion": journal.get("targetVersion", ""),
            "schemaVersion": journal.get("schemaVersion", 1),
            "tenants": journal.get("tenants", []),
            "result": "ROLLED_BACK",
        })
        return {"status": "ROLLED_BACK", "releaseId": release_id}
    except Exception:
        (state / "maintenance").write_text("recovery failed for " + release_id + "\n", encoding="utf-8")
        raise
    finally:
        remove(lock_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "execute", "recover"))
    parser.add_argument("envelope", nargs="?")
    parser.add_argument("--config", required=True)
    parser.add_argument("--confirm", help="service group id required for execute")
    parser.add_argument("--now", help="UTC timestamp for deterministic testing")
    parser.add_argument("--audit", help="append a redacted validation audit record")
    args = parser.parse_args()
    config = shared_release.load_config(args.config)
    now = shared_release.instant(args.now, "--now") if args.now else datetime.now(timezone.utc)
    if args.command == "recover":
        print(json.dumps(recover(config, now=now), ensure_ascii=False, sort_keys=True))
        return 0
    if not args.envelope:
        raise ValueError("envelope is required")
    envelope = shared_release.load_json(args.envelope)
    plan = shared_release.verify_envelope(envelope, config["public_key_path"], now)
    tenants = shared_release.active_tenants(config)
    current = shared_release.current_state(config["state_dir"])
    if args.command == "validate":
        result = shared_release.dry_run(plan, config, tenants, current, now)
        if args.audit:
            shared_release.append_audit(args.audit, shared_release.audit_record(plan, "VALID", now))
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    if args.confirm != config["service_group_id"]:
        raise PermissionError("execute requires --confirm with the exact service group id")
    executor = SharedExecutor(config, plan, now=now)
    executor.execute()
    print(json.dumps({"status": "SUCCEEDED", "releaseId": plan["id"], "targetVersion": plan["targetVersion"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError, PermissionError, RuntimeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
