import json
import os
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
import financial_reconciliation


class FinancialReconciliationTests(unittest.TestCase):
    def test_database_identifier_and_container_are_validated(self):
        with self.assertRaises(ValueError):
            financial_reconciliation.audit("mysql", "delta; DROP DATABASE delta")
        with self.assertRaises(ValueError):
            financial_reconciliation.audit("mysql container", "delta")

    def test_all_zero_counts_pass(self):
        result = financial_reconciliation.audit("mysql", "delta", lambda name, sql: "0\n")
        self.assertTrue(result["passed"])
        self.assertEqual(result["totalViolations"], 0)
        self.assertEqual(len(result["checks"]), 12)

    def test_violation_and_query_error_are_reported_without_identifiers(self):
        def runner(name, sql):
            if name == "wallet_negative":
                return "2\n"
            if name == "transaction_continuity":
                raise RuntimeError("table missing")
            return "0\n"

        result = financial_reconciliation.audit("mysql", "delta", runner)
        self.assertFalse(result["passed"])
        self.assertEqual(result["totalViolations"], 2)
        wallet = next(row for row in result["checks"] if row["name"] == "wallet_negative")
        self.assertEqual(wallet["violations"], 2)
        continuity = next(row for row in result["checks"] if row["name"] == "transaction_continuity")
        self.assertEqual(continuity["status"], "error")
        self.assertNotIn("table missing", json.dumps(result))

    def test_report_is_private_and_contains_no_query_text(self):
        temporary = tempfile.TemporaryDirectory(dir=os.environ.get("TEMP"))
        try:
            path = Path(temporary.name) / "finance.json"
            financial_reconciliation.write_report(
                financial_reconciliation.audit("mysql", "delta", lambda name, sql: "0\n"), path)
            text = path.read_text(encoding="utf-8")
            self.assertIn('"passed": true', text)
            self.assertNotIn("SELECT", text)
            if os.name != "nt":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        finally:
            temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
