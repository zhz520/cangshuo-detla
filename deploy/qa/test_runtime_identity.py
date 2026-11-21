import copy
import unittest

import runtime_identity


def snapshot(version="1.18.4", build="a" * 64, admin="b" * 64, h5="c" * 64,
             admin_build="d" * 64, h5_build="e" * 64):
    return {"components": {
        "backend": {"version": version, "buildId": build},
        "admin": {"version": version, "entrySha256": admin, "buildId": admin_build},
        "h5": {"version": version, "entrySha256": h5, "buildId": h5_build},
    }}


class RuntimeIdentityTest(unittest.TestCase):
    def test_configuration_is_not_part_of_code_comparison(self):
        first = snapshot()
        second = copy.deepcopy(first)
        second["configuration"] = {"purchase": False}
        second["components"]["admin"]["settings"] = {"theme": "dark"}
        self.assertTrue(runtime_identity.compare({"742": first, "747": second})["complete"])

    def test_same_version_with_different_code_is_a_mismatch(self):
        second = snapshot(admin="d" * 64)
        result = runtime_identity.compare({"742": snapshot(), "747": second})
        self.assertFalse(result["sameKnownCode"])
        self.assertEqual(result["differences"][0]["component"], "admin")

    def test_build_id_catches_same_entry_with_different_chunks(self):
        second = snapshot(h5_build="f" * 64)
        result = runtime_identity.compare({"742": snapshot(), "747": second})
        self.assertFalse(result["sameKnownCode"])
        self.assertEqual(result["differences"], [{"tenant": "747", "component": "h5",
                                                   "field": "buildId",
                                                   "baseline": "e" * 64, "observed": "f" * 64}])

    def test_backend_without_new_build_endpoint_is_unknown_not_equal(self):
        second = snapshot(build=None)
        result = runtime_identity.compare({"742": snapshot(), "747": second})
        self.assertTrue(result["sameKnownCode"])
        self.assertFalse(result["complete"])
        self.assertEqual(result["unknown"], [{"tenant": "747", "component": "backend", "field": "buildId"}])

    def test_snapshot_shape_rejects_omitted_component(self):
        broken = snapshot()
        del broken["components"]["h5"]
        with self.assertRaises(ValueError):
            runtime_identity.compare({"742": snapshot(), "747": broken})
