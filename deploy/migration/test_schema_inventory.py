import hashlib
import io
import json
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
import schema_inventory


BASELINE = Path("delta_game.sql")
BACKEND = Path("delta-game/delta-admin/src/main/resources/db/migration")
BAOTA = Path("deploy/baota/migrations")


class SchemaInventoryTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root / BACKEND).mkdir(parents=True)
        (self.root / BAOTA).mkdir(parents=True)
        self.put(BASELINE, b"SET @example = 1;\n")

    def put(self, relative, content):
        (self.root / relative).write_bytes(content)
        return relative.as_posix()

    def run_cli(self, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            status = schema_inventory.main(["--root", str(self.root), *args])
        return status, stdout.getvalue(), stderr.getvalue()

    def test_scope_versions_order_and_unversioned_file(self):
        ten = self.put(BACKEND / "V20261114_10__ten.sql", b"-- tenth")
        first = self.put(BACKEND / "V20261114__first.sql", b"-- first")
        two = self.put(BACKEND / "V20261114_2__two.sql", b"-- second")
        baota = self.put(BAOTA / "20261114-add-widget.sql", b"-- deployment")
        unknown = self.put(BAOTA / "unversioned.SQL", b"-- unknown")
        self.put(Path("delta_game_recharge_migration.sql"), b"-- outside scope")
        self.put(BACKEND.parent / "workbench.sql", b"-- startup helper")
        (self.root / BACKEND / "nested").mkdir()
        self.put(BACKEND / "nested" / "V20261115__outside.sql", b"-- outside scope")

        report = schema_inventory.inventory(self.root)
        self.assertEqual(
            [(row["phase"], row["source"], row["version"], row["path"]) for row in report["files"]],
            [
                ("initial-install", "baseline", None, BASELINE.as_posix()),
                ("upgrade", "backend", "V20261114", first),
                ("upgrade", "backend", "V20261114_2", two),
                ("upgrade", "backend", "V20261114_10", ten),
                ("upgrade", "baota", "20261114", baota),
                ("upgrade", "baota", None, unknown),
            ],
        )
        self.assertEqual(report["unversionedUpgradeFiles"], [unknown])
        self.assertEqual(report["repeatedVersions"], [])
        self.assertEqual(report["crossSourceNameMatches"], [])

    def test_version_repeats_exact_bytes_and_cross_source_names_are_independent(self):
        crlf = b"\xef\xbb\xbf-- synthetic payload\r\n"
        lf = crlf.replace(b"\r\n", b"\n")
        first = self.put(BACKEND / "V20261112__coupon_multi_claim.sql", crlf)
        second = self.put(BACKEND / "V20261112__marketing_display.sql", lf)
        same = self.put(BAOTA / "20261112-coupon-multi-claim.sql", crlf)
        changed = self.put(BAOTA / "20261112-marketing-display.sql", crlf)
        digest = hashlib.sha256(crlf).hexdigest()

        report = schema_inventory.inventory(self.root)
        self.assertEqual(
            report["repeatedVersions"],
            [
                {"source": "backend", "version": "V20261112", "paths": [first, second]},
                {"source": "baota", "version": "20261112", "paths": [same, changed]},
            ],
        )
        self.assertEqual(report["identicalFiles"], [{"sha256": digest, "paths": [first, same, changed]}])
        self.assertEqual(
            report["crossSourceNameMatches"],
            [
                {"filenameVersion": "20261112", "paths": [first, same], "allBytesIdentical": True},
                {"filenameVersion": "20261112", "paths": [second, changed], "allBytesIdentical": False},
            ],
        )
        self.assertEqual(report["files"][1]["sha256"], digest)
        self.assertEqual(report["files"][2]["sha256"], hashlib.sha256(lf).hexdigest())

    def test_literal_create_and_alter_targets_only(self):
        fixture = br"""
/* CREATE TABLE widgets (id int); */
-- ALTER TABLE widgets ADD ignored int;
# CREATE TABLE widgets (id int);
/*!50100 CREATE TABLE widgets (id int); */
SET @quoted = 'quote\' ; CREATE TABLE widgets (id int);';
SET @quoted = 'quote'' ; CREATE TABLE widgets (id int);';
SET @quoted = "quote"" ; CREATE TABLE widgets (id int);";
INSERT INTO another_table VALUES (1, 'ALTER TABLE widgets ADD ignored int');
CREATE TABLE widgets_history (id int);
ALTER TABLE widgets_history ADD widgets int;
CREATE TABLE `some``name;CREATE TABLE widgets` (id int);
SET @unstable = 'unterminated ; CREATE TABLE widgets (id int);
"""
        self.put(BACKEND / "V20261112__not_matches.sql", fixture)
        self.put(BASELINE, b"\xef\xbb\xbf" + br"""
cReAtE /* description never emitted */ TABLE IF NOT EXISTS `demo`.`Widgets` (id int);
CREATE TABLE widgets (id int);
""")
        upgrade = self.put(BAOTA / "20261112-widgets.sql", br"""
ALTER
TABLE `example`.widgets ADD remark varchar(80) DEFAULT 'fixture-secret';
CREATE TEMPORARY TABLE IF NOT EXISTS `example`.`widgets` (id int);
""")
        status, stdout, stderr = self.run_cli("table", "WIDGETS")
        self.assertEqual((status, stderr), (0, ""))
        report = json.loads(stdout)
        self.assertEqual(report["table"], "WIDGETS")
        self.assertEqual(report["skippedFiles"], [])
        self.assertEqual(
            [(row["path"], row["operations"]) for row in report["matches"]],
            [(BASELINE.as_posix(), ["CREATE_TABLE"]), (upgrade, ["ALTER_TABLE", "CREATE_TABLE"])],
        )
        for hidden in ("fixture-secret", "description", "id int", "demo", "widgets_history"):
            self.assertNotIn(hidden, stdout)

    def test_dynamic_sql_is_not_resolved_and_unsupported_files_are_reported(self):
        self.put(BASELINE, b"SET @sql = 'ALTER TABLE widgets ADD fixture_secret INT'; PREPARE st FROM @sql;")
        bad_encoding = self.put(BACKEND / "V20261112__bad_encoding.sql", b"\xff")
        delimiter = self.put(
            BAOTA / "20261112-routine.sql",
            b"CREATE TABLE widgets (id int);\nDELIMITER //\nCREATE PROCEDURE noop() BEGIN SELECT 1; END//",
        )
        status, stdout, stderr = self.run_cli("table", "widgets")
        self.assertEqual((status, stderr), (0, ""))
        self.assertEqual(json.loads(stdout), {
            "table": "widgets",
            "matches": [],
            "skippedFiles": [
                {"path": bad_encoding, "reason": "not-utf8"},
                {"path": delimiter, "reason": "custom-delimiter"},
            ],
        })
        self.assertEqual(len(schema_inventory.inventory(self.root)["files"]), 3)

    def test_inventory_cli_is_deterministic_and_writes_nothing(self):
        self.put(BAOTA / "20261112-add-widget.sql", b"INSERT INTO fixture VALUES ('fixture-secret');")
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        status, stdout, stderr = self.run_cli("inventory")
        self.assertEqual((status, stderr), (0, ""))
        self.assertEqual(len(json.loads(stdout)["files"]), 2)
        self.assertNotIn("fixture-secret", stdout)
        self.assertNotIn(str(self.root), stdout)
        self.assertEqual(self.run_cli("inventory"), (status, stdout, stderr))
        after = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_bad_table_name_is_not_echoed(self):
        status, stdout, stderr = self.run_cli("table", "private-schema.widgets")
        self.assertEqual((status, stdout), (1, ""))
        self.assertIn("unqualified ASCII identifier", stderr)
        self.assertNotIn("private-schema", stderr)

    def test_incomplete_layout_fails_without_partial_json(self):
        (self.root / BAOTA).rmdir()
        status, stdout, stderr = self.run_cli("inventory")
        self.assertEqual((status, stdout), (1, ""))
        self.assertIn("deploy/baota/migrations", stderr)
        self.assertNotIn(str(self.root), stderr)

    def test_linked_entries_are_rejected(self):
        link = self.root / BAOTA / "20261112-linked.sql"
        try:
            link.symlink_to(self.root / BASELINE)
        except (NotImplementedError, OSError) as error:
            if isinstance(error, NotImplementedError) or getattr(error, "winerror", None) == 1314:
                self.skipTest("symlinks are not available to this process")
            raise
        status, stdout, stderr = self.run_cli("inventory")
        self.assertEqual((status, stdout), (1, ""))
        self.assertIn("linked path is not supported", stderr)


if __name__ == "__main__":
    unittest.main()
