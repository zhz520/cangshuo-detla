import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "migration_registry.py"
spec = importlib.util.spec_from_file_location("migration_registry", SCRIPT)
registry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(registry)


class MigrationRegistryTest(unittest.TestCase):
    def test_collects_startup_apply_and_baseline_scripts_with_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "deploy" / "baota" / "migrations").mkdir(parents=True)
            (root / "delta-game" / "delta-admin" / "src" / "main" / "resources" / "db").mkdir(parents=True)
            (root / "deploy" / "baota" / "migrations" / "20260101-example.sql").write_text("ALTER TABLE t ADD COLUMN c INT;", encoding="utf-8")
            (root / "delta-game" / "delta-admin" / "src" / "main" / "resources" / "db" / "governance-v1.sql").write_text("CREATE TABLE t(id INT);", encoding="utf-8")
            (root / "delta_game.sql").write_text("CREATE TABLE base(id INT);", encoding="utf-8")
            result = registry.collect(root)
            self.assertEqual([row["source"] for row in result["migrations"]], ["APPLY", "BASELINE", "STARTUP"])
            self.assertTrue(all(len(row["sha256"]) == 64 for row in result["migrations"]))

    def test_duplicate_checksums_are_reported_not_silently_merged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "deploy" / "baota" / "migrations").mkdir(parents=True)
            (root / "delta-game" / "delta-admin" / "src" / "main" / "resources" / "db").mkdir(parents=True)
            sql = "CREATE TABLE t(id INT);"
            (root / "deploy" / "baota" / "migrations" / "20260101-a.sql").write_text(sql, encoding="utf-8")
            (root / "deploy" / "baota" / "migrations" / "20260101-b.sql").write_text(sql, encoding="utf-8")
            result = registry.collect(root)
            self.assertEqual(len(result["duplicateChecksums"]), 1)
            self.assertEqual(len(result["duplicateChecksums"][0]["files"]), 2)


if __name__ == "__main__":
    unittest.main()
