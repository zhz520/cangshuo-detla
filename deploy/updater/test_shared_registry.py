import json
import tempfile
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import shared_registry


class SharedRegistryTests(unittest.TestCase):
    def registry(self):
        return {"tenants": {"tenant-a": {
            "active": True,
            "hosts": ["shop-a.example.com"],
            "jdbcUrl": "jdbc:mysql://mysql:3306/delta_a?useSSL=false",
            "username": "delta_a",
            "password": "secret-a",
            "platformCustomerId": "customer-a"
        }}}

    def test_valid_registry_and_secret_redaction(self):
        rows = shared_registry.validate_registry(self.registry())
        self.assertEqual(rows[0]["database"], "delta_a")
        rendered = shared_registry.redacted(self.registry())
        self.assertEqual(rendered["tenants"]["tenant-a"]["password"], "***")
        self.assertNotIn("secret-a", json.dumps(rendered))

    def test_duplicate_hosts_and_active_missing_database_are_rejected(self):
        duplicate = self.registry()
        duplicate["tenants"]["tenant-b"] = {
            "active": True, "hosts": ["shop-a.example.com"],
            "jdbcUrl": "jdbc:mysql://mysql:3306/delta_b", "username": "delta_b",
            "password": "secret-b", "platformCustomerId": "customer-b"
        }
        with self.assertRaises(ValueError):
            shared_registry.validate_registry(duplicate)
        missing = self.registry()
        missing["tenants"]["tenant-a"]["jdbcUrl"] = ""
        with self.assertRaises(ValueError):
            shared_registry.validate_registry(missing)

    def test_sql_output_uses_database_identifier_without_password_value(self):
        tenant = shared_registry.validate_registry(self.registry())[0]
        sql = shared_registry.sql_for(tenant)
        self.assertIn("CREATE DATABASE IF NOT EXISTS `delta_a`", sql)
        self.assertIn("<set-a-random-password>", sql)
        self.assertNotIn("secret-a", sql)


if __name__ == "__main__":
    unittest.main()
