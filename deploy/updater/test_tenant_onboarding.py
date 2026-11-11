import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
import shared_registry
import tenant_onboarding


class TenantOnboardingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.export_path = self.root / "runtime-tenants.json"
        self.credentials_path = self.root / "tenant-credentials.json"
        self.target = self.root / "tenant-registry.json"
        self.instance_id = "81fa9bd2-2a1e-40ef-8b7e-501b79c3ddf9"

    def tearDown(self):
        self.temporary.cleanup()

    def export_document(self, tenants=None):
        if tenants is None:
            tenants = [{
                "tenantId": "jax",
                "name": "JAX",
                "customerId": "delta",
                "projectId": "delta-project",
                "productId": "detla",
                "platformCustomerId": "delta",
                "platformInstanceId": self.instance_id,
                "hosts": ["jax738.idcgov.cn", "jaxapi738.idcgov.cn"],
                "wildcardDomains": [],
                "maintenanceUntil": "2027-12-01T00:00:00Z",
                "active": True,
            }]
        return {"formatVersion": 1, "generatedAt": "2026-11-11T00:00:00Z",
                "tenants": tenants}

    def credentials_document(self, tenants=None):
        if tenants is None:
            tenants = [{
                "tenantId": "jax",
                "platformInstanceId": self.instance_id,
                "jdbcUrl": "jdbc:mysql://mysql:3306/delta_game?useSSL=false",
                "username": "delta_game",
                "password": "secret-a",
                "maximumPoolSize": 8,
                "minimumIdle": 0,
                "connectionTimeoutMs": 5000,
                "miniappEnabled": True,
                "miniappAppId": "wx-test-app",
                "miniappSecret": "miniapp-secret",
            }]
        return {"formatVersion": 1, "tenants": tenants}

    def write_json(self, path, document, private=False):
        path.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
        if private and os.name == "posix":
            os.chmod(path, 0o600)
        return path

    def merge(self, export=None, credentials=None, existing=None):
        return tenant_onboarding.merge_documents(
            export or self.export_document(),
            credentials or self.credentials_document(),
            existing,
        )

    def run_cli(self, apply=False, audit=None, actor=None):
        arguments = [
            "merge",
            "--authorization-export", str(self.export_path),
            "--credentials", str(self.credentials_path),
            "--output", str(self.target),
            "--allowed-root", str(self.root),
        ]
        if apply:
            arguments.append("--apply")
        if audit:
            arguments.extend(["--audit-log", str(audit)])
        if actor:
            arguments.extend(["--actor", actor])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = tenant_onboarding.main(arguments)
        return code, json.loads(output.getvalue())

    def prepare_cli_files(self, export=None, credentials=None):
        self.write_json(self.export_path, export or self.export_document())
        self.write_json(
            self.credentials_path,
            credentials or self.credentials_document(),
            private=True,
        )

    def test_explicit_runtime_tenant_id_and_instance_uuid_are_merged(self):
        merged, diff = self.merge()
        self.assertEqual(list(merged["tenants"]), ["jax"])
        entry = merged["tenants"]["jax"]
        self.assertEqual(entry["platformInstanceId"], self.instance_id)
        self.assertEqual(entry["platformCustomerId"], "delta")
        self.assertEqual(entry["hosts"], ["jax738.idcgov.cn", "jaxapi738.idcgov.cn"])
        rows = shared_registry.validate_registry(merged)
        self.assertEqual(rows[0]["id"], "jax")
        self.assertEqual(rows[0]["database"], "delta_game")
        self.assertEqual(diff["addedTenantIds"], ["jax"])

    def test_uuid_is_not_used_as_runtime_tenant_id(self):
        for tenant_id in (str(uuid.uuid4()), uuid.uuid4().hex):
            export = self.export_document([{
                "tenantId": tenant_id,
                "platformInstanceId": self.instance_id,
                "hosts": ["jax738.idcgov.cn"],
                "active": True,
            }])
            credentials = self.credentials_document([{
                "tenantId": tenant_id,
                "platformInstanceId": self.instance_id,
                "jdbcUrl": "jdbc:mysql://mysql:3306/delta_game",
                "username": "delta_game",
                "password": "secret-a",
            }])
            with self.assertRaisesRegex(ValueError, "runtime id"):
                tenant_onboarding.merge_documents(export, credentials)

    def test_wildcard_domains_are_rejected_instead_of_silently_dropped(self):
        row = dict(self.export_document()["tenants"][0])
        row["wildcardDomains"] = ["*.example.com"]
        with self.assertRaisesRegex(ValueError, "wildcardDomains"):
            tenant_onboarding.validate_export(self.export_document([row]))

    def test_unknown_tenant_and_instance_mismatch_are_rejected(self):
        unknown = self.credentials_document([{
            "tenantId": "other",
            "jdbcUrl": "jdbc:mysql://mysql:3306/delta_other",
            "username": "delta_other",
            "password": "secret-other",
        }])
        with self.assertRaisesRegex(ValueError, "absent from authorization export"):
            self.merge(credentials=unknown)

        mismatch = self.credentials_document([{
            "tenantId": "jax",
            "platformInstanceId": str(uuid.uuid4()),
            "jdbcUrl": "jdbc:mysql://mysql:3306/delta_game",
            "username": "delta_game",
            "password": "secret-a",
        }])
        with self.assertRaisesRegex(ValueError, "platformInstanceId does not match"):
            self.merge(credentials=mismatch)

    def test_export_credentials_and_duplicate_domains_are_rejected(self):
        exposed = self.export_document()
        exposed["tenants"][0]["password"] = "must-not-be-accepted"
        with self.assertRaisesRegex(ValueError, "must not contain credential") as raised:
            self.merge(export=exposed)
        self.assertNotIn("must-not-be-accepted", str(raised.exception))

        duplicate = self.export_document([
            {
                "tenantId": "jax",
                "platformInstanceId": self.instance_id,
                "hosts": ["jax738.idcgov.cn"],
                "active": True,
            },
            {
                "tenantId": "742",
                "platformInstanceId": str(uuid.uuid4()),
                "hosts": ["jax738.idcgov.cn"],
                "active": True,
            },
        ])
        with self.assertRaisesRegex(ValueError, "host belongs to multiple tenants"):
            self.merge(export=duplicate)

    def test_active_tenant_requires_credentials_but_inactive_tenant_does_not(self):
        empty = self.credentials_document([])
        with self.assertRaisesRegex(ValueError, "active tenant requires"):
            self.merge(credentials=empty)

        miniapp_only = self.credentials_document([{
            "tenantId": "jax",
            "miniappEnabled": False,
        }])
        with self.assertRaisesRegex(ValueError, "local database credentials"):
            self.merge(credentials=miniapp_only)

        inactive = self.export_document([{
            "tenantId": "jax",
            "hosts": ["jax738.idcgov.cn"],
            "active": False,
        }])
        merged, unused = tenant_onboarding.merge_documents(inactive, empty)
        self.assertFalse(merged["tenants"]["jax"]["active"])
        self.assertNotIn("jdbcUrl", merged["tenants"]["jax"])
        shared_registry.validate_registry(merged)

    def test_existing_unrelated_tenant_is_preserved(self):
        existing = {"tenants": {
            "old": {
                "active": True,
                "hosts": ["old.example.com"],
                "jdbcUrl": "jdbc:mysql://mysql:3306/delta_old",
                "username": "delta_old",
                "password": "old-secret",
                "platformCustomerId": "old-customer",
                "platformInstanceId": str(uuid.uuid4()),
            }
        }}
        merged, diff = self.merge(existing=existing)
        self.assertEqual(sorted(merged["tenants"]), ["jax", "old"])
        self.assertEqual(diff["retainedTenantIds"], ["old"])
        shared_registry.validate_registry(merged)

    def test_existing_platform_mismatch_is_rejected(self):
        existing = {"tenants": {"jax": {
            "active": True,
            "hosts": ["jax738.idcgov.cn"],
            "jdbcUrl": "jdbc:mysql://mysql:3306/delta_game",
            "username": "delta_game",
            "password": "secret-a",
            "platformCustomerId": "delta",
            "platformInstanceId": str(uuid.uuid4()),
        }}}
        with self.assertRaisesRegex(ValueError, "conflicts with existing registry"):
            self.merge(existing=existing)

    def test_dry_run_does_not_create_target_and_apply_is_idempotent(self):
        self.prepare_cli_files()
        code, summary = self.run_cli()
        self.assertEqual(code, 0)
        self.assertFalse(self.target.exists())
        self.assertEqual(summary["mode"], "dry-run")
        self.assertEqual(summary["action"], "create")

        code, first = self.run_cli(apply=True)
        self.assertEqual(code, 0)
        self.assertEqual(first["result"], "APPLIED")
        content = self.target.read_bytes()
        if os.name == "posix":
            self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o600)

        code, second = self.run_cli(apply=True)
        self.assertEqual(code, 0)
        self.assertEqual(second["result"], "UNCHANGED")
        self.assertEqual(second["action"], "unchanged")
        self.assertEqual(self.target.read_bytes(), content)

    def test_atomic_write_keeps_old_file_if_replace_fails(self):
        self.target.write_text("old-content", encoding="utf-8")
        with mock.patch.object(tenant_onboarding.os, "replace",
                               side_effect=OSError("simulated replace failure")):
            with self.assertRaises(OSError):
                tenant_onboarding.atomic_write_json(
                    self.target, {"tenants": {"jax": {"active": False}}}
                )
        self.assertEqual(self.target.read_text(encoding="utf-8"), "old-content")
        leftovers = list(self.root.glob(".tenant-registry.json.*.tmp"))
        self.assertEqual(leftovers, [])

    def test_output_rejects_traversal_symlink_and_permissions_apply(self):
        self.prepare_cli_files()
        with self.assertRaises(ValueError):
            tenant_onboarding.safe_output_path(
                str(self.root / ".." / "outside.json"), str(self.root)
            )
        victim = self.root / "victim.json"
        victim.write_text("{}", encoding="utf-8")
        link = self.root / "registry-link.json"
        try:
            link.symlink_to(victim)
        except (OSError, NotImplementedError):
            link = None
        if link is not None:
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                tenant_onboarding.safe_output_path(str(link), str(self.root))
            link.unlink()

        code, unused = self.run_cli(apply=True)
        self.assertEqual(code, 0)
        if os.name == "posix":
            self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o600)

    def test_audit_log_is_redacted_and_records_hashes(self):
        self.prepare_cli_files()
        audit = self.root / "onboarding-audit.jsonl"
        code, summary = self.run_cli(apply=True, audit=audit, actor="ops-user")
        self.assertEqual(code, 0)
        self.assertEqual(summary["result"], "APPLIED")
        raw = audit.read_text(encoding="utf-8")
        self.assertNotIn("secret-a", raw)
        self.assertNotIn("miniapp-secret", raw)
        record = json.loads(raw)
        self.assertEqual(record["actor"], "ops-user")
        self.assertEqual(len(record["inputSha256"]), 64)
        self.assertEqual(record["tenantIds"], ["jax"])
        self.assertEqual(record["result"], "APPLIED")
        if os.name == "posix":
            self.assertEqual(stat.S_IMODE(audit.stat().st_mode), 0o600)

    def test_sql_template_uses_placeholder_and_never_prints_password(self):
        merged, unused = self.merge()
        sql = tenant_onboarding.sql_template(merged, "jax")
        self.assertIn("CREATE DATABASE IF NOT EXISTS", sql)
        self.assertIn("delta_game", sql)
        self.assertIn("<set-a-random-password>", sql)
        self.assertNotIn("secret-a", sql)

    def test_duplicate_json_keys_size_and_credentials_permissions_are_rejected(self):
        duplicate = self.root / "duplicate.json"
        duplicate.write_text(
            '{"formatVersion":1,"tenants":[],"tenants":[]}',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            tenant_onboarding.load_json_limited(duplicate, "duplicate input")

        bad_type = self.export_document()
        bad_type["tenants"][0]["active"] = "true"
        with self.assertRaisesRegex(ValueError, "must be boolean"):
            self.merge(export=bad_type)

        large = self.root / "large.json"
        large.write_bytes(b" " * (tenant_onboarding.MAX_JSON_BYTES + 1))
        with self.assertRaisesRegex(ValueError, "too large"):
            tenant_onboarding.load_json_limited(large, "large input")

        if os.name == "posix":
            self.write_json(self.credentials_path, self.credentials_document())
            os.chmod(self.credentials_path, 0o644)
            with self.assertRaisesRegex(ValueError, "0600"):
                tenant_onboarding.load_json_limited(
                    self.credentials_path, "credential file", private=True
                )


if __name__ == "__main__":
    unittest.main()
