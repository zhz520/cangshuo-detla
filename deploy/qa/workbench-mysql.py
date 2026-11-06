"""Emit only schema for the local WorkbenchIntegrationTest MySQL fixture."""
import re
from pathlib import Path

root = Path(__file__).resolve().parents[2]
tables = {
    "order", "order_player", "order_progress", "complaint", "withdraw",
    "order_relay_request", "player_replace_request", "player", "player_wallet",
    "payment", "chat_session", "chat_message", "system_notification", "user",
}
dump = (root / "delta_game.sql").read_text(encoding="utf-8")
ddl = {
    match[1]: match[0] for match in re.finditer(
        r"CREATE TABLE `([^\n`]+)` \(.*?\n\) ENGINE=[^\n]*;", dump, re.S
    ) if match[1] in tables
}
if ddl.keys() != tables:
    raise SystemExit("Required test tables missing from base schema")
resource = root / "delta-game/delta-admin/src/main/resources/db"
print("SET NAMES utf8mb4; CREATE DATABASE IF NOT EXISTS workbench_qa CHARACTER SET utf8mb4; USE workbench_qa;")
for name in sorted(tables):
    print(ddl[name].replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ", 1))
for name in ("governance-v1.sql", "workbench.sql"):
    print((resource / name).read_text(encoding="utf-8"))
