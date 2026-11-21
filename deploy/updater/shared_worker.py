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
import urllib.error
import urllib.parse
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
import stat

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shared_release
import shared_registry

MAX_ARTIFACT = shared_release.MAX_ARTIFACT


def elapsed_ms(start):
    return round((time.monotonic() - start) * 1000)


@contextmanager
def measure(timings, phase):
    start = time.monotonic()
    try:
        yield
    finally:
        timings[phase] = elapsed_ms(start)


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


def read_symlink(path):
    target = Path(path)
    return os.readlink(str(target)) if target.is_symlink() else None


def replace_symlink(target, destination):
    destination = Path(destination)
    temporary = destination.with_name("." + destination.name + "." + uuid.uuid4().hex)
    os.symlink(str(target), str(temporary), target_is_directory=True)
    if destination.is_symlink():
        destination.unlink()
    elif destination.exists():
        raise ValueError("current release path exists and is not a symlink")
    os.replace(str(temporary), str(destination))


def read_github_token(config):
    value = config.get("github_token_path")
    if not value:
        return None
    source = Path(value)
    if not source.is_file() or source.is_symlink():
        raise ValueError("github_token_path must be a regular file")
    if os.name == "posix" and stat.S_IMODE(source.stat().st_mode) & 0o077:
        raise ValueError("github_token_path must be readable only by its owner")
    token = source.read_text(encoding="utf-8").strip()
    if not token or len(token) > 500 or any(character.isspace() for character in token):
        raise ValueError("GitHub token file is invalid")
    return token


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def github_asset_api_url(package, repository, token):
    prefix = "https://github.com/" + repository + "/releases/download/"
    if not package.get("url", "").startswith(prefix):
        raise ValueError("untrusted artifact URL")
    suffix = package["url"][len(prefix):]
    if "/" not in suffix:
        raise ValueError("artifact URL is incomplete")
    tag, name = suffix.split("/", 1)
    if not tag or not name or "/" in name:
        raise ValueError("artifact URL is invalid")
    request = urllib.request.Request(
        "https://api.github.com/repos/" + repository + "/releases/tags/" + urllib.parse.quote(tag, safe=""),
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": "Bearer " + token,
            "User-Agent": "Cangshuo-Shared-Updater",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        # Artifact tasks must not share urllib's implicit global opener.
        with urllib.request.build_opener().open(request, timeout=30) as response:
            release = json.loads(response.read(2_000_000).decode("utf-8"))
    except Exception as error:
        raise ValueError("GitHub release metadata is unavailable") from error
    asset = next((item for item in release.get("assets", [])
                  if item.get("name") == name and item.get("size") == package.get("size")), None)
    if not asset or not isinstance(asset.get("id"), int):
        raise ValueError("GitHub release asset does not match the signed plan")
    return "https://api.github.com/repos/" + repository + "/releases/assets/" + str(asset["id"])


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


def start_services(operations, health_host, services, override, expected_versions, timings=None):
    if timings is None:
        timings = {}
    for service in ("backend", "admin", "h5"):
        if service not in services:
            continue
        service_timings = timings[service] = {}
        with measure(service_timings, "composeUp"):
            operations.compose_up([service], override)
        with measure(service_timings, "health"):
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
        token = read_github_token(self.config)
        headers = {"User-Agent": "Cangshuo-Shared-Updater"}
        if token:
            headers["Authorization"] = "Bearer " + token
            headers["Accept"] = "application/octet-stream"
            request_url = github_asset_api_url(package, self.config["repository"], token)
        else:
            request_url = package["url"]
        request = urllib.request.Request(request_url, headers=headers)
        redirect = urllib.request.build_opener(NoRedirect())
        try:
            source = redirect.open(request, timeout=self.config.get("download_timeout_seconds", 60))
        except urllib.error.HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                error.close()
                raise ValueError("GitHub artifact download failed") from error
            location = error.headers.get("Location", "")
            error.close()
            if not location.startswith(("https://github.com/", "https://release-assets.githubusercontent.com/", "https://objects.githubusercontent.com/")):
                raise ValueError("untrusted artifact redirect")
            source = redirect.open(urllib.request.Request(location, headers={"User-Agent": "Cangshuo-Shared-Updater"}),
                                   timeout=self.config.get("download_timeout_seconds", 60))
        with source, target.open("wb") as output:
            if not source.url.startswith(("https://github.com/", "https://release-assets.githubusercontent.com/", "https://objects.githubusercontent.com/")):
                raise ValueError("untrusted artifact redirect")
            while True:
                data = source.read(1024 * 1024)
                if not data:
                    break
                size += len(data)
                if size > MAX_ARTIFACT:
                    raise ValueError("artifact too large")
                if size > package["size"]:
                    raise ValueError("artifact checksum mismatch")
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
            compose_file = Path(name)
            if not compose_file.is_absolute():
                compose_file = Path(self.config["compose_directory"]) / compose_file
            command += ["-f", str(compose_file)]
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
        self.parallel_preparation = operations is None
        self.ops = DockerOperations(config) if operations is None else operations
        self.fixed_now = now
        self.now = self.execution_time()
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
        self.platform_tenants_dir = Path(config["platform_tenants_dir"]) if config.get("platform_tenants_dir") else None

    def execution_time(self):
        return self.fixed_now if self.fixed_now is not None else datetime.now(timezone.utc)

    def require_execution_deadlines(self):
        now = self.execution_time()
        if now >= self.checked["expires"]:
            raise ValueError("release plan is expired")
        if not self.checked["start"] <= now <= self.checked["end"]:
            raise ValueError("maintenance window is not open")

    def prepare_artifacts(self, staging, timings):
        packages = self.plan["packages"]
        by_component = {package["component"]: {} for package in packages}
        timings["artifacts"] = by_component

        def prepare(package):
            component = package["component"]
            archive = staging / (component + ".zip")
            # Each task owns its ZIP, extraction directory and timing entries.
            with measure(by_component[component], "downloadAndVerify"):
                self.ops.download(package, archive)
            with measure(by_component[component], "extractAndVerify"):
                self.ops.extract(archive, staging / component, package)

        if self.parallel_preparation:
            # Only the built-in adapter is known to be safe here. Exiting the pool
            # joins all tasks, even if result() raises, before staging can be removed.
            with ThreadPoolExecutor(max_workers=len(shared_release.COMPONENTS)) as pool:
                futures = [pool.submit(prepare, package) for package in packages]
                for future in futures:
                    future.result()
        else:
            for package in packages:
                prepare(package)

    def audit_execution(self, result, timings, start):
        record = shared_release.audit_record(self.plan, result, self.now)
        # Total is wall time to this outcome, not a sum of overlapping stages.
        record["timingsMs"] = {**timings, "total": elapsed_ms(start)}
        shared_release.append_audit(self.state / "audit.jsonl", record)

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
            if component == "backend":
                source = source / "delta-admin.jar"
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
        started = time.monotonic()
        timings = {}
        previous_override = None
        previous_current = None
        previous_link = None
        previous_versions = {}
        previous_component_versions = {}
        staging = self.state / "releases-staging" / self.plan["id"]
        backup_dir = self.state / "backups" / self.plan["id"]
        release_dir = self.root / "releases" / self.plan["id"]
        staging_created = False
        release_move_started = False
        changed = False
        rollback_failed = False
        try:
            self.now = self.execution_time()
            self.checked = shared_release.validate_plan(
                self.plan, self.config, self.active, shared_release.current_state(self.state),
                self.now, require_window=True)
            previous_override = read_optional(self.override)
            previous_current = read_optional(self.current)
            previous_link = read_symlink(self.root / "current")
            previous_versions = json.loads(previous_current).get("components", {}) if previous_current else {}
            if self.platform_tenants_dir:
                for tenant in self.plan["tenants"]:
                    tenant_dir = self.platform_tenants_dir / tenant
                    if not tenant_dir.is_dir():
                        raise ValueError("platform tenant directory does not exist: " + tenant)
                    previous_component_versions[tenant] = read_optional(tenant_dir / "component-versions.json")
            if backup_dir.exists():
                raise ValueError("backup directory already exists")
            if release_dir.exists():
                raise ValueError("release directory already exists")
            with measure(timings, "prepareArtifacts"):
                staging.mkdir(mode=0o700, parents=True)
                staging_created = True
                self.prepare_artifacts(staging, timings)
            self.require_execution_deadlines()
            with measure(timings, "backup"):
                self.ops.backup(backup_dir, self.plan, self.databases)
            with measure(timings, "stageRelease"):
                release_dir.parent.mkdir(parents=True, exist_ok=True)
                release_move_started = True
                shutil.move(str(staging), str(release_dir))
                for path in release_dir.rglob("*"):
                    os.chmod(path, 0o755 if path.is_dir() else 0o644)
                os.chmod(release_dir, 0o755)
            # Once live switching starts, finish it or roll back even if a deadline passes.
            self.require_execution_deadlines()
            write_json(self.journal, {
                "id": self.plan["id"],
                "previousOverride": previous_override,
                "previousCurrent": previous_current,
                "previousCurrentLink": previous_link,
                "services": list(shared_release.COMPONENTS),
                "versionsBefore": previous_versions,
                "previousComponentVersions": previous_component_versions,
                "releaseDir": str(release_dir),
                "serviceGroupId": self.plan["serviceGroupId"],
                "targetVersion": self.plan["targetVersion"],
                "healthHost": self.health_host,
                "schemaVersion": self.plan["schemaVersion"],
                "tenants": list(self.plan["tenants"]),
            })
            changed = True
            with measure(timings, "switchAndHealth"):
                self.write_override(release_dir)
                start_services(self.ops, self.health_host, list(shared_release.COMPONENTS), self.override,
                               {package["component"]: package["version"] for package in self.plan["packages"]},
                               timings=timings.setdefault("services", {}))
            with measure(timings, "finalizeRelease"):
                replace_symlink(release_dir, self.root / "current")
                if self.platform_tenants_dir:
                    for tenant in self.plan["tenants"]:
                        write_json(self.platform_tenants_dir / tenant / "component-versions.json", {
                            "backend": self.plan["targetVersion"], "admin": self.plan["targetVersion"],
                            "h5": self.plan["targetVersion"], "release": self.plan["targetVersion"],
                        })
                write_json(self.current, {
                    "release": self.plan["targetVersion"],
                    "releaseId": self.plan["id"],
                    "components": {package["component"]: package["version"] for package in self.plan["packages"]},
                    "updatedAt": self.now.astimezone(timezone.utc).isoformat(),
                })
                marker = self.state / "installed-plans" / self.plan["id"]
                write_json(marker, self.plan)
            self.audit_execution("SUCCEEDED", timings, started)
            remove(self.journal)
            remove(self.maintenance)
            try:
                self.prune()
            except Exception:
                pass
        except Exception as error:
            if changed:
                rollback_started = time.monotonic()
                try:
                    if previous_override is None:
                        remove(self.override)
                    else:
                        self.override.write_text(previous_override, encoding="utf-8")
                    if previous_current is None:
                        remove(self.current)
                    else:
                        self.current.write_text(previous_current, encoding="utf-8")
                    current_link = self.root / "current"
                    if previous_link is None:
                        if current_link.is_symlink():
                            current_link.unlink()
                    else:
                        replace_symlink(previous_link, current_link)
                    for tenant, content in previous_component_versions.items():
                        target = self.platform_tenants_dir / tenant / "component-versions.json"
                        if content is None:
                            remove(target)
                        else:
                            target.write_text(content, encoding="utf-8")
                            os.chmod(target, 0o600)
                    remove(self.state / "installed-plans" / self.plan["id"])
                    start_services(self.ops, self.health_host, list(shared_release.COMPONENTS),
                                   self.override if previous_override else None, previous_versions)
                    shutil.rmtree(release_dir, ignore_errors=True)
                    remove(self.journal)
                    timings["rollback"] = elapsed_ms(rollback_started)
                    self.audit_execution("ROLLED_BACK", timings, started)
                except Exception:
                    rollback_failed = True
                    self.maintenance.write_text("rollback failed for " + self.plan["id"] + "\n", encoding="utf-8")
                    timings["rollback"] = elapsed_ms(rollback_started)
                    self.audit_execution("FAILED", timings, started)
            else:
                if release_move_started:
                    shutil.rmtree(release_dir, ignore_errors=True)
                self.audit_execution("FAILED", timings, started)
            if rollback_failed:
                raise RuntimeError("release failed and rollback did not complete") from error
            raise
        finally:
            if staging_created:
                shutil.rmtree(staging, ignore_errors=True)
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
        previous_link = journal.get("previousCurrentLink")
        current_link = Path(config["root"]) / "current"
        if previous_link is None:
            if current_link.is_symlink():
                current_link.unlink()
        else:
            replace_symlink(previous_link, current_link)
        previous_component_versions = journal.get("previousComponentVersions", {})
        platform_tenants_dir = Path(config["platform_tenants_dir"]) if config.get("platform_tenants_dir") else None
        for tenant, content in previous_component_versions.items():
            if platform_tenants_dir is None:
                break
            target = platform_tenants_dir / tenant / "component-versions.json"
            if content is None:
                remove(target)
            else:
                target.write_text(content, encoding="utf-8")
                os.chmod(target, 0o600)
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


def process_inbox(config, operations=None, now=None, inbox=None):
    """Claim and execute every JSON release plan in the private inbox once."""
    if not config.get("allow_execute", False):
        raise PermissionError("shared execution is disabled by config")
    state = Path(config["state_dir"])
    source = Path(inbox or config.get("inbox_dir") or state / "inbox")
    processing = state / "processing"
    processed = state / "processed"
    rejected = state / "rejected"
    for directory in (source, processing, processed, rejected):
        directory.mkdir(parents=True, exist_ok=True)
        os.chmod(directory, 0o700)
    results = []
    for path in sorted(source.iterdir(), key=lambda item: item.name):
        if path.suffix.lower() != ".json" or path.is_symlink() or not path.is_file():
            continue
        claimed = processing / (path.name + "." + uuid.uuid4().hex[:8])
        shutil.move(str(path), str(claimed))
        try:
            if claimed.stat().st_size > 2_000_000:
                raise ValueError("release envelope is too large")
            envelope = shared_release.load_json(claimed)
            plan = shared_release.verify_envelope(envelope, config["public_key_path"], now or datetime.now(timezone.utc))
            SharedExecutor(config, plan, operations=operations, now=now).execute()
            shutil.move(str(claimed), str(processed / claimed.name))
            results.append({"file": path.name, "status": "SUCCEEDED", "releaseId": plan["id"]})
        except Exception as error:
            shutil.move(str(claimed), str(rejected / claimed.name))
            reason = rejected / (claimed.name + ".error")
            reason.write_text(type(error).__name__ + "\n", encoding="utf-8")
            os.chmod(reason, 0o600)
            results.append({"file": path.name, "status": "REJECTED", "error": type(error).__name__})
    return results


def run_daemon(config, operations=None, inbox=None, interval=5):
    if not config.get("allow_execute", False):
        raise PermissionError("shared execution is disabled by config")
    if Path(config["state_dir"]).joinpath("journal.json").exists():
        raise RuntimeError("unfinished shared release journal exists; recover before starting daemon")
    while True:
        results = process_inbox(config, operations=operations, inbox=inbox)
        if results:
            print(json.dumps(results, ensure_ascii=False, sort_keys=True), flush=True)
        time.sleep(max(1.0, float(interval)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "execute", "recover", "daemon"))
    parser.add_argument("envelope", nargs="?")
    parser.add_argument("--config", required=True)
    parser.add_argument("--confirm", help="service group id required for execute")
    parser.add_argument("--now", help="UTC timestamp for deterministic testing")
    parser.add_argument("--audit", help="append a redacted validation audit record")
    parser.add_argument("--inbox", help="private JSON release-plan inbox for daemon mode")
    parser.add_argument("--interval", type=float, default=5, help="daemon polling interval in seconds")
    args = parser.parse_args()
    config = shared_release.load_config(args.config)
    now = shared_release.instant(args.now, "--now") if args.now else None
    if args.command == "daemon":
        run_daemon(config, inbox=args.inbox, interval=args.interval)
        return 0
    if args.command == "recover":
        print(json.dumps(recover(config, now=now), ensure_ascii=False, sort_keys=True))
        return 0
    if not args.envelope:
        raise ValueError("envelope is required")
    current_time = now or datetime.now(timezone.utc)
    envelope = shared_release.load_json(args.envelope)
    plan = shared_release.verify_envelope(envelope, config["public_key_path"], current_time)
    tenants = shared_release.active_tenants(config)
    current = shared_release.current_state(config["state_dir"])
    if args.command == "validate":
        result = shared_release.dry_run(plan, config, tenants, current, current_time)
        if args.audit:
            shared_release.append_audit(args.audit, shared_release.audit_record(plan, "VALID", current_time))
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
