#!/usr/bin/env python3
"""Merge authorization tenant metadata with protected database credentials.

The authorization export supplies explicit runtime tenant ids and platform
instance UUIDs. The local credential file supplies database and optional
miniapp values keyed by the same runtime tenant id. A UUID is never converted
into a tenant id. The command is dry-run by default and never prints passwords.
"""

import argparse
import copy
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

import shared_registry


MAX_JSON_BYTES = 2_000_000
MAX_TENANTS = 500
MAX_HOSTS = 50
MAX_WILDCARDS = 50
MAX_JSON_DEPTH = 20

EXPORT_KEYS = {
    "tenantId", "name", "customerId", "projectId", "productId",
    "platformCustomerId", "platformInstanceId", "hosts", "wildcardDomains",
    "serviceGroupId", "maintenanceUntil", "active",
}
EXPORT_CREDENTIAL_KEYS = {
    "jdbcUrl", "username", "password", "passwordHash", "driverClassName",
    "miniappAppId", "miniappSecret", "storageRoot",
}
CREDENTIAL_KEYS = {
    "tenantId", "platformInstanceId", "platformCustomerId", "jdbcUrl",
    "username", "password", "driverClassName", "maximumPoolSize",
    "minimumIdle", "connectionTimeoutMs", "storageRoot", "miniappEnabled",
    "miniappAppId", "miniappSecret",
}
ROUTING_KEYS = {"active", "hosts", "wildcardDomains"}
REGISTRY_CREDENTIAL_FIELDS = (
    "jdbcUrl", "username", "password", "driverClassName", "maximumPoolSize",
    "minimumIdle", "connectionTimeoutMs", "storageRoot", "miniappEnabled",
    "miniappAppId", "miniappSecret",
)
JDBC_SECRET_QUERY_KEYS = {
    "password", "passwd", "pwd", "secret", "token", "accesskey", "secretkey",
}
DB_USER = re.compile(r"^[A-Za-z0-9_.-]{1,32}$")


def _json_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError("JSON must not contain non-finite numbers")


def _check_depth(value):
    stack = [(value, 1)]
    while stack:
        item, depth = stack.pop()
        if depth > MAX_JSON_DEPTH:
            raise ValueError("JSON nesting is too deep")
        if isinstance(item, dict):
            for child in item.values():
                stack.append((child, depth + 1))
        elif isinstance(item, list):
            for child in item:
                stack.append((child, depth + 1))


def load_json_limited(path, label, private=False):
    """Load bounded strict JSON and return (document, raw bytes, sha256)."""
    source = Path(path)
    if source.is_symlink():
        raise ValueError(label + " must not be a symbolic link")
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(str(source), flags)
    except OSError as error:
        raise ValueError(label + " is not readable") from error
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode):
            raise ValueError(label + " must be a regular file")
        if info.st_size > MAX_JSON_BYTES:
            raise ValueError(label + " is too large")
        if private and os.name == "posix" and info.st_mode & 0o077:
            raise ValueError(label + " must be readable only by its owner (0600)")
        with os.fdopen(descriptor, "rb") as stream:
            descriptor = -1
            raw = stream.read(MAX_JSON_BYTES + 1)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    if len(raw) > MAX_JSON_BYTES:
        raise ValueError(label + " is too large")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise ValueError(label + " must be UTF-8 JSON") from error
    try:
        document = json.loads(
            text,
            object_pairs_hook=_json_pairs,
            parse_constant=_reject_constant,
        )
    except RecursionError as error:
        raise ValueError(label + " JSON nesting is too deep") from error
    except json.JSONDecodeError as error:
        raise ValueError(label + " is not valid JSON at line " +
                         str(error.lineno) + " column " + str(error.colno)) from error
    _check_depth(document)
    if not isinstance(document, dict):
        raise ValueError(label + " root must be a JSON object")
    return document, raw, hashlib.sha256(raw).hexdigest()


def _unexpected(document, allowed, label):
    unknown = sorted(set(document) - set(allowed))
    if unknown:
        safe = []
        for name in unknown[:20]:
            if re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", name):
                safe.append(name)
            else:
                safe.append("<invalid-key>")
        suffix = "" if len(unknown) <= 20 else " (+" + str(len(unknown) - 20) + " more)"
        raise ValueError(label + " has unexpected fields: " + ", ".join(safe) + suffix)


def _text(mapping, key, label, required=False, maximum=500, strip=True,
          allow_empty=False):
    if key not in mapping:
        if required:
            raise ValueError(label + " requires " + key)
        return ""
    value = mapping[key]
    if value is None and not required:
        return ""
    if not isinstance(value, str):
        raise ValueError(label + " " + key + " must be a string")
    text = value.strip() if strip else value
    if not text and not allow_empty:
        raise ValueError(label + " " + key + " must not be empty")
    if len(text) > maximum:
        raise ValueError(label + " " + key + " is too long")
    if any(ord(character) < 32 or ord(character) == 127 for character in text):
        raise ValueError(label + " " + key + " contains control characters")
    return text


def _optional_text(mapping, key, label, maximum=500):
    if key not in mapping or mapping[key] is None:
        return ""
    return _text(mapping, key, label, maximum=maximum, allow_empty=True)


def _boolean(mapping, key, label, required=False):
    if key not in mapping:
        if required:
            raise ValueError(label + " requires " + key)
        return None
    value = mapping[key]
    if not isinstance(value, bool):
        raise ValueError(label + " " + key + " must be boolean")
    return value


def _integer(mapping, key, label, minimum, maximum):
    if key not in mapping:
        return None
    value = mapping[key]
    if type(value) is not int:
        raise ValueError(label + " " + key + " must be an integer")
    if not minimum <= value <= maximum:
        raise ValueError(label + " " + key + " is out of range")
    return value


def _tenant_id(mapping, label):
    tenant_id = _text(mapping, "tenantId", label, required=True, maximum=48)
    if not shared_registry.TENANT_ID.fullmatch(tenant_id):
        raise ValueError(label + " tenantId must be a lowercase runtime id")
    try:
        parsed = uuid.UUID(tenant_id)
    except (ValueError, AttributeError):
        parsed = None
    if parsed is not None:
        raise ValueError(label + " tenantId must be a runtime id, not a platform instance UUID")
    return tenant_id


def _instance_id(mapping, label, required=False):
    value = _text(mapping, "platformInstanceId", label, required=required,
                  maximum=36, allow_empty=not required)
    if not value:
        return ""
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError) as error:
        raise ValueError(label + " platformInstanceId must be a UUID") from error
    return str(parsed)


def _hosts(mapping, key, label, active):
    if key not in mapping:
        values = []
    else:
        values = mapping[key]
    if not isinstance(values, list):
        raise ValueError(label + " " + key + " must be an array")
    if len(values) > MAX_HOSTS:
        raise ValueError(label + " " + key + " has too many entries")
    normalized = []
    for value in values:
        if not isinstance(value, str):
            raise ValueError(label + " " + key + " entries must be strings")
        try:
            normalized.append(shared_registry.normalize_host(value))
        except ValueError as error:
            raise ValueError(label + " has an invalid host") from error
    if len(set(normalized)) != len(normalized):
        raise ValueError(label + " has duplicate hosts")
    if active and not normalized:
        raise ValueError(label + " active tenant requires hosts")
    return sorted(normalized)


def _wildcards(mapping, label):
    if "wildcardDomains" not in mapping:
        return []
    values = mapping["wildcardDomains"]
    if not isinstance(values, list):
        raise ValueError(label + " wildcardDomains must be an array")
    if len(values) > MAX_WILDCARDS:
        raise ValueError(label + " wildcardDomains has too many entries")
    normalized = []
    for value in values:
        if not isinstance(value, str):
            raise ValueError(label + " wildcardDomains entries must be strings")
        if not value.startswith("*."):
            raise ValueError(label + " wildcardDomains entries must start with *.")
        try:
            suffix = shared_registry.normalize_host(value[2:])
        except ValueError as error:
            raise ValueError(label + " has an invalid wildcard domain") from error
        normalized.append("*." + suffix)
    if len(set(normalized)) != len(normalized):
        raise ValueError(label + " has duplicate wildcard domains")
    return normalized


def validate_export(document):
    _unexpected(document, {"formatVersion", "generatedAt", "tenants"},
                "authorization export")
    version = document.get("formatVersion")
    if type(version) is not int or version != 1:
        raise ValueError("authorization export formatVersion must be 1")
    if "generatedAt" in document and document["generatedAt"] is not None:
        _text(document, "generatedAt", "authorization export", maximum=80)
    tenants = document.get("tenants")
    if not isinstance(tenants, list):
        raise ValueError("authorization export tenants must be an array")
    if not tenants:
        raise ValueError("authorization export has no tenants")
    if len(tenants) > MAX_TENANTS:
        raise ValueError("authorization export has too many tenants")

    result = []
    ids = set()
    hosts = {}
    instances = {}
    for index, raw in enumerate(tenants):
        label = "authorization export tenant " + str(index + 1)
        if not isinstance(raw, dict):
            raise ValueError(label + " must be an object")
        exposed = sorted(set(raw) & EXPORT_CREDENTIAL_KEYS)
        if exposed:
            raise ValueError(
                "authorization export must not contain credential fields: " +
                ", ".join(exposed)
            )
        _unexpected(raw, EXPORT_KEYS, label)
        tenant_id = _tenant_id(raw, label)
        if tenant_id in ids:
            raise ValueError("duplicate tenantId in authorization export: " + tenant_id)
        ids.add(tenant_id)
        active = _boolean(raw, "active", label, required=True)
        tenant_hosts = _hosts(raw, "hosts", label, active)
        for host in tenant_hosts:
            if host in hosts:
                raise ValueError("host belongs to multiple tenants: " + host)
            hosts[host] = tenant_id
        wildcards = _wildcards(raw, label)
        if wildcards:
            raise ValueError(
                label + " wildcardDomains are not supported by the shared registry; "
                "list each exact runtime host instead"
            )
        instance_id = _instance_id(raw, label, required=active)
        if instance_id:
            if instance_id in instances:
                raise ValueError("platformInstanceId is duplicated in authorization export")
            instances[instance_id] = tenant_id
        for key in ("name", "customerId", "projectId", "productId",
                    "platformCustomerId", "serviceGroupId", "maintenanceUntil"):
            _optional_text(raw, key, label, maximum=200 if key == "name" else 128)
        service_group_id = _optional_text(raw, "serviceGroupId", label, maximum=48)
        if service_group_id and not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,47}", service_group_id):
            raise ValueError(label + " serviceGroupId must be a lowercase service-group id")
        result.append({
            "tenantId": tenant_id,
            "active": active,
            "hosts": tenant_hosts,
            "platformInstanceId": instance_id,
            "platformCustomerId": _optional_text(
                raw, "platformCustomerId", label, maximum=128
            ),
            "serviceGroupId": service_group_id,
        })
    return result


def _validate_jdbc_url(value, label):
    if not value.startswith("jdbc:mysql://"):
        raise ValueError(label + " jdbcUrl must use jdbc:mysql://")
    parsed = urlsplit(value[len("jdbc:"):])
    if parsed.username is not None or parsed.password is not None:
        raise ValueError(label + " jdbcUrl must not contain embedded credentials")
    for key, unused in parse_qsl(parsed.query, keep_blank_values=True):
        if key.lower() in JDBC_SECRET_QUERY_KEYS:
            raise ValueError(label + " jdbcUrl must not contain secret query parameters")
    return value


def _storage_root(value, label):
    if not value:
        return ""
    if not (value.startswith("/") or re.match(r"^[A-Za-z]:[\\/]", value)):
        raise ValueError(label + " storageRoot must be absolute")
    if ".." in re.split(r"[\\/]+", value):
        raise ValueError(label + " storageRoot must not contain path traversal")
    return value


def _credential_rows(document):
    _unexpected(document, {"formatVersion", "tenants"}, "credential file")
    version = document.get("formatVersion")
    if type(version) is not int or version != 1:
        raise ValueError("credential file formatVersion must be 1")
    raw_tenants = document.get("tenants")
    rows = []
    if isinstance(raw_tenants, dict):
        for index, (key, value) in enumerate(raw_tenants.items()):
            if not isinstance(key, str) or not isinstance(value, dict):
                raise ValueError("credential tenants must be objects keyed by tenantId")
            rows.append((key, value, "credential tenant " + str(index + 1)))
    elif isinstance(raw_tenants, list):
        for index, value in enumerate(raw_tenants):
            if not isinstance(value, dict):
                raise ValueError("credential tenants entries must be objects")
            rows.append((None, value, "credential tenant " + str(index + 1)))
    else:
        raise ValueError("credential file tenants must be an object or array")
    return rows


def validate_credentials(document):
    rows = _credential_rows(document)
    if len(rows) > MAX_TENANTS:
        raise ValueError("credential file has too many tenants")
    result = {}
    for map_key, raw, label in rows:
        routing = sorted(set(raw) & ROUTING_KEYS)
        if routing:
            raise ValueError(
                "credential file must not define routing fields: " +
                ", ".join(routing)
            )
        _unexpected(raw, CREDENTIAL_KEYS, label)
        tenant_id = _tenant_id(raw, label)
        if map_key is not None and map_key != tenant_id:
            raise ValueError(label + " tenantId must match its map key")
        if tenant_id in result:
            raise ValueError("duplicate tenantId in credential file: " + tenant_id)
        instance_id = _instance_id(raw, label)
        customer_id = _optional_text(raw, "platformCustomerId", label, maximum=128)

        db_keys = {
            "jdbcUrl", "username", "password", "driverClassName",
            "maximumPoolSize", "minimumIdle", "connectionTimeoutMs",
        }
        present_db = set(raw) & db_keys
        credential = {}
        if present_db:
            if not {"jdbcUrl", "username", "password"}.issubset(raw):
                raise ValueError(label + " database credentials are incomplete")
            jdbc_url = _text(raw, "jdbcUrl", label, required=True, maximum=2048)
            credential["jdbcUrl"] = _validate_jdbc_url(jdbc_url, label)
            username = _text(raw, "username", label, required=True, maximum=32)
            if not DB_USER.fullmatch(username):
                raise ValueError(label + " username is not a safe database identifier")
            credential["username"] = username
            credential["password"] = _text(
                raw, "password", label, required=True, maximum=512, strip=False
            )
            driver = _optional_text(raw, "driverClassName", label, maximum=200)
            if driver:
                if driver != "com.mysql.cj.jdbc.Driver":
                    raise ValueError(label + " driverClassName is not supported")
                credential["driverClassName"] = driver
            maximum_pool = _integer(raw, "maximumPoolSize", label, 1, 100)
            minimum_idle = _integer(raw, "minimumIdle", label, 0, 100)
            timeout = _integer(raw, "connectionTimeoutMs", label, 250, 60000)
            if maximum_pool is not None:
                credential["maximumPoolSize"] = maximum_pool
            if minimum_idle is not None:
                if minimum_idle > (maximum_pool if maximum_pool is not None else 8):
                    raise ValueError(label + " minimumIdle must not exceed maximumPoolSize")
                credential["minimumIdle"] = minimum_idle
            if timeout is not None:
                credential["connectionTimeoutMs"] = timeout

        storage = _storage_root(
            _optional_text(raw, "storageRoot", label, maximum=512), label
        )
        if storage:
            credential["storageRoot"] = storage
        enabled = _boolean(raw, "miniappEnabled", label)
        if enabled is not None:
            credential["miniappEnabled"] = enabled
        app_id = _optional_text(raw, "miniappAppId", label, maximum=128)
        secret = _text(
            raw, "miniappSecret", label, maximum=512, strip=False,
            allow_empty=True,
        ) if "miniappSecret" in raw else ""
        if enabled and (not app_id or not secret):
            raise ValueError(label + " miniappEnabled requires app id and secret")
        if app_id:
            credential["miniappAppId"] = app_id
        if secret:
            credential["miniappSecret"] = secret
        if instance_id:
            credential["platformInstanceId"] = instance_id
        if customer_id:
            credential["platformCustomerId"] = customer_id
        result[tenant_id] = credential
    return result


def _canonical_bytes(document):
    return (json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True)
            + "\n").encode("utf-8")


def _inside(path, root):
    try:
        Path(path).relative_to(Path(root))
        return True
    except ValueError:
        return False


def safe_output_path(value, allowed_root):
    target = Path(value)
    root = Path(allowed_root)
    if not target.is_absolute() or not root.is_absolute():
        raise ValueError("output and allowed root must be absolute paths")
    if ".." in target.parts or ".." in root.parts:
        raise ValueError("output path must not contain path traversal")
    if not _inside(target, root) or target == root:
        raise ValueError("output must be a file inside the allowed root")
    if root.is_symlink() or not root.is_dir():
        raise ValueError("allowed root must be a real directory")
    if not target.parent.exists() or not target.parent.is_dir():
        raise ValueError("output parent directory must already exist")
    current = target.parent
    while current != root:
        if current.is_symlink():
            raise ValueError("output path must not traverse symbolic links")
        if root not in current.parents:
            raise ValueError("output escaped the allowed root")
        current = current.parent
    if target.is_symlink():
        raise ValueError("output must not be a symbolic link")
    if target.exists() and not target.is_file():
        raise ValueError("output must be a regular file")
    if not _inside(target.parent.resolve(strict=True), root.resolve(strict=True)):
        raise ValueError("output escaped the allowed root")
    return target.parent.resolve(strict=True) / target.name


def safe_audit_path(value):
    target = Path(value)
    if not target.is_absolute():
        raise ValueError("audit log must be an absolute path")
    if ".." in target.parts:
        raise ValueError("audit log must not contain path traversal")
    if not target.parent.exists() or not target.parent.is_dir():
        raise ValueError("audit log parent directory must already exist")
    current = target.parent
    while current != current.parent:
        if current.is_symlink():
            raise ValueError("audit log must not traverse symbolic links")
        current = current.parent
    if target.is_symlink():
        raise ValueError("audit log must not be a symbolic link")
    if target.exists() and not target.is_file():
        raise ValueError("audit log must be a regular file")
    return target.parent.resolve(strict=True) / target.name


def _same_path(left, right):
    return os.path.normcase(os.path.abspath(str(left))) == os.path.normcase(
        os.path.abspath(str(right))
    )


def atomic_write_json(target, document):
    """Write canonical JSON via a private temporary file and atomic replace."""
    target = Path(target)
    payload = _canonical_bytes(document)
    descriptor, temporary = tempfile.mkstemp(
        prefix="." + target.name + ".", suffix=".tmp", dir=str(target.parent)
    )
    temporary_path = Path(temporary)
    try:
        if hasattr(os, "fchmod"):
            os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            descriptor = -1
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary_path, 0o600)
        os.replace(str(temporary_path), str(target))
        temporary_path = None
        _fsync_directory(target.parent)
        os.chmod(target, 0o600)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()


def _fsync_directory(directory):
    if os.name != "posix":
        return
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    try:
        descriptor = os.open(str(directory), flags)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    except OSError:
        pass


def _load_existing(target):
    if not target.exists():
        return None
    document, unused_raw, unused_hash = load_json_limited(
        target, "existing registry"
    )
    try:
        shared_registry.validate_registry(document)
    except (ValueError, TypeError, AttributeError, OSError) as error:
        raise ValueError("existing registry is invalid: " + str(error)) from error
    return document


def _tenant_entry(export_tenant, credential, existing_entry):
    tenant_id = export_tenant["tenantId"]
    active = export_tenant["active"]
    if active and not credential:
        raise ValueError("active tenant requires local credentials: " + tenant_id)
    credential = credential or {}
    if active and not credential.get("jdbcUrl"):
        raise ValueError("active tenant requires local database credentials: " + tenant_id)

    use_existing_fill = not active and not credential and existing_entry is not None
    entry = copy.deepcopy(existing_entry) if use_existing_fill else {}
    entry["active"] = active
    entry["hosts"] = list(export_tenant["hosts"])

    export_instance = export_tenant["platformInstanceId"]
    credential_instance = credential.get("platformInstanceId", "")
    existing_instance = existing_entry.get("platformInstanceId", "") if existing_entry else ""
    if export_instance and existing_instance and export_instance != existing_instance:
        raise ValueError("platformInstanceId conflicts with existing registry: " + tenant_id)
    if export_instance and credential_instance and export_instance != credential_instance:
        raise ValueError("platformInstanceId does not match tenant: " + tenant_id)
    instance_id = export_instance or credential_instance
    if active and not instance_id:
        raise ValueError("active tenant requires platformInstanceId: " + tenant_id)
    if instance_id:
        entry["platformInstanceId"] = instance_id

    export_customer = export_tenant["platformCustomerId"]
    credential_customer = credential.get("platformCustomerId", "")
    existing_customer = existing_entry.get("platformCustomerId", "") if existing_entry else ""
    if export_customer and existing_customer and export_customer != existing_customer:
        raise ValueError("platformCustomerId conflicts with existing registry: " + tenant_id)
    if export_customer and credential_customer and export_customer != credential_customer:
        raise ValueError("platformCustomerId does not match tenant: " + tenant_id)
    customer_id = export_customer or credential_customer
    if customer_id:
        entry["platformCustomerId"] = customer_id
    elif use_existing_fill and entry.get("platformCustomerId"):
        customer_id = entry["platformCustomerId"]
    if active and not customer_id:
        raise ValueError("active tenant requires platformCustomerId: " + tenant_id)

    for key in REGISTRY_CREDENTIAL_FIELDS:
        if key in credential:
            entry[key] = credential[key]
        elif not use_existing_fill and key in entry:
            del entry[key]
    return entry


def _tenant_diff(existing, merged, export_ids):
    old = existing.get("tenants", {}) if existing else {}
    new = merged["tenants"]
    added = sorted(set(new) - set(old))
    retained = sorted(set(old) - set(export_ids))
    changed = []
    for tenant_id in sorted(set(old) & set(new)):
        fields = sorted(
            key for key in set(old[tenant_id]) | set(new[tenant_id])
            if old[tenant_id].get(key) != new[tenant_id].get(key)
        )
        if fields:
            changed.append({"tenantId": tenant_id, "fields": fields})
    old_hosts = {
        host for entry in old.values() for host in entry.get("hosts", [])
    }
    new_hosts = {
        host for entry in new.values() for host in entry.get("hosts", [])
    }
    return {
        "addedTenantIds": added,
        "changedTenants": changed,
        "retainedTenantIds": retained,
        "removedTenantIds": [],
        "hostsAdded": sorted(new_hosts - old_hosts),
        "hostsRemoved": sorted(old_hosts - new_hosts),
    }


def merge_documents(export_document, credentials_document, existing_document=None):
    export_tenants = validate_export(export_document)
    credentials = validate_credentials(credentials_document)
    export_ids = {tenant["tenantId"] for tenant in export_tenants}
    unknown = sorted(set(credentials) - export_ids)
    if unknown:
        raise ValueError(
            "credentials reference tenants absent from authorization export: " +
            ", ".join(unknown)
        )

    existing_tenants = {}
    if existing_document is not None:
        existing_tenants = copy.deepcopy(existing_document.get("tenants", {}))
    merged_tenants = copy.deepcopy(existing_tenants)
    for export_tenant in export_tenants:
        tenant_id = export_tenant["tenantId"]
        merged_tenants[tenant_id] = _tenant_entry(
            export_tenant,
            credentials.get(tenant_id),
            existing_tenants.get(tenant_id),
        )
    merged = {
        "tenants": {
            tenant_id: merged_tenants[tenant_id]
            for tenant_id in sorted(merged_tenants)
        }
    }
    try:
        shared_registry.validate_registry(merged)
    except (ValueError, TypeError, AttributeError, OSError) as error:
        raise ValueError("merged registry is invalid: " + str(error)) from error
    diff = _tenant_diff(existing_document, merged, export_ids)
    return merged, diff


def sql_template(document, tenant=None):
    rows = shared_registry.validate_registry(document)
    if tenant:
        if not shared_registry.TENANT_ID.fullmatch(tenant):
            raise ValueError("tenant id is invalid")
        rows = [row for row in rows if row["id"] == tenant]
        if not rows:
            raise ValueError("tenant not found: " + tenant)
    else:
        rows = [row for row in rows if row.get("active") and row["database"]]
    blocks = []
    for row in rows:
        if not row["database"]:
            raise ValueError("tenant has no database credentials: " + row["id"])
        blocks.append("-- " + row["id"] + "\n" + shared_registry.sql_for(row))
    if not blocks:
        raise ValueError("no active tenant has database credentials")
    return "\n".join(blocks)


def _combined_hash(export_bytes, credential_bytes):
    digest = hashlib.sha256()
    digest.update(b"delta-tenant-onboarding-v1\n")
    digest.update(export_bytes)
    digest.update(b"\n")
    digest.update(credential_bytes)
    return digest.hexdigest()


def _audit_actor(value):
    return _text({"actor": value}, "actor", "audit", required=True, maximum=128)


def append_audit(path, record):
    target = safe_audit_path(path)
    payload = (json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    flags = os.O_CREAT | os.O_APPEND | os.O_WRONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(str(target), flags, 0o600)
    try:
        if hasattr(os, "fchmod"):
            os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "ab", closefd=True) as stream:
            descriptor = -1
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    os.chmod(target, 0o600)


def _merge_command(args):
    target = safe_output_path(args.output, args.allowed_root)
    if any(_same_path(target, item) for item in (
        Path(args.authorization_export), Path(args.credentials)
    )):
        raise ValueError("output must differ from all input files")
    export_document, export_bytes, export_hash = load_json_limited(
        args.authorization_export, "authorization export"
    )
    credentials_document, credential_bytes, credential_hash = load_json_limited(
        args.credentials, "credential file", private=True
    )
    existing = _load_existing(target)
    merged, diff = merge_documents(export_document, credentials_document, existing)
    action = "create"
    if existing is not None:
        action = "unchanged" if existing.get("tenants", {}) == merged["tenants"] else "update"
    input_hash = _combined_hash(export_bytes, credential_bytes)
    tenant_ids = sorted(merged["tenants"])
    active_ids = sorted(
        tenant_id for tenant_id, entry in merged["tenants"].items()
        if entry.get("active") is True
    )

    actor = ""
    audit_path = None
    if args.audit_log:
        actor = _audit_actor(args.actor)
        audit_path = safe_audit_path(args.audit_log)
        protected_paths = [
            Path(args.authorization_export), Path(args.credentials), target
        ]
        if any(_same_path(audit_path, item) for item in protected_paths):
            raise ValueError("audit log must differ from all input and output files")
    if args.apply:
        if action == "unchanged":
            os.chmod(target, 0o600)
        else:
            atomic_write_json(target, merged)

    result = "DRY_RUN"
    if args.apply:
        result = "UNCHANGED" if action == "unchanged" else "APPLIED"
    summary = {
        "mode": "apply" if args.apply else "dry-run",
        "result": result,
        "action": action,
        "target": str(target),
        "tenantCount": len(tenant_ids),
        "tenantIds": tenant_ids,
        "activeTenantIds": active_ids,
        "inputs": {
            "inputSha256": input_hash,
            "exportSha256": export_hash,
            "credentialsSha256": credential_hash,
        },
        "diff": diff,
    }
    if audit_path is not None:
        append_audit(audit_path, {
            "formatVersion": 1,
            "at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "actor": actor,
            "operation": "tenant-registry-merge",
            "result": result,
            "action": action,
            "target": str(target),
            "inputSha256": input_hash,
            "exportSha256": export_hash,
            "credentialsSha256": credential_hash,
            "tenantIds": tenant_ids,
            "activeTenantIds": active_ids,
        })
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def _sql_command(args):
    export_document, unused_export_bytes, unused_export_hash = load_json_limited(
        args.authorization_export, "authorization export"
    )
    credentials_document, unused_credential_bytes, unused_credential_hash = load_json_limited(
        args.credentials, "credential file", private=True
    )
    merged, unused_diff = merge_documents(
        export_document, credentials_document, None
    )
    print(sql_template(merged, args.tenant), end="")
    return 0


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command")

    merge = commands.add_parser(
        "merge", help="preview or apply a tenant registry merge"
    )
    merge.add_argument("--authorization-export", required=True)
    merge.add_argument("--credentials", required=True)
    merge.add_argument("--output", required=True)
    merge.add_argument("--allowed-root", required=True)
    merge.add_argument("--apply", action="store_true")
    merge.add_argument("--audit-log")
    merge.add_argument("--actor")

    sql = commands.add_parser(
        "sql-template", help="print password-free database/account SQL templates"
    )
    sql.add_argument("--authorization-export", required=True)
    sql.add_argument("--credentials", required=True)
    sql.add_argument("--tenant")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.command:
        parser.error("a command is required")
    if args.command == "merge":
        return _merge_command(args)
    if args.command == "sql-template":
        return _sql_command(args)
    parser.error("unknown command")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
