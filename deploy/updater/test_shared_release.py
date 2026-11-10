import base64
import json
import os
import tempfile
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).parent))
import shared_release


class SharedReleaseTests(unittest.TestCase):
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
        self.config_path = self.root / "config.json"
        self.config = {
            "service_group_id": "delta-shared-prod",
            "root": str(self.root),
            "state_dir": str(self.state),
            "compose_directory": str(self.root),
            "compose_files": ["docker-compose.shared.yml"],
            "env_file": str(self.root / ".env"),
            "mysql_container": "delta-shared-mysql",
            "containers": {"backend": "delta-shared-backend", "admin": "delta-shared-admin", "h5": "delta-shared-h5"},
            "registry_file": str(self.registry),
            "backup_paths": [str(self.root / "data")],
            "repository": "owner/cangshuo-detla",
            "public_key_path": str(self.public_key),
            "allow_execute": True,
        }
        self.config_path.write_text(json.dumps(self.config), encoding="utf-8")
        self.now = datetime.now(timezone.utc)

    def tearDown(self):
        self.temporary.cleanup()

    def plan(self, **changes):
        version = changes.get("targetVersion", "1.11.3")
        tag = changes.get("releaseTag", "v" + version)
        base = {
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
        base.update(changes)
        return base

    def envelope(self, plan):
        payload = base64.urlsafe_b64encode(json.dumps(plan).encode()).decode().rstrip("=")
        signature = self.key.sign((shared_release.FORMAT + "." + payload).encode())
        return {"format": shared_release.FORMAT, "payload": payload,
                "signature": base64.urlsafe_b64encode(signature).decode().rstrip("=")}

    def dry(self, envelope=None, **kwargs):
        plan = shared_release.verify_envelope(envelope or self.envelope(self.plan()), self.config["public_key_path"])
        return shared_release.dry_run(plan, self.config, shared_release.active_tenants(self.config),
                                      shared_release.current_state(self.state), self.now, **kwargs)

    def test_valid_plan_produces_ordered_dry_run(self):
        result = self.dry()
        self.assertEqual(result["status"], "VALID")
        self.assertTrue(result["executeAllowed"])
        self.assertEqual(result["tenants"], ["tenant-a"])
        self.assertLess(result["steps"].index("backup-mysql"), result["steps"].index("start-backend"))
        self.assertLess(result["steps"].index("health-backend"), result["steps"].index("start-admin"))

    def test_signature_group_and_tampering_are_rejected(self):
        envelope = self.envelope(self.plan())
        envelope["signature"] = ("B" if envelope["signature"][0] != "B" else "C") + envelope["signature"][1:]
        with self.assertRaises(ValueError):
            shared_release.verify_envelope(envelope, self.config["public_key_path"])
        with self.assertRaises(ValueError):
            self.dry(self.envelope(self.plan(serviceGroupId="other-group")))

    def test_expired_schema_backup_and_tenant_mismatch_are_rejected(self):
        for changes in [
            {"expiresAt": (self.now - timedelta(minutes=1)).isoformat()},
            {"schemaVersion": 2},
            {"backupRequired": False},
            {"tenants": ["tenant-b"]},
            {"tenants": []},
        ]:
            with self.assertRaises(ValueError):
                self.dry(self.envelope(self.plan(**changes)))

    def test_window_and_component_downgrade_are_gated(self):
        future = self.plan(maintenanceWindow={
            "startAt": (self.now + timedelta(hours=1)).isoformat(),
            "endAt": (self.now + timedelta(hours=2)).isoformat(),
        })
        result = self.dry(self.envelope(future))
        self.assertFalse(result["executeAllowed"])
        self.assertIn("maintenance window is not open", result["blockingReasons"])
        with self.assertRaises(ValueError):
            self.dry(self.envelope(future), require_window=True)
        (self.state / "current.json").write_text(json.dumps({
            "release": "1.11.2", "components": {"backend": "1.11.4"}}), encoding="utf-8")
        with self.assertRaises(ValueError):
            self.dry(self.envelope(self.plan()))

    def test_package_url_component_and_installed_plan_are_rejected(self):
        bad_url = self.plan()
        bad_url["packages"][0]["url"] = "https://example.test/cangshuo-backend.zip"
        with self.assertRaises(ValueError):
            self.dry(self.envelope(bad_url))
        missing = self.plan()
        missing["packages"] = missing["packages"][:2]
        with self.assertRaises(ValueError):
            self.dry(self.envelope(missing))
        installed = self.plan()
        marker = self.state / "installed-plans" / installed["id"]
        marker.parent.mkdir(parents=True)
        marker.write_text("{}")
        with self.assertRaises(ValueError):
            self.dry(self.envelope(installed))

    def test_audit_records_are_redacted(self):
        plan = self.plan()
        audit = self.root / "audit.jsonl"
        shared_release.append_audit(audit, shared_release.audit_record(plan, "VALID", self.now))
        text = audit.read_text(encoding="utf-8")
        self.assertIn(plan["id"], text)
        self.assertNotIn("payload", text)
        self.assertNotIn("signature", text)
        self.assertNotIn("secret-a", text)


if __name__ == "__main__":
    unittest.main()
