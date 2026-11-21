import hashlib
import io
import json
import os
import tempfile
import unittest
import uuid
import zipfile
import base64
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event
from unittest.mock import patch
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).parent))
import shared_release
import shared_worker


class FakeResponse:
    def __init__(self, value):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, _limit=-1):
        return json.dumps(self.value).encode("utf-8")


def mock_response(value):
    return FakeResponse(value)


class FakeOperations:
    def __init__(self, fail_health_once=None):
        self.calls = []
        self.fail_health_once = fail_health_once
        self.failed = False

    def backup(self, destination, plan, databases):
        destination.mkdir(parents=True)
        for tenant in databases:
            (destination / ("tenant-" + tenant + ".sql")).write_bytes(b"dump")
        (destination / "customer-files.tar.gz").write_bytes(b"files")
        self.calls.append(("backup", tuple(sorted(databases))))

    def download(self, package, target):
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"zip")
        self.calls.append(("download", package["component"]))

    def extract(self, archive, destination, package):
        destination.mkdir(parents=True)
        if package["component"] == "backend":
            (destination / "delta-admin.jar").write_bytes(b"jar")
        else:
            (destination / "index.html").write_text("<html></html>", encoding="utf-8")
            (destination / "cangshuo-version.json").write_text(json.dumps({
                "component": package["component"], "version": package["version"]}), encoding="utf-8")
        self.calls.append(("extract", package["component"]))

    def compose_up(self, services, override):
        self.calls.append(("compose", tuple(services), str(override) if override else None))

    def health(self, service, expected_version, health_host):
        self.calls.append(("health", service, expected_version, health_host))
        if (not self.failed and self.fail_health_once and service == self.fail_health_once):
            self.failed = True
            raise RuntimeError("health failed")


class SharedWorkerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("TEMP"))
        self.root = Path(self.temporary.name)
        self.state = self.root / "state"
        self.state.mkdir()
        self.key = Ed25519PrivateKey.generate()
        self.public_key = self.root / "public.pem"
        self.public_key.write_bytes(self.key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
        self.registry = self.root / "registry.json"
        self.registry.write_text(json.dumps({"tenants": {"tenant-a": {
            "active": True,
            "hosts": ["tenant-a.example.test"],
            "jdbcUrl": "jdbc:mysql://mysql:3306/delta_a",
            "username": "delta_a",
            "password": "secret-a",
            "platformCustomerId": "customer-a",
        }}}), encoding="utf-8")
        self.config = {
            "service_group_id": "delta-shared-prod",
            "root": str(self.root),
            "state_dir": str(self.state),
            "compose_directory": str(self.root),
            "compose_files": ["docker-compose.shared.yml"],
            "compose_override": str(self.root / "compose.shared-release.json"),
            "env_file": str(self.root / ".env"),
            "mysql_container": "delta-shared-mysql",
            "containers": {"backend": "delta-shared-backend", "admin": "delta-shared-admin", "h5": "delta-shared-h5"},
            "registry_file": str(self.registry),
            "backup_paths": [str(self.root / "data")],
            "repository": "owner/cangshuo-detla",
            "public_key_path": str(self.public_key),
            "health_host": "tenant-a.example.test",
            "allow_execute": True,
            "health_attempts": 2,
            "health_interval_seconds": 0,
            "backup_retention": 3,
            "release_retention": 3,
        }
        self.now = datetime.now(timezone.utc)
        self.plan = self.make_plan()

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def read_link(path):
        value = os.readlink(str(path))
        if value.startswith("\\\\?\\"):
            value = value[4:]
        return value

    def make_plan(self, **changes):
        version = "1.11.3"
        tag = "v" + version
        plan = {
            "format": shared_release.FORMAT,
            "id": str(uuid.uuid4()),
            "serviceGroupId": "delta-shared-prod",
            "releaseTag": tag,
            "issuedAt": self.now.isoformat(),
            "expiresAt": (self.now + timedelta(minutes=30)).isoformat(),
            "maintenanceWindow": {
                "startAt": (self.now - timedelta(minutes=5)).isoformat(),
                "endAt": (self.now + timedelta(minutes=30)).isoformat(),
            },
            "schemaVersion": 1,
            "backupRequired": True,
            "tenants": ["tenant-a"],
            "fromVersion": "1.11.2",
            "targetVersion": version,
            "items": ["1.11.3:backend"],
            "packages": [
                {"component": component, "version": version, "size": 100,
                 "sha256": "a" * 64,
                 "url": f"https://github.com/owner/cangshuo-detla/releases/download/{tag}/cangshuo-{component}.zip"}
                for component in shared_release.COMPONENTS
            ],
        }
        plan.update(changes)
        return plan

    def envelope(self, plan=None):
        plan = plan or self.plan
        payload = base64.urlsafe_b64encode(json.dumps(plan).encode()).decode().rstrip("=")
        signature = self.key.sign((shared_release.FORMAT + "." + payload).encode())
        return {
            "format": shared_release.FORMAT,
            "payload": payload,
            "signature": base64.urlsafe_b64encode(signature).decode().rstrip("="),
        }

    def artifact_plan(self):
        archives = {}
        plan = self.make_plan()
        for package in plan["packages"]:
            component = package["component"]
            payload = io.BytesIO()
            with zipfile.ZipFile(payload, "w") as archive:
                if component == "backend":
                    jar = io.BytesIO()
                    with zipfile.ZipFile(jar, "w") as inner:
                        inner.writestr("BOOT-INF/classes/platform/catalog.json", json.dumps({
                            "version": package["version"], "schemaVersion": 1,
                        }))
                    archive.writestr("delta-admin.jar", jar.getvalue())
                else:
                    archive.writestr("index.html", "<html></html>")
                    archive.writestr("cangshuo-version.json", json.dumps({
                        "version": package["version"], "component": component,
                    }))
            data = payload.getvalue()
            archives[package["url"]] = data
            package.update(size=len(data), sha256=hashlib.sha256(data).hexdigest())
        return plan, archives

    def read_audit(self):
        return json.loads((self.state / "audit.jsonl").read_text(encoding="utf-8"))

    def test_private_inbox_executes_a_signed_plan_and_moves_it_to_processed(self):
        inbox = self.state / "inbox"
        inbox.mkdir()
        (inbox / "release.json").write_text(json.dumps(self.envelope()), encoding="utf-8")
        results = shared_worker.process_inbox(self.config, FakeOperations(), self.now)
        self.assertEqual(results[0]["status"], "SUCCEEDED")
        self.assertFalse((inbox / "release.json").exists())
        self.assertEqual(len(list((self.state / "processed").glob("*.json.*"))), 1)
        self.assertEqual(json.loads((self.state / "current.json").read_text(encoding="utf-8"))["release"], "1.11.3")

    def test_private_inbox_rejects_invalid_json_without_executing(self):
        inbox = self.state / "inbox"
        inbox.mkdir()
        (inbox / "broken.json").write_text("not-json", encoding="utf-8")
        results = shared_worker.process_inbox(self.config, FakeOperations(), self.now)
        self.assertEqual(results[0]["status"], "REJECTED")
        self.assertFalse((self.state / "current.json").exists())
        rejected = [path for path in (self.state / "rejected").glob("broken.json.*") if not path.name.endswith(".error")]
        self.assertEqual(len(rejected), 1)

    def test_private_github_token_file_is_loaded_without_printing_it(self):
        token_file = self.root / "github-token"
        token_file.write_text("test-token-value", encoding="utf-8")
        if os.name == "posix":
            os.chmod(token_file, 0o600)
        config = dict(self.config, github_token_path=str(token_file))
        self.assertEqual(shared_worker.read_github_token(config), "test-token-value")
        if os.name == "posix":
            os.chmod(token_file, 0o644)
            with self.assertRaisesRegex(ValueError, "owner"):
                shared_worker.read_github_token(config)

    def test_successful_execution_updates_each_platform_tenant_component_version(self):
        platform = self.root / "platform" / "tenants"
        tenants = ("tenant-a", "tenant-b", "tenant-c")
        registry = json.loads(self.registry.read_text(encoding="utf-8"))
        for tenant in tenants:
            database = tenant.replace("-", "_")
            registry["tenants"][tenant] = dict(registry["tenants"]["tenant-a"],
                hosts=[tenant + ".example.test"], jdbcUrl="jdbc:mysql://mysql:3306/" + database,
                username=database, platformCustomerId="customer-" + tenant)
            tenant_dir = platform / tenant
            tenant_dir.mkdir(parents=True)
            (tenant_dir / "component-versions.json").write_text('{"release":"1.11.2"}', encoding="utf-8")
            (tenant_dir / "local-settings.json").write_text(json.dumps({"tenant": tenant}), encoding="utf-8")
        self.registry.write_text(json.dumps(registry), encoding="utf-8")
        config = dict(self.config, platform_tenants_dir=str(platform))
        plan = self.make_plan(tenants=list(tenants))
        operations = FakeOperations()
        shared_worker.SharedExecutor(config, plan, operations, self.now).execute()
        self.assertIn(("backup", tenants), operations.calls)
        for tenant in tenants:
            tenant_dir = platform / tenant
            values = json.loads((tenant_dir / "component-versions.json").read_text(encoding="utf-8"))
            self.assertEqual(values, {"backend": "1.11.3", "admin": "1.11.3", "h5": "1.11.3", "release": "1.11.3"})
            self.assertEqual(json.loads((tenant_dir / "local-settings.json").read_text(encoding="utf-8")),
                             {"tenant": tenant})

    def test_private_github_asset_is_resolved_through_the_release_api(self):
        package = {
            "component": "backend",
            "version": "1.11.3",
            "size": 100,
            "sha256": "a" * 64,
            "url": "https://github.com/owner/cangshuo-detla/releases/download/v1.11.3/cangshuo-backend.zip",
        }
        response = mock_response({"assets": [{"id": 17, "name": "cangshuo-backend.zip", "size": 100}]})
        with patch("shared_worker.urllib.request.OpenerDirector.open", return_value=response):
            url = shared_worker.github_asset_api_url(package, "owner/cangshuo-detla", "private-token")
        self.assertEqual(url, "https://api.github.com/repos/owner/cangshuo-detla/releases/assets/17")

    def test_private_github_asset_rejects_size_mismatch(self):
        package = {
            "component": "admin", "version": "1.11.3", "size": 100,
            "sha256": "a" * 64,
            "url": "https://github.com/owner/cangshuo-detla/releases/download/v1.11.3/cangshuo-admin.zip",
        }
        response = mock_response({"assets": [{"id": 18, "name": "cangshuo-admin.zip", "size": 99}]})
        with patch("shared_worker.urllib.request.OpenerDirector.open", return_value=response):
            with self.assertRaisesRegex(ValueError, "does not match"):
                shared_worker.github_asset_api_url(package, "owner/cangshuo-detla", "private-token")

    def test_injected_adapter_verifies_every_component_then_backs_up_and_switches_serially(self):
        operations = FakeOperations()
        shared_worker.SharedExecutor(self.config, self.plan, operations, self.now).execute()

        self.assertEqual([call[:2] for call in operations.calls], [
            ("download", "backend"), ("extract", "backend"),
            ("download", "admin"), ("extract", "admin"),
            ("download", "h5"), ("extract", "h5"),
            ("backup", ("tenant-a",)),
            ("compose", ("backend",)), ("health", "backend"),
            ("compose", ("admin",)), ("health", "admin"),
            ("compose", ("h5",)), ("health", "h5"),
        ])
        current = json.loads((self.state / "current.json").read_text(encoding="utf-8"))
        self.assertEqual(current["release"], "1.11.3")
        self.assertTrue((self.state / "installed-plans" / self.plan["id"]).exists())
        self.assertFalse((self.state / "journal.json").exists())
        self.assertFalse((self.state / "execute.lock").exists())
        self.assertEqual(self.read_link(self.root / "current"), str(self.root / "releases" / self.plan["id"]))
        override = json.loads((self.root / "compose.shared-release.json").read_text(encoding="utf-8"))
        self.assertEqual(override["services"]["backend"]["volumes"][0].rsplit(":", 2)[0],
                         str(self.root / "releases" / self.plan["id"] / "backend" / "delta-admin.jar"))
        audit = self.read_audit()
        self.assertEqual(audit["result"], "SUCCEEDED")
        timings = audit["timingsMs"]
        for phase in ("prepareArtifacts", "backup", "stageRelease", "switchAndHealth", "finalizeRelease", "total"):
            self.assertIsInstance(timings[phase], int)
            self.assertGreaterEqual(timings[phase], 0)
        self.assertEqual(set(timings["services"]), set(shared_release.COMPONENTS))
        for service in timings["services"].values():
            self.assertEqual(set(service), {"composeUp", "health"})

    def test_default_adapter_prepares_all_three_archives_in_parallel(self):
        plan, archives = self.artifact_plan()
        executor = shared_worker.SharedExecutor(self.config, plan, now=self.now)
        staging = self.state / "releases-staging" / plan["id"]
        operations = FakeOperations()
        downloading = Barrier(3, timeout=5)
        extracting = Barrier(3, timeout=5)
        extract = executor.ops.extract

        def open_artifact(request, **_):
            response = io.BytesIO(archives[request.full_url])
            response.url = request.full_url
            downloading.wait()
            return response

        def extract_artifact(archive, destination, package):
            extracting.wait()
            extract(archive, destination, package)

        def backup(destination, plan, databases):
            self.assertTrue((staging / "backend" / "delta-admin.jar").is_file())
            for component in ("admin", "h5"):
                version = json.loads((staging / component / "cangshuo-version.json").read_text(encoding="utf-8"))
                self.assertEqual(version, {"component": component, "version": plan["targetVersion"]})
            operations.backup(destination, plan, databases)

        with (patch("shared_worker.urllib.request.OpenerDirector.open", side_effect=open_artifact),
              patch.object(executor.ops, "extract", side_effect=extract_artifact),
              patch.object(executor.ops, "backup", side_effect=backup),
              patch.object(executor.ops, "compose_up", side_effect=operations.compose_up),
              patch.object(executor.ops, "health", side_effect=operations.health)):
            executor.execute()

        self.assertEqual([call[:2] for call in operations.calls], [
            ("backup", ("tenant-a",)),
            ("compose", ("backend",)), ("health", "backend"),
            ("compose", ("admin",)), ("health", "admin"),
            ("compose", ("h5",)), ("health", "h5"),
        ])
        self.assertFalse(staging.exists())
        timings = self.read_audit()["timingsMs"]
        self.assertEqual(set(timings["artifacts"]), set(shared_release.COMPONENTS))
        for component in timings["artifacts"].values():
            self.assertEqual(set(component), {"downloadAndVerify", "extractAndVerify"})
            self.assertTrue(all(isinstance(value, int) and value >= 0 for value in component.values()))

    def test_default_adapter_checksum_failure_waits_for_peers_before_cleanup(self):
        plan, archives = self.artifact_plan()
        backend, _, h5 = plan["packages"]
        backend["sha256"] = "0" * 64
        executor = shared_worker.SharedExecutor(self.config, plan, now=self.now)
        staging = self.state / "releases-staging" / plan["id"]
        h5_started, finish_h5, pool_joining = Event(), Event(), Event()
        cleanup_observations = []
        remove_tree = shared_worker.shutil.rmtree

        class ObservedPool(ThreadPoolExecutor):
            def shutdown(self, *args, **kwargs):
                pool_joining.set()
                super().shutdown(*args, **kwargs)

        def open_artifact(request, **_):
            if request.full_url == h5["url"]:
                h5_started.set()
                if not finish_h5.wait(10):
                    raise RuntimeError("test did not release H5 download")
            response = io.BytesIO(archives[request.full_url])
            response.url = request.full_url
            return response

        def observe_cleanup(path, *args, **kwargs):
            if path == staging:
                cleanup_observations.append((staging / "h5" / "cangshuo-version.json").is_file())
            return remove_tree(path, *args, **kwargs)

        with (patch("shared_worker.ThreadPoolExecutor", ObservedPool),
              patch("shared_worker.urllib.request.OpenerDirector.open", side_effect=open_artifact),
              patch("shared_worker.shutil.rmtree", side_effect=observe_cleanup),
              patch.object(executor.ops, "backup") as backup,
              patch.object(executor.ops, "compose_up") as compose,
              ThreadPoolExecutor(max_workers=1) as caller):
            future = caller.submit(executor.execute)
            try:
                self.assertTrue(h5_started.wait(5))
                self.assertTrue(pool_joining.wait(5))
                self.assertTrue(staging.is_dir())
                self.assertTrue(executor.lock_path.is_file())
                self.assertFalse(future.done())
                backup.assert_not_called()
                compose.assert_not_called()
            finally:
                finish_h5.set()
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                future.result(timeout=5)
            backup.assert_not_called()
            compose.assert_not_called()

        self.assertEqual(cleanup_observations, [True])
        self.assertFalse(staging.exists())
        self.assertFalse(executor.lock_path.exists())
        self.assertFalse(executor.journal.exists())
        self.assertFalse(executor.override.exists())
        audit = self.read_audit()
        self.assertEqual(audit["result"], "FAILED")
        timings = audit["timingsMs"]
        self.assertNotIn("backup", timings)
        self.assertNotIn("switchAndHealth", timings)
        self.assertEqual(set(timings["artifacts"]["backend"]), {"downloadAndVerify"})
        for component in ("admin", "h5"):
            self.assertIn("extractAndVerify", timings["artifacts"][component])
        self.assertNotIn("github.com", json.dumps(audit))

    def test_artifact_download_does_not_write_more_than_signed_size(self):
        plan, archives = self.artifact_plan()
        package = plan["packages"][0]
        response = io.BytesIO(archives[package["url"]] + b"extra")
        response.url = package["url"]
        target = self.root / "artifact.zip"
        with patch("shared_worker.urllib.request.OpenerDirector.open", return_value=response):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                shared_worker.DockerOperations(self.config).download(package, target)
        self.assertLessEqual(target.stat().st_size, package["size"])

    def test_extraction_failure_never_reaches_backup_or_switch(self):
        operations = FakeOperations()
        executor = shared_worker.SharedExecutor(self.config, self.plan, operations, self.now)
        extract = operations.extract

        def reject_last_archive(archive, destination, package):
            extract(archive, destination, package)
            if package["component"] == "h5":
                raise ValueError("frontend version does not match release: sensitive-test-value")

        with patch.object(operations, "extract", side_effect=reject_last_archive):
            with self.assertRaisesRegex(ValueError, "frontend version"):
                executor.execute()

        self.assertNotIn("backup", [call[0] for call in operations.calls])
        self.assertNotIn("compose", [call[0] for call in operations.calls])
        self.assertFalse(executor.journal.exists())
        self.assertFalse((self.state / "releases-staging" / self.plan["id"]).exists())
        self.assertNotIn("sensitive-test-value", json.dumps(self.read_audit()))

    def test_backup_failure_never_switches_or_writes_a_journal(self):
        operations = FakeOperations()
        executor = shared_worker.SharedExecutor(self.config, self.plan, operations, self.now)
        with patch.object(operations, "backup", side_effect=RuntimeError("backup failed")) as backup:
            with self.assertRaisesRegex(RuntimeError, "backup failed"):
                executor.execute()

        backup.assert_called_once()
        self.assertEqual(operations.calls[-1], ("extract", "h5"))
        self.assertNotIn("compose", [call[0] for call in operations.calls])
        self.assertFalse(executor.journal.exists())
        self.assertFalse(executor.override.exists())
        self.assertFalse((self.state / "releases-staging" / self.plan["id"]).exists())
        self.assertNotIn("switchAndHealth", self.read_audit()["timingsMs"])

    def test_window_closed_after_preparation_does_not_backup(self):
        operations = FakeOperations()
        plan = self.make_plan(expiresAt=(self.now + timedelta(hours=2)).isoformat())
        executor = shared_worker.SharedExecutor(self.config, plan, operations, self.now)
        closed = self.now + timedelta(hours=1)
        with patch.object(executor, "execution_time", side_effect=[self.now, closed]):
            with self.assertRaisesRegex(ValueError, "maintenance window"):
                executor.execute()
        self.assertEqual(operations.calls[-1], ("extract", "h5"))
        self.assertNotIn("backup", [call[0] for call in operations.calls])
        self.assertNotIn("compose", [call[0] for call in operations.calls])

    def test_window_closed_after_backup_does_not_switch(self):
        operations = FakeOperations()
        plan = self.make_plan(expiresAt=(self.now + timedelta(hours=2)).isoformat())
        executor = shared_worker.SharedExecutor(self.config, plan, operations, self.now)
        closed = self.now + timedelta(hours=1)
        with patch.object(executor, "execution_time", side_effect=[self.now, self.now, closed]):
            with self.assertRaisesRegex(ValueError, "maintenance window"):
                executor.execute()
        self.assertEqual(operations.calls[-1], ("backup", ("tenant-a",)))
        self.assertFalse(executor.journal.exists())
        self.assertFalse(executor.override.exists())
        self.assertFalse((self.root / "releases" / plan["id"]).exists())

    def test_plan_expiring_at_backup_gate_does_not_backup(self):
        deadline = self.now + timedelta(minutes=1)
        plan = self.make_plan(expiresAt=deadline.isoformat())
        operations = FakeOperations()
        with patch.object(shared_worker, "datetime", wraps=datetime) as clock:
            clock.now.side_effect = [self.now, self.now, deadline, deadline]
            executor = shared_worker.SharedExecutor(self.config, plan, operations)
            with self.assertRaisesRegex(ValueError, "release plan is expired"):
                executor.execute()
        self.assertEqual(operations.calls[-1], ("extract", "h5"))
        self.assertFalse((self.state / "backups" / plan["id"]).exists())
        self.assertFalse((self.state / "releases-staging" / plan["id"]).exists())
        self.assertFalse(executor.journal.exists())
        self.assertFalse(executor.override.exists())
        audit = self.read_audit()
        self.assertEqual(audit["result"], "FAILED")
        self.assertNotIn("backup", audit["timingsMs"])
        self.assertNotIn("detail", audit)

    def test_plan_expiring_at_switch_gate_keeps_backup_without_switching(self):
        deadline = self.now + timedelta(minutes=1)
        plan = self.make_plan(expiresAt=deadline.isoformat())
        operations = FakeOperations()
        with patch.object(shared_worker, "datetime", wraps=datetime) as clock:
            clock.now.side_effect = [self.now, self.now, self.now, deadline]
            executor = shared_worker.SharedExecutor(self.config, plan, operations)
            with self.assertRaisesRegex(ValueError, "release plan is expired"):
                executor.execute()
        self.assertEqual(operations.calls[-1], ("backup", ("tenant-a",)))
        self.assertTrue((self.state / "backups" / plan["id"] / "customer-files.tar.gz").is_file())
        self.assertFalse((self.root / "releases" / plan["id"]).exists())
        self.assertFalse(executor.journal.exists())
        self.assertFalse(executor.override.exists())
        self.assertFalse(executor.current.exists())
        audit = self.read_audit()
        self.assertEqual(audit["result"], "FAILED")
        self.assertIn("backup", audit["timingsMs"])
        self.assertNotIn("switchAndHealth", audit["timingsMs"])
        self.assertNotIn("detail", audit)

    def test_deadlines_passing_after_switch_starts_do_not_interrupt_success(self):
        operations = FakeOperations()
        executor = shared_worker.SharedExecutor(self.config, self.plan, operations, self.now)
        clock = self.now
        health = operations.health

        def finish_health(service, expected_version, health_host):
            nonlocal clock
            health(service, expected_version, health_host)
            clock = self.now + timedelta(hours=1)

        with (patch.object(executor, "execution_time", side_effect=lambda: clock),
              patch.object(operations, "health", side_effect=finish_health)):
            executor.execute()
        self.assertEqual([call[1] for call in operations.calls if call[0] == "health"],
                         ["backend", "admin", "h5"])
        self.assertEqual(self.read_audit()["result"], "SUCCEEDED")

    def test_health_failure_restores_previous_override_and_current(self):
        previous_override = self.root / "compose.shared-release.json"
        previous_override.write_text('{"services":{"backend":{"volumes":["/old:/app/app.jar:ro"]}}}', encoding="utf-8")
        (self.state / "current.json").write_text(json.dumps({
            "release": "1.11.2", "components": {"backend": "1.11.2", "admin": "1.11.2", "h5": "1.11.2"}}), encoding="utf-8")
        previous_link = str(self.root / "releases" / "previous")
        os.symlink(previous_link, str(self.root / "current"), target_is_directory=True)
        operations = FakeOperations(fail_health_once="admin")
        executor = shared_worker.SharedExecutor(self.config, self.plan, operations, self.now)
        clock = self.now
        health = operations.health

        def finish_health(service, expected_version, health_host):
            nonlocal clock
            clock = self.now + timedelta(hours=1)
            health(service, expected_version, health_host)

        with (patch.object(executor, "execution_time", side_effect=lambda: clock),
              patch.object(operations, "health", side_effect=finish_health)):
            with self.assertRaisesRegex(RuntimeError, "health failed"):
                executor.execute()

        self.assertEqual(previous_override.read_text(encoding="utf-8"),
                         '{"services":{"backend":{"volumes":["/old:/app/app.jar:ro"]}}}')
        current = json.loads((self.state / "current.json").read_text(encoding="utf-8"))
        self.assertEqual(current["release"], "1.11.2")
        self.assertFalse((self.state / "installed-plans" / self.plan["id"]).exists())
        self.assertFalse((self.state / "journal.json").exists())
        self.assertFalse((self.state / "execute.lock").exists())
        self.assertEqual(self.read_link(self.root / "current"), previous_link)
        audit = self.read_audit()
        self.assertEqual(audit["result"], "ROLLED_BACK")
        self.assertGreaterEqual(audit["timingsMs"]["rollback"], 0)

    def test_rollback_health_failure_retains_journal_maintenance_and_backup(self):
        operations = FakeOperations()
        executor = shared_worker.SharedExecutor(self.config, self.plan, operations, self.now)
        with patch.object(operations, "health", side_effect=RuntimeError("health failed")) as health:
            with self.assertRaisesRegex(RuntimeError, "rollback did not complete"):
                executor.execute()
        self.assertEqual(health.call_count, 2)
        self.assertTrue(executor.journal.is_file())
        self.assertTrue(executor.maintenance.is_file())
        self.assertTrue((self.state / "backups" / self.plan["id"] / "tenant-tenant-a.sql").is_file())
        self.assertFalse(executor.lock_path.exists())
        audit = self.read_audit()
        self.assertEqual(audit["result"], "FAILED")
        self.assertGreaterEqual(audit["timingsMs"]["rollback"], 0)

    def test_execution_refuses_outside_window_and_does_not_backup(self):
        future = self.make_plan(maintenanceWindow={
            "startAt": (self.now + timedelta(hours=1)).isoformat(),
            "endAt": (self.now + timedelta(hours=2)).isoformat(),
        })
        operations = FakeOperations()
        with self.assertRaises(ValueError):
            shared_worker.SharedExecutor(self.config, future, operations, self.now).execute()
        self.assertFalse(any(call[0] == "backup" for call in operations.calls))
        self.assertFalse((self.state / "execute.lock").exists())

    def test_recover_restores_journal_and_removes_release(self):
        override = self.root / "compose.shared-release.json"
        override.write_text('{"services":{}}', encoding="utf-8")
        current = self.state / "current.json"
        current.write_text(json.dumps({"release": "1.11.3", "components": {"backend": "1.11.3"}}), encoding="utf-8")
        release_dir = self.root / "releases" / self.plan["id"]
        release_dir.mkdir(parents=True)
        journal = {
            "id": self.plan["id"],
            "previousOverride": '{"services":{}}',
            "previousCurrent": json.dumps({"release": "1.11.2", "components": {"backend": "1.11.2"}}),
            "services": ["backend", "admin", "h5"],
            "versionsBefore": {"backend": "1.11.2", "admin": "1.11.2", "h5": "1.11.2"},
            "previousCurrentLink": str(self.root / "releases" / "previous"),
            "releaseDir": str(release_dir),
            "serviceGroupId": "delta-shared-prod",
            "targetVersion": "1.11.3",
            "healthHost": "tenant-a.example.test",
        }
        (self.state / "journal.json").write_text(json.dumps(journal), encoding="utf-8")
        os.symlink(str(release_dir), str(self.root / "current"), target_is_directory=True)
        operations = FakeOperations()
        result = shared_worker.recover(self.config, operations, self.now)
        self.assertEqual(result["status"], "ROLLED_BACK")
        self.assertFalse((self.state / "journal.json").exists())
        self.assertFalse(release_dir.exists())
        self.assertEqual(json.loads(current.read_text(encoding="utf-8"))["release"], "1.11.2")
        self.assertEqual(self.read_link(self.root / "current"), str(self.root / "releases" / "previous"))
        self.assertFalse((self.state / "execute.lock").exists())

    def test_zip_traversal_and_wrong_backend_version_are_rejected(self):
        operations = shared_worker.DockerOperations({})
        bad = self.root / "bad.zip"
        with zipfile.ZipFile(bad, "w") as zipped:
            zipped.writestr("../escaped", "x")
        with self.assertRaises(ValueError):
            operations.extract(bad, self.root / "bad", {"component": "admin", "version": "1.11.3"})
        wrong = self.root / "wrong.zip"
        with zipfile.ZipFile(wrong, "w") as zipped:
            zipped.writestr("delta-admin.jar", b"not a jar")
        with self.assertRaises(Exception):
            operations.extract(wrong, self.root / "wrong", {"component": "backend", "version": "1.11.3"})

    def test_real_backend_and_frontend_archive_shapes_are_accepted(self):
        operations = shared_worker.DockerOperations({})
        inner = self.root / "inner.jar"
        with zipfile.ZipFile(inner, "w") as zipped:
            zipped.writestr("BOOT-INF/classes/platform/catalog.json", json.dumps({
                "version": "1.11.3", "schemaVersion": 1}))
        backend = self.root / "backend.zip"
        with zipfile.ZipFile(backend, "w") as zipped:
            zipped.write(inner, "delta-admin.jar")
        operations.extract(backend, self.root / "backend-out", {"component": "backend", "version": "1.11.3"})
        self.assertTrue((self.root / "backend-out" / "delta-admin.jar").is_file())

        frontend = self.root / "admin.zip"
        with zipfile.ZipFile(frontend, "w") as zipped:
            zipped.writestr("index.html", "<html></html>")
            zipped.writestr("cangshuo-version.json", json.dumps({"component": "admin", "version": "1.11.3"}))
        operations.extract(frontend, self.root / "admin-out", {"component": "admin", "version": "1.11.3"})
        self.assertTrue((self.root / "admin-out" / "index.html").is_file())

    def test_compose_files_are_resolved_against_project_directory(self):
        operations = shared_worker.DockerOperations({
            "compose_directory": "/srv/delta-shared",
            "compose_files": ["compose.yml"],
            "env_file": "/srv/delta-shared/.env",
        })
        calls = []
        operations.run = lambda args, timeout=180: calls.append(args)
        operations.compose_up(["backend"], Path("/srv/delta-shared/override.json"))
        self.assertIn("-f", calls[0])
        self.assertIn(str(Path("/srv/delta-shared/compose.yml")), calls[0])
        self.assertIn(str(Path("/srv/delta-shared/override.json")), calls[0])

    def test_lock_maintenance_and_disabled_execution_are_rejected(self):
        (self.state / "execute.lock").write_text("other", encoding="utf-8")
        with self.assertRaises(RuntimeError):
            shared_worker.SharedExecutor(self.config, self.plan, FakeOperations(), self.now).execute()
        (self.state / "execute.lock").unlink()
        (self.state / "maintenance").write_text("hold\n", encoding="utf-8")
        with self.assertRaises(RuntimeError):
            shared_worker.SharedExecutor(self.config, self.plan, FakeOperations(), self.now).execute()
        (self.state / "maintenance").unlink()
        disabled = dict(self.config, allow_execute=False)
        with self.assertRaises(PermissionError):
            shared_worker.SharedExecutor(disabled, self.plan, FakeOperations(), self.now).execute()

    def test_recovery_does_not_delete_a_release_outside_the_managed_root(self):
        outside = self.root.parent / ("outside-" + self.plan["id"])
        outside.mkdir()
        marker = outside / "keep.txt"
        marker.write_text("keep", encoding="utf-8")
        journal = {
            "id": self.plan["id"],
            "previousOverride": None,
            "previousCurrent": None,
            "services": ["backend", "admin", "h5"],
            "versionsBefore": {},
            "releaseDir": str(outside),
            "serviceGroupId": "delta-shared-prod",
            "targetVersion": "1.11.3",
            "healthHost": "tenant-a.example.test",
        }
        (self.state / "journal.json").write_text(json.dumps(journal), encoding="utf-8")
        result = shared_worker.recover(self.config, FakeOperations(), self.now)
        self.assertEqual(result["status"], "ROLLED_BACK")
        self.assertTrue(marker.exists())

    def test_recovery_rejects_invalid_journal_identifier(self):
        journal = {"id": "../../escape", "previousOverride": None, "previousCurrent": None}
        (self.state / "journal.json").write_text(json.dumps(journal), encoding="utf-8")
        with self.assertRaises(ValueError):
            shared_worker.recover(self.config, FakeOperations(), self.now)


if __name__ == "__main__":
    unittest.main()
