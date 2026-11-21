import copy
import json
from pathlib import Path
import tempfile
import unittest

import release_parity as parity


def manifest():
    return {"schemaVersion": 1, "version": "1.18.4", "commit": "a" * 40, "dirty": False,
            "assets": [{"component": component, "name": f"cangshuo-{component}.zip",
                        "size": 100, "sha256": "a" * 64} for component in sorted(parity.COMPONENTS)]}


def features():
    return {"role": "admin", "features": {
        "order_assignment": {"entitled": True, "enabled": True, "visible": True, "allowed": True},
        "advanced_reports": {"entitled": False, "enabled": False, "visible": False, "allowed": False},
    }}


class ReleaseParityTest(unittest.TestCase):
    def test_order_and_timestamp_do_not_affect_manifest_match(self):
        candidate = manifest()
        candidate["assets"].reverse()
        candidate["createdAt"] = "different"
        self.assertTrue(parity.compare_manifests(manifest(), candidate)["matches"])

    def test_equal_version_but_changed_asset_or_commit_is_rejected(self):
        candidate = manifest()
        candidate["commit"] = "b" * 40
        candidate["assets"][0]["sha256"] = "b" * 64
        self.assertEqual(parity.compare_manifests(manifest(), candidate)["differences"], ["commit", "admin"])

    def test_duplicate_dirty_and_invalid_assets_are_rejected(self):
        for update in (lambda m: m.update(dirty=True), lambda m: m["assets"].pop(),
                       lambda m: m["assets"].__setitem__(1, m["assets"][0]),
                       lambda m: m["assets"][0].update(size=True)):
            with self.subTest(update=update), self.assertRaises(ValueError):
                data = manifest()
                update(data)
                parity.manifest(data)

    def test_role_rules_and_customer_policy_are_explicit(self):
        expected = features()
        actual = copy.deepcopy(expected)
        actual["features"]["other_plan_feature"] = copy.deepcopy(actual["features"]["order_assignment"])
        result = parity.compare_features(expected, actual)
        self.assertTrue(result["matches"])
        self.assertEqual(result["observedOutsidePolicy"], ["other_plan_feature"])
        with self.assertRaises(ValueError):
            parity.compare_features(expected, dict(actual, role="cs"))

    def test_enabled_is_not_the_same_as_visible_or_allowed(self):
        expected = features()
        actual = copy.deepcopy(expected)
        actual["features"]["order_assignment"].update(visible=False, allowed=None)
        del actual["features"]["advanced_reports"]
        result = parity.compare_features(expected, actual)
        self.assertFalse(result["matches"])
        self.assertEqual(result["missing"], ["advanced_reports"])
        self.assertEqual(result["differences"][0]["field"], "visible")
        self.assertEqual(result["unknown"][0]["field"], "allowed")

    def test_raw_response_and_duplicate_json_are_rejected(self):
        data = features()
        data["token"] = "synthetic"
        with self.assertRaises(ValueError):
            parity.compare_features(features(), data)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "fixture.json"
            path.write_text('{"role":"admin","role":"cs","features":{}}', encoding="utf-8")
            with self.assertRaises(ValueError):
                parity.load(path)
            path.write_text(json.dumps(features()), encoding="utf-8")
            self.assertEqual(parity.load(path), features())
