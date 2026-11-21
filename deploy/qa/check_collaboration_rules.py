"""Read-only checks for collaboration entry points; not business acceptance."""

import argparse
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


CHECKLIST = "docs/ai/CONTEXT.md"
ENTRY_FILES = ("AGENTS.md", "README.md", "docs/README.md", "docs/VIBE_CODING.md")
REQUIRED_FILES = ENTRY_FILES + (
    CHECKLIST,
    "docs/CURRENT_STATE.md",
    "docs/ARCHITECTURE.md",
    "docs/BUSINESS_RULES.md",
    "docs/OPERATIONS.md",
    "docs/CONFIGURATION.md",
    "docs/CHANGELOG.md",
    "docs/ai/routing.json",
    "docs/ai/index.json",
)
RECEIPT = re.compile(r"已读取 VIBE_CODING\.md v(\d{4}-\d{2}-\d{2}\.\d+)")
LINK = re.compile(r"\[[^\]\n]*\]\((<[^>\n]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\)")


def check_rules(root: Path, external_entries=()):
    root = root.resolve()
    errors = []
    texts = {}

    def read(path):
        try:
            content = path.read_text(encoding="utf-8-sig")
        except (OSError, UnicodeError):
            errors.append(f"{path}: missing or unreadable UTF-8 file")
            return None
        if "\ufffd" in content:
            errors.append(f"{path}: Unicode replacement character found")
        if re.search(r"^(?:<{7}|={7}|>{7})(?:\s|$)", content, re.MULTILINE):
            errors.append(f"{path}: unresolved merge marker")
        return content

    for name in REQUIRED_FILES:
        content = read(root / name)
        if content is not None:
            texts[name] = content
    versions = re.findall(r"^文档版本：\s*(\d{4}-\d{2}-\d{2}\.\d+)\s*$",
                          texts.get("docs/VIBE_CODING.md", ""), re.MULTILINE)
    version = versions[0] if len(versions) == 1 else None
    if version is None:
        errors.append("docs/VIBE_CODING.md: expected one document version")

    def check_receipt(name, content, required=False):
        receipts = RECEIPT.findall(content)
        if required and not receipts:
            errors.append(f"{name}: missing reading receipt")
        if version and any(receipt != version for receipt in receipts):
            errors.append(f"{name}: reading receipt differs from VIBE version {version}")

    for name in ENTRY_FILES:
        content = texts.get(name, "")
        check_receipt(name, content, name in ("AGENTS.md", "docs/VIBE_CODING.md"))
        if Path(CHECKLIST).name not in content:
            errors.append(f"{name}: missing regression checklist entry")

    # Only explicit, local Markdown file links in the collaboration entry points.
    # Historic version numbers and prose are deliberately not rewritten or banned.
    for name in ENTRY_FILES + (CHECKLIST,):
        content = texts.get(name, "")
        for match in LINK.finditer(content):
            target = match.group(1).strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            path = (root / name).parent / unquote(parsed.path)
            if not path.is_file():
                errors.append(f"{name}: broken local file link: {target}")
        seen = set()
        for line in content.splitlines():
            if line.startswith("#"):
                seen.clear()
            if line.startswith("- ") and (LINK.search(line) or re.fullmatch(r"- `[^`]+\.md`", line)):
                if line in seen:
                    errors.append(f"{name}: duplicate document bullet in one section")
                seen.add(line)

    for entry in external_entries:
        entry = Path(entry).resolve()
        content = read(entry)
        if content is not None:
            check_receipt(str(entry), content, required=True)
            if Path(CHECKLIST).name not in content:
                errors.append(f"{entry}: missing regression checklist entry")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--entry", type=Path, action="append", default=[],
                        help="Additional local AGENTS entry to check (repeatable).")
    args = parser.parse_args()
    errors = check_rules(args.root, args.entry)
    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print("Collaboration rules passed: entries, receipts, UTF-8 and local links. "
          "This is not business or production acceptance.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
