-- V88: Billing-events audit — one evolving row per store transaction that
-- records what happened to it across every redeem attempt (see
-- docs/wiki/purchases.md). This is the source of truth for two things support
-- and observability need that the money-path tables don't carry:
--
--   * support: "what happened to this person's purchase" — the caller, the
--     receipt's owning account, the product, the disposition reason, how many
--     times it was attempted, and where it ended up.
--   * the Grafana billing-health panel: pending / stuck counts with age,
--     mismatch rate, grant-on-replay rate, refunded counts, escalations, and
--     retry distributions, sliceable by reason and final action.
--
-- Distinct from billing_transactions, which is the atomic grant + idempotency
-- record (one row per GRANTED transaction). billing_events records attempts
-- whether or not they granted — a mismatch, a sign-in-to-claim nudge, a refund
-- — so it is the disposition log, not the ledger.
--
-- One row per (store, transaction_id), upserted: each attempt bumps
-- attempt_count and rewrites the latest caller / reason / final_action /
-- updated_at, keeping first_seen_at. So the row tells the whole journey of a
-- purchase ("seen 3 times, first pending, then granted on replay") rather than
-- scattering it. Attempts with no decodable store transaction id (a forged
-- signature, an unconfigured validator) are not recorded here — they have no
-- purchase identity to anchor a support lookup on and are covered by Sentry /
-- logs.
--
-- Same FK-omission rationale as billing_transactions: Supabase owns
-- auth.users, our Testcontainers Postgres does not, and this is a billing
-- audit record that should outlive the accounts it references rather than
-- cascade-delete with them. caller_user_id and receipt_owner are both real
-- auth.users ids (two of the same human's accounts) but carry no FK.

CREATE TABLE billing_events (
    id              BIGSERIAL PRIMARY KEY,
    store           TEXT NOT NULL,
    transaction_id  TEXT NOT NULL,
    caller_user_id  UUID NOT NULL,
    receipt_owner   UUID,
    product_id      TEXT NOT NULL,
    reason          TEXT,
    final_action    TEXT NOT NULL,
    attempt_count   INTEGER NOT NULL,
    first_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT billing_events_store_txn_uq UNIQUE (store, transaction_id)
);

-- Support lookups: "show me this user's purchase attempts", newest first.
CREATE INDEX billing_events_caller_idx
    ON billing_events (caller_user_id, updated_at DESC);

-- The billing-health panel scans a trailing time window and groups by
-- final_action / reason, so index the time dimension it filters on.
CREATE INDEX billing_events_updated_idx
    ON billing_events (updated_at DESC);
