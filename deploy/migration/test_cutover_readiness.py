import argparse
import hashlib
import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
import cutover_readiness


class CutoverReadinessTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("TEMP"))
        self.root = Path(self.temporary.name)
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()
        self.now = datetime.now(timezone.utc)
        assets = []
        for component in ("backend", "admin", "h5"):
            name = "cangshuo-" + component + ".zip"
            payload = (component + "-candidate").encode()
            (self.artifacts / name).write_bytes(payload)
            assets.append({"component": component, "name": name, "size": len(payload),
                           "sha256": hashlib.sha256(payload).hexdigest()})
        self.manifest = self.root / "manifest.json"
        self.manifest.write_text(json.dumps({"schemaVersion": 1, "version": "1.12.0", "assets": assets}), encoding="utf-8")
        self.file_report = self.root / "file.json"
        self.file_report.write_text(json.dumps({"identical": True, "missing": [], "changed": [], "extra": [], "sourceFiles": 510}), encoding="utf-8")
        self.finance_report = self.root / "finance.json"
        self.finance_report.write_text(json.dumps({"passed": True, "totalViolations": 0, "checks": [{"name": "wallet_negative", "status": "ok"}]}), encoding="utf-8")
        self.staging_report = self.root / "staging.json"
        self.staging_report.write_text(json.dumps({"passed": True, "version": "1.12.0"}), encoding="utf-8")
        self.registry = self.root / "registry.json"
        self.registry.write_text(json.dumps({"tenants": {"742": {
            "active": True, "hosts": ["jgsapi.kk520yy.com"],
            "jdbcUrl": "jdbc:mysql://mysql:3306/client742_game",
            "username": "delta_742", "password": "test-only",
            "platformCustomerId": "742"}}}), encoding="utf-8")
        self.state_dir = self.root / "state"
        tenant_dir = self.state_dir / "742"
        tenant_dir.mkdir(parents=True)
        (tenant_dir / "state.json").write_text(json.dumps({
            "version": 1, "tenant": "742", "phase": "accepted", "revision": 8,
            "changeTicket": "CHG-2026-001", "routeGeneration": 0}), encoding="utf-8")
        self.args = argparse.Namespace(
            tenant="742", release_manifest=str(self.manifest), artifacts_dir=str(self.artifacts),
            state_dir=str(self.state_dir), registry=str(self.registry),
            file_report=str(self.file_report), finance_report=str(self.finance_report),
            staging_report=str(self.staging_report),
            maintenance_start=(self.now - timedelta(minutes=5)).isoformat(),
            maintenance_end=(self.now + timedelta(minutes=30)).isoformat(),
            customer_accepted=True, now=self.now.isoformat(), report=None, fail_if_not_ready=False)

    def tearDown(self):
        self.temporary.cleanup()

    def test_complete_evidence_is_ready(self):
        result = cutover_readiness.audit(self.args)
        self.assertTrue(result["ready"])
        self.assertEqual(result["releaseVersion"], "1.12.0")
        self.assertEqual(result["blocking"], [])

    def test_artifact_hash_mismatch_blocks_readiness(self):
        (self.artifacts / "cangshuo-admin.zip").write_bytes(b"tampered")
        result = cutover_readiness.audit(self.args)
        self.assertFalse(result["ready"])
        self.assertIn("release", result["blocking"])

    def test_file_finance_state_window_and_customer_blocks_are_reported(self):
        self.file_report.write_text(json.dumps({"identical": False, "missing": ["a"], "changed": [], "extra": []}), encoding="utf-8")
        self.finance_report.write_text(json.dumps({"passed": False, "totalViolations": 2, "checks": []}), encoding="utf-8")
        state_path = self.state_dir / "742" / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["phase"] = "planned"
        state_path.write_text(json.dumps(state), encoding="utf-8")
        args = argparse.Namespace(**vars(self.args))
        args.maintenance_start = (self.now + timedelta(hours=1)).isoformat()
        args.maintenance_end = (self.now + timedelta(hours=2)).isoformat()
        args.customer_accepted = False
        result = cutover_readiness.audit(args)
        self.assertFalse(result["ready"])
        for name in ("file_delta", "finance", "tenant_state", "maintenance_window", "customer_acceptance"):
            self.assertIn(name, result["blocking"])

    def test_missing_reports_and_wrong_tenant_are_not_ready(self):
        args = argparse.Namespace(**vars(self.args))
        args.file_report = None
        args.finance_report = None
        args.staging_report = None
        args.tenant = "other"
        result = cutover_readiness.audit(args)
        self.assertFalse(result["ready"])
        self.assertIn("registry", result["blocking"])
        self.assertIn("tenant_state", result["blocking"])
        self.assertIn("file_delta", result["blocking"])
        self.assertIn("finance", result["blocking"])


if __name__ == "__main__":
    unittest.main()
