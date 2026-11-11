#!/usr/bin/env python3
"""Install the restricted shared service-group worker on a Linux host."""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shared_release

IMAGE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._/:@-]{0,180}$")
UNIT = "cangshuo-shared-worker.service"


def build_unit(template, image):
    if not IMAGE.fullmatch(image or ""):
        raise ValueError("invalid Docker image name")
    text = template.read_text(encoding="utf-8")
    if text.count("@IMAGE@") != 1:
        raise ValueError("service template must contain exactly one @IMAGE@ placeholder")
    return text.replace("@IMAGE@", image)


def validate_inputs(config_path, install_config, public_key, registry, compose, env_file):
    config = shared_release.load_config(config_path)
    if Path(config["public_key_path"]).resolve() != Path(public_key).resolve():
        raise ValueError("config public_key_path does not match --public-key")
    if Path(config["registry_file"]).resolve() != Path(registry).resolve():
        raise ValueError("config registry_file does not match --registry")
    compose_paths = [Path(config["compose_directory"]) / name for name in config["compose_files"]]
    if Path(compose).resolve() not in {path.resolve() for path in compose_paths if path.exists()}:
        raise ValueError("--compose does not match an existing config compose file")
    if Path(config["env_file"]).resolve() != Path(env_file).resolve():
        raise ValueError("--env-file does not match config env_file")
    for path in (Path(public_key), Path(registry), Path(compose), Path(env_file), Path(config_path)):
        if not path.is_file():
            raise ValueError("required file is missing: " + str(path))
    state = Path(config["state_dir"])
    inbox = Path(config.get("inbox_dir") or state / "inbox")
    return config, state, inbox


def apply_install(install_config, unit_text, state, inbox, image, enable):
    if os.geteuid() != 0:
        raise PermissionError("--apply must run as root")
    target = Path("/www/delta/shared/shared-worker.json")
    if Path(install_config).resolve() != target.resolve():
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".json.tmp")
        shutil.copyfile(install_config, temporary)
        os.chmod(temporary, 0o600)
        temporary.replace(target)
    source = Path(__file__).resolve().parent
    subprocess.run(["docker", "build", "-f", str(source / "Dockerfile.shared-worker"),
                    "-t", image, str(source)], check=True)
    if subprocess.run(["systemctl", "is-active", "--quiet", UNIT]).returncode == 0:
        subprocess.run(["systemctl", "stop", UNIT], check=True)
    for directory in (state, inbox, state / "processing", state / "processed", state / "rejected"):
        directory.mkdir(parents=True, exist_ok=True)
        os.chmod(directory, 0o700)
    unit_path = Path("/etc/systemd/system") / UNIT
    temporary = unit_path.with_suffix(".service.tmp")
    temporary.write_text(unit_text, encoding="utf-8")
    os.chmod(temporary, 0o644)
    temporary.replace(unit_path)
    subprocess.run(["systemctl", "daemon-reload"], check=True)
    if enable:
        subprocess.run(["systemctl", "enable", "--now", UNIT], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--install-config", default="/www/delta/shared/shared-worker.json")
    parser.add_argument("--public-key", required=True)
    parser.add_argument("--registry", required=True)
    parser.add_argument("--compose", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--image", default="cangshuo-shared-worker:1")
    parser.add_argument("--template", default=str(Path(__file__).with_name("shared-worker.service.template")))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--enable", action="store_true")
    args = parser.parse_args()
    config, state, inbox = validate_inputs(args.config, args.install_config, args.public_key,
                                           args.registry, args.compose, args.env_file)
    unit_text = build_unit(Path(args.template), args.image)
    if args.enable and not config.get("allow_execute", False):
        raise ValueError("refusing to enable while config allow_execute is false")
    result = {
        "service": UNIT,
        "image": args.image,
        "stateDir": str(state),
        "inbox": str(inbox),
        "allowExecute": bool(config.get("allow_execute", False)),
        "enable": bool(args.enable),
        "applied": bool(args.apply),
    }
    if args.apply:
        apply_install(args.install_config, unit_text, state, inbox, args.image, args.enable)
    print(__import__("json").dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, PermissionError, subprocess.CalledProcessError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
