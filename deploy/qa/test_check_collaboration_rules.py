"""Ensure the guard rejects the drift it is intended to catch."""

from pathlib import Path
import tempfile
import unittest

from check_collaboration_rules import CHECKLIST, ENTRY_FILES, REQUIRED_FILES, check_rules


class CollaborationRulesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in REQUIRED_FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            text = "# Rules\n"
            if name in ENTRY_FILES:
                text += f"`{CHECKLIST}`\n已读取 VIBE_CODING.md v2026-11-13.8\n"
            if name == "docs/VIBE_CODING.md":
                text += "文档版本：2026-11-13.8\n历史版本 1.13.0 曾通过，不代表当前状态。\n"
            path.write_text(text, encoding="utf-8")

    def append(self, name, text):
        with (self.root / name).open("a", encoding="utf-8") as stream:
            stream.write(text)

    def test_valid_and_historical_versions_are_allowed(self):
        self.append("README.md", "[规则](docs/VIBE_CODING.md#规则)\n[remote](https://example.test)\n")
        self.assertEqual([], check_rules(self.root))

    def test_stale_receipt_is_rejected(self):
        self.append("AGENTS.md", "已读取 VIBE_CODING.md v2026-11-11.18\n")
        self.assertTrue(any("differs" in item for item in check_rules(self.root)))

    def test_missing_checklist_and_entry_are_rejected(self):
        (self.root / CHECKLIST).unlink()
        (self.root / "AGENTS.md").write_text("# Old entry\n", encoding="utf-8")
        errors = check_rules(self.root)
        self.assertTrue(any("unreadable" in item for item in errors))
        self.assertTrue(any("missing reading receipt" in item for item in errors))
        self.assertTrue(any("missing regression" in item for item in errors))

    def test_broken_link_and_duplicate_bullet_are_rejected(self):
        self.append("README.md", "- [missing](docs/不存在.md)\n" * 2)
        errors = check_rules(self.root)
        self.assertTrue(any("broken local" in item for item in errors))
        self.assertTrue(any("duplicate" in item for item in errors))

    def test_conflict_and_damaged_encoding_are_rejected(self):
        self.append("docs/VIBE_CODING.md", "<<<<<<< ours\n\ufffd\n")
        errors = check_rules(self.root)
        self.assertTrue(any("merge marker" in item for item in errors))
        self.assertTrue(any("replacement character" in item for item in errors))

    def test_invalid_utf8_is_rejected(self):
        (self.root / "AGENTS.md").write_bytes(b"\xff\xfe")
        self.assertTrue(any("unreadable UTF-8" in item for item in check_rules(self.root)))

    def test_external_entry_checked_against_selected_root(self):
        external = self.root / "external-AGENTS.md"
        external.write_text(f"{CHECKLIST}\n已读取 VIBE_CODING.md v2026-11-13.7\n", encoding="utf-8")
        self.assertTrue(any("differs" in item for item in check_rules(self.root, [external])))
        external.write_text(f"{CHECKLIST}\n已读取 VIBE_CODING.md v2026-11-13.8\n", encoding="utf-8")
        self.assertEqual([], check_rules(self.root, [external]))

    def test_missing_or_duplicate_version_is_rejected(self):
        self.append("docs/VIBE_CODING.md", "文档版本：2026-11-13.9\n")
        self.assertTrue(any("expected one document version" in item for item in check_rules(self.root)))
        (self.root / "docs/VIBE_CODING.md").write_text("# No version\n", encoding="utf-8")
        self.assertTrue(any("expected one document version" in item for item in check_rules(self.root)))


if __name__ == "__main__":
    unittest.main()
