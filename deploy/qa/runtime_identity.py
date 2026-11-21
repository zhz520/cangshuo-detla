#!/usr/bin/env python3
"""Probe public build identity and compare deployed code, excluding tenant configuration."""

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path


VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
HEX64 = re.compile(r"[a-f0-9]{64}")
SCRIPT = re.compile(r"<script\b[^>]*\bsrc=[\"'](?P<src>/?(?:assets|static)/[A-Za-z0-9._/-]+\.js)[\"']", re.I)
MAX_BODY = 2 * 1024 * 1024
COMPONENTS = ("backend", "admin", "h5")
FIELDS = {"backend": ("version", "buildId"), "admin": ("version", "entrySha256", "buildId"),
          "h5": ("version", "entrySha256", "buildId")}


def fail(message):
    raise ValueError(message)


def fetch(url):
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache", "User-Agent": "Delta-Runtime-Identity"})
    with urllib.request.urlopen(request, timeout=8) as response:
        body = response.read(MAX_BODY + 1)
        if len(body) > MAX_BODY:
            fail("response too large")
        return response.status, response.headers.get_content_type(), body


def component(base, name, stamp):
    root = base.rstrip("/") + "/"
    version = None
    build_id = None
    try:
        status, kind, body = fetch(root + "cangshuo-version.json?probe=" + stamp)
        if status == 200 and kind == "application/json":
            value = json.loads(body.decode("utf-8"))
            if value.get("component") == name and isinstance(value.get("version"), str) and VERSION.fullmatch(value["version"]):
                version = value["version"]
            if isinstance(value.get("buildId"), str) and HEX64.fullmatch(value["buildId"]):
                build_id = value["buildId"]
    except Exception:
        pass
    entry = None
    try:
        status, kind, body = fetch(root + "?probe=" + stamp)
        if status == 200 and kind == "text/html":
            match = SCRIPT.search(body.decode("utf-8", "replace"))
            if match:
                asset = urllib.parse.urljoin(root, match.group("src"))
                status, kind, data = fetch(asset)
                if status == 200 and kind in {"application/javascript", "text/javascript", "application/x-javascript"}:
                    entry = hashlib.sha256(data).hexdigest()
    except Exception:
        pass
    return {"version": version, "entrySha256": entry, "buildId": build_id}


def backend(base, stamp):
    try:
        status, kind, body = fetch(base.rstrip("/") + "/api/system/config/runtime-identity?probe=" + stamp)
        if status == 200 and kind == "application/json":
            value = json.loads(body.decode("utf-8")).get("data") or {}
            version = value.get("backend_version")
            build = value.get("backend_build_id")
            return {"version": version if isinstance(version, str) and VERSION.fullmatch(version) else None,
                    "buildId": build if isinstance(build, str) and (HEX64.fullmatch(build) or build == "development") else None}
    except Exception:
        pass
    return {"version": None, "buildId": None}


def probe(spec):
    stamp = str(int(time.time() * 1000))
    result = {}
    for tenant, sites in spec.items():
        admin, h5 = sites
        result[tenant] = {"schemaVersion": 1, "tenant": tenant, "components": {
            "backend": backend(admin, stamp), "admin": component(admin, "admin", stamp),
            "h5": component(h5, "h5", stamp)}}
    return result


def load(path):
    value = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        fail("snapshot must be an object")
    return value


def normalize(value, name):
    if not isinstance(value, dict):
        fail(name + " must be an object")
    components = value.get("components")
    if not isinstance(components, dict) or set(components) != set(COMPONENTS):
        fail(name + " must contain backend, admin and h5")
    normalized = {}
    for component in COMPONENTS:
        row = components[component]
        if not isinstance(row, dict):
            fail(name + " component must be an object")
        normalized[component] = {field: row.get(field) for field in FIELDS[component]}
    return normalized


def compare(snapshots):
    names = sorted(snapshots)
    if len(names) < 2:
        fail("at least two snapshots are required")
    rows = {name: normalize(snapshots[name], name) for name in names}
    baseline = names[0]
    differences, unknown = [], []
    for name in names[1:]:
        for component in COMPONENTS:
            for field in FIELDS[component]:
                left, right = rows[baseline][component][field], rows[name][component][field]
                if left is None or right is None:
                    unknown.append({"tenant": name, "component": component, "field": field})
                elif left != right:
                    differences.append({"tenant": name, "component": component, "field": field,
                                        "baseline": left, "observed": right})
    return {"baseline": baseline, "sameKnownCode": not differences,
            "complete": not differences and not unknown, "differences": differences, "unknown": unknown}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    probe_parser = sub.add_parser("probe")
    probe_parser.add_argument("--site", action="append", required=True,
                              help="tenant=admin-base,h5-base (repeatable)")
    probe_parser.add_argument("--output")
    compare_parser = sub.add_parser("compare")
    compare_parser.add_argument("snapshots", nargs="+")
    args = parser.parse_args(argv)
    try:
        if args.mode == "probe":
            spec = {}
            for item in args.site:
                tenant, separator, value = item.partition("=")
                urls = value.split(",")
                if not separator or not re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", tenant) or len(urls) != 2:
                    fail("site must be tenant=admin-base,h5-base")
                for url in urls:
                    parsed = urllib.parse.urlsplit(url)
                    if parsed.scheme != "https" or not parsed.hostname or parsed.query or parsed.fragment:
                        fail("site bases must be plain HTTPS origins")
                spec[tenant] = urls
            result = probe(spec)
            text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
            if args.output:
                Path(args.output).write_text(text, encoding="utf-8")
            print(text, end="")
            return 0
        result = compare({Path(path).stem: load(path) for path in args.snapshots})
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"sameKnownCode": False, "error": str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result["sameKnownCode"]:
        return 1
    return 0 if result["complete"] else 3


if __name__ == "__main__":
    raise SystemExit(main())
