#!/usr/bin/env python3
"""Controlled per-tenant migration state machine with evidence and audit.

The tool records phases; it does not copy data, change DNS, stop services or
execute a migration. Evidence comes from the backup, import, file, finance and
acceptance tools and is validated before a phase transition is accepted.
"""

import argparse
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

TENANT = re.compile(r"^[a-z0-9][a-z0-9-]{0,47}$")
TICKET = re.compile(r"^[A-Za-z0-9._:-]{4,80}$")
HASH = re.compile(r"^[0-9a-f]{64}$")
DATABASE = re.compile(r"^[A-Za-z0-9_]{1,64}$")

PHASES = (
    "planned", "backup_verified", "target_ready", "maintenance_started",
    "data_imported", "files_synced", "finance_verified", "accepted",
    "route_switched", "observed", "old_stopped", "rolled_back", "failed",
)
TRANSITIONS = {
    "planned": ("backup_verified", "failed"),
    "backup_verified": ("target_ready", "failed"),
    "target_ready": ("maintenance_started", "rolled_back", "failed"),
    "maintenance_started": ("data_imported", "rolled_back", "failed"),
    "data_imported": ("files_synced", "rolled_back", "failed"),
    "files_synced": ("finance_verified", "rolled_back", "failed"),
    "finance_verified": ("accepted", "rolled_back", "failed"),
    "accepted": ("route_switched", "rolled_back", "failed"),
    "route_switched": ("observed", "rolled_back", "failed"),
    "observed": ("old_stopped", "rolled_back", "failed"),
    "old_stopped": (),
    "rolled_back": (),
    "failed": (),
}


def instant(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(field + " must be an ISO-8601 UTC timestamp")
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except AttributeError:
        parsed = None
        fallback = re.sub(r"([+-]\d{2}):(\d{2})$", r"\1\2", text)
        for pattern in ("%Y-%m-%dT%H:%M:%S.%f%z", "%Y-%m-%dT%H:%M:%S%z"):
            try:
                parsed = datetime.strptime(fallback, pattern)
                break
            except ValueError:
                pass
        if parsed is None:
            raise ValueError(field + " must be an ISO-8601 UTC timestamp")
    except ValueError as error:
        raise ValueError(field + " must be an ISO-8601 UTC timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(field + " must include a timezone")
    return parsed.astimezone(timezone.utc)


def require_bool(evidence, key):
    if evidence.get(key) is not True:
        raise ValueError("evidence " + key + " must be true")


def require_hash(evidence, key):
    value = evidence.get(key)
    if not isinstance(value, str) or not HASH.fullmatch(value):
        raise ValueError("evidence " + key + " must be a SHA-256 hex digest")


def require_text(evidence, key, maximum=500):
    value = evidence.get(key)
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > maximum:
        raise ValueError("evidence " + key + " must be non-empty text")
    return value.strip()


def require_database(evidence, key):
    value = evidence.get(key)
    if not isinstance(value, str) or not DATABASE.fullmatch(value):
        raise ValueError("evidence " + key + " must be a database identifier")


ALLOWED = {
    "backup_verified": {"backupSha256", "backupBytes", "restoreVerified"},
    "target_ready": {"targetDatabase", "registryValidated", "syntheticStartVerified"},
    "maintenance_started": {"windowStart", "windowEnd", "oldWritesStopped", "callbackReceiverVerified"},
    "data_imported": {"sourceDatabase", "importReportSha256", "rowCountsVerified"},
    "files_synced": {"fileReportSha256", "fileReportIdentical"},
    "finance_verified": {"financeReportSha256", "financeReportPassed"},
    "accepted": {"customerAccepted", "acceptanceEvidenceSha256", "acceptanceAt"},
    "route_switched": {"routeGeneration", "dnsVerified", "healthVerified"},
    "observed": {"observationEndsAt", "healthVerified"},
    "old_stopped": {"oldServicesStopped", "backupRetained"},
    "rolled_back": {"reason", "rollbackVerified"},
    "failed": {"reason"},
}


def validate_evidence(to_phase, evidence, state, now):
    if not isinstance(evidence, dict):
        raise ValueError("evidence must be an object")
    allowed = ALLOWED.get(to_phase, set())
    unexpected = set(evidence) - allowed
    if unexpected:
        raise ValueError("unexpected evidence keys: " + ",".join(sorted(unexpected)))
    if to_phase == "backup_verified":
        require_hash(evidence, "backupSha256")
        size = evidence.get("backupBytes")
        if not isinstance(size, int) or size <= 0:
            raise ValueError("evidence backupBytes must be a positive integer")
        require_bool(evidence, "restoreVerified")
    elif to_phase == "target_ready":
        require_database(evidence, "targetDatabase")
        require_bool(evidence, "registryValidated")
        require_bool(evidence, "syntheticStartVerified")
    elif to_phase == "maintenance_started":
        start = instant(evidence.get("windowStart"), "windowStart")
        end = instant(evidence.get("windowEnd"), "windowEnd")
        if end <= start or end - start > timedelta(hours=8):
            raise ValueError("maintenance window is invalid or longer than eight hours")
        if not start <= now <= end:
            raise ValueError("maintenance window is not open")
        require_bool(evidence, "oldWritesStopped")
        require_bool(evidence, "callbackReceiverVerified")
        state["maintenanceWindow"] = {"startAt": start.isoformat(), "endAt": end.isoformat()}
    elif to_phase == "data_imported":
        require_database(evidence, "sourceDatabase")
        require_hash(evidence, "importReportSha256")
        require_bool(evidence, "rowCountsVerified")
    elif to_phase == "files_synced":
        require_hash(evidence, "fileReportSha256")
        require_bool(evidence, "fileReportIdentical")
    elif to_phase == "finance_verified":
        require_hash(evidence, "financeReportSha256")
        require_bool(evidence, "financeReportPassed")
    elif to_phase == "accepted":
        require_bool(evidence, "customerAccepted")
        require_hash(evidence, "acceptanceEvidenceSha256")
        accepted_at = instant(evidence.get("acceptanceAt"), "acceptanceAt")
        if accepted_at > now:
            raise ValueError("acceptanceAt is in the future")
    elif to_phase == "route_switched":
        generation = evidence.get("routeGeneration")
        if not isinstance(generation, int) or generation <= int(state.get("routeGeneration", 0)):
            raise ValueError("routeGeneration must increase")
        require_bool(evidence, "dnsVerified")
        require_bool(evidence, "healthVerified")
        state["routeGeneration"] = generation
    elif to_phase == "observed":
        ends = instant(evidence.get("observationEndsAt"), "observationEndsAt")
        if ends > now:
            raise ValueError("observation period has not ended")
        require_bool(evidence, "healthVerified")
    elif to_phase == "old_stopped":
        require_bool(evidence, "oldServicesStopped")
        require_bool(evidence, "backupRetained")
    elif to_phase in ("rolled_back", "failed"):
        require_text(evidence, "reason")
        if to_phase == "rolled_back":
            require_bool(evidence, "rollbackVerified")


def tenant_dir(state_dir, tenant):
    if not TENANT.fullmatch(tenant or ""):
        raise ValueError("invalid tenant id")
    root = Path(state_dir)
    if not root.is_absolute():
        raise ValueError("state directory must be absolute")
    directory = root / tenant
    directory.mkdir(parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    return directory


def load_state(state_dir, tenant):
    path = tenant_dir(state_dir, tenant) / "state.json"
    if not path.exists():
        raise ValueError("tenant state does not exist")
    if path.stat().st_size > 1_000_000:
        raise ValueError("tenant state is too large")
    state = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(state, dict) or state.get("tenant") != tenant or state.get("phase") not in PHASES:
        raise ValueError("tenant state is invalid")
    return state


def write_state(state_dir, tenant, state):
    directory = tenant_dir(state_dir, tenant)
    path = directory / "state.json"
    temporary = directory / "state.json.tmp"
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(str(temporary), str(path))


def append_audit(state_dir, tenant, record):
    path = tenant_dir(state_dir, tenant) / "audit.jsonl"
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    os.chmod(path, 0o600)


class Lock:
    def __init__(self, state_dir, tenant):
        self.path = tenant_dir(state_dir, tenant) / "transition.lock"
        self.descriptor = None

    def __enter__(self):
        try:
            self.descriptor = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError as error:
            raise RuntimeError("another transition is running for this tenant") from error
        os.write(self.descriptor, str(os.getpid()).encode())
        os.close(self.descriptor)
        return self

    def __exit__(self, *args):
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass


def initialize(state_dir, tenant, change_ticket, now=None):
    if not TICKET.fullmatch(change_ticket or ""):
        raise ValueError("change ticket must be 4-80 safe characters")
    now = now or datetime.now(timezone.utc)
    with Lock(state_dir, tenant):
        path = tenant_dir(state_dir, tenant) / "state.json"
        if path.exists():
            raise ValueError("tenant state already exists")
        state = {
            "version": 1,
            "tenant": tenant,
            "changeTicket": change_ticket,
            "phase": "planned",
            "revision": 0,
            "routeGeneration": 0,
            "createdAt": now.isoformat(),
            "updatedAt": now.isoformat(),
            "evidence": {},
        }
        write_state(state_dir, tenant, state)
        append_audit(state_dir, tenant, {
            "at": now.isoformat(), "tenant": tenant, "from": None, "to": "planned",
            "revision": 0, "changeTicket": change_ticket, "evidence": {},
        })
    return state


def transition(state_dir, tenant, to_phase, evidence, now=None):
    if to_phase not in PHASES:
        raise ValueError("unknown phase")
    now = now or datetime.now(timezone.utc)
    with Lock(state_dir, tenant):
        state = load_state(state_dir, tenant)
        current = state["phase"]
        if to_phase not in TRANSITIONS.get(current, ()):
            raise ValueError("transition is not allowed: " + current + " -> " + to_phase)
        validate_evidence(to_phase, evidence, state, now)
        previous = current
        state["phase"] = to_phase
        state["revision"] = int(state.get("revision", 0)) + 1
        state["updatedAt"] = now.isoformat()
        state.setdefault("evidence", {})[to_phase] = evidence
        write_state(state_dir, tenant, state)
        append_audit(state_dir, tenant, {
            "at": now.isoformat(), "tenant": tenant, "from": previous, "to": to_phase,
            "revision": state["revision"], "changeTicket": state["changeTicket"],
            "evidence": evidence,
        })
    return state


def list_tenants(state_dir):
    root = Path(state_dir)
    if not root.is_dir():
        return []
    result = []
    for path in sorted(root.iterdir()):
        if path.is_dir() and TENANT.fullmatch(path.name) and (path / "state.json").is_file():
            result.append(path.name)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command")
    init = sub.add_parser("init")
    init.add_argument("--state-dir", required=True)
    init.add_argument("--tenant", required=True)
    init.add_argument("--change-ticket", required=True)
    show = sub.add_parser("show")
    show.add_argument("--state-dir", required=True)
    show.add_argument("--tenant", required=True)
    move = sub.add_parser("transition")
    move.add_argument("--state-dir", required=True)
    move.add_argument("--tenant", required=True)
    move.add_argument("--to", required=True, choices=PHASES)
    move.add_argument("--evidence", required=True)
    move.add_argument("--now")
    audit = sub.add_parser("audit")
    audit.add_argument("--state-dir", required=True)
    audit.add_argument("--tenant", required=True)
    listing = sub.add_parser("list")
    listing.add_argument("--state-dir", required=True)
    args = parser.parse_args()
    if not args.command:
        parser.error("a command is required")
    if args.command == "init":
        state = initialize(args.state_dir, args.tenant, args.change_ticket)
        print(json.dumps(state, ensure_ascii=False, sort_keys=True))
    elif args.command == "show":
        print(json.dumps(load_state(args.state_dir, args.tenant), ensure_ascii=False, sort_keys=True, indent=2))
    elif args.command == "audit":
        path = tenant_dir(args.state_dir, args.tenant) / "audit.jsonl"
        if not path.exists():
            raise ValueError("tenant audit does not exist")
        print(path.read_text(encoding="utf-8"), end="")
    elif args.command == "list":
        print(json.dumps(list_tenants(args.state_dir), ensure_ascii=False))
    else:
        evidence_path = Path(args.evidence)
        if not evidence_path.is_file() or evidence_path.stat().st_size > 100_000:
            raise ValueError("evidence file is missing or too large")
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        now = instant(args.now, "--now") if args.now else datetime.now(timezone.utc)
        state = transition(args.state_dir, args.tenant, args.to, evidence, now)
        print(json.dumps(state, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError, RuntimeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)
