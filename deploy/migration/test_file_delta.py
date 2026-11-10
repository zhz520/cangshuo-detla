import json
import os
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
import file_delta


class FileDeltaTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("TEMP"))
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.target = self.root / "target"
        self.source.mkdir()
        self.target.mkdir()
        (self.source / "a.txt").write_text("one", encoding="utf-8")
        (self.source / "nested").mkdir()
        (self.source / "nested" / "b.bin").write_bytes(b"two")
        (self.target / "a.txt").write_text("old", encoding="utf-8")
        (self.target / "extra.txt").write_text("keep", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def test_compare_reports_missing_changed_and_extra(self):
        result = file_delta.compare(self.source, self.target)
        self.assertEqual(result["missing"], ["nested/b.bin"])
        self.assertEqual(result["changed"], ["a.txt"])
        self.assertEqual(result["extra"], ["extra.txt"])
        self.assertFalse(result["identical"])
        self.assertEqual(result["missingBytes"], 3)

    def test_dry_run_does_not_write_and_apply_copies_without_deleting_extra(self):
        dry = file_delta.sync(self.source, self.target, apply=False)
        self.assertFalse(dry["applied"])
        self.assertEqual((self.target / "a.txt").read_text(encoding="utf-8"), "old")
        self.assertFalse((self.target / "nested" / "b.bin").exists())

        applied = file_delta.sync(self.source, self.target, apply=True)
        self.assertTrue(applied["applied"])
        self.assertEqual((self.target / "a.txt").read_text(encoding="utf-8"), "one")
        self.assertEqual((self.target / "nested" / "b.bin").read_bytes(), b"two")
        self.assertTrue((self.target / "extra.txt").exists())
        self.assertEqual(applied["after"]["missing"], [])
        self.assertEqual(applied["after"]["changed"], [])
        self.assertEqual(applied["after"]["extra"], ["extra.txt"])

    def test_verify_is_identical_after_extra_is_removed(self):
        (self.target / "extra.txt").unlink()
        file_delta.sync(self.source, self.target, apply=True)
        result = file_delta.compare(self.source, self.target)
        self.assertTrue(result["identical"])

    def test_exclude_skips_derived_thumbnail_directory(self):
        (self.source / ".thumbnails").mkdir()
        (self.source / ".thumbnails" / "derived.jpg").write_bytes(b"thumb")
        result = file_delta.compare(self.source, self.target, [".thumbnails"])
        self.assertNotIn(".thumbnails/derived.jpg", result["missing"])
        result = file_delta.compare(self.source, self.target)
        self.assertIn(".thumbnails/derived.jpg", result["missing"])

    def test_symlinked_source_file_is_rejected(self):
        link = self.source / "link.txt"
        try:
            os.symlink(str(self.source / "a.txt"), str(link))
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are not available")
        with self.assertRaises(ValueError):
            file_delta.scan(self.source)

    def test_manifest_and_report_are_written_without_contents(self):
        report = self.root / "report.json"
        file_delta.write_report(file_delta.manifest(self.source), report)
        data = json.loads(report.read_text(encoding="utf-8"))
        self.assertEqual(data["files"]["a.txt"]["size"], 3)
        self.assertNotIn("one", report.read_text(encoding="utf-8"))
        if os.name != "nt":
            self.assertEqual(report.stat().st_mode & 0o777, 0o600)


if __name__ == "__main__":
    unittest.main()
