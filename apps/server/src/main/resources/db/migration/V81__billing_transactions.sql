-- V81: Server-side record of redeemed store purchases (IAP).
--
-- BILL-1. Today the client credits chips locally on store confirmation,
-- so the server never sees the receipt and a forged one could mint chips.
-- This table is the idempotency + audit boundary for the new
-- `POST /v1/billing/redeem` endpoint: one row per redeemed store
-- transaction, written in the same transaction as the wallet grant so the
-- credit and the record commit together or roll back together.
--
-- `UNIQUE (store, order_id)` is what makes redemption idempotent. A
-- client retry, a duplicate webhook, or two racing requests for the same
-- store transaction all collapse to a single grant: the second insert
-- trips the unique constraint and the endpoint returns the current
-- balance instead of crediting again.
--
-- `store` is the platform marketplace ('apple' | 'google'); `order_id` is
-- that platform's stable transaction id (StoreKit 2 transaction id /
-- Play purchase order id). `product_id` and `granted_chips` are
-- snapshotted at redeem time so the audit row stays meaningful even if the
-- catalog's grant amount changes later. `user_id` ties the redemption to
-- the authenticated caller for abuse review.
--
-- Same FK-omission rationale as the wallet migrations: Supabase owns
-- `auth.users`, our Testcontainers Postgres does not, and the JWT check in
-- the application layer guarantees the user_id is real.

CREATE TABLE billing_transactions (
    id             BIGSERIAL PRIMARY KEY,
    store          TEXT NOT NULL,
    order_id       TEXT NOT NULL,
    user_id        UUID NOT NULL,
    product_id     TEXT NOT NULL,
    granted_chips  BIGINT NOT NULL,
    redeemed_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT billing_transactions_store_order_uq UNIQUE (store, order_id)
);

-- Per-user history reads ("show me this user's purchases") for abuse
-- review use the (user_id, redeemed_at DESC) shape.
CREATE INDEX billing_transactions_user_redeemed_idx
    ON billing_transactions (user_id, redeemed_at DESC);
