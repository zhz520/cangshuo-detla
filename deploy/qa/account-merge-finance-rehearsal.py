"""Synthetic 520 account-merge finance rehearsal.

This command has no input, database connection or write path. It models only
wallet/ledger invariants on hard-coded examples. It grants no merge permission.
"""
from dataclasses import dataclass
from decimal import Decimal
from copy import deepcopy
import unittest

def money(amount):
    return Decimal(amount).quantize(Decimal("0.01"))

@dataclass(frozen=True)
class Posting:
    account: int
    before: Decimal
    amount: Decimal
    after: Decimal
    reference: str

@dataclass(frozen=True)
class Wallet:
    available: Decimal
    frozen: Decimal = Decimal("0.00")

def fixture():
    return {
        "wallets": {1: Wallet(money("37.10")), 2: Wallet(money("9.05"))},
        "ledger": [Posting(1, money("0"), money("37.10"), money("37.10"), "original-source"),
                   Posting(2, money("0"), money("9.05"), money("9.05"), "original-target")],
        "orders": ((20, 1, "CONFIRMED"), (21, 2, "REVIEWED")),
        "payments": ((30, 1, "PAID"), (31, 2, "PAID")),
        "pending_refunds": (),
    }

def reconcile(state):
    latest = {}
    for row in state["ledger"]:
        if row.before + row.amount != row.after or latest.get(row.account, row.before) != row.before:
            raise ValueError("ledger discrepancy")
        latest[row.account] = row.after
    for key, wallet in state["wallets"].items():
        if wallet.available < 0 or wallet.frozen < 0 or latest.get(key, money("0")) != wallet.available:
            raise ValueError("wallet discrepancy")

def simulate(state, inject=None):
    """Return a copy showing accounting effects only; never reassign orders or payments."""
    reconcile(state)
    if any(status not in {"CONFIRMED", "REVIEWED", "REFUNDED", "SETTLED", "CANCELLED", "ARBITRATED"}
           for _, account, status in state["orders"] if account == 1):
        raise ValueError("active order")
    if any(status in {"PAYING", "PENDING", "REFUNDING"}
           for _, account, status in state["payments"] if account == 1):
        raise ValueError("active payment")
    if state["pending_refunds"]:
        raise ValueError("active refund")
    a, b = (state["wallets"][account] for account in (1, 2))
    if a.frozen or b.frozen:
        raise ValueError("frozen balance")
    ref = "test-paired-posting"
    if any(p.reference == ref for p in state["ledger"]):
        return deepcopy(state)
    snapshot = deepcopy(state)
    if not a.available:
        return snapshot
    snapshot["ledger"].append(Posting(1, a.available, -a.available, money("0"), ref))
    if inject:
        inject()
    snapshot["ledger"].append(Posting(2, b.available, a.available, a.available + b.available, ref))
    snapshot["wallets"] = {1: Wallet(money("0")), 2: Wallet(a.available + b.available)}
    reconcile(snapshot)
    return snapshot

class FinanceRehearsal(unittest.TestCase):
    def test_historical_transactions_and_ownership_unchanged(self):
        old = fixture()
        new = simulate(old)
        self.assertEqual(new["ledger"][:2], old["ledger"])
        self.assertEqual(new["orders"], old["orders"])
        self.assertEqual(new["payments"], old["payments"])
        self.assertEqual(sum(x.available for x in new["wallets"].values()), money("46.15"))
        self.assertEqual(sum(p.amount for p in new["ledger"][2:]), money("0"))
        self.assertEqual(new["wallets"][2].available, money("46.15"))

    def test_replay_is_noop(self):
        state = simulate(fixture())
        self.assertEqual(simulate(state), state)

    def test_failure_between_entries_leaves_original_intact(self):
        state = fixture()
        def fail():
            raise RuntimeError("simulated interruption")
        with self.assertRaises(RuntimeError):
            simulate(state, fail)
        self.assertEqual(state, fixture())

    def test_frozen_money_and_ongoing_orders_and_refunds_block(self):
        for name in ("freeze", "order", "payment", "refund"):
            with self.subTest(name=name):
                state = fixture()
                if name == "freeze":
                    state["wallets"][1] = Wallet(money("37.10"), money("0.01"))
                if name == "order":
                    state["orders"] = ((20, 1, "PENDING_PAYMENT"),)
                if name == "payment":
                    state["payments"] = ((30, 1, "REFUNDING"),)
                if name == "refund":
                    state["pending_refunds"] = (30,)
                before = deepcopy(state)
                with self.assertRaises(ValueError):
                    simulate(state)
                self.assertEqual(state, before)

    def test_stale_or_inconsistent_balance_blocks(self):
        state = fixture()
        state["wallets"][1] = Wallet(money("38.10"))
        with self.assertRaisesRegex(ValueError, "wallet discrepancy"):
            simulate(state)

if __name__ == "__main__":
    unittest.main(verbosity=2)
