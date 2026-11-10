import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
import tenant_state


class TenantStateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("TEMP"))
        self.state_dir = Path(self.temporary.name) / "state"
        self.tenant = "tenant-a"
        self.now = datetime.now(timezone.utc)
        tenant_state.initialize(str(self.state_dir), self.tenant, "CHG-2026-001", self.now)

    def tearDown(self):
        self.temporary.cleanup()

    def move(self, phase, evidence):
        return tenant_state.transition(str(self.state_dir), self.tenant, phase, evidence, self.now)

    def to_route_switched(self):
        self.move("backup_verified", {"backupSha256": "a" * 64, "backupBytes": 100, "restoreVerified": True})
        self.move("target_ready", {"targetDatabase": "delta_a", "registryValidated": True, "syntheticStartVerified": True})
        self.move("maintenance_started", {
            "windowStart": (self.now - timedelta(minutes=5)).isoformat(),
            "windowEnd": (self.now + timedelta(minutes=30)).isoformat(),
            "oldWritesStopped": True, "callbackReceiverVerified": True,
        })
        self.move("data_imported", {"sourceDatabase": "client_a", "importReportSha256": "b" * 64, "rowCountsVerified": True})
        self.move("files_synced", {"fileReportSha256": "c" * 64, "fileReportIdentical": True})
        self.move("finance_verified", {"financeReportSha256": "d" * 64, "financeReportPassed": True})
        self.move("accepted", {
            "customerAccepted": True, "acceptanceEvidenceSha256": "e" * 64,
            "acceptanceAt": (self.now - timedelta(minutes=1)).isoformat(),
        })
        return self.move("route_switched", {"routeGeneration": 1, "dnsVerified": True, "healthVerified": True})

    def test_happy_path_reaches_old_stopped_with_audit(self):
        self.to_route_switched()
        self.move("observed", {"observationEndsAt": (self.now - timedelta(minutes=1)).isoformat(), "healthVerified": True})
        state = self.move("old_stopped", {"oldServicesStopped": True, "backupRetained": True})
        self.assertEqual(state["phase"], "old_stopped")
        self.assertEqual(state["revision"], 10)
        self.assertEqual(state["routeGeneration"], 1)
        audit = (self.state_dir / self.tenant / "audit.jsonl").read_text(encoding="utf-8")
        self.assertEqual(len(audit.strip().splitlines()), 11)
        self.assertIn('"to": "old_stopped"', audit)

    def test_skipping_phase_is_rejected(self):
        with self.assertRaises(ValueError):
            self.move("data_imported", {"sourceDatabase": "client_a", "importReportSha256": "b" * 64, "rowCountsVerified": True})

    def test_missing_or_unsafe_evidence_is_rejected(self):
        with self.assertRaises(ValueError):
            self.move("backup_verified", {"backupSha256": "short", "backupBytes": 100, "restoreVerified": True})
        with self.assertRaises(ValueError):
            self.move("backup_verified", {"backupSha256": "a" * 64, "backupBytes": 100, "restoreVerified": True, "password": "secret"})
        with self.assertRaises(ValueError):
            self.move("backup_verified", {"backupSha256": "a" * 64, "backupBytes": 100})

    def test_maintenance_window_must_be_open(self):
        self.move("backup_verified", {"backupSha256": "a" * 64, "backupBytes": 100, "restoreVerified": True})
        self.move("target_ready", {"targetDatabase": "delta_a", "registryValidated": True, "syntheticStartVerified": True})
        with self.assertRaises(ValueError):
            self.move("maintenance_started", {
                "windowStart": (self.now + timedelta(hours=1)).isoformat(),
                "windowEnd": (self.now + timedelta(hours=2)).isoformat(),
                "oldWritesStopped": True, "callbackReceiverVerified": True,
            })

    def test_rollback_from_route_switched_and_terminal_state(self):
        self.to_route_switched()
        state = self.move("rolled_back", {"reason": "post-switch health failure", "rollbackVerified": True})
        self.assertEqual(state["phase"], "rolled_back")
        with self.assertRaises(ValueError):
            self.move("route_switched", {"routeGeneration": 2, "dnsVerified": True, "healthVerified": True})

    def test_duplicate_init_lock_and_listing(self):
        with self.assertRaises(ValueError):
            tenant_state.initialize(str(self.state_dir), self.tenant, "CHG-2026-002", self.now)
        lock = self.state_dir / self.tenant / "transition.lock"
        lock.write_text("busy", encoding="utf-8")
        with self.assertRaises(RuntimeError):
            self.move("backup_verified", {"backupSha256": "a" * 64, "backupBytes": 100, "restoreVerified": True})
        lock.unlink()
        self.assertEqual(tenant_state.list_tenants(str(self.state_dir)), [self.tenant])

    def test_state_and_audit_files_are_private(self):
        state = self.state_dir / self.tenant / "state.json"
        audit = self.state_dir / self.tenant / "audit.jsonl"
        self.assertTrue(state.exists())
        self.assertTrue(audit.exists())
        if os.name != "nt":
            self.assertEqual(state.stat().st_mode & 0o777, 0o600)
            self.assertEqual(audit.stat().st_mode & 0o777, 0o600)
            self.assertEqual((self.state_dir / self.tenant).stat().st_mode & 0o777, 0o700)


if __name__ == "__main__":
    unittest.main()
