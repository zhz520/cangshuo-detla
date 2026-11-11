#!/usr/bin/env python3
"""Append read-only Delta shared-service-group capacity and error metrics."""

import argparse
import fcntl
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_CONTAINERS = [
    "delta-shared-backend",
    "delta-shared-mysql",
    "delta-shared-redis",
    "delta-admin-ui",
    "delta-h5",
]


def command(args, timeout=30):
    return subprocess.run(args, check=False, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout)


def memory():
    values = {}
    with open("/proc/meminfo", "r", encoding="ascii") as stream:
        for line in stream:
            key, value = line.split(":", 1)
            values[key] = int(value.strip().split()[0])
    return {
        "totalKiB": values.get("MemTotal"),
        "availableKiB": values.get("MemAvailable"),
        "freeKiB": values.get("MemFree"),
        "swapTotalKiB": values.get("SwapTotal"),
        "swapFreeKiB": values.get("SwapFree"),
    }


def docker_stats(containers):
    result = command(["docker", "stats", "--no-stream", "--format", "{{json .}}"] + containers, timeout=45)
    if result.returncode != 0:
        raise RuntimeError("docker stats failed")
    rows = {}
    for line in result.stdout.decode("utf-8", "replace").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        rows[row.get("Name")] = {
            "cpuPercent": row.get("CPUPerc"),
            "memoryUsage": row.get("MemUsage"),
            "memoryPercent": row.get("MemPerc"),
            "pids": row.get("PIDs"),
            "networkIO": row.get("NetIO"),
            "blockIO": row.get("BlockIO"),
        }
    return rows


def mysql_snapshot(container, databases):
    names = ",".join("'" + name.replace("'", "''") + "'" for name in databases)
    sql = (
        "SELECT CONCAT('table:',table_schema),COUNT(*) FROM information_schema.tables "
        "WHERE table_schema IN (" + names + ") AND table_type='BASE TABLE' GROUP BY table_schema;"
        "SHOW GLOBAL STATUS WHERE Variable_name IN "
        "('Threads_connected','Threads_running','Innodb_buffer_pool_pages_free',"
        "'Innodb_buffer_pool_pages_total','Innodb_row_lock_current_waits','Uptime');"
    )
    result = command(["docker", "exec", container, "sh", "-lc",
                      'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N -B -e "' + sql + '"'], timeout=45)
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", "replace").strip().splitlines()
        raise RuntimeError(message[-1] if message else "mysql metrics failed")
    tableCounts = {}
    status = {}
    for line in result.stdout.decode("utf-8", "replace").splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and parts[0].startswith("table:") and parts[1].isdigit():
            tableCounts[parts[0][6:]] = int(parts[1])
        elif len(parts) == 2:
            status[parts[0]] = parts[1]
    return {"tableCounts": tableCounts, "status": status}


def error_counts(containers):
    counts = {}
    for container in containers:
        result = command(["docker", "logs", "--since", "30m", container], timeout=30)
        text = (result.stdout + result.stderr).decode("utf-8", "replace").upper()
        counts[container] = sum(1 for line in text.splitlines() if "ERROR" in line or "EXCEPTION" in line)
    return counts


def append(path, value):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    line = (json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    descriptor = os.open(str(target), os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        os.chmod(str(target), 0o600)
        os.write(descriptor, line)
        os.fsync(descriptor)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="/var/lib/delta-shared/monitoring/metrics.jsonl")
    parser.add_argument("--mysql-container", default="delta-shared-mysql")
    parser.add_argument("--databases", default="delta_game,delta_742_game,delta_747_game")
    parser.add_argument("--containers", nargs="*", default=DEFAULT_CONTAINERS)
    args = parser.parse_args()
    record = {"at": datetime.now(timezone.utc).isoformat(), "host": "103.38.82.146"}
    try:
        record["memory"] = memory()
        record["load"] = list(os.getloadavg())
        record["disk"] = {key: getattr(shutil.disk_usage("/www"), key) for key in ("total", "used", "free")}
        record["containers"] = docker_stats(args.containers)
        record["mysql"] = mysql_snapshot(args.mysql_container, [x for x in args.databases.split(",") if x])
        record["errors30m"] = error_counts(args.containers)
    except Exception as error:
        record["error"] = type(error).__name__
    append(args.output, record)
    print(json.dumps(record, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
