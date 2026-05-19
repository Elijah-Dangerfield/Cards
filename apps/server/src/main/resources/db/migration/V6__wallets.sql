-- V6: Server-authoritative chip wallets.
--
-- One row per user — chips live server-side as of V6 so they survive
-- reinstall, persist across devices, and stay consistent through the
-- delete-account flow. The client keeps a local Room cache for fast
-- reads, but the server is the source of truth.
--
-- The starter grant lives in the application layer (lazy-create on
-- first `GET /v1/me/wallet`), not as a column default, so the grant
-- amount can be tuned without a migration. The DB-level
-- `CHECK (balance >= 0)` is a defense-in-depth backstop against an
-- application-layer bug that would otherwise drive a user's balance
-- negative — a NOT NULL constraint plus a non-negative invariant is
-- the strongest signal of a real bug if it ever trips.
--
-- Same FK-omission rationale as the previous V1-V5 migrations:
-- Supabase owns `auth.users`, our Testcontainers Postgres does not,
-- and the JWT check in the application layer guarantees the user_id
-- is real. Profile + inventory + equipment follow the same pattern.

CREATE TABLE wallets (
    user_id     UUID PRIMARY KEY,
    balance     BIGINT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT wallets_balance_non_negative CHECK (balance >= 0)
);


-- Append-only ledger of every chip movement applied to a wallet.
--
-- The `(user_id, idempotency_key)` primary key is what lets the
-- client retry a sync without double-applying a delta — if the same
-- idempotency key arrives twice the second one is a no-op. The
-- canonical use case: a flaky upload after the chip-pack purchase
-- where the client doesn't know whether the server saw the request,
-- so it retries with the same key.
--
-- `reason` is a free-form short string from the client describing
-- the source ("iap.chip_pack_medium", "achievement.first_full_house",
-- "starter_grant"). Future analytics + abuse review can join this
-- against the auth.users + inventory tables.
--
-- Deltas can be negative (debits) once the client wires shop spend
-- through this endpoint, but the wallets-level CHECK constraint
-- enforces the non-negative invariant. A delta that would push the
-- balance below zero is rejected by the repo without writing the
-- ledger row.
CREATE TABLE wallet_events (
    user_id          UUID NOT NULL,
    idempotency_key  TEXT NOT NULL,
    delta            BIGINT NOT NULL,
    reason           TEXT NOT NULL,
    applied_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

-- Per-user history reads ("show me my recent chip movements") use
-- the (user_id, applied_at DESC) shape. Without the dedicated index
-- the planner would scan the user's full event set and sort in
-- memory; not terrible at V1 volume but cheap to fix now.
CREATE INDEX wallet_events_user_applied_idx
    ON wallet_events (user_id, applied_at DESC);
