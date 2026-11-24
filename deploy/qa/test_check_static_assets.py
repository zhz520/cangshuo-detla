import tempfile
import unittest
import zipfile
from pathlib import Path

import check_static_assets as checker


class StaticAssetTests(unittest.TestCase):
    def test_registered_subpackage_asset_is_not_mistaken_for_root_static(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            asset = 'pages-player/static/icons/暂无记录.svg'
            (root / 'index.html').write_text(f'<img src="/{asset}">', encoding='utf-8')
            self.assertEqual(checker.check(root)['missingReferences'], [asset])
            (root / asset).parent.mkdir(parents=True)
            (root / asset).touch()
            self.assertEqual(checker.check(root)['missingReferences'], [])

    def test_missing_reference_is_reported_and_present_file_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'assets').mkdir()
            (root / 'index.html').write_text('<script type="module" src="/assets/main.js"></script>', encoding='utf-8')
            (root / 'assets/main.js').write_text('import("assets/订单.js"); import("assets/present.js");', encoding='utf-8')
            (root / 'assets/present.js').write_text('', encoding='utf-8')
            result = checker.check(root)
            self.assertEqual(result['missingReferences'], ['assets/订单.js'])
            (root / 'assets/订单.js').write_text('', encoding='utf-8')
            self.assertEqual(checker.check(root)['missingReferenceCount'], 0)

    def test_injected_version_file_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'static'
            root.mkdir()
            (root / 'index.html').write_text('<div></div>', encoding='utf-8')
            archive = Path(directory) / 'release.zip'
            with zipfile.ZipFile(archive, 'w') as zipped:
                zipped.writestr('index.html', '<div></div>')
                zipped.writestr('cangshuo-version.json', '{"version":"1.17.0"}')
            result = checker.check(root, archive)
            self.assertEqual(result['extraFileCount'], 0)
            self.assertEqual(result['missingArchiveEntryCount'], 0)

    def test_archive_name_mismatch_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'static'
            root.mkdir()
            (root / 'index.html').write_text('<div></div>', encoding='utf-8')
            archive = Path(directory) / 'release.zip'
            with zipfile.ZipFile(archive, 'w') as zipped:
                zipped.writestr('index.html', '<div></div>')
                zipped.writestr('assets/订单.js', '')
            result = checker.check(root, archive)
            self.assertEqual(result['missingArchiveEntryCount'], 1)


if __name__ == '__main__':
    unittest.main()


