import json
import os
import tempfile
import unittest
import uuid
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).parent))
import shared_release
import shared_worker


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
        audit = (self.state / "audit.jsonl").read_text(encoding="utf-8")
        self.assertIn('"result": "SUCCEEDED"', audit)

    def test_health_failure_restores_previous_override_and_current(self):
        previous_override = self.root / "compose.shared-release.json"
        previous_override.write_text('{"services":{"backend":{"volumes":["/old:/app/app.jar:ro"]}}}', encoding="utf-8")
        (self.state / "current.json").write_text(json.dumps({
            "release": "1.11.2", "components": {"backend": "1.11.2", "admin": "1.11.2", "h5": "1.11.2"}}), encoding="utf-8")
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
            "releaseDir": str(release_dir),
            "serviceGroupId": "delta-shared-prod",
            "targetVersion": "1.11.3",
            "healthHost": "tenant-a.example.test",
        }
        (self.state / "journal.json").write_text(json.dumps(journal), encoding="utf-8")
        operations = FakeOperations()
        result = shared_worker.recover(self.config, operations, self.now)
        self.assertEqual(result["status"], "ROLLED_BACK")
        self.assertFalse((self.state / "journal.json").exists())
        self.assertFalse(release_dir.exists())
        self.assertEqual(json.loads(current.read_text(encoding="utf-8"))["release"], "1.11.2")
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
