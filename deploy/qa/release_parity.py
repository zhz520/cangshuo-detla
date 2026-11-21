#!/usr/bin/env python3
"""Read-only QA gates for promoting tested assets and comparing tenant feature state."""

import argparse
import json
import re
from pathlib import Path


COMPONENTS = {"backend", "admin", "h5"}
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
COMMIT = re.compile(r"[a-f0-9]{40}")
DIGEST = re.compile(r"[a-f0-9]{64}")
FEATURE_ID = re.compile(r"[a-z0-9][a-z0-9_.:-]{0,95}")
MAX_JSON = 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result


def load(path):
    with Path(path).open("rb") as stream:
        data = stream.read(MAX_JSON + 1)
    require(len(data) <= MAX_JSON, "input JSON exceeds size limit")
    return json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique_fields)


def match(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def manifest(value):
    require(isinstance(value, dict), "manifest must be an object")
    require(type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1, "unsupported manifest schema")
    require(value.get("dirty") is False, "manifest is not marked as a clean build")
    require(match(VERSION, value.get("version")), "invalid manifest release version")
    require(match(COMMIT, value.get("commit")), "invalid manifest commit")
    require(isinstance(value.get("assets"), list) and len(value["assets"]) == len(COMPONENTS),
            "manifest must contain three components")
    result = {}
    for asset in value["assets"]:
        require(isinstance(asset, dict), "asset must be an object")
        component = asset.get("component")
        require(isinstance(component, str) and component in COMPONENTS and component not in result,
                "invalid or repeated asset component")
        require(asset.get("name") == f"cangshuo-{component}.zip", "unexpected asset name")
        require(type(asset.get("size")) is int and asset["size"] > 0, "invalid asset size")
        require(match(DIGEST, asset.get("sha256")), "invalid asset SHA-256")
        result[component] = (asset["size"], asset["sha256"])
    return value["version"], value["commit"], result


def compare_manifests(tested, candidate):
    tested_version, tested_commit, tested_assets = manifest(tested)
    candidate_version, candidate_commit, candidate_assets = manifest(candidate)
    changes = [key for key, before, after in (
        ("version", tested_version, candidate_version), ("commit", tested_commit, candidate_commit),
    ) if before != after]
    changes.extend(component for component in sorted(COMPONENTS)
                   if tested_assets[component] != candidate_assets[component])
    return {"matches": not changes, "testedVersion": tested_version,
            "candidateVersion": candidate_version, "differences": changes}


FEATURE_FIELDS = ("entitled", "enabled", "visible", "allowed")


def features(value):
    require(isinstance(value, dict) and set(value) == {"role", "features"},
            "expected only a role and normalized features; do not pass raw API data")
    require(match(FEATURE_ID, value["role"]), "invalid role label")
    rows = value["features"]
    require(isinstance(rows, dict) and bool(rows), "a nonempty feature snapshot is required")
    for feature, states in rows.items():
        require(match(FEATURE_ID, feature), "invalid feature ID")
        require(isinstance(states, dict) and set(states) == set(FEATURE_FIELDS),
                "feature requires entitled/enabled/visible/allowed fields")
        require(all(state is None or type(state) is bool for state in states.values()),
                "feature states must be true, false, or null for unverified")
    return value["role"], rows


def compare_features(expected, observed):
    expected_role, before = features(expected)
    observed_role, after = features(observed)
    require(expected_role == observed_role, "role labels differ; compare equivalent roles")
    require(all(state is not None for row in before.values() for state in row.values()),
            "expected feature policy may not contain unknowns")
    missing = sorted(before.keys() - after.keys())
    differences, unknown = [], []
    for key in sorted(before.keys() & after.keys()):
        for field in FEATURE_FIELDS:
            if after[key][field] is None:
                unknown.append({"feature": key, "field": field})
            elif before[key][field] != after[key][field]:
                differences.append({"feature": key, "field": field,
                                    "expected": before[key][field], "observed": after[key][field]})
    return {"matches": not (missing or differences or unknown), "role": expected_role,
            "comparedFeatures": len(before), "missing": missing,
            "differences": differences, "unknown": unknown,
            "observedOutsidePolicy": sorted(after.keys() - before.keys())}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    for mode in ("promotion", "features"):
        command = sub.add_parser(mode)
        command.add_argument("expected")
        command.add_argument("observed")
    args = parser.parse_args(argv)
    try:
        compare = compare_manifests if args.mode == "promotion" else compare_features
        result = compare(load(args.expected), load(args.observed))
    except (OSError, UnicodeError, ValueError) as error:
        message = "input could not be read" if isinstance(error, (OSError, UnicodeError, json.JSONDecodeError)) else str(error)
        print(json.dumps({"matches": False, "error": message}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["matches"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
