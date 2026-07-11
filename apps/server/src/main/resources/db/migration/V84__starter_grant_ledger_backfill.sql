-- V84: Backfill the missing starter-grant ledger rows (ECON-1).
--
-- The lazy wallet create set balance = 10,000 without writing a
-- wallet_events row, so the ledger could not explain the starter chips:
-- prod balances summed 146,000 against a 126,000-chip ledger. The code
-- now writes a `starter_grant` event in the same transaction as the
-- wallet insert; this backfills the rows for wallets created before the
-- fix so the conservation invariant
-- (SUM(wallets.balance) = SUM(wallet_events.delta)) holds on prod.
--
-- 10,000 is the only starter amount that has ever shipped, and the
-- `(user_id, idempotency_key)` primary key makes a re-run (or a wallet
-- that somehow already has the row) a no-op.

INSERT INTO wallet_events (user_id, idempotency_key, delta, reason, applied_at)
SELECT w.user_id, 'starter_grant', 10000, 'starter_grant', w.created_at
FROM wallets w
ON CONFLICT (user_id, idempotency_key) DO NOTHING;
