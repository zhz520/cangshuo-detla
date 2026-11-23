import json
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import install_shared_worker
import shared_release


class SharedWorkerInstallTests(unittest.TestCase):
    def test_service_template_uses_validated_image_and_example_config_is_disabled(self):
        template = Path(__file__).with_name("shared-worker.service.template")
        unit = install_shared_worker.build_unit(template, "cangshuo-shared-worker:1.13.0")
        self.assertIn("cangshuo-shared-worker:1.13.0", unit)
        self.assertNotIn("@IMAGE@", unit)
        self.assertIn("ExecStop=-/usr/bin/docker stop --time 20 delta-shared-worker", unit)
        config = json.loads(Path(__file__).with_name("shared-worker.json.example").read_text(encoding="utf-8"))
        if os.name != "nt":
            shared_release.validate_config(config)
        self.assertFalse(config["allow_execute"])

    def test_service_template_rejects_command_injection_in_image_name(self):
        template = Path(__file__).with_name("shared-worker.service.template")
        with self.assertRaises(ValueError):
            install_shared_worker.build_unit(template, "image;systemctl restart evil")


if __name__ == "__main__":
    unittest.main()
