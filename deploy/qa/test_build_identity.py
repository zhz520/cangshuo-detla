import tempfile
import unittest
from pathlib import Path

import build_identity


class BuildIdentityTest(unittest.TestCase):
    def test_identity_ignores_generated_version_and_file_order(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            a, b = Path(first), Path(second)
            (a / "assets").mkdir()
            (b / "assets").mkdir()
            (a / "index.html").write_text("same", encoding="utf-8")
            (b / "index.html").write_text("same", encoding="utf-8")
            (a / "assets" / "app.js").write_text("code", encoding="utf-8")
            (b / "assets" / "app.js").write_text("code", encoding="utf-8")
            (a / "cangshuo-version.json").write_text('{"buildId":"old"}', encoding="utf-8")
            (b / "cangshuo-version.json").write_text('{"buildId":"new"}', encoding="utf-8")
            self.assertEqual(build_identity.tree_sha256(a), build_identity.tree_sha256(b))

    def test_relative_path_and_content_are_both_covered(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            a, b = Path(first), Path(second)
            (a / "x.js").write_text("same", encoding="utf-8")
            (b / "y.js").write_text("same", encoding="utf-8")
            self.assertNotEqual(build_identity.tree_sha256(a), build_identity.tree_sha256(b))
            (b / "y.js").write_text("changed", encoding="utf-8")
            self.assertNotEqual(build_identity.tree_sha256(a), build_identity.tree_sha256(b))

    def test_symlink_and_missing_tree_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                build_identity.tree_sha256(root / "missing")
            try:
                (root / "link").symlink_to(root / "target")
            except (OSError, NotImplementedError):
                self.skipTest("symbolic links are unavailable")
            with self.assertRaises(ValueError):
                build_identity.tree_sha256(root)
