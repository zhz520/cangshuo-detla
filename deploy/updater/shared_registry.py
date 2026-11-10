#!/usr/bin/env python3
"""Validate and inspect a shared Delta tenant registry without modifying a database."""

import argparse
import json
import re
import sys
import uuid
from pathlib import Path
from urllib.parse import urlsplit

TENANT_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,47}$")
DB_NAME = re.compile(r"^[A-Za-z0-9_]{1,64}$")


def normalize_host(value):
    if not isinstance(value, str):
        raise ValueError("host must be a string")
    host = value.strip().lower().rstrip(".")
    if not host or len(host) > 253 or host.startswith("."):
        raise ValueError("invalid host")
    if any(char in host for char in "/\\@:*" ) or any(char.isspace() for char in host):
        raise ValueError("host contains port, path or wildcard")
    ascii_host = host.encode("idna").decode("ascii")
    labels = ascii_host.split(".")
    if any(not label or len(label) > 63 or label.startswith("-") or label.endswith("-") for label in labels):
        raise ValueError("invalid host label")
    return ascii_host


def validate_registry(document):
    if not isinstance(document, dict) or not isinstance(document.get("tenants"), dict):
        raise ValueError("registry must contain a tenants object")
    if not document["tenants"]:
        raise ValueError("registry has no tenants")
    seen_hosts = {}
    result = []
    for tenant_id, raw in document["tenants"].items():
        if not isinstance(tenant_id, str) or not TENANT_ID.fullmatch(tenant_id):
            raise ValueError("invalid tenant id: " + str(tenant_id))
        if not isinstance(raw, dict):
            raise ValueError("tenant entry must be an object: " + tenant_id)
        hosts = [normalize_host(host) for host in raw.get("hosts", [])]
        if len(set(hosts)) != len(hosts):
            raise ValueError("duplicate host in tenant: " + tenant_id)
        for host in hosts:
            if host in seen_hosts:
                raise ValueError("host belongs to multiple tenants: " + host)
            seen_hosts[host] = tenant_id
        active = raw.get("active", True)
        if not isinstance(active, bool):
            raise ValueError("active must be boolean: " + tenant_id)
        jdbc_url = raw.get("jdbcUrl", "")
        username = raw.get("username", "")
        password = raw.get("password", "")
        if active and (not hosts or not jdbc_url or not username or not password):
            raise ValueError("active tenant requires hosts, jdbcUrl, username and password: " + tenant_id)
        if jdbc_url and not str(jdbc_url).startswith("jdbc:mysql://"):
            raise ValueError("only MySQL JDBC URLs are supported: " + tenant_id)
        database = ""
        database_host = ""
        if jdbc_url:
            parsed = urlsplit(str(jdbc_url)[len("jdbc:"):])
            database = parsed.path.lstrip("/")
            if not parsed.hostname or not DB_NAME.fullmatch(database):
                raise ValueError("invalid JDBC host or database name: " + tenant_id)
            database_host = parsed.hostname
        instance_id = raw.get("platformInstanceId", "")
        if instance_id:
            uuid.UUID(str(instance_id))
        if active and not raw.get("platformCustomerId"):
            raise ValueError("active tenant requires platformCustomerId: " + tenant_id)
        if raw.get("miniappEnabled") and (not raw.get("miniappAppId") or not raw.get("miniappSecret")):
            raise ValueError("miniappEnabled requires app id and secret: " + tenant_id)
        pool = raw.get("maximumPoolSize", 8)
        if not isinstance(pool, int) or not 1 <= pool <= 100:
            raise ValueError("maximumPoolSize must be 1..100: " + tenant_id)
        result.append({
            "id": tenant_id,
            "active": active,
            "hosts": hosts,
            "database": database,
            "databaseHost": database_host,
            "username": username,
            "maximumPoolSize": pool,
            "platformCustomerId": raw.get("platformCustomerId", ""),
            "platformInstanceId": instance_id,
        })
    return result


def load(path):
    source = Path(path)
    if not source.is_file() or source.stat().st_size > 2_000_000:
        raise ValueError("registry file missing or too large")
    return json.loads(source.read_text(encoding="utf-8"))


def redacted(document):
    copy = json.loads(json.dumps(document))
    for tenant in copy.get("tenants", {}).values():
        if "password" in tenant:
            tenant["password"] = "***" if tenant["password"] else ""
        if "miniappSecret" in tenant:
            tenant["miniappSecret"] = "***" if tenant["miniappSecret"] else ""
    return copy


def sql_for(tenant):
    db = tenant["database"]
    user = tenant["username"]
    return (
        "CREATE DATABASE IF NOT EXISTS `{db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;\n"
        "CREATE USER IF NOT EXISTS '{user}'@'%' IDENTIFIED BY '<set-a-random-password>';\n"
        "GRANT ALL PRIVILEGES ON `{db}`.* TO '{user}'@'%';\n"
        "FLUSH PRIVILEGES;\n"
    ).format(db=db, user=user)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "redact", "sql"))
    parser.add_argument("registry")
    parser.add_argument("--tenant", help="tenant id for sql output")
    args = parser.parse_args()
    document = load(args.registry)
    tenants = validate_registry(document)
    if args.command == "validate":
        for tenant in tenants:
            print(json.dumps({key: value for key, value in tenant.items() if key not in ("password", "miniappSecret")}, ensure_ascii=False, sort_keys=True))
    elif args.command == "redact":
        print(json.dumps(redacted(document), ensure_ascii=False, indent=2))
    else:
        selected = [tenant for tenant in tenants if not args.tenant or tenant["id"] == args.tenant]
        if not selected:
            raise ValueError("tenant not found")
        for tenant in selected:
            if not tenant["database"]:
                raise ValueError("tenant has no database: " + tenant["id"])
            print("-- " + tenant["id"])
            print(sql_for(tenant), end="")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
