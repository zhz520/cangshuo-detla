import json
import os
import tempfile
import unittest
import uuid
import zipfile
import base64
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
        tenant_dir = platform / "tenant-a"
        tenant_dir.mkdir(parents=True)
        version_file = tenant_dir / "component-versions.json"
        version_file.write_text('{"backend":"1.11.2","admin":"1.11.2","h5":"1.11.2","release":"1.11.2"}', encoding="utf-8")
        config = dict(self.config, platform_tenants_dir=str(platform))
        shared_worker.SharedExecutor(config, self.plan, FakeOperations(), self.now).execute()
        values = json.loads(version_file.read_text(encoding="utf-8"))
        self.assertEqual(values, {"backend": "1.11.3", "admin": "1.11.3", "h5": "1.11.3", "release": "1.11.3"})

    def test_private_github_asset_is_resolved_through_the_release_api(self):
        package = {
            "component": "backend",
            "version": "1.11.3",
            "size": 100,
            "sha256": "a" * 64,
            "url": "https://github.com/owner/cangshuo-detla/releases/download/v1.11.3/cangshuo-backend.zip",
        }
        response = mock_response({"assets": [{"id": 17, "name": "cangshuo-backend.zip", "size": 100}]})
        with patch("shared_worker.urllib.request.urlopen", return_value=response):
            url = shared_worker.github_asset_api_url(package, "owner/cangshuo-detla", "private-token")
        self.assertEqual(url, "https://api.github.com/repos/owner/cangshuo-detla/releases/assets/17")

    def test_private_github_asset_rejects_size_mismatch(self):
        package = {
            "component": "admin", "version": "1.11.3", "size": 100,
            "sha256": "a" * 64,
            "url": "https://github.com/owner/cangshuo-detla/releases/download/v1.11.3/cangshuo-admin.zip",
        }
        response = mock_response({"assets": [{"id": 18, "name": "cangshuo-admin.zip", "size": 99}]})
        with patch("shared_worker.urllib.request.urlopen", return_value=response):
            with self.assertRaisesRegex(ValueError, "does not match"):
                shared_worker.github_asset_api_url(package, "owner/cangshuo-detla", "private-token")

    def test_successful_execution_backs_up_stages_and_checks_in_order(self):
        operations = FakeOperations()
        shared_worker.SharedExecutor(self.config, self.plan, operations, self.now).execute()

        backup = next(call for call in operations.calls if call[0] == "backup")
        self.assertEqual(backup[1], ("tenant-a",))
        self.assertIn(("download", "backend"), operations.calls)
        self.assertIn(("extract", "admin"), operations.calls)
        compose = [call[1] for call in operations.calls if call[0] == "compose"]
        self.assertEqual(compose, [("backend",), ("admin",), ("h5",)])
        health = [call[1] for call in operations.calls if call[0] == "health"]
        self.assertEqual(health, ["backend", "admin", "h5"])
        current = json.loads((self.state / "current.json").read_text(encoding="utf-8"))
        self.assertEqual(current["release"], "1.11.3")
        self.assertTrue((self.state / "installed-plans" / self.plan["id"]).exists())
        self.assertFalse((self.state / "journal.json").exists())
        self.assertFalse((self.state / "execute.lock").exists())
        self.assertEqual(self.read_link(self.root / "current"), str(self.root / "releases" / self.plan["id"]))
        override = json.loads((self.root / "compose.shared-release.json").read_text(encoding="utf-8"))
        self.assertEqual(override["services"]["backend"]["volumes"][0].rsplit(":", 2)[0],
                         str(self.root / "releases" / self.plan["id"] / "backend" / "delta-admin.jar"))
        audit = (self.state / "audit.jsonl").read_text(encoding="utf-8")
        self.assertIn('"result": "SUCCEEDED"', audit)

    def test_health_failure_restores_previous_override_and_current(self):
        previous_override = self.root / "compose.shared-release.json"
        previous_override.write_text('{"services":{"backend":{"volumes":["/old:/app/app.jar:ro"]}}}', encoding="utf-8")
        (self.state / "current.json").write_text(json.dumps({
            "release": "1.11.2", "components": {"backend": "1.11.2", "admin": "1.11.2", "h5": "1.11.2"}}), encoding="utf-8")
        previous_link = str(self.root / "releases" / "previous")
        os.symlink(previous_link, str(self.root / "current"), target_is_directory=True)
        operations = FakeOperations(fail_health_once="admin")
        with self.assertRaises(RuntimeError):
            shared_worker.SharedExecutor(self.config, self.plan, operations, self.now).execute()

        self.assertEqual(previous_override.read_text(encoding="utf-8"),
                         '{"services":{"backend":{"volumes":["/old:/app/app.jar:ro"]}}}')
        current = json.loads((self.state / "current.json").read_text(encoding="utf-8"))
        self.assertEqual(current["release"], "1.11.2")
        self.assertFalse((self.state / "installed-plans" / self.plan["id"]).exists())
        self.assertFalse((self.state / "journal.json").exists())
        self.assertFalse((self.state / "execute.lock").exists())
        self.assertEqual(self.read_link(self.root / "current"), previous_link)
        audit = (self.state / "audit.jsonl").read_text(encoding="utf-8")
        self.assertIn('"result": "ROLLED_BACK"', audit)

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
