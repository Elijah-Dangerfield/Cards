-- V83: Record which store environment verified each purchase (BILL-6).
--
-- TestFlight installs of the production app always transact against Apple's
-- StoreKit sandbox — the tester pays nothing — and the same is true of Play
-- license testers. Those mints were recorded indistinguishably from real
-- revenue: the owner's two test buys put 125,000 unpaid chips into the prod
-- economy reading as income. Sandbox purchasers exist for as long as
-- TestFlight does, launch included, so the split is a permanent data
-- dimension, not a pre-launch cleanup.
--
-- Two changes:
--
--  1. `billing_transactions.environment` ('sandbox' | 'production'), written
--     from the receipt validator's verdict at redeem time.
--  2. The wallet ledger's reason prefix now splits: `iap.<product>` strictly
--     means real money; sandbox mints write `iap_sandbox.<product>`. The
--     `iap.%` LIKE gates (orphan-sweep paying-account guards, install-sibling
--     sweep) therefore correctly stop treating testers as paying customers,
--     and the economy dashboards can segment supply by prefix.
--
-- Backfill: every purchase to date was sandbox — distribution has been
-- TestFlight-only and APPLE_STORE_ENVIRONMENT is 'Sandbox' on both Fly apps —
-- so existing rows are tagged sandbox and existing iap ledger reasons are
-- rewritten to the sandbox prefix.

ALTER TABLE billing_transactions
    ADD COLUMN environment TEXT NOT NULL DEFAULT 'sandbox'
    CONSTRAINT billing_transactions_environment_ck
        CHECK (environment IN ('sandbox', 'production'));

-- New writes must state the environment explicitly.
ALTER TABLE billing_transactions ALTER COLUMN environment DROP DEFAULT;

UPDATE wallet_events
SET reason = 'iap_sandbox.' || substr(reason, 5)
WHERE reason LIKE 'iap.%';
