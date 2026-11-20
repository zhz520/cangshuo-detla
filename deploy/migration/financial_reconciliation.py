#!/usr/bin/env python3
"""Read-only financial consistency checks for one tenant database.

Every check is a SELECT. The tool never writes to the database and reports only
counts, never user names, phone numbers, order numbers or payment identifiers.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

DATABASE = re.compile(r"^[A-Za-z0-9_]{1,64}$")


def checks(database):
    prefix = "`" + database + "`."
    def count(table, condition):
        return ("SELECT COALESCE(SUM(CASE WHEN " + condition + " THEN 1 ELSE 0 END),0) FROM "
                + prefix + table)

    return [
        ("wallet_negative", count("wallet", "CAST(balance AS DECIMAL(18,4)) < -0.005 OR CAST(frozen_amount AS DECIMAL(18,4)) < -0.005")),
        ("player_wallet_negative", count("player_wallet", "CAST(balance AS DECIMAL(18,4)) < -0.005 OR CAST(frozen_amount AS DECIMAL(18,4)) < -0.005")),
        ("transaction_arithmetic", count("`transaction`", "type NOT IN ('WITHDRAW','WITHDRAW_COMPLETE') AND ABS(CAST(balance_after AS DECIMAL(18,4)) - CAST(balance_before AS DECIMAL(18,4)) - CASE WHEN type='ADMIN_DEDUCT' THEN -CAST(amount AS DECIMAL(18,4)) ELSE CAST(amount AS DECIMAL(18,4)) END) > 0.005")),
        ("transaction_continuity", "SELECT COALESCE(SUM(CASE WHEN previous_after IS NOT NULL AND ABS(CAST(balance_before AS DECIMAL(18,4))-CAST(previous_after AS DECIMAL(18,4))) > 0.005 THEN 1 ELSE 0 END),0) FROM (SELECT balance_before, LAG(balance_after) OVER (PARTITION BY user_type,user_id ORDER BY id) previous_after FROM " + prefix + "`transaction`) t"),
        ("user_wallet_latest_mismatch", "SELECT COALESCE(SUM(CASE WHEN (t.balance_after IS NULL AND ABS(CAST(w.balance AS DECIMAL(18,4))) > 0.005) OR (t.balance_after IS NOT NULL AND ABS(CAST(w.balance AS DECIMAL(18,4))-CAST(t.balance_after AS DECIMAL(18,4))) > 0.005) THEN 1 ELSE 0 END),0) FROM " + prefix + "wallet w LEFT JOIN (SELECT user_id,balance_after FROM (SELECT user_id,balance_after,ROW_NUMBER() OVER(PARTITION BY user_id ORDER BY id DESC) rn FROM " + prefix + "`transaction` WHERE user_type='USER') x WHERE rn=1) t ON t.user_id=w.user_id"),
        ("player_wallet_latest_mismatch", "SELECT COALESCE(SUM(CASE WHEN (t.balance_after IS NULL AND ABS(CAST(w.balance AS DECIMAL(18,4))) > 0.005) OR (t.balance_after IS NOT NULL AND ABS(CAST(w.balance AS DECIMAL(18,4))-CAST(t.balance_after AS DECIMAL(18,4))) > 0.005) THEN 1 ELSE 0 END),0) FROM " + prefix + "player_wallet w LEFT JOIN (SELECT player_id,balance_after FROM (SELECT user_id AS player_id,balance_after,ROW_NUMBER() OVER(PARTITION BY user_id ORDER BY id DESC) rn FROM " + prefix + "`transaction` WHERE user_type='PLAYER') x WHERE rn=1) t ON t.player_id=w.player_id"),
        ("order_payment_amount_mismatch", "SELECT COUNT(*) FROM (SELECT p.order_id,SUM(p.amount) paid,MAX(o.amount) order_amount FROM " + prefix + "payment p JOIN " + prefix + "`order` o ON o.id=p.order_id WHERE p.biz_type='ORDER' AND p.status IN ('PAID','REFUNDED') GROUP BY p.order_id HAVING ABS(paid-order_amount) > 0.005) t"),
        ("refund_request_amount_mismatch", "SELECT COUNT(*) FROM (SELECT r.payment_id,SUM(r.amount) requested,MAX(p.refund_amount) refunded FROM " + prefix + "refund_request r JOIN " + prefix + "payment p ON p.id=r.payment_id WHERE r.status='REFUNDED' GROUP BY r.payment_id HAVING ABS(requested-refunded) > 0.005) t"),
        ("withdraw_frozen_mismatch", "SELECT COALESCE(SUM(CASE WHEN ABS(CAST(pw.frozen_amount AS DECIMAL(18,4))-COALESCE(w.pending,0)) > 0.005 THEN 1 ELSE 0 END),0) FROM " + prefix + "player_wallet pw LEFT JOIN (SELECT player_id,SUM(amount) pending FROM " + prefix + "withdraw WHERE status='PENDING' GROUP BY player_id) w ON w.player_id=pw.id"),
        ("player_pending_income_mismatch", "SELECT COALESCE(SUM(CASE WHEN ABS(CAST(pw.pending_income AS DECIMAL(18,4))-COALESCE(s.pending,0)) > 0.005 THEN 1 ELSE 0 END),0) FROM " + prefix + "player_wallet pw LEFT JOIN (SELECT player_id,SUM(amount) pending FROM " + prefix + "player_income_settlement WHERE status='PENDING' GROUP BY player_id) s ON s.player_id=pw.id"),
        ("payment_refund_over_amount", count("payment", "CAST(refund_amount AS DECIMAL(18,4)) > CAST(amount AS DECIMAL(18,4)) + 0.005")),
        ("payment_amount_not_positive", count("payment", "CAST(amount AS DECIMAL(18,4)) <= 0")),
    ]


def mysql_query(container, sql):
    command = ["docker", "exec", "-i", container, "sh", "-c",
               'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B']
    result = subprocess.run(command, input=sql.encode("utf-8"),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", "replace").strip().splitlines()
        raise RuntimeError(message[-1] if message else "mysql query failed")
    return result.stdout.decode("utf-8", "replace")


def audit(container, database, runner=None):
    if not DATABASE.fullmatch(database or ""):
        raise ValueError("database must contain only letters, digits and underscore")
    if not container or any(character.isspace() for character in container):
        raise ValueError("mysql container name is invalid")
    runner = runner or (lambda name, sql: mysql_query(container, sql))
    rows = []
    total = 0
    for name, sql in checks(database):
        try:
            output = runner(name, sql).strip()
            value = int(output.splitlines()[0]) if output else 0
            if value < 0:
                raise ValueError("negative count")
            rows.append({"name": name, "violations": value, "status": "ok" if value == 0 else "violation"})
            total += value
        except Exception as error:
            rows.append({"name": name, "status": "error", "error": type(error).__name__})
    return {
        "version": 1,
        "database": database,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "checks": rows,
        "totalViolations": total,
        "passed": total == 0 and all(row["status"] == "ok" for row in rows),
    }


def write_report(value, path):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(str(temporary), str(target))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("audit",))
    parser.add_argument("--mysql-container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--report")
    parser.add_argument("--fail-on-violation", action="store_true")
    args = parser.parse_args()
    result = audit(args.mysql_container, args.database)
    if args.report:
        write_report(result, args.report)
    summary = {"database": result["database"], "totalViolations": result["totalViolations"],
               "passed": result["passed"], "checks": [{"name": row["name"], "status": row["status"], "violations": row.get("violations")} for row in result["checks"]]}
    print(json.dumps(summary, ensure_ascii=False))
    return 1 if args.fail_on_violation and not result["passed"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, RuntimeError, json.JSONDecodeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        raise SystemExit(2)


