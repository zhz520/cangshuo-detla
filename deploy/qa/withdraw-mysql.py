"""Emit an isolated MySQL schema for AdminWithdrawConcurrencyTest."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
schema = root / "delta-game/delta-admin/src/test/resources/withdraw-schema.sql"
print("SET NAMES utf8mb4;")
print("CREATE DATABASE IF NOT EXISTS withdraw_qa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
print("USE withdraw_qa;")
print(schema.read_text(encoding="utf-8"))
